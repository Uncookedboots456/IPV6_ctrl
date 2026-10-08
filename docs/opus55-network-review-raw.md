# Opus 5.5 独立评审原文（未逐条验证）

2026-10-07，通过 antigravity-cli 调用 `claude-opus-5-5-high`；进程退出码为0并返回完整正文。下方保留原文，不代表已在设备验证。原文存在不准确或过度推断的表述，包括 REDIRECT 的 UDP 支持、固定超时阈值和现有脚本的拒绝条件；以 [网络测试矩阵](network-test-matrix.md) 的核对说明为准。

---

# Android root IPv6 控制项目第一阶段评审：架构与测试设计

标注说明：【机】是已知机制，有官方资料或源码可查；【推】是推断，需要在设备上核实；【建】是建议。下文没有任何已验证的测试结果。

## 结论

1. **第一阶段方案方向正确，不需要先加 daemon。** 方案只改一个接口、只执行一次、可以恢复，和旧版"持续强制"的风险面是分开的。daemon 会重新出现"和系统反复抢写"的形态。在微信问题的因果确定之前，不应该引入 daemon。
2. **脚本的真实效果范围很窄。** 它只是"某一时刻让某一接口失去 IPv6 地址和路由"【机：disable_ipv6 从 0 改为 1 时，内核会删除该接口的全部地址和路由】。它不追踪切网，不阻止系统把值改回，也不影响代理内部的 IPv6，包括 TUN 内的 IPv6、DNS 返回的 AAAA 记录、代理出站优先用 IPv6。所以报告只能写"接口 X 在时间段 T 内没有 IPv6"，不能写"已禁用 IPv6"。
3. **最高风险的四个漏测点：**
   - 把 464XLAT 或 IPv6-only 网络误当成 IPv4 网络写入。这会连带断掉 IPv4。
   - 代理在启动时探测 IPv6，然后固定为仅 IPv4 模式。A2 恢复后它仍停在这个模式。
   - DNS 返回 AAAA 或 IPv6 fake-ip，但 ip6tables 的规则链不完整。
   - Android 重新配置接口时会把 disable_ipv6 写回去【机：netd 的 `InterfaceController::setEnableIPv6`，IpClient 启动配置时会启用 IPv6】。
4. **恢复必须用"比较后再写"（compare-and-swap）的方式。** 只有 boot ID 相同、ifindex 相同、并且当前值仍是脚本写入的 1 时，才写回原值。如果当前值已经是 0，只报告"已被系统改回"，不写。
5. **boot ID 加 ifindex 不能证明是同一个网络。** rmnet_data 接口常常是预先建好的，换 APN 或重新拨号后可能同名、同 ifindex。【建】额外记录网络身份（IPv4 地址、IPv6 前缀），只用来告警，不作为写入依据。
6. **以下场景第一阶段只观察，禁止写：** 热点、Always-on 加 lockdown、多个代理并存、IPv6-only 网络。

## 一、最容易漏测的冲突

### 1. "接口存在"不等于"实际承载流量"

- **两类 TUN 要分开。**
  - root 原生 TUN（例如 sing-box、clash 的 auto-route）不在 ConnectivityService 注册，`dumpsys connectivity` 里看不到 VPN 网络。
  - VpnService 的 TUN 会作为 VPN 网络出现，带有 UID 范围和底层网络【机】。
  - 判断 TUN 是否真的承载流量，要同时满足三点：ip rule 指向 TUN 的路由表；接口收发计数在增长；`ip route get <目标> uid <微信UID>` 选中了 TUN。
  - 在 TUN 场景下，App 的 IPv6 可能仍然进入 TUN（TUN 通常自带 IPv6 地址），脚本只影响代理的出站。所以"禁 IPv6 是否有效"应该在物理接口的出站上判定，而不是从 App 的角度看。
- **TPROXY 有监听端口不等于规则生效。** 生效需要三样东西【机：内核 tproxy 文档】：
  - mangle 表 PREROUTING 里的 `TPROXY --on-port/--tproxy-mark`；
  - `ip rule fwmark … lookup N`；
  - 路由表 N 里的 `local default dev lo`。
  - IPv6 需要用 ip6tables 和 `ip -6 rule/route` 另配一套，常见情况是只配了 IPv4。
  - 本机发出的流量要在 mangle OUTPUT 打 mark，再重新路由回 PREROUTING【推：这是常见实现】。
  - 热点客户端的流量只经过 PREROUTING 和 FORWARD，不经过 OUTPUT。因此 `--uid-owner` 绕过对热点流量无效【机：owner 匹配只适用于本机产生的包】。
