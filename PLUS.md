# kdeconnect+ 维护规则

本仓库是 KDE Connect Android（`invent.kde.org/network/kdeconnect-android`）的个人下游：
`cagedbird043/kdeconnect-android`，工作分支 `cagedbird/plus`。
`cagedbird/plus` = 某个官方正式版本 tag + 一组 plus 补丁提交。本文件由 `.omp/config.yml` 的
`contextFiles.extra` 加载；与官方规则冲突时以本文件为准。

## 分支与跟进

- `cagedbird/plus` 只建立在官方正式版本 tag 上，跟进新 tag 用 rebase，不 merge。
- 官方版本以 invent.kde.org 的 tag 为准；`upstream`（GitHub）只是镜像。
- 官方文件尽量不改；必须改的地方改动要小且集中，降低 rebase 冲突。新功能优先做成独立插件。
- plus 专属内容放在官方没有的路径（如本文件、`.omp/config.yml`、`.github/workflows/plus-*.yml`、`.github/plus/`、
  `plus/`、`.agents/skills/plus-release/`）。官方文件里的 plus 改动都带 `kdeconnect+` 注释，方便 rebase 时识别。
- 通用修复优先向官方提 MR（remote `invent-fork`）；合入后删除对应 plus 补丁。

## 提交组织

- 每个 plus 补丁是一个按主题划分的独立提交，提交信息用中文。
- 修改已有补丁用 `git commit --fixup=<该补丁提交>`，再 `git rebase -i --autosquash <官方 tag>` 合并回去；
  不在补丁之上堆叠零散的修补提交。
- 推送 `cagedbird/plus` 使用 `git push --force-with-lease`。

## 包名与升级

- 包名 `top.miceworld.kdeconnectplus`，与官方版共存；不改 Kotlin 命名空间和源码包名。
- 官方包名硬编码的适配点：`NotificationsPlugin.kt` 用实际包名过滤自身通知，
  `CompositeReceiveFileJob.kt` 用 `BuildConfig.APPLICATION_ID` 构造 FileProvider authority。
- 已发布版本必须能被之后的版本直接覆盖升级：
  - 始终使用同一把 release 签名密钥；密钥只存在仓库 Secrets 和用户的离线备份中，不进仓库。
    仓库里的 `debug.keystore` 是官方自带的，不用于发布。
  - versionCode 每次发布只增不减。

## 版本

- versionName `X.Y.Z-plus.N`：`X.Y.Z` 是所基于的官方 tag；同一 tag 上重新发布递增 `N`，跟进新 tag 从 `plus.1` 重计。
- versionCode = 官方 versionCode × 100 + N（`N` ≤ 99），由 `./gradlew -PplusRelease=N` 传入；不传时 N 为 0，只用于本地构建。
- 包名、版本号、签名都集中在 `build.gradle.kts` 里 `android {}` 末尾的 `kdeconnect+` 块。

## 自动化（GitHub Actions）

仓库默认分支是 `cagedbird/plus`（定时任务只在默认分支上运行）。

- `plus-follow.yml`：每天一次，也可手动运行。从 invent.kde.org 取 `vX.Y.Z` 正式版 tag，
  若有比当前基础更新的 tag，就把补丁 rebase 到**下一个** tag 上（一次只前进一个），编译并跑单元测试，
  用部署钥匙推送 `cagedbird/plus`，再触发 `plus-release.yml` 并等它成功。
  任何一步失败都会停用本工作流；人工修好（通常是解决 rebase 冲突后推送）再到 Actions 页面重新启用。
- `plus-release.yml`：只在 `cagedbird/plus` 上运行。计算 `vX.Y.Z-plus.N`，跑单元测试，编译签名 APK，
  创建同名 GitHub Release 并上传 `kdeconnect-plus-X.Y.Z-plus.N.apk` 和 R8 符号表
  `kdeconnect-plus-X.Y.Z-plus.N-mapping.txt`（用来还原崩溃日志）。Obtainium 订阅本仓库的 Release 即可更新。
- `.github/plus/upstream-tag.sh`：两者共用，找出当前基于的官方 tag 和下一个 tag。
- 仓库 Secrets：`PLUS_DEPLOY_KEY`（可写部署钥匙的私钥）、`PLUS_KEYSTORE_BASE64`、`PLUS_KEYSTORE_PASSWORD`、
  `PLUS_KEY_ALIAS`、`PLUS_KEY_PASSWORD`。
- 本地签名构建：设置环境变量 `PLUS_KEYSTORE_FILE`、`PLUS_KEYSTORE_PASSWORD`、`PLUS_KEY_ALIAS`、`PLUS_KEY_PASSWORD`。
- 手动发版步骤见 skill `plus-release`。

## 功能：通过 Shizuku 自动同步剪贴板

- 代码在 `plus/shizuku-clipboard/`（Kotlin、AIDL、资源、R8 规则、单元测试），由 `build.gradle.kts` 末尾的
  `kdeconnect+` 块并入应用模块；不是独立 Gradle 子项目，因为官方的许可证报告插件无法解析 Android 库子项目。
- 官方文件里的接入点：`ClipboardListener.kt`（启动 Shizuku、`publish()`、写入优先经 Shizuku 并打 `FROM_REMOTE` 标记）、
  `ClipboardPlugin.kt`（`canSyncAutomatically` 与设置页）、`AndroidManifest.xml`（ShizukuProvider、`overrideLibrary`）。
  跟进官方时这几处最容易冲突。
