# IPV6 Ctrl Runtime · v2.0（versionCode 9）

最小独立 Root 执行者，配套 v2.0 / versionCode 9 App。协议字符串为 `ipv6ctrl-managed-v2`。它复用 PR #1 的逐接口巡检、值变化时回写及回读检查，替换“由 all 猜测目标”的旧策略。模块和 App 之间只使用 Root 私有文件，不需要 Zygisk、应用作用域或 `nc`。

## 构建与安装

在 Linux/WSL 使用 NDK r29（29.0.14206865）：

```sh
python3 -m unittest discover -s tests -v
ANDROID_NDK_HOME=/path/to/android-ndk-r29 sh scripts/build_runtime.sh
python3 scripts/package_runtime.py
```

zip 生成于 `android/build/runtime/IPV6-Ctrl-runtime-v2.0-arm64.zip`，包含编译后的 `ipv6_daemon`、与 APK 同源的 `control.sh`、模块服务/安装/卸载脚本；不打包旧 WebUI 或旧 daemon。CI 会重编译并要求二进制逐字节一致。

仅支持 **arm64 / Android 11+**。先在旧 App 恢复参数，停用旧 IPv6 模块，再用 Magisk / KernelSU / APatch 的模块安装入口安装 zip，重启后配套使用新 App。复用原模块 ID `ipv6_ctrl`，避免同名旧模块自启；不同 ID 的其他网络模块仍需自行停用。已在 Android 16 / arm64、SELinux Enforcing 下实测 KernelSU 安装和一次开机，Magisk / APatch 尚未实测。

## 操作

- 开机服务先同步调用 `ipv6_daemon --control adopt`，共享现有 `control.lock`，结束后才启动 worker。worker 默认待命；无 `desired`、模块被 `disable`/`remove` 时不写网络。
- 只有 Root 私有目录中内容精确为 `ipv4-lock` 的 `desired` 才触发开机接管。首次安装、升级和普通开关都不会从 `all` 或旧 `managed` 推断跨重启意图。
- 显式 `lock`（仅新调用方）要求 worker 正在运行：捕获或沿用本次开机快照，应用 `1` 并回读，成功后先原子提交本次 `managed`，再原子替换 `desired`。已经本次 blocked + managed、`desired=none` 时同样可升级为持久锁定。`desired` 写入失败时如实返回“仅本次接管”错误，不把已核对的本次禁用回滚掉，也不报完整成功。只有 `managed` 与 `desired` 都提交成功后，才把本 boot 旧的 `boot_apply=failed|interrupted` 改成 `manual`；标记写入失败时锁定仍然生效，但如实报错并保留旧标记，绝不冒充自动 adopt 成功。
- App 首页显式调用 `lock` 并在确认中说明跨重启接管、退出 App 仍保持和全设备连接风险；普通快捷磁贴仍只调用 `toggle`，关闭不新建 `desired`，开启会清除 `desired`。解锁入口是 `pause`（保留当前参数）或 `restore`（先解除锁定再恢复快照），失败保留快照。
- `pause`、`enable`、`restore` 和 `toggle` 走到开启都先在锁内清除 `desired`（失败则中止），再停止本次 `managed`。`block` 和 `toggle` 关闭仍只影响本次开机；磁贴关闭不等于持久锁定。解锁后下一次开机不会复活。
- 开机 adopt 先丢弃旧 boot 的快照，再捕获新基线，随后写中断标记、应用 `1` 并回读。同一 boot 重复 adopt 不覆盖原快照；已有本次 `managed` 加本次快照时直接安全继续，并保留已有的 `failed|interrupted|manual` 标记，不把重试或手动结果改写为 `applied`。失败只回滚一次并保留快照与 `desired`，写入绑定本次 boot 的 `boot_apply` 失败标记；本次不再自动 apply，巡检也不会重试。固定短错误足够诊断，不保存原始网络日志。
- 每 3 秒扫描 all/default/所有接口，只回写偏离 `1` 的参数。新增接口和单接口回写都能被下一轮发现。没有后台联网探测，也不会调用或修改其他网络模块的配置。App 不新增后台 Root/API 轮询；健康显示只是最近一次读取与巡检时间窗的确认。
- `enable` 先把本次接管停下并把当前接口写为 `0` 后核对，保留修改前快照供恢复；“恢复”在恢复失败时保持不接管并保留快照。模块被禁用或移除后 worker 退出，不擅自把参数写成 `0`；开机服务只在模块仍启用时最多再拉起 worker 2 次并退避，不会重复 adopt。卸载钩子尝试通过同一控制路径恢复，失败保留快照。

