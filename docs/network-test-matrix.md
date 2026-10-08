# 第一阶段网络兼容性测试矩阵

本计划用于 `scripts/ipv6_trial.sh` 的单接口一次性试验。当前所有实机用例均为**待执行**，不代表微信、代理或设备网络已验收。

2026-10-07 首次通过 antigravity-cli 请求时，Google资格检查接口返回503；用户要求排查重试后，`claude-opus-5-5-high` 已完整返回评审，退出码0。以下综合项目约束、一手资料及核对后的评审建议；[Opus原文](opus55-network-review-raw.md)另存，含未采纳的错误或推断，不能直接当作实机结果。

## 先确认实际环境

第一轮保持日常配置，只关闭一个已确认的物理上行接口。不新增 TUN、不启用新 TPROXY、不修改代理/DNS、不装 daemon/hook。环境不明确时先只读调查，不直接进入变更测试。

| 维度 | 必须记录 | 容易误判之处 |
|---|---|---|
| 上行 | Wi-Fi/蜂窝/双连接；Android 默认网络、物理接口名和 ifindex | Wi-Fi 图标不证明业务走 Wi-Fi；蜂窝回退可能掩盖故障 |
| 地址族 | 上行是否有可用 IPv4、原生 IPv6、CLAT/DNS64/NAT64；代理服务器是否依赖 IPv6 | 接口有 IPv4 地址不证明 IPv4 脱离 IPv6 也能联网 |
| TUN | 存在、UP、持有进程、root 原生 TUN 或 VpnService、实际路由/UID范围 | 接口存在不代表微信流量经过；隧道内 IPv6 与物理出口 IPv6 属于不同层 |
| 透明代理 | TPROXY/REDIRECT/TUN；监听端口、有效链引用、计数增量、mark/mask、策略路由 | 有监听端口或孤立规则不代表已接管；本机 OUTPUT 与热点转发路径不同 |
| 应用范围 | 微信真实 UID/用户、是否被代理、直连、UID绕过、域名直连规则 | 微信直连不能证明系统 DNS 也绕过代理 |
| DNS | 系统/私人 DNS、代理 DNS、fake-ip/真实 IP、A/AAAA行为 | 改 DNS 与改 IPv6 同时进行，就难以判断原因 |
| VPN策略 | 应用包含/排除、Always-on、阻止非VPN连接、底层网络 | VPN 断开后按策略断网是预期行为，不应要求绕过联网 |
| 生命周期 | 代理启动顺序、进程冷热、锁屏状态、网络切换顺序 | 重开微信不能当作清空系统 DNS 缓存；重启代理会改变试验环境 |
| 其他控制者 | 旧 ipv6_daemon、Surfing 等自身 IPv6 开关、其他 root 网络模块 | 本脚本无 daemon 不代表设备没有其他写入者；本阶段只记录，不强制争抢 |
| 恢复依据 | boot ID、目标 ifindex、原值、快照、应用前后时间；另记netId/SSID或APN及地址前缀变化 | 同名接口可能已重建；同ifindex也不能单独证明仍是同一网络；不能把旧快照强写给新接口 |

TUN 与 TPROXY 并非必然互斥；已有组合应记录各自接管范围。本阶段不为了“覆盖组合”主动构造多代理叠加。

## 用例与优先级

先完成 P0；随后只选择设备实际使用的 P1 配置。P2 按环境选做。每个配置都重新取得自己的 A 基线，避免一次做完所有组合。

**A1 → B → A2**：A1 为原状态，B 为对明确目标接口关闭一次，A2 为恢复原值并复测。网络切换用例应分别在 A 和 B 做同方向切换，比较恢复时间；B 中不得为维持关闭状态再写一次。

