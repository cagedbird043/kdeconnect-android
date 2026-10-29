package top.miceworld.kdeconnectplus.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException

/** root 模式下剪贴板进程和父进程之间的管道协议。 */
class ShellClipboardMainTest {
    @Test
    fun framesRoundTrip() {
        val buffer = ByteArrayOutputStream()
        val out = DataOutputStream(buffer)
        out.writeByte(ShellClipboardMain.FRAME_STATUS)
        out.writeInt(BridgeStatus.UNSUPPORTED_SIGNATURE)
        ShellClipboardMain.writeClip(out, "密码 🔑\n第二行", sensitive = true)
        ShellClipboardMain.writeClip(out, "", sensitive = false)

        val input = DataInputStream(ByteArrayInputStream(buffer.toByteArray()))
        val status = ShellClipboardMain.readFrame(input)
        assertEquals(ShellClipboardMain.FRAME_STATUS, status.type)
        assertEquals(BridgeStatus.UNSUPPORTED_SIGNATURE, status.status)

        val clip = ShellClipboardMain.readFrame(input)
        assertEquals(ShellClipboardMain.FRAME_CLIP, clip.type)
        assertEquals("密码 🔑\n第二行", clip.text)
        assertTrue(clip.sensitive)

        val empty = ShellClipboardMain.readFrame(input)
        assertEquals("", empty.text)
        assertFalse(empty.sensitive)
    }

    @Test(expected = EOFException::class)
    fun truncatedFrameFails() {
        val buffer = ByteArrayOutputStream()
        ShellClipboardMain.writeClip(DataOutputStream(buffer), "hello", sensitive = false)
        val bytes = buffer.toByteArray().copyOf(buffer.size() - 2)
        ShellClipboardMain.readFrame(DataInputStream(ByteArrayInputStream(bytes)))
    }

    @Test(expected = IOException::class)
    fun unknownFrameFails() {
        ShellClipboardMain.readFrame(DataInputStream(ByteArrayInputStream(byteArrayOf(9))))
    }
}
