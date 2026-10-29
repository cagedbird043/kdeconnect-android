package top.miceworld.kdeconnectplus.shizuku

import android.content.ClipData
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log

/**
 * 在 uid 2000 的进程里监听和写入剪贴板。所有读取都串行在自己的线程上执行。
 * 电脑发来的内容（带 FROM_REMOTE 标记）在读取正文之前就跳过，不会触发“Shell 已粘贴”提示。
 * 写入用 shell 身份，绕过 ColorOS 等系统对后台应用写剪贴板的限制。
 */
internal class ClipboardWatcher(private val onClip: (text: String, sensitive: Boolean) -> Unit) {
    private val handler = Handler(HandlerThread("PlusClipboardWatcher").apply { start() }.looper)
    private var api: ClipboardApi? = null
    private var listener: Any? = null
    private var userId = 0

    @Synchronized
    fun start(userId: Int): Int {
        if (Process.myUid() != BridgeStatus.SHELL_UID) return BridgeStatus.NOT_SHELL_UID
        stop()
        val (status, api) = ClipboardApi.connect()
        if (api == null) return status
        val listener = ClipboardApi.createListener { handler.post(::readClip) }
        api.addListener(listener, userId)
        this.api = api
        this.listener = listener
        this.userId = userId
        // 主动读一次，电脑一连上就能拿到手机上现有的内容。
        handler.post(::readClip)
        return BridgeStatus.OK
    }

    @Synchronized
    fun stop() {
        val api = api ?: return
        try {
            api.removeListener(listener!!, userId)
        } catch (e: Throwable) {
            Log.w(TAG, "removeListener failed", e)
        }
        this.api = null
        this.listener = null
    }

    /** 以 shell 身份写入电脑发来的文本，并打上 FROM_REMOTE 标记；未在监听或写入失败时返回 false。 */
    fun write(text: String): Boolean {
        val api: ClipboardApi
        val userId: Int
        synchronized(this) {
            api = this.api ?: return false
            userId = this.userId
        }
        return try {
            val clip = ClipData.newPlainText(null, text)
            ClipContent.markFromRemote(clip)
            api.setPrimaryClip(clip, userId)
        } catch (e: Throwable) {
            Log.w(TAG, "write clipboard failed", e)
            false
        }
    }

    private fun readClip() {
        val api: ClipboardApi
        val userId: Int
        synchronized(this) {
            api = this.api ?: return
            userId = this.userId
        }
        try {
            // 锁屏、自动清空时读不到，直接忽略。
            val description = api.primaryClipDescription(userId) ?: return
            if (ClipContent.isFromRemote(description)) return
            val clip = api.primaryClip(userId) ?: return
            val text = ClipContent.extractText(clip) ?: return
            onClip(text, ClipContent.isSensitive(description))
        } catch (e: Throwable) {
            Log.w(TAG, "read clipboard failed", e)
        }
    }

    private companion object {
        const val TAG = "PlusClipboardWatcher"
    }
}
