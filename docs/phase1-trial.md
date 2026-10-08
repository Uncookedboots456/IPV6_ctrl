# 第一阶段：一次性 IPv6 试验

目标是先验证单次 IPv6 变更对微信等应用的影响。脚本只改手工指定的一个接口，保存原值，写入一次后退出。没有 daemon、自启、定时回写、LSPosed hook 或防火墙规则。

**本阶段只推送 `scripts/ipv6_trial.sh`，不要安装旧模块包，也不要运行旧的 `ipv6_manager.sh` 或 `service.sh`：它们仍会启动 daemon。** 如果旧模块已启用，应先禁用旧模块并重启。试验脚本检测到 `ipv6_daemon` 会拒绝修改网络。

一次性脚本足以验证兼容性，但不保证持续禁用：Android 可能重新开启接口，新接口也不会自动被处理。发现恢复为 `0` 时只记录时间，不反复执行关闭命令。

涉及 TUN、TPROXY、VpnService、DNS、蜂窝/Wi-Fi切换时，先按 [网络测试矩阵](network-test-matrix.md) 确认实际路径，再选择适用用例；不要把存在接口/端口等同于流量已经接管。

## 准备与恢复

在项目目录 `IPV6_ctrl` 的电脑终端执行：

```sh
adb push scripts/ipv6_trial.sh /data/local/tmp/ipv6_trial.sh
adb shell
su
sh /data/local/tmp/ipv6_trial.sh status
```

先确认微信在当前网络正常。第一轮建议固定 Wi-Fi，必要时关闭蜂窝数据，避免蜂窝回退掩盖 Wi-Fi 故障；每个对照阶段保持相同设置。根据 `status` 确认接口名称。以下命令适用于接口为 `wlan0` 的设备，在上面的手机 root shell 中执行：

```sh
sh /data/local/tmp/ipv6_trial.sh disable wlan0
sh /data/local/tmp/ipv6_trial.sh status
```

测试结束或出现异常时立即执行：

```sh
sh /data/local/tmp/ipv6_trial.sh restore
sh /data/local/tmp/ipv6_trial.sh status
```

原值保存在 root 私有目录 `/data/adb/ipv6_ctrl_trial/snapshot`。重复关闭会被拒绝，防止覆盖恢复依据；应用或即时回读失败会尝试恢复。快照对应的 boot ID 或接口 ifindex 发生变化时，恢复命令拒绝写入并保留快照，避免把旧状态写给重建的接口。

恢复的是原 `disable_ipv6` 参数，不能重建被中断的连接。Linux 禁用接口 IPv6 时会删除它的 IPv6 地址和路由；恢复参数后，可能需要等待网络重新配置或手动重连 Wi-Fi。[内核文档](https://kernel.org/doc/html/latest/networking/ip-sysctl.html#proc-sys-net-ipv6-variables)

若设备重启或接口已经重建，先检查当前网络是否恢复正常。确认旧试验已结束、无需保留恢复依据后，可在 root shell 中删除旧快照：

```sh
rm /data/adb/ipv6_ctrl_trial/snapshot
```

若脚本被强杀留下 `lock` 目录，确认没有另一个试验脚本运行后，使用 `rmdir /data/adb/ipv6_ctrl_trial/lock` 清理空锁目录。不要删除仍在进行的试验快照或锁。

## 微信验证顺序

采用 A → B → A：原状态、单次关闭、恢复原值。先固定网络完成这一轮，再逐项引入网络切换；发生严重异常时停止添加变量。

| 阶段 | 手动操作 | 记录内容 |
|---|---|---|
| A：基线 | 验证文字、图片、语音消息、朋友圈、语音/视频通话、锁屏收消息 | 能否完成、耗时、是否已有异常 |
| B：单次关闭 | 保持同一网络重复上述业务；先观察现有微信进程，再单独测试重开微信 | 业务差异、异常时间、接口值是否仍为 `1` |
| A：恢复 | 执行 `restore`，重复失败项；必要时手动重连网络并单独记录 | 是否恢复、恢复所需时间和操作 |
| 稳定后再测 | 分别测试 Wi-Fi 重连、Wi-Fi/蜂窝切换、飞行模式、VPN/代理开关 | 接口创建/重开、IPv6 参数、业务差异 |

收发消息和通话由用户手动完成；脚本不会操作微信、发送消息或采集聊天内容。锁屏消息需由用户用测试账号或联系人配合，网页连通不能替代这项验证。

每次业务测试前后运行一次 `status`。初期保持 Surfing、DNS 和代理规则不变；之后需要比较代理开关时，单独增加对照，避免同时改动多个变量。

## 如何判断下一步

- B 阶段异常、恢复后改善：说明一次 IPv6 变更就可能影响业务，应先定位业务/网络依赖，暂不加入强制守护。
- B 阶段正常，但接口后来恢复 `0`：兼容性与持续控制是两件事，记录触发条件后，再决定是否需要事件 hook 或 daemon。
- A、B 均异常：不能归因于本次变更，先恢复基线。
- B 阶段参数仍为 `1` 且业务正常：只说明当前接口、网络和测试项通过，不代表蜂窝/VPN/热点或全设备 IPv6 已验证。

## 本地验证与证据边界

在 Linux/WSL 下从 `IPV6_ctrl` 运行：

```sh
python3 -m unittest discover -s tests -v
```

测试把 procfs、网络接口和快照路径替换到临时目录，验证修改范围、恢复、失败回滚、daemon 冲突及重建接口保护，不接触宿主真实网络。证据来自这些模拟测试；结论限于脚本逻辑。实际路径是“记录基线 → 单接口写入 → 人工业务验证 → 恢复 → 对照”，Android shell/SELinux 和微信实机表现仍需上述手动验收。
