package top.miceworld.kdeconnectplus.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Log
import org.kde.kdeconnect_tp.R
import rikka.shizuku.Shizuku
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 应用进程这一侧：根据 Shizuku 的状态绑定对应的 UserService，把剪贴板事件交给 [onClip]。
 * 所有状态变化都在自己的线程上串行处理，每一步都可以安全地重复执行。
 */
object ShizukuClipboard {
    enum class State(val summary: Int) {
        DISABLED(R.string.plus_shizuku_state_disabled),
        UNSUPPORTED_ANDROID(R.string.plus_shizuku_state_unsupported_android),
        NOT_INSTALLED(R.string.plus_shizuku_state_not_installed),
        NOT_RUNNING(R.string.plus_shizuku_state_not_running),
        OUTDATED(R.string.plus_shizuku_state_outdated),
        PERMISSION_REQUIRED(R.string.plus_shizuku_state_permission_required),
        PERMISSION_DENIED(R.string.plus_shizuku_state_permission_denied),
        UNSUPPORTED_MODE(R.string.plus_shizuku_state_unsupported_mode),
        CONNECTING(R.string.plus_shizuku_state_connecting),
        READY_SHELL(R.string.plus_shizuku_state_ready_shell),
        READY_ROOT(R.string.plus_shizuku_state_ready_root),
        ERROR(R.string.plus_shizuku_state_error),
    }

    const val PREF_ENABLED = "plus_shizuku_clipboard_enabled"
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val MIN_SHIZUKU_VERSION = 13
    private const val PERMISSION_REQUEST_CODE = 0x504c
    private const val TAG = "PlusShizukuClipboard"

    @Volatile
    var state: State = State.DISABLED
        private set

    /** state 为 ERROR 时的 BridgeStatus 状态码。 */
    @Volatile
    var errorStatus: Int = BridgeStatus.OK
        private set

    val isReady: Boolean get() = state == State.READY_SHELL || state == State.READY_ROOT

    private val stateListeners = CopyOnWriteArraySet<() -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker by lazy { Handler(HandlerThread("PlusShizukuClipboard").apply { start() }.looper) }

    private lateinit var appContext: Context
    private var onClip: ((String, Boolean) -> Unit)? = null
    private var bridge: IClipboardBridge? = null
    private var boundArgs: Shizuku.UserServiceArgs? = null
    private var shizukuListenersAdded = false

    private val callback = object : IClipboardCallback.Stub() {
        override fun onClipChanged(text: String, sensitive: Boolean) {
            mainHandler.post { onClip?.invoke(text, sensitive) }
        }

        override fun onStopped(status: Int) {
            worker.post {
                bridge = null
                setState(State.ERROR, status)
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            worker.post { onBridgeConnected(IClipboardBridge.Stub.asInterface(binder)) }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            worker.post {
                bridge = null
                boundArgs = null
                evaluate()
            }
        }
    }

    private val binderReceived = Shizuku.OnBinderReceivedListener { worker.post(::evaluate) }
    private val binderDead = Shizuku.OnBinderDeadListener {
        worker.post {
            bridge = null
            boundArgs = null
            evaluate()
        }
    }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { _, _ -> worker.post(::evaluate) }

    /** 应用启动时调用一次；[onClip] 在主线程上收到 (文本, 是否敏感)。 */
    fun init(context: Context, onClip: (text: String, sensitive: Boolean) -> Unit) {
        appContext = context.applicationContext
        this.onClip = onClip
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            state = State.UNSUPPORTED_ANDROID
            return
        }
        worker.post(::evaluate)
    }

    fun isEnabled(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            context.getSharedPreferences(context.packageName + "_preferences", Context.MODE_PRIVATE)
                .getBoolean(PREF_ENABLED, false)

    /** 设置页的开关变化后调用；开启且未授权时会弹出 Shizuku 授权框。 */
    fun onEnabledChanged() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        worker.post {
            evaluate()
            if (state == State.PERMISSION_REQUIRED) {
                Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
            }
        }
    }

