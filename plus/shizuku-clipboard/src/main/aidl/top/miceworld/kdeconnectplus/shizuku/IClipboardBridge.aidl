package top.miceworld.kdeconnectplus.shizuku;

import top.miceworld.kdeconnectplus.shizuku.IClipboardCallback;

// 由 Shizuku UserService 实现：shell 模式是 ShellClipboardService，root 模式是 RootClipboardService。
interface IClipboardBridge {
    // Shizuku 约定的销毁事务编号。
    void destroy() = 16777114;

    // 开始监听 userId 的剪贴板，返回 BridgeStatus 中的状态码。重复调用会替换 callback。
    int start(IClipboardCallback callback, int userId) = 1;

    void stop() = 2;

    // 系统全局设置 clipboard_show_access_notifications；读取失败返回 -1，否则 0 或 1。
    int getAccessNotificationsEnabled() = 3;

    boolean setAccessNotificationsEnabled(boolean enabled) = 4;

    // 以 shell 身份写入电脑发来的文本（带 FROM_REMOTE 标记）。未在监听或写入失败时返回 false。
    boolean setText(String text) = 5;
}
