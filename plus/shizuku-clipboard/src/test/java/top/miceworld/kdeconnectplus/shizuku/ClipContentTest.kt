package top.miceworld.kdeconnectplus.shizuku

import android.content.ClipData
import android.content.ClipDescription
import android.net.Uri
import android.os.PersistableBundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
// 本功能只在 Android 10+ 启用；不指定时 Robolectric 用 minSdk 23。
@Config(sdk = [29])
class ClipContentTest {
    @Test
    fun plainTextWins() {
        assertEquals("hello", ClipContent.extractText(ClipData.newPlainText(null, "hello")))
    }

    @Test
    fun htmlFallsBackToPlainText() {
        val clip = ClipData(ClipDescription(null, arrayOf(ClipDescription.MIMETYPE_TEXT_HTML)),
            ClipData.Item(null, "<b>bold</b> text"))
        assertEquals("bold text", ClipContent.extractText(clip))
    }

    @Test
    fun uriOnlyIsSkipped() {
        val clip = ClipData(ClipDescription(null, arrayOf("image/png")), ClipData.Item(Uri.parse("content://x/1")))
        assertNull(ClipContent.extractText(clip))
    }

    @Test
    fun remoteMarkSurvivesExistingExtras() {
        val clip = ClipData.newPlainText(null, "from pc")
        assertFalse(ClipContent.isFromRemote(clip.description))
        clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }

        ClipContent.markFromRemote(clip)

        assertTrue(ClipContent.isFromRemote(clip.description))
        assertTrue(ClipContent.isSensitive(clip.description))
    }

    @Test
    fun sensitiveFlag() {
        val clip = ClipData.newPlainText(null, "secret")
        assertFalse(ClipContent.isSensitive(clip.description))
        clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        assertTrue(ClipContent.isSensitive(clip.description))
    }
}
