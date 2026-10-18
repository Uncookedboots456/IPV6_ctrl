# IPV6 Ctrl

帮助 Android Root 设备**回退到纯 IPv4** 的系统级网络控制客户端。当前源码为 **2.1-dev**：一个客户端 APK 加一个 Root Runtime 模块，联网检测直接在客户端运行，已移除按应用控制、LSPosed Hook、作用域请求和独立探针 APK。

支持名为 **ipv6** 的下拉快捷开关：亮起为 IPv6 开启，关闭为纯 IPv4；默认跟随系统，不自动接管，按实际状态显示。可在客户端诊断页添加，首次需授权 Root。正常切换留在下拉面板，以副标题反馈进度，不弹窗或跳页；当前版本待[真机复测](docs/quick-tile-validation-20261019.md)。快捷开关需配套本次 Runtime（协议 v2）。

[客户端使用与构建](android/README.md) · [Runtime 说明](module/README.md) · [旧版 daemon / WebUI](docs/legacy-daemon-webui.md)

## 开发版安装与使用

开发版尚未发布。已发布的 [v2.0 Pre-release](https://github.com/Uncookedboots456/IPV6_ctrl/releases/tag/v2.0) 仍为单次修改、两个 APK 的旧方案，附件保持原样；参见 [v2.0 说明](docs/v2.0-release.md)。请使用同一源码构建的 2.1-dev 客户端与 Runtime zip。

1. Runtime 支持 arm64 / Android 11+，客户端支持 Android 9+。先在旧客户端恢复系统配置；若使用过应用策略，先恢复“跟随系统”、停用本模块并清空其 LSPosed 作用域，再重启目标进程或设备。升级后可卸载旧的 `dev.ipv6ctrl.probe`。
2. 通过 Root 管理器安装 `IPV6-Ctrl-runtime-2.1-dev-arm64.zip`，重启设备并安装客户端。开机服务默认待命，不猜测用户目标或自动禁用 IPv6。
3. 客户端点击“读取系统 · Root”授权并检查所有接口。点击“启用纯 IPv4”，保存修改前快照、逐项写入并核对后，独立服务每 3 秒检查各接口，仅修正偏离值；退出客户端后继续运行。
4. 点击“检测网络”，客户端向 ipify 的 IPv4 / IPv6 HTTPS API 各查询一次，结果在首页及诊断页查看。首次打开、后台服务均不会自动请求 API，无自动重试或备用服务，不保存公网 IP。
5. 诊断页的“停止持续保持”解除接管并保留参数；“恢复原网络设置”先停止接管，再恢复快照。恢复失败保留快照，重启设备后旧目标与快照失效、默认不接管。卸载前请先恢复并核对。

## 范围与限制

- 系统控制修改 `all`、`default` 和各接口的 `disable_ipv6`，影响全设备及已有连接；IPv6-only 网络可能断网。恢复参数后，地址和连接仍需系统重新建立。
- 周期纠偏存在窗口，不能保证零泄漏。daemon 异常退出没有自动重启监督，需在客户端刷新检查服务状态；切网后应重新读取和检测。
- IPv6 API 成功说明检测时客户端到 API 的 IPv6 路径可用；失败可能由 DNS、VPN、网络或 API 故障引起，不能单凭失败证明阻断成功。
- 联网结果只保留在当前客户端进程中，系统修改后标记需重测；不会复用旧独立探针报告。时间线最多 40 条实际会话事件。
- 使用系统原生 Java UI，无第三方运行库。Root Runtime 与客户端不通过应用 Hook 或 Zygisk 通信。

## 验证与构建

此前单客户端版本通过主机临时文件联测、Android 真机隔离，以及 KernelSU 安装/一次真实开机、接管、强制停止客户端、新接口纠偏、暂停、双向切网和快照恢复检查。新增快捷开关仍待真机复测；后续 Android 临时目录测试的真实参数前后比较出现变化，未计为整体通过，详见[本轮验收边界](docs/quick-tile-validation-20261019.md)。

验收中曾出现一次重复接管回读失败并安全回滚；后续三次命令行重复操作及完整客户端复测通过，但该异常原因尚未定位。当前网络在 IPv6 参数全部启用时，IPv6 API 仍解析失败，尚无真实 IPv6 API 成功的正向证据。**本轮修改暂留本地，未推送或发布**；详见[真机验收记录](docs/device-validation-20261017.md)。

使用 JDK 21、SDK 37.0、Build Tools 36.0.0；Runtime 使用 NDK r29（29.0.14206865）在 Linux/WSL 构建：

```sh
python3 -m unittest discover -s tests -v
ANDROID_NDK_HOME=/path/to/android-ndk-r29 sh scripts/build_runtime.sh
python3 scripts/package_runtime.py
cd android
sh tests/system_ipv6_test.sh
./gradlew onlineTest :app:assembleDebug :app:lintDebug
```

Windows 使用 `gradlew.bat`，SDK 路径见 [Android 说明](android/README.md)。输出为一个客户端 APK 和一个 Runtime zip；没有独立探针构建任务。

## English

IPV6 Ctrl 2.1-dev provides system-wide Root IPv6 control through one client APK and an independent Root Runtime module. Manual IPv4/IPv6 HTTPS checks run inside the client. Per-app policies, LSPosed hooks/scopes and the standalone probe have been removed. The worker starts idle and only maintains an explicitly requested target for the current boot. Polling has a correction window; API failure alone does not prove blocking. This development version has not been released; the published v2.0 assets remain unchanged.

许可证：[GPL-3.0-only](LICENSE)。图标许可见客户端“开源许可”。
