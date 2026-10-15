# IPV6 Ctrl

当前先进行**无 daemon 的单接口一次性试验**，用于验证微信等应用的网络兼容性。实机使用 `scripts/ipv6_trial.sh`；模块启动入口仍会启用全接口 daemon。部署、恢复和 A/B/A 测试步骤见 [第一阶段试验说明](docs/phase1-trial.md)。

daemon 的首轮稳定性重构与隔离测试见 [重构状态与构建说明](docs/refactor-status.md)。这不表示旧模块的全接口策略或微信业务已完成实机验收。

## 中文

### 简介

IPV6 Ctrl 是一个面向 Android Root 设备的轻量 IPv6 控制工具，用于手动开启、关闭和检查 IPv6 状态。`v0.1.4` 起，模块提供常驻 `ipv6_daemon`；本分支改为定时检查 `/proc/sys/net/ipv6/conf` 并仅回写差值。

许可证：GPL-3.0-only。详见 `LICENSE`。

### 需求

- Android Root 权限
- Android shell 环境
- Magisk / KernelSU / APatch 模块管理器，或手动推送到设备目录
- KernelSU WebUI 为当前主界面入口
- 在线 IPv6 检查可选依赖 `curl` 或 `wget`
- 守护进程控制依赖支持 Unix socket 的 `nc` 实现及 `timeout`；随包二进制为 arm64、最低 API 30

### 部署

刷入模块 zip，或手动推送源码目录：

```sh
adb push IPV6_ctrl /data/local/tmp/IPV6_ctrl
adb shell
su
cd /data/local/tmp/IPV6_ctrl
chmod 755 scripts/*.sh
chmod 755 ipv6_daemon
```

### 命令行使用

```sh
sh scripts/ipv6_manager.sh status
sh scripts/ipv6_manager.sh disable
sh scripts/ipv6_manager.sh enable
sh scripts/ipv6_manager.sh json-status
```

### WebUI 使用

KernelSU：在 KernelSU Manager 中打开模块 WebUI。WebUI 提供手动启用、禁用、刷新状态，以及通过系统浏览器打开 `test-ipv6.com` 的外部测试入口。WebUI 通过模块脚本向 `ipv6_daemon.sock` 发送命令，返回值为干净 JSON。

CLI fallback：

```sh
sh scripts/ipv6_manager.sh json-status
sh scripts/ipv6_manager.sh online-check
```

当前版本以 KernelSU `webroot` 为主，旧的本地端口 WebUI 已弃用。模块安装后会启动后台 `ipv6_daemon` 服务。

### 注意事项

- CLI 命令必须在 Root 环境下运行。
- `enable` / `disable` 通过 daemon socket 操作；daemon 不可用时返回 JSON 错误。状态查询不会启动 daemon，可回退到只读 procfs 状态。
- 在线检查命令为 `sh scripts/ipv6_manager.sh online-check`，会尝试 ipify、SeeIP、icanhazip。
- 模块包根目录必须包含 `module.prop`，否则模块管理器可能提示 archive 内文件缺失。

## English

### Overview

IPV6 Ctrl is a lightweight IPv6 control tool for rooted Android devices. Starting with `v0.1.4`, it provides a resident `ipv6_daemon`; this branch polls `/proc/sys/net/ipv6/conf` and only writes values that differ from its target.

License: GPL-3.0-only. See `LICENSE`.

### Requirements

- Android root access
- Android shell
- Magisk / KernelSU / APatch module manager, or manual push to a device directory
- KernelSU WebUI is now the primary UI path
- Online IPv6 check optionally requires `curl` or `wget`
- Daemon control requires a Unix socket capable `nc` and `timeout`; the bundled binary targets arm64, API 30 or later

### Install

Flash the module zip, or push the source directory manually:

```sh
adb push IPV6_ctrl /data/local/tmp/IPV6_ctrl
adb shell
su
cd /data/local/tmp/IPV6_ctrl
chmod 755 scripts/*.sh
chmod 755 ipv6_daemon
```

### CLI Usage

```sh
sh scripts/ipv6_manager.sh status
sh scripts/ipv6_manager.sh disable
sh scripts/ipv6_manager.sh enable
sh scripts/ipv6_manager.sh json-status
```

### WebUI Usage

KernelSU: open the module WebUI from KernelSU Manager. The WebUI provides manual enable, disable, status refresh, and an external `test-ipv6.com` test entry opened through the Android browser. Commands are sent to `ipv6_daemon.sock`, and the response path stays clean JSON.

CLI fallback:

```sh
sh scripts/ipv6_manager.sh json-status
sh scripts/ipv6_manager.sh online-check
```

This release uses KernelSU `webroot` as the primary UI path. The old local port-based WebUI is deprecated. The module starts `ipv6_daemon` as a background service after install/boot.

### Notes

- The CLI commands must run as root.
- `enable` and `disable` use the daemon socket and return JSON errors if unavailable. Status queries never start the daemon and can read procfs directly.
- Online check command: `sh scripts/ipv6_manager.sh online-check`, with fallback across ipify, SeeIP, and icanhazip.
- A flashable module zip must include `module.prop` at archive root, or module managers may report a missing file in archive.
