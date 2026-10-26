# IPV6 Ctrl

帮助 Android Root 设备**回退到纯 IPv4** 的系统级网络控制客户端。当前版本为 **v2.0 正式版（versionCode 9）**：一个客户端 APK 加一个 Root Runtime 模块，联网检测直接在客户端运行，已移除按应用控制、LSPosed Hook、作用域请求和独立探针 APK。

支持名为 **ipv6** 的下拉快捷开关：亮起为 IPv6 开启，关闭为纯 IPv4；默认跟随系统，不自动接管，按实际状态显示。可在客户端诊断页添加，首次需授权 Root。正常切换留在下拉面板，以副标题反馈进度，不弹窗或跳页；当前版本待[真机复测](docs/quick-tile-validation-20261019.md)。快捷开关需配套本次 Runtime（协议 v2）。

[客户端使用与构建](android/README.md) · [Runtime 说明](module/README.md) · [旧版 daemon / WebUI](docs/legacy-daemon-webui.md)

## 安装与使用

相比早期 v2.0 测试版（单次修改、两个 APK，详见 [v2.0 测试版归档说明](docs/v2.0-release.md)），v2.0 正式版（versionCode 9）合并为单一客户端 APK + 独立 Root Runtime 模块，新增持续纠偏守护、跨重启持久锁定、每次打开自动检测 IPv4/IPv6 以及全新中心状态圆环 + 双开关界面。

1. Runtime 支持 arm64 / Android 11+，客户端支持 Android 9+。先在旧客户端恢复系统配置；若使用过应用策略，先恢复“跟随系统”、停用本模块并清空其 LSPosed 作用域，再重启目标进程或设备。升级后可卸载旧的 `dev.ipv6ctrl.probe`。
2. 通过 Root 管理器安装 `IPV6-Ctrl-runtime-v2.0-arm64.zip`，重启设备并安装客户端 `IPV6-Ctrl-v2.0-debug.apk`。开机服务默认待命，不猜测用户目标或自动禁用 IPv6。
3. 首次打开客户端点击“授权 Root”授权并检查所有接口；已授权后每次打开应用都会自动只读系统参数。首页采用**中心状态圆环 + 双开关**设计：
   - **纯 IPv4 模式（开关 1）**：开启即保存本次开机快照、逐项写入并核对，独立服务每 3 秒检查各接口修正偏离值；关闭即恢复修改前设置（无快照时直接开启 IPv6）。直接点按中心大圆环也可一键切换纯 IPv4 模式。
   - **锁定（开关 2）**：开启即写入跨重启持久锁定意图（若当前尚未开启纯 IPv4 模式，会一并开启并锁定），设备重启后自动重新接管，退出客户端后仍保持；关闭即解除锁定（走 `pause`）。
   - 普通磁贴的关闭只在本轮开机生效，不会新建持久意图；磁贴开启会清除锁定意图并把 IPv6 打开。
4. **每次点开应用（冷启动或从后台切回前台）都会自动向 ipify 的 IPv4 / IPv6 HTTPS API 各查询一次**，并说明不保存公网 IP；结果在首页底部及诊断页查看，点击旋转图标也可随时手动重测。默认网络变化或切换系统参数后也会自动重测，前台停留到 5 分钟过期时仅使显示失效，无后台轮询。
5. 诊断页以分组键值卡片展示系统详情，点击“仅解除锁定 / 停止保持”只清除锁定意图与本次接管、保留当前参数；首页“恢复 IPv6”先解除锁定，再恢复本次开机快照（无快照时可直接“开启 IPv6”）。恢复失败保留快照。重启时只有精确的跨重启锁定意图才会自动接管；无意图时默认待命，旧目标与旧快照不会恢复。卸载前请先恢复并核对。

## 范围与限制

