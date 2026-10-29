---
name: plus-release
description: 发布和验证 cagedbird043/kdeconnect-android 的 kdeconnect+（cagedbird/plus 分支）：跟进官方新版本、处理 plus-follow 失败、手动发 GitHub Release APK、检查签名与版本号。用户说“发布 kdeconnect+”“跟上游”“plus-follow 挂了”“发个新版 APK”时使用。推送或公开发布前确认用户已授权。
compatibility: Git、gh、JDK 21+、Android SDK（platform 37、build-tools）、可选 adb + Waydroid 或真机。
---

# kdeconnect+ 发布流程

规则以仓库根目录 `PLUS.md` 为准，本 skill 只给操作步骤。

## 前置

- 仓库 `cagedbird043/kdeconnect-android`，分支 `cagedbird/plus`；`gh` 命令始终带 `-R cagedbird043/kdeconnect-android`。
- 官方 tag 从 invent 取：`git fetch --no-tags https://invent.kde.org/network/kdeconnect-android.git '+refs/tags/v*:refs/tags/v*'`。
- 当前基础 tag：`.github/plus/upstream-tag.sh base`；下一个：`.github/plus/upstream-tag.sh select`。
- 提交信息用中文，无 AI 署名；修改已有 plus 补丁用 fixup + `git rebase -i --autosquash <基础 tag>`。

## 情况一：自动跟进正常

`plus-follow` 每天自动跑，不需要人工操作。查看：

```sh
gh run list -R cagedbird043/kdeconnect-android --workflow plus-follow.yml --limit 5
gh release list -R cagedbird043/kdeconnect-android --limit 5
```

## 情况二：plus-follow 失败（已自动停用）

1. 看失败步骤：`gh run view <run-id> -R cagedbird043/kdeconnect-android --log-failed`。
2. rebase 冲突：本地复现并解决，冲突多在 `PLUS.md` 列出的接入点。

   ```sh
   git fetch origin && git switch cagedbird/plus && git reset --hard origin/cagedbird/plus   # 确认没有本地未推送修改后再执行
   old=$(git rev-parse HEAD)
   git rebase --onto <next> <base> cagedbird/plus   # 解决冲突时保持每个 plus 补丁仍是独立提交
   ```

3. 本地验证：`./gradlew --no-daemon assembleDebug testDebugUnitTest`；涉及剪贴板时按 `PLUS.md` 用 Waydroid/真机实测。
4. 推送：`git push --force-with-lease=cagedbird/plus:$old origin cagedbird/plus`。
5. 发布：`gh workflow run plus-release.yml --ref cagedbird/plus -R cagedbird043/kdeconnect-android`，
   用 `gh run watch <id> --exit-status` 等它成功。
6. 重新启用：`gh workflow enable plus-follow.yml -R cagedbird043/kdeconnect-android`。
   若还有更新的官方 tag，可手动 `gh workflow run plus-follow.yml` 继续前进。

## 情况三：同一官方版本上发新版（plus 补丁有改动）

1. 修改、fixup、autosquash，本地验证同上。
2. 推送 `cagedbird/plus`（带租约），再手动运行 `plus-release.yml`；它会把 `N` 加一。

## 发布后检查

```sh
tag=$(gh release list -R cagedbird043/kdeconnect-android --limit 1 --json tagName --jq '.[0].tagName')
gh release download "$tag" -R cagedbird043/kdeconnect-android -p '*.apk' -D /tmp/plus-release --clobber
apksigner verify --print-certs /tmp/plus-release/*.apk   # 证书 SHA-256 必须是 34c55840…2ae1
aapt2 dump badging /tmp/plus-release/*.apk | head -1      # 包名 top.miceworld.kdeconnectplus，versionCode 比上一版大
```

证书指纹变了或 versionCode 没变大，说明发布有问题，不要让用户安装，先查原因。

## 签名密钥

- 只存在仓库 Secrets（`PLUS_KEYSTORE_BASE64` 等）和用户的离线备份中，永远不要重新生成。
- 本地签名构建所需的环境变量见 `PLUS.md`。

只报告实际执行过的验证，失败或受环境限制的检查单列。
