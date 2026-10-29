package top.miceworld.kdeconnectplus.shizuku

import android.system.Os
import android.system.OsConstants
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import kotlin.system.exitProcess

/**
 * root 模式下的剪贴板进程入口，由 RootClipboardService 用 app_process 启动。
 *
 * 以 uid 0 启动时先降权到 uid 2000 再 execve 自己：binder 在进程启动时就记下了身份，
 * 必须换一个新进程，系统才会把它当成 shell。降权后的进程通过 stdout 把事件和写入结果传回父进程，
 * 从 stdin 读父进程要写入的文本；stdin 关闭（父进程退出或要求停止）时退出。
 *
 * 参数：<userId> <进程名>
 */
object ShellClipboardMain {
    const val APP_PROCESS = "/system/bin/app_process"

    /** 子 → 父：启动结果，[Frame.status] 是 BridgeStatus 状态码。 */
    const val FRAME_STATUS = 1

    /** 子 → 父：剪贴板变化，[Frame.text] 与 [Frame.sensitive] 有效。 */
    const val FRAME_CLIP = 2

    /** 父 → 子：要写入剪贴板的文本。 */
    const val FRAME_SET_TEXT = 3

    /** 子 → 父：上一条 FRAME_SET_TEXT 的结果，[Frame.status] 为 1 成功、0 失败。 */
    const val FRAME_WRITTEN = 4

    @JvmStatic
    fun main(args: Array<String>) {
        val userId = args[0].toInt()
        val niceName = args[1]
        if (Os.getuid() == BridgeStatus.ROOT_UID) {
            dropToShell(args, niceName)
            return
        }

        // 事件通道单独占用原来的 stdout，fd 1 本身指向 /dev/null，避免其它输出混进来。
        val channel = Os.dup(FileDescriptor.out)
        val devNull = Os.open("/dev/null", OsConstants.O_WRONLY, 0)
        Os.dup2(devNull, OsConstants.STDOUT_FILENO)
        Os.close(devNull)
        val out = DataOutputStream(BufferedOutputStream(FileOutputStream(channel)))

        val watcher = ClipboardWatcher { text, sensitive ->
            synchronized(out) { writeText(out, FRAME_CLIP, text, sensitive) }
        }
        val status = try {
            watcher.start(userId)
        } catch (_: Throwable) {
            BridgeStatus.LAUNCH_FAILED
        }
        synchronized(out) { writeStatus(out, FRAME_STATUS, status) }
        if (status != BridgeStatus.OK) exitProcess(1)

        val input = DataInputStream(BufferedInputStream(System.`in`))
        try {
            while (true) {
                val frame = readFrame(input)
                if (frame.type != FRAME_SET_TEXT) continue
                val written = watcher.write(frame.text)
                synchronized(out) { writeStatus(out, FRAME_WRITTEN, if (written) 1 else 0) }
            }
        } catch (_: IOException) {
        }
        watcher.stop()
        exitProcess(0)
    }

    private fun dropToShell(args: Array<String>, niceName: String) {
        Os.setgid(BridgeStatus.SHELL_UID)
        Os.setuid(BridgeStatus.SHELL_UID)
        if (Os.getuid() != BridgeStatus.SHELL_UID || Os.geteuid() != BridgeStatus.SHELL_UID) exitProcess(2)
        val env = System.getenv().map { "${it.key}=${it.value}" }.toTypedArray()
        Os.execve(
            APP_PROCESS,
            arrayOf(APP_PROCESS, "/system/bin", "--nice-name=$niceName", ShellClipboardMain::class.java.name, *args),
            env,
        )
    }

    fun writeStatus(out: DataOutputStream, type: Int, status: Int) {
        out.writeByte(type)
        out.writeInt(status)
        out.flush()
    }

    fun writeText(out: DataOutputStream, type: Int, text: String, sensitive: Boolean) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        out.writeByte(type)
        out.writeBoolean(sensitive)
        out.writeInt(bytes.size)
        out.write(bytes)
        out.flush()
    }

    /** 读一帧；状态类帧 [Frame.status] 有效，文本类帧 [Frame.text] 与 [Frame.sensitive] 有效。 */
    fun readFrame(input: DataInputStream): Frame = when (val type = input.readUnsignedByte()) {
        FRAME_STATUS, FRAME_WRITTEN -> Frame(type, status = input.readInt())
        FRAME_CLIP, FRAME_SET_TEXT -> {
            val sensitive = input.readBoolean()
            val bytes = ByteArray(input.readInt())
            input.readFully(bytes)
            Frame(type, text = String(bytes, Charsets.UTF_8), sensitive = sensitive)
        }
        else -> throw IOException("unknown frame $type")
    }

    class Frame(val type: Int, val status: Int = 0, val text: String = "", val sensitive: Boolean = false)
}