- **mark/mask 冲突。** netd 用 fwmark 的低位保存 netId 和权限标志，并按它做策略路由【机：Fwmark.h】。
  - 如果代理在 OUTPUT 用不带 mask 的 `--set-mark` 覆盖 mark，会破坏 netd 的选路，症状很像"部分 App 断网"。
  - 要检查：是否使用 `--set-xmark 值/掩码`；ip rule 是否带 mask；规则优先级和 netd 的规则是否交错。
- **TCP 和 UDP 处理方式不同。**
  - REDIRECT 只在 nat 表，实际上只处理 TCP。常见配置是"TCP 走 REDIRECT，UDP 走 TPROXY"的混合方式。
  - 微信通话和 QUIC 走 UDP。如果 ip6tables 没有 UDP 规则，IPv6 的 UDP 会直接漏出代理。
  - B 阶段后这类泄漏会消失，容易被误判为"禁 IPv6 修好了问题"。
- **代理出站对 IPv6 的依赖：**
  - 节点域名有 AAAA 记录，或者节点本身是 IPv6 地址；
  - DIRECT 出站优先用 IPv6；
  - 出站绑定在 wlan0 上（bind-interface 或自动检测接口）；
  - 代理启动时探测一次 IPv6 后就固定了行为。
  - 后果是：B 阶段后可能先超时再回落到 IPv4（不一定实现了 Happy Eyeballs），A2 恢复后不一定能复原。

### 2. 微信、DNS、VPN、IPv6-only 的识别和解释

- **按 UID 绕过：** 包不进入代理，走系统路由。
  - DNS 是否也一起绕过，取决于 DNS 劫持规则，以及 DnsResolver 发查询时套接字属于哪个 UID【推：AOSP 会把查询套接字设为应用的 UID，不同版本要核对】。
  - 微信双开或工作资料里的 UID 不同（用户号 ×100000 + appId）。只绕过主 UID 是常见漏洞。
- **按域名 DIRECT：** 包会进入代理，由代理自己直连。
  - 微信大量使用自有 DNS 和 IP 直连【推：mars/newdns】，域名规则常常匹配不到，流量会落到兜底规则。
  - 判定路径要看代理日志实际命中了哪条规则，不能看"配置里写了 DIRECT"。
- **fake-ip：**
  - 代理重启后映射会丢失，App 缓存的 198.18.0.0/15 地址（或 IPv6 fake 段）会失效。已建立的长连接比新连接更容易受影响。
  - 如果 IPv6 fake 段没有对应的 ip6 规则，流量会进黑洞。
- **Private DNS（私人 DNS）：**
  - 有关闭、自动、严格三种模式【机】。自动和严格模式下，系统走 853/TCP 的 DoT，可能绕过只劫持 UDP 53 的 fake-ip。
  - 严格模式下 DoT 不通会导致网络验证失败。
  - 只按日常实际设置测试，不去遍历各种组合。
- **Always-on 与 lockdown：**
  - lockdown 会在 VPN 未连接时阻断非豁免 App【机】。
  - 如果 VPN 服务器端点依赖 IPv6，B 阶段后 VPN 重连失败，结果是全部断网。这看起来像脚本搞坏了，其实是 VPN 的问题。
  - 用 `settings get secure always_on_vpn_app` 和 `settings get secure always_on_vpn_lockdown` 读取状态。
- **IPv6-only、464XLAT、DNS64、NAT64：**
  - 在 NAT64 网络上，Android 会启动 clat，出现 `v4-<上行接口>` 和 192.0.0.4/29 地址【机：RFC 6877、RFC 7335】。
  - 这时 IPv4 实际由 IPv6 承载，禁掉上行接口的 IPv6 就等于断网。
  - 识别方法：
    - 存在 `v4-*` 接口；
    - `dumpsys connectivity` 里有 NAT64 前缀；
    - `ipv4only.arpa` 解析得到 64:ff9b::/96 或运营商前缀【RFC 7050】；
    - Wi-Fi 也可能是 RFC 8925 的"优先 IPv6-only"网络。
  - "独立 IPv4 蜂窝"的判定标准：上行接口自己有不在 192.0.0.0/29 内的 IPv4 地址，并且 IPv4 默认路由经过它本身。

## 二、判定口径（每个用例按四项分别判定）

- **业务可用：** 在 A1 对同一动作做基线。消息送达 ≤10 秒（锁屏时 ≤ 基线 + 60 秒），媒体收发和通话能完成。
- **禁 IPv6 策略有效：**
  - 判定条件：目标接口值为 1；没有任何 IPv6 地址；没有该接口的 IPv6 路由；`/proc/net/dev_snmp6/<if>` 中 Ip6OutRequests 增量约等于 0。
  - 只对"目标接口 + 时间段"判定。切到其他上行接口时一律记"未覆盖"，既不算通过也不算失败。
