# IPV6 Ctrl Android 应用

原生 Java 管理界面和现代 LSPosed API 102 模块。默认跟随系统，仅对所选普通应用的 Java 地址解析进行排序或过滤。Native 网络栈、已有连接及系统 IPv6 开关不在覆盖范围内。

界面采用 `design/index.html` 的新视觉：总览集中显示已保存策略、框架连接和上次探针结果，三档策略选择后需显式保存；作用范围和诊断从首页进入，底部固定刷新与探针入口。

操作时间线记录实际发生的读取、保存请求、框架确认、作用域请求与探针返回。最多保留本次应用进程的 40 条记录，完全关闭后清空；不展示模拟的开机或守护事件。诊断中的独立探针 APK 用于确认真正的目标进程注入，管理应用自身不作为 hook 测试目标。

## 构建

使用 JDK 21、Android SDK `platforms;android-37.0` 和 `build-tools;36.0.0`。将 SDK 路径放入本地 `local.properties` 的 `sdk.dir`，或设置 `ANDROID_HOME`。

```sh
cd android
./gradlew policyTest :app:assembleDebug :probe:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r probe/build/outputs/apk/debug/probe-debug.apk
```

Windows 使用 `gradlew.bat`。调试 APK 使用本机 Android 调试签名；模块与探针必须由同一密钥签名，才能接收探针报告。密钥不入库。

## 首次使用

1. 在支持 API 102 的 LSPosed 中启用 IPV6 Ctrl，只勾选 `dev.ipv6ctrl.probe` 进行首轮测试。
2. 打开 IPV6 Ctrl，确认框架服务已连接。在作用范围页勾选独立探针，保存目标。
3. 在总览选择策略并保存。在诊断页运行独立探针，返回查看结果。
4. 新增框架作用域后，完全重启目标应用。恢复时选择“跟随系统”，保存并重启目标。

“优先使用 IPv4”保留 IPv6 备用；“过滤 IPv6 解析结果”会令仅返回 IPv6 的 Java 解析失败。保存失败且无法确认远端结果时，应用会禁止继续写入，要求完全关闭后重开核对。

## 检查范围

`policyTest` 是无网络的纯 Java 断言检查，覆盖排序、过滤、原始结果不被修改、IPv6-only 失败及无效配置回退。GitHub Actions 使用相同检查并构建两个 APK，不代表完成 LSPosed 实机验收。

实机探针只解析 `127.0.0.1`、`::1` 和空主机名的回环结果：

| 已保存策略 | IPv4 | IPv6 字面量 | 空主机名首选 | IPv4 / IPv6 数量 |
| --- | --- | --- | --- | --- |
| 跟随系统 | 成功 | 成功 | IPv6 | 1 / 1 |
| 优先 IPv4 | 成功 | 成功 | IPv4 | 1 / 1 |
| 过滤 IPv6 | 成功 | 失败 | IPv4 | 1 / 0 |

上表基于 AOSP 解析器；设备差异需实际核对。探针还必须显示 `api=102` 注入标记。未注入或未选为策略目标时，应保持系统行为。

设备已经配置相应策略后，可用 Python 标准库重新启动探针并核对实际结果：

```sh
python tests/device_probe.py --mode SYSTEM
python tests/device_probe.py --mode IPV4_FIRST
python tests/device_probe.py --mode IPV4_ONLY
```

每次只执行与当前已保存策略相符的一条命令。`--unhooked` 检查未注入时的系统行为，`--unselected` 检查未被选作策略目标时的系统行为；脚本本身不会改模块配置。

构建依赖仅 libxposed service 及其传递依赖；Xposed API 为 compileOnly。界面使用 Android 原生控件，统一线条图标的许可及依赖许可见 APK 的“开源许可”。
