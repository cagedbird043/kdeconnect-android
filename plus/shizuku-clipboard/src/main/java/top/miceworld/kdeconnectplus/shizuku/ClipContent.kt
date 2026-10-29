package top.miceworld.kdeconnectplus.shizuku

import android.annotation.TargetApi
import android.content.ClipData
import android.content.ClipDescription
import android.os.Build
import android.os.PersistableBundle
import android.text.Html

/** 剪贴板内容的解析与标记；应用进程和 shell 进程共用。 */
object ClipContent {
    /** 写在 ClipDescription extras 里，表示这条内容是电脑发来的，不要再发回去。 */
    const val EXTRA_FROM_REMOTE = "top.miceworld.kdeconnectplus.FROM_REMOTE"

    /** 与 ClipDescription.EXTRA_IS_SENSITIVE 相同；直接用字符串，Android 13 以前也能读到。 */
    private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

    fun markFromRemote(clip: ClipData) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val extras = clip.description.extras ?: PersistableBundle()
        extras.putBoolean(EXTRA_FROM_REMOTE, true)
        clip.description.extras = extras
    }

    fun isFromRemote(description: ClipDescription): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
            description.extras?.getBoolean(EXTRA_FROM_REMOTE, false) == true

    fun isSensitive(description: ClipDescription): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
            description.extras?.getBoolean(EXTRA_IS_SENSITIVE, false) == true

    /** 取第一项的文本：优先纯文本，其次把 HTML 转成纯文本；只有 URI（图片、文件）时返回 null。 */
    @TargetApi(Build.VERSION_CODES.N)
    fun extractText(clip: ClipData): String? {
        if (clip.itemCount == 0) return null
        val item = clip.getItemAt(0)
        item.text?.let { return it.toString() }
        val html = item.htmlText ?: return null
        return Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString().trim()
    }
}