- **流量路径正确：** 和配置的预期一致。
  - UID 绕过：微信套接字属于微信 UID，源地址是上行接口的 IPv4。
  - 经代理：规则计数增长，或代理日志有命中。
- **恢复正确：** 分三层，分别记录。
  - 第一层：sysctl 值等于原值。
  - 第二层：IPv6 地址和默认路由重新出现，记录耗时（可能要等路由器通告，或者开关一次 Wi-Fi）。
  - 第三层：iptables 规则和 ip rule 与 A1 对比一致。
  - 微信旧连接不要求存活，只要求新连接正常。
- **跳过：** 日常配置里不存在该前置条件。跳过不等于通过。

## 三、用例表

| ID | 前置条件 | 操作 B / 动作 | 关键检查 | 失败 / 跳过标准 |
|---|---|---|---|---|
| P0-01 | 日常状态 | 不写，只读清点 | TUN 类型；TPROXY 三件套（v4/v6）；REDIRECT；clat；Private DNS；Always-on；微信各 UID；其他会写 disable_ipv6 的程序（含旧版残留、代理模块自带的 IPv6 开关） | 发现未知写入者 → 停止所有写入类用例 |
| P0-02 | 无 | 用 all、default、lo、tun\*、v4-\*、不存在的接口、原值已是 1 的接口、没有 IPv4 的接口分别调用脚本 | 全部被拒绝，所有值不变 | 任何一个被写入 → 阻断 |
| P0-03 | 稳定双栈 Wi-Fi，代理停止 | 写 wlan0，然后 A2 | 四项判定；记录 IPv6 地址恢复耗时 | 业务失败，或 A2 的 sysctl 值不等于原值 |
| P0-04 | 同上，代理按日常配置运行 | 写 wlan0 | 代理日志中的 IPv6 拨号错误；AAAA 处理；v4/v6 规则计数 | 业务比 A1 差，或路径偏离预期 |
| P0-05 | 微信在前台、已在线（热长连接） | B 后第 0、1、5 分钟收发消息 | 首条消息延迟；是否重连 | 5 分钟内没有恢复 |
| P0-06 | 已处于 B 状态 | 分三种情况：系统改回 / 重启手机 / 接口重建，然后运行恢复脚本 | 分别应为：不写并报告 / 拒绝 / 拒绝 | 任何一种情况强行写入 → 阻断 |
| P0-07 | 存在 clat 网络 | 只读，然后调用脚本 | 脚本必须拒绝 | 没有这种网络时跳过 |
| P0-08 | B 状态下出现异常 | 按第四节的恢复顺序操作 | 每一步之后复测，记录在哪一步恢复 | 全部走完仍异常 → 保存取证 |
| P1-01 | 关闭 Wi-Fi，确认蜂窝有原生 IPv4 | 写 rmnet_dataX | 四项判定，加上通话和媒体 | 无法确认原生 IPv4 → 跳过 |
| P1-02 | wlan0 处于 B | 关闭 Wi-Fi，切到蜂窝 | 蜂窝的 IPv6 仍在，策略记"未覆盖"；检查业务 | 报告写成"全局禁用"即判定错误 |
| P1-03 | wlan0 处于 B | 从蜂窝切回 Wi-Fi，或重连同一 SSID | wlan0 的值是否被系统改回；恢复脚本是否按比较后再写的规则执行 | 观察项 |
| P1-04 | B 状态 | 开关飞行模式或 Wi-Fi | 被改回的时机；ifindex 是否变化 | 观察项 |
| P1-05 | B 状态 | 重启代理 | v4/v6 规则是否重建；fake-ip；微信恢复用时 | 规则缺失，或微信超过 2 分钟未恢复 |
| P1-06 | A1 | 分两种顺序：先启动代理再 B；先 B 再启动代理，然后 A2 | A2 后代理是否仍是仅 IPv4 模式 | A2 后的路径和 A1 不同 → 恢复失败 |
| P1-07 | B 状态 | 强行停止微信后重新打开（冷启动） | 登录、消息拉取、新建连接 | 比 A1 明显差 |
| P1-08 | B 状态 | 收发图片和视频；语音、视频通话 | UDP 路径（代理日志或 `ss -u`） | A1 能通而 B 不能 |
| P1-09 | B 状态，熄屏 15 分钟和 30 分钟 | 用另一台设备发消息 | 送达延迟和 A1 对比 | 明显比 A1 差 |
| P1-10 | 日常 Private DNS 模式 | B | DoT 是否绕过代理 DNS；是否仍返回 AAAA | 解析失败，或产生 IPv6 黑洞 |
| P2-01 | 开启热点 | 只观察，不写上行接口 | 客户端的 IPv6；FORWARD 路径；硬件或 eBPF 转发加速可能绕过 iptables【推】 | 第一阶段禁止写 |
| P2-02 | 日常使用 Always-on/lockdown 时 | 只读 | VPN 端点走 IPv4 还是 IPv6 | 禁止写 |
| P2-03 | 多个代理并存或切换 | 只观察 | ip rule 优先级；mark 冲突 | 禁止写 |
| P2-04 | 有微信双开或工作资料 | B | 每个 UID 的路径分别检查 | 漏掉某个 UID → 路径失败 |

