# IPV6 Ctrl

## 中文

### 简介

IPV6 Ctrl 是一个面向 Android Root 设备的轻量 IPv6 控制工具，用于手动开启、关闭和检查 IPv6 状态。`v0.1.4` 起，模块使用常驻 `ipv6_daemon` 监听和回写运行时 `/proc/sys/net/ipv6/conf` 参数，避免厂商网络栈反复抢写。

许可证：GPL-3.0-only。详见 `LICENSE`。

### 需求

- Android Root 权限
- Android shell 环境
- Magisk / KernelSU / APatch 模块管理器，或手动推送到设备目录
- KernelSU WebUI 为当前主界面入口
- 在线 IPv6 检查可选依赖 `curl` 或 `wget`
- 守护进程控制依赖设备可用的 Unix socket `nc` 实现（`nc` / `toybox nc` / `busybox nc`）

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
- `enable` / `disable` / `json-status` 默认走 daemon socket；daemon 不可用时脚本会回退到旧的多轮 apply。
- 在线检查命令为 `sh scripts/ipv6_manager.sh online-check`，会尝试 ipify、SeeIP、icanhazip。
- 模块包根目录必须包含 `module.prop`，否则模块管理器可能提示 archive 内文件缺失。
- `scripts/ipv6_webui_server.sh` 已在项目中弃用。

## English

### Overview

IPV6 Ctrl is a lightweight IPv6 control tool for rooted Android devices. Starting with `v0.1.4`, it uses a resident `ipv6_daemon` to watch and re-apply runtime `/proc/sys/net/ipv6/conf` values when vendor networking components try to override them.

License: GPL-3.0-only. See `LICENSE`.

### Requirements

- Android root access
- Android shell
- Magisk / KernelSU / APatch module manager, or manual push to a device directory
- KernelSU WebUI is now the primary UI path
- Online IPv6 check optionally requires `curl` or `wget`
- Daemon control requires a Unix socket `nc` implementation on-device (`nc`, `toybox nc`, or `busybox nc`)

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
- `enable`, `disable`, and `json-status` now prefer the daemon socket path; the old multi-pass shell logic remains as a fallback if the daemon is unavailable.
- Online check command: `sh scripts/ipv6_manager.sh online-check`, with fallback across ipify, SeeIP, and icanhazip.
- A flashable module zip must include `module.prop` at archive root, or module managers may report a missing file in archive.
- `scripts/ipv6_webui_server.sh` is deprecated in the project.
