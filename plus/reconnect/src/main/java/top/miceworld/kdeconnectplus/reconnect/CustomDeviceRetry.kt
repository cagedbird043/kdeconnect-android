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
 * 向自定义 IP 重发身份包，让对方来连我们；已连接但只走回环时，另行探测更快的局域网。
 *
 * - 断线重连持续退避；LAN 每轮立即探测，再按 15、30、60、120、240、300 秒间隔探测后停止。
 * - LAN 在网络变化、打开应用或路径降级时开始新一轮；亮屏只触发自定义 IP 重连。
 * - 用普通 Handler 计时，不设闹钟：手机休眠时不会被唤醒。
 *
 * 所有状态都只在主线程上读写。
 */
class CustomDeviceRetry(
    context: Context,
    private val hasCustomDevices: () -> Boolean,
    sendToCustomDevices: () -> Unit,
    private val lanPreference: LanLinkPreference,
) {
    private val app = context.applicationContext as Application
    private val handler = Handler(Looper.getMainLooper())
    private var started = false
    private val customRetry = Retry(
        needsRetry = {
            hasCustomDevices() && KdeConnect.getInstance().devices.values.any { it.isPaired && !it.isReachable }
        },
        sendIdentity = sendToCustomDevices,
    )
    private val lanRetry = Retry(lanPreference::needsRetry, ::sendLanIdentity, bounded = true)
    private var lastLanSend = 0L
    private var lanProbeNotBefore = 0L
    private val sendLan = Runnable(::sendLanIdentity)

    private val screenOn = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = wakeUpCustomDevices()
    }

    private val activityStarted = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            wakeUpCustomDevices()
            if (started) lanRetry.restart()
        }
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
        customRetry.restart()
        lanRetry.restart()
    }

    fun stop() = handler.post {
        if (!started) return@post
        started = false
        app.unregisterReceiver(screenOn)
        app.unregisterActivityLifecycleCallbacks(activityStarted)
        KdeConnect.getInstance().removeDeviceListChangedCallback(CALLBACK_KEY)
        customRetry.cancel()
        handler.removeCallbacks(sendLan)
        lanRetry.cancel()
    }

    /** 官方已发送发现广播：只重置自定义 IP 重连，不启动 LAN 探测。 */
    fun onNetworkChange() = handler.post {
        customRetry.lastSend = SystemClock.elapsedRealtime()
        customRetry.restart()
    }

    /** 真实网络事件独立于官方广播限速；失去 LAN 时取消待发包，再次可用时启动新一轮。 */
    fun onLanNetworkChange() = handler.post {
        handler.removeCallbacks(sendLan)
        // 官方稍后可能发送含自定义 IP 的发现广播，先预留两秒，避免首个 LAN 探测与它并发。
        lanProbeNotBefore = maxOf(lanProbeNotBefore, SystemClock.elapsedRealtime() + MIN_SEND_INTERVAL_MS)
        lanRetry.restart()
    }

    /** 同一个 LanLink 换 socket 不一定触发设备列表回调；LAN 降到回环时尽早重新探测。 */
    fun onLinksChanged(lanDowngraded: Boolean) {
        val notBefore = if (lanDowngraded) SystemClock.elapsedRealtime() + DESKTOP_CONNECTION_DELAY_MS else 0L
        handler.post {
            if (!started) return@post
            if (lanDowngraded) {
                lanProbeNotBefore = maxOf(lanProbeNotBefore, notBefore)
                lanRetry.restart()
            }
            onDevicesChanged()
        }
    }

    /**
     * 收到来自 127.0.0.1 的身份包：本机代理转发进来的，原始地址已经丢了，回连只会连到自己。
     * 对方没连上时改为向自定义 IP 发身份包，让对方来连我们。对方往往刚启动或刚刷新，
     * 稍等一下再发，免得它还没准备好接收。
     */
    fun onLoopbackIdentity(deviceId: String) = handler.postDelayed({
        if (!started || KdeConnect.getInstance().getDevice(deviceId)?.isReachable == true) return@postDelayed
        if (customRetry.send()) Log.i(TAG, "identity from loopback, asked custom devices to connect to us")
    }, LOOPBACK_REPLY_DELAY_MS)

    private fun wakeUpCustomDevices() {
        if (!started || !customRetry.needsRetry()) return
        customRetry.send()
        customRetry.restart()
    }

    private fun onDevicesChanged() {
        if (!started) return
        if (!customRetry.needsRetry()) customRetry.cancel()
        else if (!customRetry.scheduled) customRetry.restart()
        if (lanPreference.needsRetry()) {
            lanRetry.resume()
        } else {
            lanRetry.cancel()
            handler.removeCallbacks(sendLan)
        }
    }

    private fun sendLanIdentity() {
        handler.removeCallbacks(sendLan)
        if (!started || !lanPreference.needsRetry()) return
        // 按实际探测时间去重；等待期间若又发了自定义身份包，到点后重新计算允许发送的最早时刻。
        val now = SystemClock.elapsedRealtime()
        val notBefore = maxOf(
            customRetry.lastSend + MIN_SEND_INTERVAL_MS,
            lastLanSend + MIN_SEND_INTERVAL_MS,
            lanProbeNotBefore,
        )
        if (now < notBefore) {
            handler.postDelayed(sendLan, notBefore - now)
        } else {
            lastLanSend = now
            lanPreference.send()
        }
    }

    private inner class Retry(
        val needsRetry: () -> Boolean,
        private val sendIdentity: () -> Unit,
        private val bounded: Boolean = false,
    ) {
        var scheduled = false
            private set
        var lastSend = 0L
        private var delayMs = INITIAL_DELAY_MS
        private var exhausted = false
        private var initialSendPending = bounded
        private val tick = Runnable {
            scheduled = false
            if (!started || !needsRetry()) return@Runnable
            send()
            if (bounded && delayMs == MAX_DELAY_MS) {
                exhausted = true
            } else {
                delayMs = (delayMs * 2).coerceAtMost(MAX_DELAY_MS)
                schedule()
            }
        }

        fun restart() {
            cancel()
            delayMs = INITIAL_DELAY_MS
            exhausted = false
            initialSendPending = bounded
            resume()
        }

        fun resume() {
            if (!started || scheduled || exhausted || !needsRetry()) return
            if (initialSendPending) {
                initialSendPending = false
                sendIdentity()
            }
            schedule()
        }

        private fun schedule() {
            handler.postDelayed(tick, delayMs)
            scheduled = true
        }

        fun cancel() {
            handler.removeCallbacks(tick)
            scheduled = false
        }

        /** 多个触发紧接着到来时只发一次，两个探测方向互不抑制。 */
        fun send(): Boolean {
            val now = SystemClock.elapsedRealtime()
            if (now - lastSend < MIN_SEND_INTERVAL_MS) return false
            lastSend = now
            sendIdentity()
            return true
        }
    }

    private companion object {
        const val TAG = "PlusCustomDeviceRetry"
        const val CALLBACK_KEY = "kdeconnectplus.CustomDeviceRetry"
        const val INITIAL_DELAY_MS = 15_000L
        const val MAX_DELAY_MS = 5 * 60_000L
        const val MIN_SEND_INTERVAL_MS = 2_000L
        const val DESKTOP_CONNECTION_DELAY_MS = 500L
        const val LOOPBACK_REPLY_DELAY_MS = 1_500L
    }
}
