# IPV6 Ctrl

## 中文

### 简介

IPV6 Ctrl 是一个面向 Android Root 设备的轻量 IPv6 控制工具，用于手动开启、关闭和检查 IPv6 状态。它只修改运行时 `/proc/sys/net/ipv6/conf` 参数，重启后通常会恢复。

### 需求

- Android Root 权限
- Android shell 环境
- WebUI 服务需要 `nc`、`toybox nc`、`busybox nc` 或 `ncat`
- 在线 IPv6 检查可选依赖 `curl` 或 `wget`

### 部署

```sh
adb push . /data/local/tmp/IPV6_ctrl
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

```sh
su
cd /data/local/tmp/IPV6_ctrl
HOST=127.0.0.1 PORT=8766 sh scripts/ipv6_webui_server.sh
```

然后在设备浏览器打开：

```text
http://127.0.0.1:8766
```

WebUI 提供手动启用、关闭、刷新状态、快速在线 IPv6 检查和完整浏览器测试入口。项目不包含开机自启，用户需要手动启动 WebUI 服务。

### 注意事项

- WebUI 服务必须以 Root 运行。
- 建议保持 `HOST=127.0.0.1`，避免局域网设备访问 Root 控制 API。
- 在线检查接口为 `GET /api/online-check`，会尝试 ipify、SeeIP、icanhazip。

## English

### Overview

IPV6 Ctrl is a lightweight IPv6 control tool for rooted Android devices. It manually enables, disables, and checks IPv6 by changing runtime `/proc/sys/net/ipv6/conf` values. Changes usually reset after reboot.

### Requirements

- Android root access
- Android shell
- WebUI server requires `nc`, `toybox nc`, `busybox nc`, or `ncat`
- Online IPv6 check optionally requires `curl` or `wget`

### Install

```sh
adb push . /data/local/tmp/IPV6_ctrl
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

```sh
su
cd /data/local/tmp/IPV6_ctrl
HOST=127.0.0.1 PORT=8766 sh scripts/ipv6_webui_server.sh
```

Open on the device:

```text
http://127.0.0.1:8766
```

The WebUI provides manual enable, disable, status refresh, quick online IPv6 check, and a full browser test link. This project does not include autostart; users start the WebUI server manually.

### Notes

- The WebUI server must run as root.
- Keep `HOST=127.0.0.1` unless you intentionally expose the root control API.
- Online check endpoint: `GET /api/online-check`, with fallback across ipify, SeeIP, and icanhazip.
