# IPV6 Ctrl Runtime · 2.1-dev

最小独立 Root 执行者，配套 2.1-dev App。它复用 PR #1 的逐接口巡检、值变化时回写及回读检查，替换“由 all 猜测目标”的旧策略。模块和 App 之间只使用 Root 私有文件，不需要 Zygisk、应用作用域或 `nc`。

## 构建与安装

在 Linux/WSL 使用 NDK r29（29.0.14206865）：

```sh
python3 -m unittest discover -s tests -v
ANDROID_NDK_HOME=/path/to/android-ndk-r29 sh scripts/build_runtime.sh
python3 scripts/package_runtime.py
```

zip 生成于 `android/build/runtime/IPV6-Ctrl-runtime-2.1-dev-arm64.zip`，包含编译后的 `ipv6_daemon`、与 APK 同源的 `control.sh`、模块服务/安装/卸载脚本；不打包旧 WebUI 或旧 daemon。CI 会重编译并要求二进制逐字节一致。

仅支持 **arm64 / Android 11+**。先在旧 App 恢复参数，停用旧 IPv6 模块，再用 Magisk / KernelSU / APatch 的模块安装入口安装 zip，重启后配套使用新 App。复用原模块 ID `ipv6_ctrl`，避免同名旧模块自启；不同 ID 的其他网络模块仍需自行停用。本轮尚未实测这些管理器的安装、SELinux 和切网兼容性。

## 操作

- 开机服务启动 worker，**默认待命，不改网络**。它不会从当前 `all` 推断用户目标。
- App 的“禁用并持续保持”要求 worker 已运行，先捕获/沿用当前开机的修改前快照，核对禁用成功后再记录接管目标。
- 每 3 秒扫描 all/default/所有接口，只回写偏离 `1` 的参数。新增接口和单接口回写都能被下一轮发现。没有后台联网探测。
- “停止持续保持”移除目标，保留参数和快照；“恢复”先移除目标，再恢复原值，新接口恢复为原 default。恢复失败不重新开启接管，也不删除快照。
- 模块被禁用或移除后 worker 退出，不擅自把全部参数写成 `0`；卸载钩子尝试通过同一控制路径恢复，失败保留快照。**卸载前仍应在 App 手动恢复并核对**。

## 边界

目标只对本次开机有效。worker 同次启动期间异常退出后，重启该 worker 会继续已明确请求的目标；设备重启后默认待命，拒用上次开机的快照。没有额外重启监督，也不会通过 Android App 后台服务保活。

巡检与 App 操作使用同一 `flock`，避免恢复与回写交错；进程被杀由内核释放锁。缺失当前开机快照时 worker 报错且不写参数。App 分别显示运行状态、请求的目标、上次巡检结果和实际接口计数，不能把“服务运行”当成所有接口永远禁用的保证。

这是周期纠正，通常在下一轮修正；调度延迟或写入失败会延长窗口，**不是零泄漏方案**。控制端退出后继续保持、新接口恢复等已在临时文件联测，真实设备清后台、切网、卸载和恢复仍待验收。v2.0 已发布 APK 与 zip 不兼容；请使用本分支配套构建。
