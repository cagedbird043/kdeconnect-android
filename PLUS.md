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
  创建同名 GitHub Release 并上传 `kdeconnect-plus-X.Y.Z-plus.N.apk`。Obtainium 订阅本仓库的 Release 即可更新。
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