| ID | 优先级 | 前置状态 | 操作 | 检查重点 |
|---|---|---|---|---|
| N01 | P0 | 日常网络与代理配置，尚未修改 | 只读记录上述维度、运行 trial status | 确认真实路径；未知地址族/接口身份时暂不修改 |
| N02 | P0 | 日常配置的正常 Wi-Fi 与蜂窝环境 | 不应用脚本，分别跑微信业务基线 | 确定原来就存在的故障、切换耗时及蜂窝回退 |
| N03 | P0 | 明确 Wi-Fi 物理接口，有可用独立 IPv4；代理固定 | 关闭蜂窝回退或控制其状态，执行 Wi-Fi A1/B/A2 | 单次变更影响、即时/业务后接口值、其他接口未改 |
| N04 | P0 | N03 当前微信进程已在运行 | A/B 各测现有连接，再单独重开微信测新连接 | 既有长连接受损与新连接失败分开，禁止清应用数据 |
| N05 | P0 | N03 稳定 | A/B 各做通话、媒体传输及锁屏收消息；结束后恢复 | 业务细项、消息延迟、通话中断与恢复，不以网页代替 |
| N06 | P0 | 日常代理固定，Wi-Fi/蜂窝均有已验证基线 | A/B 分别做 Wi-Fi→蜂窝、蜂窝→Wi-Fi；最后恢复 | 默认网络、实际出口、代理上行、原目标值和 ifindex；新上行未应用必须单列 |
| N07 | P1 | 设备已有可切换的直连配置 | 恢复原值后切到直连，建立新基线，再做 Wi-Fi A/B/A | 区分代理交互与物理上行变更；“直连”需核对残留接管链 |
| N08 | P1 | 蜂窝有已确认的独立可用 IPv4，不依赖 CLAT 传 IPv4 | Wi-Fi关闭，针对明确蜂窝接口做独立 A/B/A | APN、PDP接口、业务与恢复；未知或IPv6依赖场景跳过 |
| N09 | P1 | 当前实际启用 root TPROXY | 固定配置，对物理上行做 A/B/A | TCP/UDP分别观察；链引用、mark、ip rule及local路由仍按预期工作 |
| N10 | P1 | 当前实际采用 REDIRECT 或 TCP REDIRECT+UDP TPROXY | 固定配置做 A/B/A，分开跑文本与通话/视频 | TCP成功不能替代UDP业务；UDP路径必须按实际配置解释 |
| N11 | P1 | 当前已有 root 原生 TUN | 只对明确物理上行做 A/B/A，不关闭TUN | TUN地址、栈类型、路由、物理出口及代理服务器连接分开检查 |
| N12 | P1 | 当前已有 VpnService | 固定应用范围做 A/B/A，再按N06双向切换 | VPN是否建立、底层网络、IPv4/IPv6处理与应用绕过范围 |
| N13 | P1 | 日常已存在 TUN+TPROXY 或 VPN+root代理 | 先只读确认分工；能解释实际路径后才做独立 A/B/A | 双重接管、回环、mark冲突、出口选错；解释不清则仅观察 |
| N14 | P1 | 已有微信直连/UID绕过配置 | 保持该配置做 A/B/A；代理接管配置另建基线复测 | UID排除、域名DIRECT、DNS去向分开记录；fake-ip不能只看UID判断 |
| N15 | P1 | P0稳定且允许单独测试代理生命周期 | 分别建立“代理先启动再B”与“B后才启动代理”的对照；另测代理正常重启 | 路由/链重建、上行检测、参数是否被改回；A2后是否仍保留B期间的IPv4优先/仅IPv4行为；禁止手工重建规则掩盖故障 |
| N16 | P1 | 有已验证可用的 Wi-Fi 与代理配置 | A/B分别做Wi-Fi断开重连、同网络重新关联 | 接口是否重建、ifindex、快照适用性、重连后新连接恢复 |
| N17 | P1 | 日常配置本身使用私人DNS或fake-ip | 保持现状做 A/B/A；其他DNS模式另建基线 | A/AAAA、DNS路径和真实业务；不在同一B阶段同时切DNS模式 |
| N18 | P2 | 可用的IPv4-only上行 | 独立 A/B/A，仍只选明确物理接口 | 不存在公网IPv6不等于没有链路本地IPv6；记录局域网副作用 |
| N19 | P2 | IPv6-only、464XLAT或仅IPv6可达的代理服务器 | 本阶段只读记录依赖，不关闭其必需IPv6 | 不是“关闭后仍应联网”的通过案例；留待专门策略设计 |
| N20 | P2 | P0/P1稳定，设备支持相应变化 | A/B分别做飞行模式恢复、4G/5G切换、双SIM数据卡切换 | 每项独立，不混在一次操作；PDP重建、快照失效保护和默认出口 |
| N21 | P2 | 当前使用热点或USB共享 | 本阶段先观察手机自身与客户端路径 | FORWARD、IPv6 RA及软件/硬件offload与本机流量不同，不默认关闭桥接/共享接口 |
| N22 | P2 | 当前已有Always-on/阻止非VPN连接 | A/B分别正常断开及恢复VPN，记录策略预期 | VPN断开时应遵守封锁；不能把策略要求的断网归因于脚本 |
| N23 | P2 | 当前有认证Wi-Fi或局域网业务 | 分别建立认证前后/打印投屏等原状态对照，再决定是否试验 | 门户、Android网络验证、局域网发现与公网业务分开 |
| N24 | P2 | 轻量业务已通过且可手动复测媒体 | A/B做大图片/文件、语音视频、已有UDP业务 | 记录持续传输、MTU/PMTU迹象及代理错误；不主动制造丢包/压力或改MTU |

