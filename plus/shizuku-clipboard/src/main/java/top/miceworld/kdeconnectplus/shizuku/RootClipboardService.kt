package top.miceworld.kdeconnectplus.shizuku

import android.content.Context
import android.os.IBinder
import android.os.Process
import android.os.RemoteException
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Shizuku 以 root（或 Sui）运行时的 UserService（uid 0）。
 * uid 0 绝不直接调用 IClipboard（Android 12+ 会清空剪贴板），而是启动一个降权到 uid 2000 的
 * 剪贴板进程（ShellClipboardMain），通过管道转发事件和写入请求。
 */
class RootClipboardService : IClipboardBridge.Stub {
    private val apkPath: String?
    private val niceName: String

    @Suppress("unused")
    constructor() {
        apkPath = null
        niceName = "kdeconnectplus:clipboard-shell"
    }

    @Suppress("unused")
    constructor(context: Context) {
        apkPath = context.applicationInfo.sourceDir
        niceName = "${context.packageName}:clipboard-shell"
    }

    private var child: java.lang.Process? = null
    private var callback: IClipboardCallback? = null
    private var deathRecipient: IBinder.DeathRecipient? = null

    /** 一次只处理一个写入请求；结果由 relay 线程从子进程的 FRAME_WRITTEN 帧放进来。 */
    private val writeLock = Any()
    private val writeResults = LinkedBlockingQueue<Boolean>()

    @Synchronized
    override fun start(callback: IClipboardCallback, userId: Int): Int {
        if (Process.myUid() != BridgeStatus.ROOT_UID) return BridgeStatus.NOT_ROOT_UID
        stop()
        val apk = apkPath ?: return BridgeStatus.NO_APK_PATH
        val process = try {
            ProcessBuilder(
                ShellClipboardMain.APP_PROCESS, "/system/bin", "--nice-name=$niceName",
                ShellClipboardMain::class.java.name, userId.toString(), niceName,
            ).apply {
                environment()["CLASSPATH"] = apk
                redirectError(ProcessBuilder.Redirect.INHERIT)
            }.start()
        } catch (e: Exception) {
            Log.e(TAG, "launch failed", e)
            return BridgeStatus.LAUNCH_FAILED
        }

        val firstStatus = LinkedBlockingQueue<Int>()
        thread(name = "PlusClipboardRelay", isDaemon = true) { relay(process, callback, firstStatus) }
        val status = firstStatus.poll(STARTUP_TIMEOUT_SECONDS, TimeUnit.SECONDS) ?: BridgeStatus.LAUNCH_FAILED
        if (status != BridgeStatus.OK) {
            process.destroy()
            return status
        }

        val recipient = IBinder.DeathRecipient { stop() }
        callback.asBinder().linkToDeath(recipient, 0)
        this.child = process
        this.callback = callback
        this.deathRecipient = recipient
        return BridgeStatus.OK
    }

    private fun relay(process: java.lang.Process, callback: IClipboardCallback, firstStatus: LinkedBlockingQueue<Int>) {
        val input = DataInputStream(process.inputStream.buffered())
        try {
            while (true) {
                val frame = ShellClipboardMain.readFrame(input)
                when (frame.type) {
                    ShellClipboardMain.FRAME_STATUS -> firstStatus.offer(frame.status)
                    ShellClipboardMain.FRAME_CLIP -> try {
                        callback.onClipChanged(frame.text, frame.sensitive)
                    } catch (_: RemoteException) {
                    }
                    ShellClipboardMain.FRAME_WRITTEN -> writeResults.offer(frame.status == 1)
                }
            }
        } catch (e: Exception) {
            if (e !is EOFException) Log.w(TAG, "relay failed", e)
        }
        firstStatus.offer(BridgeStatus.LAUNCH_FAILED)
        val unexpected = synchronized(this) { child === process }
        if (unexpected) {
            Log.w(TAG, "clipboard process exited")
            stop()
            try {
                callback.onStopped(BridgeStatus.PROCESS_EXITED)
            } catch (_: RemoteException) {
            }
        }
    }

    @Synchronized
    override fun stop() {
        val process = child
        child = null
        if (process != null) {
            try {
                process.outputStream.close()
            } catch (_: Exception) {
            }
            process.destroy()
        }
        deathRecipient?.let { callback?.asBinder()?.unlinkToDeath(it, 0) }
        callback = null
        deathRecipient = null
    }

    override fun destroy() {
        stop()
        exitProcess(0)
    }

    override fun getAccessNotificationsEnabled(): Int = AccessNotifications.get()

    override fun setAccessNotificationsEnabled(enabled: Boolean): Boolean = AccessNotifications.set(enabled)

    override fun setText(text: String): Boolean = synchronized(writeLock) {
        val process = synchronized(this) { child } ?: return false
        writeResults.clear()
        try {
            ShellClipboardMain.writeText(
                DataOutputStream(process.outputStream), ShellClipboardMain.FRAME_SET_TEXT, text, false,
            )
        } catch (e: IOException) {
            Log.w(TAG, "send to clipboard process failed", e)
            return false
        }
        writeResults.poll(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS) ?: false
    }

    private companion object {
        const val TAG = "PlusClipboardRoot"
        const val STARTUP_TIMEOUT_SECONDS = 10L
        const val WRITE_TIMEOUT_SECONDS = 3L
    }
}
