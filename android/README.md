# IPV6 Ctrl Android

一个 APK 包含原生管理界面和现代 LSPosed 模块入口，要求 Android 9+、Xposed API 102。默认跟随系统，仅对用户选择且已获 LSPosed 授权的普通应用调整 Java 地址解析。

- 总览：跟随系统 / IPv4 优先 / 过滤 IPv6 解析，保存与恢复。
- 作用范围：选择普通应用，通过框架请求授权后才能应用策略。
- 诊断：独立探针确认真实注入与地址结果，框架连接不代表 hook 已生效。
- 配置使用 libxposed remote preferences，hook 只读取进程内不可变快照；不启动守护进程、不执行 su、不写 procfs。

首版 hook `java.net.Inet6AddressImpl.lookupAllHostAddr(String,int)`，不覆盖 Native/JNI 直连、已有连接或全设备流量。IPv4 优先保留 IPv6 备用；过滤 IPv6 后如没有 IPv4，Java 解析抛出 UnknownHostException。新作用域需重新启动目标进程。

## 构建和测试

使用 JDK 21、Android SDK 37.0 / Build Tools 36.0.0。Gradle 与 AGP 固定版本，构建工具和 SDK 不进入 Git。

```sh
./gradlew policyTest :app:assembleDebug :probe:assembleDebug
```

`policyTest` 使用 Java 断言，不依赖第三方测试框架。`probe` 是独立测试 APK，使用空主机名及 127.0.0.1、::1 验证真实解析 hook，不访问外网。测试范围只勾选探针；不要为首次验收选择系统组件或日常使用的应用。

运行时依赖仅为 [libxposed service](https://github.com/JingMatrix/libxposed-service)；[API 102](https://github.com/JingMatrix/libxposed-api) 仅用于编译，由框架提供。图标沿用设计稿的统一 24 单位圆角线条风格，许可随 APK 提供。

保存确认失败时显示“未确认”，不把 Binder 超时误报成回滚成功。此时完全关闭管理应用后重开核对配置。