    fun requestPermission() {
        worker.post {
            if (Shizuku.pingBinder() && !Shizuku.isPreV11()) Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
        }
    }

    fun addStateListener(listener: () -> Unit) {
        stateListeners.add(listener)
    }

    fun removeStateListener(listener: () -> Unit) {
        stateListeners.remove(listener)
    }

    /** 1 显示、0 不显示、-1 不可用（未就绪或读取失败）。会做跨进程调用，不要在主线程调用。 */
    fun getAccessNotificationsEnabled(): Int = try {
        bridge?.accessNotificationsEnabled ?: -1
    } catch (e: Exception) {
        -1
    }

    /** 会做跨进程调用，不要在主线程调用。 */
    fun setAccessNotificationsEnabled(enabled: Boolean): Boolean = try {
        bridge?.setAccessNotificationsEnabled(enabled) ?: false
    } catch (e: Exception) {
        false
    }

    private fun evaluate() {
        if (!shizukuListenersAdded) {
            shizukuListenersAdded = true
            Shizuku.addBinderReceivedListenerSticky(binderReceived, worker)
            Shizuku.addBinderDeadListener(binderDead, worker)
            Shizuku.addRequestPermissionResultListener(permissionResult, worker)
        }
        if (!isEnabled(appContext)) {
            unbind()
            setState(State.DISABLED)
            return
        }
        if (!Shizuku.pingBinder()) {
            bridge = null
            boundArgs = null
            setState(if (isShizukuInstalled()) State.NOT_RUNNING else State.NOT_INSTALLED)
            return
        }
        if (Shizuku.isPreV11() || Shizuku.getVersion() < MIN_SHIZUKU_VERSION) {
            setState(State.OUTDATED)
            return
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            unbind()
            setState(
                if (Shizuku.shouldShowRequestPermissionRationale()) State.PERMISSION_DENIED
                else State.PERMISSION_REQUIRED
            )
            return
        }
        if (bridge != null || boundArgs != null) return

        val serviceClass = when (Shizuku.getUid()) {
            BridgeStatus.SHELL_UID -> ShellClipboardService::class.java
            BridgeStatus.ROOT_UID -> RootClipboardService::class.java
            else -> {
                setState(State.UNSUPPORTED_MODE)
                return
            }
        }
        val packageInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        val args = Shizuku.UserServiceArgs(ComponentName(appContext.packageName, serviceClass.name))
            .tag(serviceClass.simpleName)
            .processNameSuffix("clipboard")
            .daemon(false)
            .version(packageInfo.longVersionCode.toInt())
            .debuggable(appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        boundArgs = args
        setState(State.CONNECTING)
        Shizuku.bindUserService(args, connection)
    }

    private fun onBridgeConnected(service: IClipboardBridge) {
        if (!isEnabled(appContext)) {
            unbind()
            return
        }
        val status = try {
            service.start(callback, android.os.Process.myUid() / PER_USER_RANGE)
        } catch (e: Exception) {
            Log.w(TAG, "start failed", e)
            BridgeStatus.LAUNCH_FAILED
        }
        if (status != BridgeStatus.OK) {
            setState(State.ERROR, status)
            return
        }
        bridge = service
        setState(if (Shizuku.getUid() == BridgeStatus.ROOT_UID) State.READY_ROOT else State.READY_SHELL)
    }

    private fun unbind() {
        try {
            bridge?.stop()
        } catch (_: Exception) {
        }
        bridge = null
        boundArgs?.let {
            try {
                Shizuku.unbindUserService(it, connection, true)
            } catch (e: Exception) {
                Log.w(TAG, "unbind failed", e)
            }
        }
        boundArgs = null
    }

    private fun isShizukuInstalled(): Boolean = try {
        appContext.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun setState(newState: State, status: Int = BridgeStatus.OK) {
        if (state == newState && errorStatus == status) return
        state = newState
        errorStatus = status
        mainHandler.post { stateListeners.forEach { it() } }
    }

    private const val PER_USER_RANGE = 100000
}
