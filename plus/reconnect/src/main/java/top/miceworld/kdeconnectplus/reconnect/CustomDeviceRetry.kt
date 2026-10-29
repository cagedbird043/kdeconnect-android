package top.miceworld.kdeconnectplus.reconnect

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import org.kde.kdeconnect.KdeConnect

/**
 * 向自定义 IP 重发身份包，让对方来连我们。官方只在启动、网络变化和手动刷新时发一次，
 * 连接断开后不会再试；在 sing-box 这类本机代理后面，对方主动来连又会变成 127.0.0.1，走不通。
 *
 * - 有已配对设备没连上时定时重发：从 15 秒开始，每次翻倍，最长 5 分钟；全部连上就停。
 * - 亮屏、打开应用时立即重发，网络变化时重置间隔（官方此时已经发过一次）。
 * - 用普通 Handler 计时，不设闹钟：手机休眠时不会被唤醒。
 *
 * 所有状态都只在主线程上读写。
 */
class CustomDeviceRetry(
    context: Context,
    private val hasCustomDevices: () -> Boolean,
    private val sendToCustomDevices: () -> Unit,
) {
    private val app = context.applicationContext as Application
    private val handler = Handler(Looper.getMainLooper())
    private var started = false
    private var scheduled = false
    private var delayMs = INITIAL_DELAY_MS
    private var lastSend = 0L

    private val tick = Runnable {
        scheduled = false
        if (!needsRetry()) return@Runnable
        val sent = send()
        delayMs = (delayMs * 2).coerceAtMost(MAX_DELAY_MS)
        if (sent) Log.i(TAG, "retried, next in ${delayMs / 1000}s")
        schedule()
    }

    private val screenOn = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = wakeUp()
    }

    private val activityStarted = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) = wakeUp()
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    fun start() = handler.post {
        if (started) return@post
        started = true
        ContextCompat.registerReceiver(
            app, screenOn, IntentFilter(Intent.ACTION_SCREEN_ON), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        app.registerActivityLifecycleCallbacks(activityStarted)
        KdeConnect.getInstance().addDeviceListChangedCallback(CALLBACK_KEY) { handler.post(::onDevicesChanged) }
        restart()
    }

    fun stop() = handler.post {
        if (!started) return@post
        started = false
        app.unregisterReceiver(screenOn)
        app.unregisterActivityLifecycleCallbacks(activityStarted)
        KdeConnect.getInstance().removeDeviceListChangedCallback(CALLBACK_KEY)
        cancel()
    }

    /** 网络变化：官方已经发过身份包，这里只把间隔重置回 15 秒。 */
    fun onNetworkChange() = handler.post {
        lastSend = SystemClock.elapsedRealtime()
        restart()
    }

    /**
     * 收到来自 127.0.0.1 的身份包：本机代理转发进来的，原始地址已经丢了，回连只会连到自己。
     * 对方没连上时改为向自定义 IP 发身份包，让对方来连我们。对方往往刚启动或刚刷新，
     * 稍等一下再发，免得它还没准备好接收。
     */
    fun onLoopbackIdentity(deviceId: String) = handler.postDelayed({
        if (KdeConnect.getInstance().getDevice(deviceId)?.isReachable == true) return@postDelayed
        if (send()) Log.i(TAG, "identity from loopback, asked custom devices to connect to us")
    }, LOOPBACK_REPLY_DELAY_MS)

    private fun wakeUp() {
        if (!started || !needsRetry()) return
        send()
        restart()
    }

    private fun onDevicesChanged() {
        if (!started) return
        if (!needsRetry()) {
            cancel()
        } else if (!scheduled) {
            // 刚断开：从最短间隔开始重试。
            restart()
        }
    }

    private fun needsRetry(): Boolean =
        hasCustomDevices() && KdeConnect.getInstance().devices.values.any { it.isPaired && !it.isReachable }

    private fun restart() {
        cancel()
        delayMs = INITIAL_DELAY_MS
        if (needsRetry()) schedule()
    }

    private fun schedule() {
        handler.postDelayed(tick, delayMs)
        scheduled = true
    }

    private fun cancel() {
        handler.removeCallbacks(tick)
        scheduled = false
    }

    /** 短时间内多个触发（亮屏紧接着打开应用、对方连发的广播等）只发一次；真正发出时返回 true。 */
    private fun send(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSend < MIN_SEND_INTERVAL_MS) return false
        lastSend = now
        sendToCustomDevices()
        return true
    }

    private companion object {
        const val TAG = "PlusCustomDeviceRetry"
        const val CALLBACK_KEY = "kdeconnectplus.CustomDeviceRetry"
        const val INITIAL_DELAY_MS = 15_000L
        const val MAX_DELAY_MS = 5 * 60_000L
        const val MIN_SEND_INTERVAL_MS = 2_000L
        const val LOOPBACK_REPLY_DELAY_MS = 1_500L
    }
}