## 微信业务探针

用户用测试账号或联系人手工配合，脚本不发送消息或采集聊天内容。每项在 A1/B/A2 各记录成功、耗时、是否需重开应用或重连网络：

1. 双向文字和语音消息；界面显示发出与对端实际收到分开。
2. 上传与下载新图片/视频/文件；避免只看已缓存内容。
3. 朋友圈与小程序新内容加载，记录与消息业务是否不同。
4. 语音/视频通话，分别测试建连和持续通话；网络切换时对比A/B掉线及恢复。
5. 锁屏收消息，先使用一致的10分钟观察窗口，必要时再扩到30分钟；记录发送/接收/解锁时间、通知到达与打开后补收，不预设其推送实现。
6. 热进程与重开后的新连接分别测；保持电池优化、后台限制等其他条件相同。

若通话在 A 状态切网也会中断，不能要求 B 状态绝对无中断；重点是 B 是否出现新的失败或明显更差的恢复。

## 四项结果独立记录

| 结果 | 判断内容 | 允许的记录值 |
|---|---|---|
| 业务 | 对照微信每个探针及网络切换恢复 | 通过/失败/未测；同时记录实测耗时 |
| 策略 | 原目标接口在观察时是否仍为1，是否换了接口/上行 | 有效/被改回/不适用/未知 |
| 路径 | 实际直连/代理/VPN及DNS路径是否符合该配置 | 符合/冲突/未知 |
| 恢复 | 原接口参数及业务是否恢复；是否需要额外操作 | 自动恢复/需重连/失败/环境变化无法适用 |

关闭 `wlan0` 后切到蜂窝，微信正常只能证明该次切换的业务可用；蜂窝未被脚本修改，策略应记“不适用”。若 `wlan0` 被系统改回0，业务正常也不能记为持续禁用通过。若 ifindex 改变、脚本拒绝旧快照恢复，保护机制可以符合预期，但网络恢复结果仍需另外验证。

本计划不声称提供全设备无IPv6泄漏。物理上行、TUN内部地址族、代理远端出口是不同观察对象。显式HTTP代理、网页、ping和root shell路由查询只能辅助诊断，不能代替微信UID与实际连接路径的证据。

建议每个关键用例复测两轮。阈值先由A基线确定，记录B相对差异，不编造通用“正常切网秒数”。出现A正常/B新增失败/A恢复后改善，即停止扩展组合并保留证据；A2不恢复时优先恢复设备网络。

