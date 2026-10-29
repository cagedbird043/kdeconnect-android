package top.miceworld.kdeconnectplus.shizuku

import android.os.IBinder
import android.os.RemoteException
import kotlin.system.exitProcess

/** Shizuku 以 adb/shell 模式运行时的 UserService（uid 2000），直接在本进程监听剪贴板。 */
class ShellClipboardService() : IClipboardBridge.Stub() {
    private var callback: IClipboardCallback? = null
    private var deathRecipient: IBinder.DeathRecipient? = null

    private val watcher = ClipboardWatcher { text, sensitive ->
        try {
            callback?.onClipChanged(text, sensitive)
        } catch (_: RemoteException) {
        }
    }

    @Synchronized
    override fun start(callback: IClipboardCallback, userId: Int): Int {
        stop()
        val status = watcher.start(userId)
        if (status != BridgeStatus.OK) return status
        val recipient = IBinder.DeathRecipient { stop() }
        callback.asBinder().linkToDeath(recipient, 0)
        this.callback = callback
        this.deathRecipient = recipient
        return BridgeStatus.OK
    }

    @Synchronized
    override fun stop() {
        watcher.stop()
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
}