## 四、最少只读取证与异常恢复顺序

**取证：** A1、B、A2 各取一次，带时间戳。

1. `boot_id`、`ifindex`、`ip -o link`
2. 目标接口、all、default 三处的 `disable_ipv6`，以及 `accept_ra`
3. `ip -4/-6 addr`；`ip rule` 和 `ip -6 rule`；相关路由表
4. `iptables-save -c` 和 `ip6tables-save -c` 的 mangle 表与 nat 表
5. `/proc/net/dev_snmp6/<if>`
6. `dumpsys connectivity`（默认网络、VPN、lockdown、NAT64/clat、DNS 服务器）；`settings get global private_dns_mode` 和 `private_dns_specifier`；always_on 相关设置
7. `ss -tuanpe`，筛出微信各 UID 的连接；代理日志的对应时间段
8. `dumpsys package com.tencent.mm` 中各用户下的 userId

**异常恢复顺序：**

1. 停止任何写入，先在 30 秒内取证。
2. 运行恢复脚本（比较后再写）。
3. 如果脚本拒绝或报告"已改回"，不要手动写。
4. 只有当前值是 1，并且确认就是本次写入的同一个接口时，才人工只对这个接口写 0。
5. 开关 Wi-Fi 或飞行模式，让系统重新配置网络。
6. 重启代理，清空 fake-ip 并重建规则。
7. 强行停止微信。
8. 重启手机。单个接口的 sysctl 设置重启后不保留。如果重启后值仍是 1，说明还有其他持久写入者，回到 P0-01。

**只观察、禁止盲目写的场景：**
- IPv6-only 或 464XLAT 网络；
- 热点上行接口；
- lockdown 开启且 VPN 端点依赖 IPv6；
- 多个代理并存；
- "移动数据始终开启"时，非默认的那个上行接口；
- 原值已经是 1 的接口；
- 任何 tun 或 v4- 开头的接口。

**daemon：** 没有必须现在加的理由。只有同时满足以下条件才值得讨论：需求明确是"跨切网持续保持"；P0/P1 证明一次性写入确实有收益；并且证明它不会复现微信问题。即使到那时，也应该是事件驱动、有白名单、限频、有总开关，并且不和 IpClient 抢写。

## 五、边界与最小代码建议（不实现）

**脚本的边界：**
- 不追踪新接口和切网；
- 不阻止系统把值改回；
- 不影响 TUN、VpnService 或代理内部的 IPv6；
- 不清理已经存在的 IPv6 TCP 连接，也不保证 IPv4 连接正常建立；
- 恢复 sysctl 后，地址和路由要等路由器通告（RA）或网络重新配置才会回来。

**【建】最小改动：**
1. 恢复改为比较后再写，三种状态分别输出：已恢复 / 已被系统改回 / 拒绝。
2. 增加写前拒绝条件：接口没有原生 IPv4；存在 `v4-<if>`；原值已是 1；接口名不在 `/sys/class/net` 中。
3. 快照里补记 IPv4 地址和 IPv6 前缀，只用来告警。
4. 写入后立即回读，并记录 dev_snmp6 的基线。
5. 输出措辞固定为"接口 X 自 T 起无 IPv6"。
6. 加锁文件，防止两次执行同时运行。

**参考资料：**
- https://docs.kernel.org/networking/ip-sysctl.html
- https://docs.kernel.org/networking/tproxy.html
- https://developer.android.com/reference/android/net/VpnService
- https://developer.android.com/develop/connectivity/vpn
- https://android-developers.googleblog.com/2018/04/dns-over-tls-support-in-android-p.html
- https://cs.android.com/android/platform/superproject/main/+/main:system/netd/server/InterfaceController.cpp
- https://cs.android.com/android/platform/superproject/main/+/main:system/netd/include/Fwmark.h
- https://cs.android.com/android/platform/superproject/main/+/main:packages/modules/NetworkStack/src/android/net/ip/IpClient.java
- https://www.rfc-editor.org/rfc/rfc6877
- https://www.rfc-editor.org/rfc/rfc7050
- https://www.rfc-editor.org/rfc/rfc7335
- https://www.rfc-editor.org/rfc/rfc8925
- https://developer.android.com/training/monitoring-device-state/doze-standby

源码链接请按设备的 Android 版本核对。
