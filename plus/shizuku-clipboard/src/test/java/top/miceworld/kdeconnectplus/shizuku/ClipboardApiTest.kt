package top.miceworld.kdeconnectplus.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 用假的接口模拟各 Android 版本 IClipboard 的方法形状。 */
class ClipboardApiTest {
    @Suppress("unused")
    interface Android13 {
        fun setPrimaryClip(clip: Any?, pkg: String?, userId: Int)
        fun getPrimaryClip(pkg: String?, userId: Int): Any?
        fun getPrimaryClipDescription(pkg: String?, userId: Int): Any?
        fun addPrimaryClipChangedListener(listener: Any?, pkg: String?, userId: Int)
        fun removePrimaryClipChangedListener(listener: Any?, pkg: String?, userId: Int)
    }

    @Suppress("unused")
    interface Android14 {
        fun setPrimaryClip(clip: Any?, pkg: String?, tag: String?, userId: Int, deviceId: Int)
        fun getPrimaryClip(pkg: String?, tag: String?, userId: Int, deviceId: Int): Any?
        fun getPrimaryClipDescription(pkg: String?, tag: String?, userId: Int, deviceId: Int): Any?
        fun addPrimaryClipChangedListener(listener: Any?, pkg: String?, tag: String?, userId: Int, deviceId: Int)
        fun removePrimaryClipChangedListener(listener: Any?, pkg: String?, tag: String?, userId: Int, deviceId: Int)
    }

    /** 某个 ROM 只改了一个方法：不能混用两套签名。 */
    @Suppress("unused")
    interface Mixed {
        fun getPrimaryClip(pkg: String?, tag: String?, userId: Int, deviceId: Int): Any?
        fun getPrimaryClipDescription(pkg: String?, userId: Int): Any?
        fun addPrimaryClipChangedListener(listener: Any?, pkg: String?, tag: String?, userId: Int, deviceId: Int)
        fun removePrimaryClipChangedListener(listener: Any?, pkg: String?, tag: String?, userId: Int, deviceId: Int)
    }

    @Test
    fun legacySignature() {
        val methods = ClipboardApi.findMethods(Android13::class.java)!!
        assertFalse(methods.withAttribution)
        assertEquals(2, methods.getPrimaryClip.parameterCount)
        assertEquals(3, methods.addListener.parameterCount)
    }

    @Test
    fun modernSignature() {
        val methods = ClipboardApi.findMethods(Android14::class.java)!!
        assertTrue(methods.withAttribution)
        assertEquals(4, methods.getPrimaryClipDescription.parameterCount)
        assertEquals(5, methods.removeListener.parameterCount)
    }

    @Test
    fun unknownSignature() {
        assertNull(ClipboardApi.findMethods(Mixed::class.java))
        assertNull(ClipboardApi.findMethods(Runnable::class.java))
    }
}
