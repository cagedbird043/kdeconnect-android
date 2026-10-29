# Shizuku 按类名反射创建 UserService；app_process 按类名启动降权后的剪贴板进程。
-keep class top.miceworld.kdeconnectplus.shizuku.ShellClipboardService {
    public <init>(...);
}
-keep class top.miceworld.kdeconnectplus.shizuku.RootClipboardService {
    public <init>(...);
}
-keep class top.miceworld.kdeconnectplus.shizuku.ShellClipboardMain {
    public static void main(java.lang.String[]);
}
