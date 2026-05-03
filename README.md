# IPV6 Ctrl

## 中文

### 简介

IPV6 Ctrl 是一个面向 Android Root 设备的轻量 IPv6 控制工具，用于手动开启、关闭和检查 IPv6 状态。它只修改运行时 `/proc/sys/net/ipv6/conf` 参数，重启后通常会恢复。

许可证：GPL-3.0-only。详见 `LICENSE`。

### 需求

- Android Root 权限
- Android shell 环境
- Magisk / KernelSU / APatch 模块管理器，或手动推送到设备目录
- KernelSU WebUI 版本优先面向 KernelSU Manager
- 在线 IPv6 检查可选依赖 `curl` 或 `wget`

### 部署

刷入模块 zip，或手动推送源码目录：

```sh
adb push IPV6_ctrl /data/local/tmp/IPV6_ctrl
adb shell
su
cd /data/local/tmp/IPV6_ctrl
chmod 755 scripts/*.sh
```

### 命令行使用

```sh
sh scripts/ipv6_manager.sh status
sh scripts/ipv6_manager.sh disable
sh scripts/ipv6_manager.sh enable
```

### WebUI 使用

KernelSU：在 KernelSU Manager 中打开模块 WebUI。

CLI fallback：

```sh
sh scripts/ipv6_manager.sh json-status
sh scripts/ipv6_manager.sh online-check
```

此预发布版本以 KernelSU `webroot` 为主，旧的本地端口 WebUI 已弃用。项目不包含开机自启。

### 注意事项

- WebUI 服务必须以 Root 运行。
- 在线检查命令为 `sh scripts/ipv6_manager.sh online-check`，会尝试 ipify、SeeIP、icanhazip。
- 模块包根目录必须包含 `module.prop`，否则模块管理器可能提示 archive 内文件缺失。
- `scripts/ipv6_webui_server.sh` 已在此预发布分支中弃用。

## English

### Overview

IPV6 Ctrl is a lightweight IPv6 control tool for rooted Android devices. It manually enables, disables, and checks IPv6 by changing runtime `/proc/sys/net/ipv6/conf` values. Changes usually reset after reboot.

License: GPL-3.0-only. See `LICENSE`.

### Requirements

- Android root access
- Android shell
- Magisk / KernelSU / APatch module manager, or manual push to a device directory
- The WebUI pre-release targets KernelSU Manager first
- Online IPv6 check optionally requires `curl` or `wget`

### Install

Flash the module zip, or push the source directory manually:

```sh
adb push IPV6_ctrl /data/local/tmp/IPV6_ctrl
adb shell
su
cd /data/local/tmp/IPV6_ctrl
chmod 755 scripts/*.sh
```

### CLI Usage

```sh
sh scripts/ipv6_manager.sh status
sh scripts/ipv6_manager.sh disable
sh scripts/ipv6_manager.sh enable
```

### WebUI Usage

KernelSU: open the module WebUI from KernelSU Manager.

CLI fallback:

```sh
sh scripts/ipv6_manager.sh json-status
sh scripts/ipv6_manager.sh online-check
```

This pre-release uses KernelSU `webroot` as the primary UI path. The old local port-based WebUI is deprecated. This project does not include autostart.

### Notes

- The CLI commands must run as root.
- Online check command: `sh scripts/ipv6_manager.sh online-check`, with fallback across ipify, SeeIP, and icanhazip.
- A flashable module zip must include `module.prop` at archive root, or module managers may report a missing file in archive.
- `scripts/ipv6_webui_server.sh` is deprecated in this pre-release branch.
