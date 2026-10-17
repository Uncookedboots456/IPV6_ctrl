# IPV6 Ctrl Android 应用

当前源码为 **2.1-dev（versionCode 4）**，需要配套的 Root Runtime 模块来持续保持 IPv6 禁用。已发布的 [v2.0 Pre-release](https://github.com/Uncookedboots456/IPV6_ctrl/releases/tag/v2.0) 仍是单次修改版，不能控制新 Runtime；其附件保持原样。

原生 Java 管理应用，默认提供 **Root 系统级 IPv6 控制**和手动联网检测；LSPosed API 102 按应用解析控制保留为可选高级功能。系统控制和联网探针都不需要框架作用域。

首页分别显示实际接口参数、独立服务、接管目标和上次巡检结果，提供持续禁用、停止保持、快照恢复和联网检测。首次打开不会请求 Root、申请应用作用域或自动联网。原有按应用策略不会被升级自动清除，可在高级入口恢复“跟随系统”并重启目标。

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

1. 安装配套的 2.1-dev 管理应用、探针 APK 和 [Runtime zip](../module/README.md)，重启设备；本轮 Runtime 仅支持 arm64 / Android 11+，无需启用 LSPosed。先恢复、停用旧版 IPv6 模块，避免多个执行者互相写入。
2. 点击“读取系统 · Root”，允许 `su`，核对所有接口的 `disable_ipv6` 参数；不以 `all` 单个值代替实际接口检查。
3. 确认独立服务运行后，选择“禁用并持续保持 IPv6”。先把当前 `all`、`default` 和每个接口的值保存到 Root 私有目录 `/data/adb/ipv6ctrl-system`，再写入 `1` 并逐项回读，最后记录本次开机的明确接管目标。没有运行中的 Runtime 会拒绝禁用，不会静默退化为一次性修改。
4. 点击“联网检测”，再返回诊断查看系统参数和外网结果。
5. “停止持续保持（不恢复参数）”只解除接管。点击“恢复修改前系统配置”会先停止接管，再恢复 `all/default` 和原有接口；期间新增接口恢复为原先 `default` 值。恢复并核对成功后删除快照，失败则保留快照并保持停止接管。

操作为**本次开机的持续保持**。模块 `service.sh` 启动独立 daemon，默认待命；显式接管后每 3 秒逐接口读取并仅回写漂移，App 清后台不影响该进程。操作与巡检共用内核文件锁，控制进程被杀后锁自动释放。重启后默认不接管，并拒绝恢复旧启动周期快照。daemon 本身异常退出不会自动重启，刷新可核对是否仍运行。卸载 App 或模块前请先恢复配置。

内核的 `disable_ipv6` 改为 `1` 会移除接口 IPv6 地址与路由，影响全设备及现有连接；IPv6-only 网络可能断网。恢复的是参数，地址与连接需要系统网络栈重新建立。Android 网络组件仍可能重新启用接口，巡检回写存在时间窗口；切换 Wi-Fi、蜂窝、VPN、热点后仍需检测。本实现不宣称永久或零泄漏阻断。参见 [Linux 内核 sysctl 文档](https://docs.kernel.org/networking/ip-sysctl.html)。

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

`policyTest`、`onlineTest` 不联网；`tests/system_ipv6_test.sh` 只修改临时目录。仓库根目录的 `tests/test_runtime.py` 在 Linux/WSL 编译同一 C 源码，并与真实控制脚本联测待命、单实例、单接口漂移、新接口、停止/恢复、恢复失败、worker 重启、控制进程被杀释放锁及跨启动失效。CI 运行这些检查、Android Lint，重编译核对随包 arm64 二进制并打包 zip。尚无新增真机验收。

界面时间线仅记录本次管理应用进程中最多 40 条真实事件；不模拟守护或开机记录。依赖仅 libxposed service 及传递依赖，Xposed API 为 compileOnly；Android 原生控件和图标许可见应用“开源许可”。