## 最小只读取证

在手机 root shell 中，按需要运行以下命令；保留A1、应用后、异常时、恢复后四个时间点。命令不支持时记为“不可用”，不安装工具、不修改规则，也不清零计数。

```sh
date
cat /proc/sys/kernel/random/boot_id
sh /data/local/tmp/ipv6_trial.sh status
ip -d link show
ip -4 addr show
ip -6 addr show
ip -4 rule show
ip -6 rule show
ip -4 route show table all
ip -6 route show table all
cmd package list packages -U com.tencent.mm
ss -lntup
```

需要解释VPN/代理路径或已经发生异常时，再按实际后端补充：

```sh
dumpsys connectivity
dumpsys netd
ss -tupn
iptables-save -c
ip6tables-save -c
```

若实际使用 nftables，可用 `nft list ruleset` 只读查看对应规则。TPROXY需看链是否真的被调用、计数增量、mark/mask和对应路由，不能只搜索一个TPROXY字符串。Android切网本来就会更新规则，不要求前后全文相同，需比较与配置对应的语义和作用范围。

需要确认DNS/VPN设置时，可补充 `settings get global private_dns_mode`、`settings get global private_dns_specifier`、`settings get secure always_on_vpn_app`、`settings get secure always_on_vpn_lockdown`。多用户设备必须确认查询的是微信所在用户；`null`或命令不可用不代表策略一定关闭。接口收发计数或`/proc/net/dev_snmp6/接口`在可用时可作为辅助证据，不能单独证明微信流量或零IPv6泄漏。

进一步只摘取代理当前模式、TUN配置、微信UID范围、DNS模式、上游地址族及异常时间附近错误日志；不导出完整订阅或带令牌的配置。原始网络记录可能包含IP、应用或VPN信息，只留本地，分享时保留必要字段，不收集聊天正文。需要抓包时另行设计范围，本阶段不默认抓取全设备流量。

## 异常时的恢复顺序

1. 停止添加变量，记录当前时间、目标接口值/ifindex和业务症状；严重断网时不为收集完整日志拖延恢复。
2. 执行 `sh /data/local/tmp/ipv6_trial.sh restore`，记录返回结果，不反复强写参数。
3. 若旧快照因接口/boot变化失效，先保留快照并核对新环境；不要强行写给重建的接口。按原配置恢复网络，必要时手动重连Wi-Fi。
4. 参数恢复后等待系统配置和业务重连，记录是否必须重开微信、重启代理或重启设备；这些是额外恢复操作，不能计作脚本自动恢复成功。
5. 若发现代理规则缺失，先记录差异，再通过原代理应用/模块的正常入口恢复。不要临时新增DROP、手工重建TPROXY链、关闭offload或清应用数据来使用例“通过”。

## 执行记录模板

复制此表为每次试验填写，不把未知项默认为关闭或未使用。

| 字段 | 填写内容 |
|---|---|
| 用例/轮次 | N编号；第几轮；日期时间 |
| 配置 | Wi-Fi/蜂窝；实际默认上行；代理模式；DNS；微信UID及接管范围 |
| 目标与恢复依据 | 接口、ifindex、boot ID、原值、快照状态 |
| A1 | 各业务结果及耗时；路径证据；切换基线 |
| B | 应用/观察时间；目标值；接口是否重建；各业务结果与耗时 |
| A2 | restore结果；各业务恢复情况；额外操作与恢复耗时 |
| 四项结论 | 业务、策略、路径、恢复分别填写；证据不足填未知 |
| 证据位置 | 本地记录路径与异常时间；不粘贴聊天/订阅凭据 |

## 机制依据与阶段决策

### Opus建议的核对与取舍

