package top.miceworld.kdeconnectplus.shizuku

/** IClipboardBridge.start 与 IClipboardCallback.onStopped 使用的状态码。 */
object BridgeStatus {
    const val OK = 0
    const val NOT_SHELL_UID = 1
    const val NO_CLIPBOARD_SERVICE = 2
    const val UNSUPPORTED_SIGNATURE = 3
    const val NOT_ROOT_UID = 4
    const val LAUNCH_FAILED = 5
    const val PROCESS_EXITED = 6
    const val NO_APK_PATH = 7

    const val SHELL_UID = 2000
    const val ROOT_UID = 0
}