`/data/adb/ipv6ctrl-system` 保持 `0700` 目录和 `0600` 文件。`managed` 与 `snapshot` 都绑定本次 `boot_id`；`desired` 不含 `boot_id`，只表达跨重启意图；`boot_apply` 记录本次自动应用是 `applied`、`failed`、`interrupted`，还是自动应用曾失败/中断后已由显式 `lock` 核实的 `manual`。这些文件都由控制路径原子替换。

## 状态与边界

`status` 保留旧字段 `state`、`disabled`、`total`、`restorable`、`daemon`、`managed`、`last_sweep`，并增加：

- `desired=locked|none|unknown`：显式跨重启锁定、无意图或损坏意图。
- `boot_apply=applied|failed|interrupted|manual|none|unknown`：本次开机自动应用结果；`manual` 表示自动应用曾失败/中断，但显式锁定已核实且持久意图已写入；无本次标记时为 `none`。
- `last_sweep_boottime` / `last_sweep_age`：worker 最近一次巡检的 `CLOCK_BOOTTIME` 秒数与读取时年龄；缺失或无法换算时明确为 `unknown`。客户端会把该年龄加上状态读取后经过的时间再判断 60 秒确认窗口，不会仅因刚读到旧的 `ok` 就显示绿色。

旧版 Runtime 的原生白名单没有 `lock`，状态也缺少上述字段；新版客户端会提示更新并禁用跨重启锁定，不会静默降级成仅本次写入。旧 `status` / `pause` / `restore` 入口仍通过同一 `control.lock` 工作。

普通 `block` 的目标只在本次开机有效；worker 同次启动期间异常退出后，重启 worker 会继续已明确请求的本次目标。只有显式 `lock` 才留下跨重启 `desired`。设备重启后旧快照和旧 `managed` 永不恢复。

巡检与 App 操作使用同一 `flock`，避免恢复与回写交错；进程被杀由内核释放锁。缺失当前开机快照时 worker 报错且不写参数，本次 `boot_apply=failed` 也不会被 `idle` 覆盖。App 分别显示运行状态、请求的目标、上次巡检结果和实际接口计数，不能把“服务运行”当成所有接口永远禁用的保证。

这是周期纠正，通常在下一轮修正；调度延迟或写入失败会延长窗口，**不是零泄漏方案**。真机已验证强制停止客户端后保持、新接口纠偏、暂停、蜂窝/Wi-Fi 双向切换和快照恢复。曾发生一次重复接管回读失败并自动回滚，后续复测通过，原因仍待定位；实际卸载恢复尚未验收。2026-10-26 已通过本次 Android 临时目录隔离、安装及一次真实开机采用明确测试意图（adopt）、客户端 lock/pause/restore、退出保持、新接口纠偏和有限重拉。该次重启前意图由测试准备写入，客户端 lock 在重启后另测；没有追加重启证明完整的客户端设锁→重启链路。Runtime 独立 review 为用户选择跳过，见[最新验收记录](../docs/device-validation-20261026.md)；[历史异常记录](../docs/device-validation-20261017.md)仍保留。v2.0 已发布 APK 与 zip 不兼容；请使用本分支配套构建。

## 快捷开关控制协议 v2

客户端下拉磁贴 `ipv6` 使用新增 `toggle`：在同一原生操作锁内读取当前参数和保持目标，再执行开启或关闭。无接管且至少部分接口启用时关闭；全部停用或已有关闭保持目标时开启。开启使用独立的 `enable` 操作，先停止接管并清除跨重启意图，再显式设置所有当前项为 `0` 并核对；保留原快照，不把恢复原参数等同于开启 IPv6。开启失败停止保持并保留快照，供重试或恢复。

新调用方用 `lock` 显式请求跨重启纯 IPv4，用 `adopt` 作为开机唯一自动应用入口。协议字符串保持 `ipv6ctrl-managed-v2`；旧客户端的 `status`/`pause`/`restore` 仍走同一原生 `flock`，v1 读取/暂停/恢复入口不变。原生前台控制对短暂锁竞争最多等待约 2 秒；worker 巡检只做非阻塞尝试。随包二进制和控制脚本必须同步构建。