- 采纳：先做一次性试验；补测代理启动顺序和恢复后的地址族选择；记录同ifindex下网络身份变化；微信双开/工作资料分别核对UID。代理是否缓存IPv6能力、fake-ip映射是否跨重启保留都按实际版本和配置验证，不预设必然发生。
- 纠正：REDIRECT并非只能处理TCP，UDP也能使用；具体代理入口支持什么另行核对。[Netfilter手册的REDIRECT选项](https://people.netfilter.org/kadlec/ipset/iptables.man.html)明确列出TCP/UDP。本计划继续分别验收TCP和UDP路径。
- 纠正：私人DNS“自动”模式有机会使用加密DNS并在不可用时回退，不能写成一定走DoT；严格模式另行判断。[AOSP PrivateDnsConfiguration](https://android.googlesource.com/platform/packages/modules/DnsResolver/+/refs/tags/android-platform-12.0.0_r39/PrivateDnsConfiguration.cpp)区分OFF/OPPORTUNISTIC/STRICT并说明回退，设备按自身版本核对。
- 不采纳：消息10秒、代理重连2分钟等未经设备基线支持的固定通过阈值；用单次`ip route get ... uid`证明微信真实路径；要求恢复后iptables全文相同；把root TUN一概视为代理TUN。Android CLAT本身也能创建TUN，识别其用途后再判断。[AOSP ClatCoordinator](https://android.googlesource.com/platform/packages/modules/Connectivity/+/96a3f144a785872d624b63e2015f55545a5dcff5/service/src/com/android/server/connectivity/ClatCoordinator.java)
- 代码现状：已有锁、写后回读、boot/ifindex恢复检查；原值已是1时不写，恢复时当前值已等于原值也不写。检查后写入不是原子CAS，不能宣称抵御所有并发写入。当前脚本尚未自动拒绝任意TUN、CLAT或没有独立IPv4的接口；这些前置条件必须人工只读确认，不能按Opus用例直接向真实接口尝试“应拒绝”。本次只更新计划，未把建议当作已实现功能。
- 恢复策略保持正文顺序：不直接照搬原文的人工强写、清空fake-ip或强停微信；额外重连/重启必须记录为额外恢复操作。重启后仍为1也不能单凭这一项断定有持久写入者，还需核对系统初始配置。

- Linux文档说明，接口 `disable_ipv6=1` 会删除该接口IPv6地址和路由，`all`读值不代表全部接口实际状态。这支持逐接口取证和恢复后另测业务。[IP sysctl](https://kernel.org/doc/html/latest/networking/ip-sysctl.html#proc-sys-net-ipv6-variables)
- TPROXY需要报文接管、策略路由及支持透明socket的代理配合，因此端口监听不能独立证明接管成功。[Linux TPROXY](https://docs.kernel.org/networking/tproxy.html)
- Mihomo分别配置TUN、自动路由、出口选择、DNS劫持及UID/应用范围，说明这些维度需要分开记录。文档针对当前版本，具体设备按已安装版本确认。[Mihomo TUN](https://wiki.metacubex.one/config/inbound/tun/)
- Android VPN有底层网络、按应用范围和地址族行为；Always-on/封锁策略需要按照设置解释。[VpnService](https://developer.android.com/reference/android/net/VpnService)、[Builder](https://developer.android.com/reference/android/net/VpnService.Builder)、[VPN指南](https://developer.android.com/develop/connectivity/vpn)
- 464XLAT通过IPv6网络承载IPv4业务；关掉其必需IPv6不能要求IPv4业务不受影响。[RFC 6877](https://datatracker.ietf.org/doc/html/rfc6877)
- 热点还有软件/硬件offload等路径，不能用本机应用结果代替共享客户端验收。[AOSP tethering](https://source.android.com/docs/core/ota/modular-system/tethering)

当前证据只足以制定测试计划，还没有理由引入daemon。先证明“单次变更兼容、恢复可靠”，再根据系统改回的具体触发条件决定是否需要事件协调或持续守护；持续强制关闭可能放大已存在的网络依赖冲突。
