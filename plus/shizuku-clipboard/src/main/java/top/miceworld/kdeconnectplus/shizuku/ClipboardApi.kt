package top.miceworld.kdeconnectplus.shizuku

import android.annotation.TargetApi
import android.content.ClipData
import android.content.ClipDescription
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * 通过反射调用隐藏的 android.content.IClipboard，以 com.android.shell 的身份读写剪贴板。
 * 只能在 uid 2000 的进程里使用：uid 0 冒用 com.android.shell 会让 Android 12+ 清空整个剪贴板。
 *
 * 签名：Android 10–13 是 (pkg, userId)；Android 14+ 是 (pkg, attributionTag, userId, deviceId)。
 * Android 12 起方法顺序也变了，所以按方法名和参数类型查找，不使用事务编号。
 */
internal class ClipboardApi(private val service: Any, private val methods: Methods) {

    class Methods(
        val getPrimaryClip: Method,
        val getPrimaryClipDescription: Method,
        val addListener: Method,
        val removeListener: Method,
        /** 部分 ROM 可能对不上；找不到时只能读不能写。 */
        val setPrimaryClip: Method?,
    ) {
        val withAttribution: Boolean get() = getPrimaryClip.parameterTypes.size == MODERN.size
    }

    fun primaryClip(userId: Int): ClipData? = call(methods.getPrimaryClip, null, userId) as ClipData?

    fun primaryClipDescription(userId: Int): ClipDescription? =
        call(methods.getPrimaryClipDescription, null, userId) as ClipDescription?

    fun addListener(listener: Any, userId: Int) {
        call(methods.addListener, listener, userId)
    }

    /** 不能写（签名不认识）时返回 false。 */
    fun setPrimaryClip(clip: ClipData, userId: Int): Boolean {
        val method = methods.setPrimaryClip ?: return false
        call(method, clip, userId)
        return true
    }

    fun removeListener(listener: Any, userId: Int) {
        call(methods.removeListener, listener, userId)
    }

    private fun call(method: Method, first: Any?, userId: Int): Any? {
        val args = if (methods.withAttribution) {
            arrayOf<Any?>(SHELL_PACKAGE, null, userId, DEVICE_ID_DEFAULT)
        } else {
            arrayOf<Any?>(SHELL_PACKAGE, userId)
        }
        val fullArgs = if (first == null) args else arrayOf(first, *args)
        try {
            return method.invoke(service, *fullArgs)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    companion object {
        const val SHELL_PACKAGE = "com.android.shell"
        private const val DEVICE_ID_DEFAULT = 0
        private const val LISTENER_DESCRIPTOR = "android.content.IOnPrimaryClipChangedListener"

        private val INT: Class<*> = Int::class.javaPrimitiveType!!
        private val LEGACY: List<Class<*>> = listOf(String::class.java, INT)
        private val MODERN: List<Class<*>> = listOf(String::class.java, String::class.java, INT, INT)

        /** 在 IClipboard 接口上找出所需的方法；读和监听用的四个方法签名不认识时返回 null。 */
        fun findMethods(iface: Class<*>): Methods? {
            for (shape in listOf(MODERN, LEGACY)) {
                fun query(name: String) = iface.methods.firstOrNull {
                    it.name == name && it.parameterTypes.toList() == shape
                }
                fun withFirst(name: String) = iface.methods.firstOrNull {
                    it.name == name && it.parameterTypes.size == shape.size + 1 &&
                        it.parameterTypes.drop(1) == shape
                }
                val getClip = query("getPrimaryClip") ?: continue
                val getDescription = query("getPrimaryClipDescription") ?: continue
                val add = withFirst("addPrimaryClipChangedListener") ?: continue
                val remove = withFirst("removePrimaryClipChangedListener") ?: continue
                return Methods(getClip, getDescription, add, remove, withFirst("setPrimaryClip"))
            }
            return null
        }

        /** 连接系统剪贴板服务；失败时返回 BridgeStatus 中的错误码。 */
        fun connect(): Pair<Int, ClipboardApi?> {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, "clipboard") as IBinder?
                ?: return BridgeStatus.NO_CLIPBOARD_SERVICE to null
            val iface = Class.forName("android.content.IClipboard")
            val methods = findMethods(iface) ?: return BridgeStatus.UNSUPPORTED_SIGNATURE to null
            val service = Class.forName("android.content.IClipboard\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)!!
            return BridgeStatus.OK to ClipboardApi(service, methods)
        }

        /**
         * 构造一个 IOnPrimaryClipChangedListener。它在所有版本上都是 oneway，只有一个无参方法，
         * 所以自己实现 Binder 即可，不需要隐藏 API 的 stub。
         */
        @TargetApi(Build.VERSION_CODES.Q)
        fun createListener(onChanged: () -> Unit): Any {
            val binder = object : Binder(LISTENER_DESCRIPTOR) {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    if (code == FIRST_CALL_TRANSACTION) {
                        onChanged()
                        return true
                    }
                    return super.onTransact(code, data, reply, flags)
                }
            }
            return Class.forName("$LISTENER_DESCRIPTOR\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)!!
        }
    }
}
