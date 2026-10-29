package top.miceworld.kdeconnectplus.shizuku

import android.system.Os
import android.system.OsConstants
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FileDescriptor
import java.io.FileOutputStream
import kotlin.system.exitProcess

/**
 * root 模式下的剪贴板进程入口，由 RootClipboardService 用 app_process 启动。
 *
 * 以 uid 0 启动时先降权到 uid 2000 再 execve 自己：binder 在进程启动时就记下了身份，
 * 必须换一个新进程，系统才会把它当成 shell。降权后的进程通过 stdout 把事件传回父进程，
 * stdin 关闭（父进程退出或要求停止）时退出。
 *
 * 参数：<userId> <进程名>
 */
object ShellClipboardMain {
    const val APP_PROCESS = "/system/bin/app_process"

    const val FRAME_STATUS = 1
    const val FRAME_CLIP = 2

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
            synchronized(out) { writeClip(out, text, sensitive) }
        }
        val status = try {
            watcher.start(userId)
        } catch (_: Throwable) {
            BridgeStatus.LAUNCH_FAILED
        }
        synchronized(out) {
            out.writeByte(FRAME_STATUS)
            out.writeInt(status)
            out.flush()
        }
        if (status != BridgeStatus.OK) exitProcess(1)

        @Suppress("ControlFlowWithEmptyBody")
        while (System.`in`.read() != -1) {
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

    fun writeClip(out: DataOutputStream, text: String, sensitive: Boolean) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        out.writeByte(FRAME_CLIP)
        out.writeBoolean(sensitive)
        out.writeInt(bytes.size)
        out.write(bytes)
        out.flush()
    }

    /** 读一帧；返回 FRAME_STATUS 时 [Frame.status] 有效，FRAME_CLIP 时 [Frame.text] 有效。 */
    fun readFrame(input: DataInputStream): Frame = when (val type = input.readUnsignedByte()) {
        FRAME_STATUS -> Frame(type, status = input.readInt())
        FRAME_CLIP -> {
            val sensitive = input.readBoolean()
            val bytes = ByteArray(input.readInt())
            input.readFully(bytes)
            Frame(type, text = String(bytes, Charsets.UTF_8), sensitive = sensitive)
        }
        else -> throw java.io.IOException("unknown frame $type")
    }

    class Frame(val type: Int, val status: Int = 0, val text: String = "", val sensitive: Boolean = false)
}
