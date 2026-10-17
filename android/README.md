# IPV6 Ctrl 客户端

源码为 **2.1-dev（versionCode 6）**，一个 `dev.ipv6ctrl` APK，配套独立 [Root Runtime](../module/README.md)。联网检测已整合到客户端；不再提供按应用解析控制、LSPosed 依赖、作用域、报告广播或独立探针 APK。

首次打开只显示界面，不请求 Root 或联网。首页围绕“回退到纯 IPv4”：主操作、简短状态、恢复原设置和手动网络检测。接口数量、服务状态、暂停保持、API 详细结果及时间线集中在诊断页，时间线最多保留当前进程的 40 条真实事件。检测区域使用系统旋转进度动画；仅当前操作显示进度，其他操作保留原文案并暂时禁用。

## 构建

使用 JDK 21、Android SDK `platforms;android-37.0` 和 `build-tools;36.0.0`。在本地 `local.properties` 配置 `sdk.dir`，或设置 `ANDROID_HOME`。

```sh
cd android
sh tests/system_ipv6_test.sh
./gradlew onlineTest :app:assembleDebug :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Windows 使用 `gradlew.bat`。没有第三方运行依赖，使用 Android 原生控件与 Java HTTPS API。调试签名不入库。

## 升级与系统控制

旧版曾使用应用策略时，先在旧客户端恢复“跟随系统”，在 LSPosed 停用本模块并清空其作用域，重启目标进程或设备以清除已有 Hook。升级客户端后可卸载旧 `dev.ipv6ctrl.probe`。已发布 v2.0 APK 不能控制新 Runtime，必须使用本分支配套客户端。

安装 Runtime zip 并重启后，先点击“读取系统 · Root”。没有运行中的 Runtime 时拒绝禁用，不会退化为单次写入。Runtime 默认待命；显式禁用时先捕获本次开机的原参数，写入并逐项核对，再记录接管目标。每 3 秒读取所有接口并修正漂移，退出客户端不会停止它。

“停止持续保持”只移除目标，保留参数和快照。“恢复”先移除目标，再恢复 `all/default` 与原接口；新增接口恢复为原 `default` 值。失败保留快照，且不恢复接管。控制操作与 worker 共用内核文件锁。设备重启后默认待命，旧快照与目标失效；daemon 异常退出没有额外重启监督。卸载前请先恢复。

参数修改会影响全设备及现有连接，IPv6-only 网络可能断网。参数恢复后地址和连接仍需系统重新建立；周期巡检也存在纠偏窗口，切网后请复核。

## 客户端联网检测

每次手动向 [ipify](https://www.ipify.org/) 发起各一次 HTTPS 请求：`api.ipify.org` 检查 IPv4，`api6.ipify.org` 检查 IPv6。使用默认网络，绕过 Java HTTP 代理，VPN 仍可影响路径。无轮询、自动重试、备用服务或后台请求。权限仅新增普通 `INTERNET`，无需应用列表或作用域授权。

保留 TLS 证书验证，拒绝重定向、过长及错误地址族响应；连接和读取超时各 6 秒，DNS 时间由系统解析器管理。不保存公网 IP，仅在当前客户端进程保留成功标记、错误类型和检测时间，旋转界面不重复请求。系统控制后旧结果提示需重测，切网后也应手动重测。旧独立探针报告不会读取或显示。

IPv6 成功说明检测时到 API 的 IPv6 路径可用；IPv4 成功、IPv6 失败不能排除 IPv6 API 自身故障；两者失败则网络/API 原因未确认。结果需结合系统参数，不等于全设备、所有路径的阻断证明。

## 验证

`onlineTest` 只校验 API 响应字面量、不联网。shell 和主机原生测试只使用临时目录。连接 Android 后可执行隔离原生测试（需 Root，禁止写真实 procfs）：

```sh
python tests/run_android_runtime.py --serial DEVICE_SERIAL --ndk-home /path/to/android-ndk-r29
```

该脚本编译独立路径的测试二进制，检查编译路径后才运行；执行前后比较真实参数并清理测试 worker。私密记录存放于忽略的 `android/build/device-debug`。KernelSU 真机已通过一次开机、接管、强制停止客户端、新接口漂移、暂停、实际蜂窝/Wi-Fi 双向切换和恢复。仍有一次回读回滚异常待定位，IPv6 API 正向路径尚未验证，暂不推送或发布。详见[验收记录](../docs/device-validation-20261017.md)。
