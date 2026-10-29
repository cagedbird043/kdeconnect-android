package top.miceworld.kdeconnectplus.shizuku

import android.util.Log

/** 系统全局设置 clipboard_show_access_notifications（“X 已粘贴”提示），在特权进程里用 settings 命令读写。 */
internal object AccessNotifications {
    private const val KEY = "clipboard_show_access_notifications"

    /** 返回 1（显示）、0（不显示）或 -1（读取失败）。未设置时系统默认显示。 */
    fun get(): Int {
        val output = run("get") ?: return -1
        return if (output.trim() == "0") 0 else 1
    }

    fun set(enabled: Boolean): Boolean = run("put", if (enabled) "1" else "0") != null

    private fun run(vararg args: String): String? = try {
        val process = ProcessBuilder("/system/bin/settings", args[0], "secure", KEY, *args.drop(1).toTypedArray())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        if (process.waitFor() == 0) output else null
    } catch (e: Exception) {
        Log.w("PlusClipboard", "settings ${args.joinToString(" ")} failed", e)
        null
    }
}