- 读和写都以 shell 身份进行。写入（电脑→手机）也走 Shizuku，因为 ColorOS 等系统会拦截后台应用写剪贴板；
  Shizuku 未就绪或写入失败时退回官方的 `ClipboardManager.setPrimaryClip`。
- adb/无线调试模式：Shizuku UserService（uid 2000）直接以 `com.android.shell` 身份调用 IClipboard。
- root/Sui 模式：**uid 0 绝不能直接调用 IClipboard**，冒用 `com.android.shell` 会让 Android 12+ 清空整个剪贴板。
  UserService（uid 0）用 `app_process` 启动 `ShellClipboardMain`，它 `setuid(2000)` 后 `execve` 自己，
  以真正的 shell 身份重新打开 binder；两者之间用 stdin/stdout 管道传递剪贴板变化、写入请求和写入结果。
- IClipboard 签名按方法名和参数类型反射查找（Android 10–13 与 14+ 两套），不使用事务编号。
- Shizuku 在应用运行期间重启后，要打开一次 KDE Connect+ 才能重新拿到 binder（Shizuku 的发送机制所致）。
- 测试：`./gradlew testDebugUnitTest --tests 'top.miceworld.*'`；真机或 Waydroid 上分别用 root 与 adb 模式验证。

## 功能：自定义 IP 自动重连

面向手机经 sing-box 等本机代理接入 tailnet 的用法：电脑发来的发现包和连接在手机上都显示为来自 `127.0.0.1`。

- 代码在 `plus/reconnect/`（`CustomDeviceRetry`、`LanLinkPreference`、“重连”按钮与 `LoopbackPayloadAddress`），
  由 `build.gradle.kts` 的 `kdeconnect+` 块并入。
- 有已配对设备没连上且设置了自定义 IP 时，定时向自定义 IP 重发身份包：15 秒起，每次翻倍，最长 5 分钟；
  全部连上就停。亮屏、打开应用时立即重发；用普通 Handler 计时，不唤醒休眠中的手机。
- 已配对设备只通过回环连接可达、且有受信任的非 VPN Wi-Fi/以太网时，每轮立即探测 LAN，随后依次间隔
  15、30、60、120、240、300 秒各探测一次，然后停止；网络变化、LAN 降为回环或打开应用时才重启一轮。
  真实网络事件在官方 200 毫秒发现广播限速前通知探测器，并为随后的官方广播预留至少 2 秒再探测 LAN；失去 LAN 时取消待发探测，恢复可用时重启一轮。
  某台已配对设备可能永远不在这个 LAN；限制每轮次数，避免它让已走 LAN 的桌面端在每次广播时都重新连接。
  IPv4 广播尝试绑定物理网络，不附带自定义 IP；无 IPv4 时由发送器记录发送失败，绑定失败则沿用官方逻辑退回未绑定 socket。
  亮屏只触发自定义 IP 重连，不重启这轮 LAN 探测；官方原有的单次发现广播保留。
  若同时重试其他断线设备，则与最近的自定义 IP 广播错开至少 2 秒，避开桌面端设备 ID 限速窗口。
  没有已配对的回环连接或没有可用 LAN 时取消待发探测；普通非回环连接不触发额外探测。
- 同一设备且证书一致的新连接沿用官方 `LanLink.reset` 替换旧 socket，以最新连接为准，回环连接也可接替失效的 LAN。
  回环连接可在本轮 LAN 探测中再次升级；桌面端无需修改，同 ID 且证书一致的连接也会替换 socket。
  已有非回环 socket 被回环替换时立即触发 LAN 探测，不等首轮 15 秒：满足距最近自定义 IP 广播和 LAN 探测各至少 2 秒、
  距降级至少 500 毫秒后，在最早允许的时刻发送；等待期间重算限速窗口。未升级时继续本轮有限退避。
- LAN 探测与常规发现共用 Android 17 局域网权限检查；网络枚举失败时记录日志并暂停探测。
- 回环与非回环连接的设备 ID 限速分开，仍各保留官方 1 秒间隔；非回环的 IP 限速不变，避免 tailnet 身份包阻挡 LAN 升级。
  收到回环身份包时不回连（只会连到自己），等 1.5 秒后改为请对方来连；回环包不按 IP 限速，并撤销其回环设备 ID
  限速登记，否则对方紧接着发来的连接会被丢掉。
- 控制连接来自回环地址时，载荷连接改试自定义 IP：先试该设备上次成功的地址，再按配置顺序尝试其余地址；
  每次 TCP 连接和 TLS 握手分别限时 3 秒，用已配对设备的证书验证发送端，握手失败也继续尝试。
  成功地址只在线程安全的内存缓存中保存，网络或自定义设备列表变化时清空；非回环连接保持官方流程。
- 官方文件里的接入点：`LanLinkProvider.kt`（按路径限速、回连、LAN 探测、共用局域网权限检查、`broadcastUdpIdentityPacket` 的
  `customDevicesOnly`、启停、链路/网络变化与自定义地址）、`LanLink.kt`（当前 socket 的回环判断、回环连接的载荷地址回退）、
  `PairingScreen.kt`（“记住的设备”上的重连按钮）。

## 开发环境

- Kotlin 语言服务器用 omp 内置的 `kotlin-lsp` 配置（JetBrains 官方 kotlin-lsp，Android Gradle 支持为实验性）。
  Arch 上从 AUR 安装 `kotlin-lsp-bin`；装好后要重开 omp 会话才会识别。
- `android-smsmms` 捆绑 `LinkProperties`、`LinkAddress`、`RouteInfo` 等 `android.net` 同名类；plus 代码不能引用这些被覆盖的类，避免 R8 把副本并入 APK。
