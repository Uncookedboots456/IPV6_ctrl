# IPV6 Ctrl Android 应用

当前发布：**[v2.0 Pre-release](https://github.com/Uncookedboots456/IPV6_ctrl/releases/tag/v2.0)**。下载主应用与探针两个调试 APK，并使用发布页的 `SHA256SUMS.txt` 校验；两个 APK 的版本均为 `2.0`（versionCode 3）。

原生 Java 管理应用，默认提供 **Root 系统级 IPv6 控制**和手动联网检测；LSPosed API 102 按应用解析控制保留为可选高级功能。系统控制和联网探针都不需要框架作用域。

首页显示实际读取的系统参数及时间，提供系统级阻断、修改前快照恢复和联网检测。首次打开不会请求 Root、申请应用作用域或自动联网。原有按应用策略不会被升级自动清除，可在高级入口恢复“跟随系统”并重启目标。

## 构建

使用 JDK 21、Android SDK `platforms;android-37.0` 和 `build-tools;36.0.0`。将 SDK 路径放入本地 `local.properties` 的 `sdk.dir`，或设置 `ANDROID_HOME`。

```sh
cd android
sh tests/system_ipv6_test.sh
./gradlew policyTest onlineTest :app:assembleDebug :probe:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r probe/build/outputs/apk/debug/probe-debug.apk
```

Windows 使用 `gradlew.bat`，隔离脚本检查可通过 Git Bash 运行。两个 APK 必须使用同一签名，才能接收受 signature 权限保护的探针报告。调试签名不入库。

## 系统级操作

1. 安装管理应用与独立探针 APK，无需启用 LSPosed。
2. 点击“读取系统 · Root”，允许 `su`，核对所有接口的 `disable_ipv6` 参数；不以 `all` 单个值代替实际接口检查。
3. 选择“系统级阻断 IPv6”。先把当前 `all`、`default` 和每个接口的值保存到 Root 私有目录 `/data/adb/ipv6ctrl-system`，再写入 `1` 并逐项回读。失败时尝试恢复，不能恢复则保留快照并报告失败。
4. 点击“联网检测”，再返回诊断查看系统参数和外网结果。
5. 点击“恢复修改前系统配置”，先恢复 `all/default`，再恢复原有接口；期间新增接口恢复为原先 `default` 值。恢复并核对成功后删除快照。

操作为**本次开机的运行时修改**，没有常驻轮询、开机脚本或旧 daemon 部署。重启后拒绝使用旧启动周期的快照。断电或进程强杀留下的锁可通过重启清除。卸载应用前请先恢复配置。

内核的 `disable_ipv6` 改为 `1` 会移除接口 IPv6 地址与路由，影响全设备及现有连接；IPv6-only 网络可能断网。恢复的是参数，地址与连接需要系统网络栈重新建立。Android 网络组件或其他 Root 工具仍可能重新启用接口；切换 Wi-Fi、蜂窝、VPN、热点后需重新读取并检测。本实现不宣称永久阻断所有网络路径。参见 [Linux 内核 sysctl 文档](https://docs.kernel.org/networking/ip-sysctl.html)。

## 联网探针

每次手动运行仅向 [ipify 官方 API](https://www.ipify.org/) 发起两次 HTTPS 请求：`api.ipify.org` 检查 IPv4，`api6.ipify.org` 检查 IPv6。使用默认网络并显式绕过 Java HTTP 代理；系统 VPN 仍可能影响路径。无自动重试、轮询、备用服务或网页测试跳转。

- IPv6 API 成功且返回合法 IPv6 字面量：此次网络路径能访问外网 IPv6。
- IPv4 成功、IPv6 失败：IPv6 路径或 API 未连通；结合系统参数判断，不能单凭失败宣称系统阻断成功。
- 两者均失败：网络、DNS、TLS、API 或阻断等原因未确认。
- 探针仍受旧的应用 IPv6 过滤策略影响时，明确显示结果受应用策略影响；系统检查建议在 LSPosed 中移除探针作用域后重启探针。

请求保留 TLS 证书验证，拒绝重定向和过长、错误地址族响应。连接及读取超时各 6 秒；DNS 时间由系统解析器管理。只保存成功标记和错误类型，不保存或广播公网 IP。探针旋转界面不会重复请求；系统写入或应用策略保存后，旧检测显示需重测。

本机回环解析结果作为独立诊断保留，不与外网可用性混淆。诊断页也可选择“仅检测本机解析（不联网）”。

## 可选应用解析策略

首页“高级：应用解析策略”保留三档策略：跟随系统、优先 IPv4、过滤 IPv6。仅对显式选择的普通应用安装 Java 解析 Hook，不安装到未选择目标；默认推荐作用域为空。不 Hook 系统进程，也不向全体应用申请作用域。新增目标、清除已有注入都需重启目标进程。

应用策略只影响 Java 地址解析，不能覆盖 Native 网络栈或替代系统控制。无法确认框架保存结果时拒绝继续写入应用策略，需完全重开核对；系统级入口仍独立可用。

在设备已配置相应策略后，可运行纯回环验收（脚本不改策略、不访问外网）：

```sh
python tests/device_probe.py --mode SYSTEM
python tests/device_probe.py --mode IPV4_FIRST
python tests/device_probe.py --mode IPV4_ONLY
```

每次仅运行匹配当前配置的一条命令。`--unhooked` 和 `--unselected` 都要求 `not-injected`，需要先重启目标。基于 AOSP 回环解析，SYSTEM 为 IPv6 首选且各一个地址；IPV4_FIRST 为 IPv4 首选且各一个；IPV4_ONLY 为 IPv4 一个、IPv6 零个。设备差异需实测。

## 验证范围

`policyTest`、`onlineTest` 不联网；`tests/system_ipv6_test.sh` 只修改临时目录，覆盖快照、重复阻断、原值恢复、新接口恢复、互斥锁、失败保留快照、重启快照失效及缺失节点拒写。CI 运行相同检查并构建两个 APK，不代表完成真实系统全局阻断验收。

界面时间线仅记录本次管理应用进程中最多 40 条真实事件；不模拟守护或开机记录。依赖仅 libxposed service 及传递依赖，Xposed API 为 compileOnly；Android 原生控件和图标许可见应用“开源许可”。
