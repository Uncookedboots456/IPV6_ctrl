# IPV6 Ctrl

面向 Android Root 设备的 IPv6 控制应用。**v2.0 为预发布版本**：默认提供系统级 IPv6 禁用、修改前快照恢复与联网 API 检测；LSPosed 按应用解析策略作为可选高级功能。

[下载 v2.0 Pre-release](https://github.com/Uncookedboots456/IPV6_ctrl/releases/tag/v2.0) · [完整使用与构建说明](android/README.md) · [旧版 daemon / WebUI 文档](docs/legacy-daemon-webui.md)

## 安装与使用

要求 Android 9 或以上；系统级修改需要 Root。默认使用不需要 LSPosed、应用作用域授权、`curl` 或 `wget`。

1. 从预发布页面下载并安装 `IPV6-Ctrl-v2.0-debug.apk`（主应用）和 `IPV6-Ctrl-Probe-v2.0-debug.apk`（独立探针）。两个 APK 使用同一调试签名，发布页同时提供 SHA-256 校验文件。
2. 打开主应用，点击“读取系统 · Root”，在 Root 管理器中授权。若提示无法调用 `su`，检查超级用户授权及针对本应用的 Root 隐藏设置。
3. 点击“系统级阻断 IPv6”。应用先保存本次开机的接口参数快照，再禁用并回读核对；恢复时选择“恢复修改前系统配置”。
4. 点击“联网检测”，通过独立探针查询 IPv4 / IPv6 API，再返回诊断查看结果。

已有 Android 应用用户应同时更新两个 APK，升级保留原有应用策略。若出现签名不一致，请先用原应用恢复系统配置，再处理旧安装；卸载前也请先恢复。旧版 Root 模块与新版 APK 是不同运行方式，新版不会自动安装或启动旧 daemon。

## 功能与范围

| 功能 | 行为 |
| --- | --- |
| 默认系统级控制 | 通过 Root 修改 `all`、`default` 和各接口的 `disable_ipv6`，影响全设备；不需要逐个应用申请作用域。 |
| 修改前快照 | 保存同次开机的原参数；写入失败时尝试回滚，恢复失败则保留快照供重试。 |
| 手动联网检测 | ipify IPv4 / IPv6 HTTPS API 各请求一次；无后台轮询、自动重试或多服务探测，不保存公网 IP。 |
| 可选应用策略 | 高级入口提供跟随系统、IPv4 优先和 IPv6 解析过滤，需要 LSPosed API 102；默认推荐作用域为空。 |
| 状态与时间线 | 系统参数、回环解析和外网结果分别展示；仅记录当前应用会话的真实操作。 |

## 预发布限制

- 系统修改**仅本次开机有效**，没有开机强制应用或常驻守护。Android 网络组件仍可能重新启用接口，切换 Wi-Fi、蜂窝、VPN、热点后请重新读取并检测。
- 禁用 IPv6 会影响已有连接，IPv6-only 网络可能断网。恢复的是参数，地址与连接需要系统网络栈重新建立。
- API 失败可能来自 DNS、网络、VPN 或服务故障，**不能单凭失败判断系统阻断成功**。联网结果只代表当前网络到该 API 的路径。
- 应用级策略仅控制选定应用的 Java 地址解析，不覆盖 Native 网络栈。旧的探针过滤策略可能影响系统检测，请在高级入口和 LSPosed 中检查已有配置。
- 附件为同签名的调试构建，供预发布测试；尚未完成真实系统恢复、切网与重启行为的完整验收，不宣称永久、全路径的 IPv6 阻断。

本地与 CI 已通过策略检查、API 响应校验、临时目录中的禁用/恢复检查、Android Lint 及两个 APK 构建。隔离测试不等同于所有设备的实机兼容性验证。

## 开发

使用 JDK 21、Android SDK 37.0 和 Build Tools 36.0.0，配置 `ANDROID_HOME` 或本地 `android/local.properties`：

```sh
cd android
sh tests/system_ipv6_test.sh
./gradlew policyTest onlineTest :app:assembleDebug :probe:assembleDebug :app:lintDebug :probe:lintDebug
```

Windows 使用 `gradlew.bat`；隔离 shell 检查可通过 Git Bash 运行。更多说明见 [android/README.md](android/README.md)。

## English

IPV6 Ctrl v2.0 is a pre-release Android app for rooted devices. It defaults to system-wide IPv6 control with a before-change parameter snapshot and manual IPv4/IPv6 HTTPS checks. Per-app Java resolver policies remain optional and require LSPosed API 102.

Install both debug-signed APKs from the [v2.0 pre-release](https://github.com/Uncookedboots456/IPV6_ctrl/releases/tag/v2.0). System changes last for the current boot; network services may re-enable IPv6. Restore parameters before uninstalling. An API failure alone does not prove blocking, and full device restoration/network-transition testing is still pending. See the [Android guide](android/README.md) and [legacy module guide](docs/legacy-daemon-webui.md).

许可证：[GPL-3.0-only](LICENSE)。第三方依赖与图标许可见应用内“开源许可”。