- 系统控制修改 `all`、`default` 和各接口的 `disable_ipv6`，影响全设备及已有连接；IPv6-only 网络可能断网。恢复参数后，地址和连接仍需系统重新建立。
- 周期纠偏存在窗口，不能保证零泄漏。开机服务在模块仍启用时最多再拉起 worker 2 次（5s/15s 退避），不会重复自动应用；仍应在客户端刷新检查服务状态，切网后重新读取和检测。
- “巡检已确认”只在状态读取未过期、上次巡检为 `ok` 且巡检年龄仍在 60 秒窗口内显示。应用不在后台轮询，不能证明两次读取之间所有接口始终正常；未知、过期或旧 Runtime 缺字段都会降级为待确认，不会显示绿色“保持中”。
- 跨重启锁定已通过主机回归和一次真实开机采用明确测试意图的检查；客户端 lock / pause / restore 另在同次开机验证。因一次重启预算，尚未补齐“新客户端设锁后再次重启”的完整端到端验证，见[最新验收边界](docs/device-validation-20261026.md)。旧 Runtime 缺字段时提示更新并禁用锁定，不静默降级。
- IPv6 API 成功说明检测时客户端到 API 的 IPv6 路径可用；失败可能由 DNS、VPN、网络或 API 故障引起，不能单凭失败证明阻断成功；禁用 IPv6 也不保证 IPv4 可用。联网检测与 Root 操作独立，超时或慢请求不会锁住恢复/暂停。
- 联网结果只保留在当前客户端进程中，系统修改后标记需重测；不会复用旧独立探针报告。时间线最多 40 条实际会话事件。
- 使用系统原生 Java UI，无第三方运行库。Root Runtime 与客户端不通过应用 Hook 或 Zygisk 通信。

## 验证与构建

**2026-10-26 / code 9**：完成 Android 临时目录隔离、一次新 Runtime 开机接管、真实客户端锁定/解锁/恢复、退出 App 保持、新接口纠偏、worker 有限重拉，以及自动检测去重、真实默认网络断开/恢复、局部更新与快速检测并行恢复测试。原始数据仅留忽略目录；Runtime 独立 review 为用户选择跳过，磁贴面板和 IPv6 API 正向路径仍未验收。详见[本轮脱敏验收记录](docs/device-validation-20261026.md)。以下为保留的历史证据，不代表旧异常已修复。

此前单客户端版本通过主机临时文件联测、Android 真机隔离，以及 KernelSU 安装/一次真实开机、接管、强制停止客户端、新接口纠偏、暂停、双向切网和快照恢复检查。新增快捷开关仍待真机复测；后续 Android 临时目录测试的真实参数前后比较出现变化，未计为整体通过，详见[本轮验收边界](docs/quick-tile-validation-20261019.md)。

验收中曾出现一次重复接管回读失败并安全回滚；后续三次命令行重复操作及完整客户端复测通过，但该异常原因尚未定位。当前网络在 IPv6 参数全部启用时，IPv6 API 仍解析失败，尚无真实 IPv6 API 成功的正向证据。**本轮修改暂留本地，未推送或发布**；详见[真机验收记录](docs/device-validation-20261017.md)。

使用 JDK 21、SDK 37.0、Build Tools 36.0.0；Runtime 使用 NDK r29（29.0.14206865）在 Linux/WSL 构建：

```sh
python3 -m unittest discover -s tests -v
ANDROID_NDK_HOME=/path/to/android-ndk-r29 sh scripts/build_runtime.sh
python3 scripts/package_runtime.py
cd android
sh tests/system_ipv6_test.sh
./gradlew onlineTest rulesTest :app:assembleDebug :app:lintDebug
```

Windows 使用 `gradlew.bat`，SDK 路径见 [Android 说明](android/README.md)。输出为一个客户端 APK 和一个 Runtime zip；没有独立探针构建任务。

## English

IPV6 Ctrl v2.0 (versionCode 9) provides system-wide Root IPv6 control through one client APK and an independent Root Runtime module. Every time the client is opened, it runs one IPv4 and one IPv6 HTTPS check (ipify; the public address is not stored) and reads the Root system state. The home screen features a central status ring and two switches: **Pure IPv4 Mode** (saves this boot's snapshot, disables IPv6 across interfaces, and keeps them disabled every 3 seconds; turning it off restores IPv6) and **Lock** (records a cross-restart intent adopted on the next boot until unlocked or restored). Per-app policies, LSPosed hooks/scopes, and the standalone probe have been removed. Polling has a correction window; API failure alone does not prove blocking.

许可证：[GPL-3.0-only](LICENSE)。图标许可见客户端“开源许可”。
