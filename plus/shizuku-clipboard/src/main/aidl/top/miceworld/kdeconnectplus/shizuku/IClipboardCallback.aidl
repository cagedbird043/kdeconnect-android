package top.miceworld.kdeconnectplus.shizuku;

oneway interface IClipboardCallback {
    void onClipChanged(String text, boolean sensitive) = 1;

    // 剪贴板监听意外停止（例如 root 模式下降权后的进程退出），参数是 BridgeStatus 中的状态码。
    void onStopped(int status) = 2;
}
