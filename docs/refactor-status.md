# 首轮稳定性重构（2026-10-15）

## 已修复与取舍

- daemon 每 3 秒检查接口，只写与目标不一致的值；删除 inotify 自触发与阻塞冷却。轮询上限明确，不承诺即时强制或零泄漏。
- socket 单实例锁、0600 权限、同 UID 对端检查、1 秒收发超时和 SIGTERM 清理；脚本使用相同 socket 路径及 3 秒客户端超时，不再自行删除 socket。
- 状态汇总所有接口，接口不一致返回 `mixed`，不再把 `all` 的单一读值当作全接口成功；接口读写错误不会报告成功。
- 删除重复的 shell 全接口写入回退；daemon 不可用时操作失败并返回 JSON。状态查询不启动 daemon，只读回退标记为 `unmanaged`。
- WebUI 直接使用 [KernelSU 原生 exec 回调接口](https://github.com/tiann/KernelSU/blob/main/js/index.js)，不需要前端打包依赖；15 秒超时释放按钮并清理回调。
- CI 保留用户要求的分支 push 和 PR 触发，固定 checkout 提交，增加 C 编译、shellcheck、mksh 及 Node 标准库桥接测试。

## 构建与复测

随包二进制为 NDK r29 构建的 arm64/API 30 程序。Linux/WSL 构建：

```sh
ANDROID_NDK_HOME=/path/to/android-ndk-r29 sh scripts/build_daemon.sh
python3 -m unittest discover -s tests -v
IPV6_TEST_SHELL=mksh python3 -m unittest discover -s tests -p test_ipv6_trial.py -v
IPV6_TEST_SHELL=mksh python3 -m unittest discover -s tests -p test_ipv6_manager.py -v
shellcheck -s sh scripts/*.sh service.sh customize.sh tests/android_smoke.sh
node --test tests/test_webui.js
```

Android 隔离测试要求 adb、root 和单一连接设备。必须编译固定假 procfs 路径的测试二进制；运行器会拒绝含真实 procfs 路径的生产二进制，也会拒绝覆盖现有测试目录：

```sh
ANDROID_NDK_HOME=/path/to/android-ndk-r29 sh scripts/build_daemon.sh /tmp/ipv6-daemon-smoke \
  '-DPROC_CONF_DIR="/data/local/tmp/ipv6-ctrl-smoke/conf"'
python3 tests/run_android_smoke.py /tmp/ipv6-daemon-smoke
```

adb 在 Windows、编译在 WSL 时，把测试二进制输出到双方可见的目录，再从 Windows 运行 Python 测试入口。

## 验证证据与尚未覆盖范围

本轮通过 24 项 Python 回归、mksh 下的 13 项 trial 与 4 项 manager 用例、4 项 Node 桥接测试及 shellcheck。NDK 二次构建与提交的二进制逐字节一致。

PJD110/API 36、root、SELinux Enforcing 下的临时目录测试通过：单实例、socket 权限、IPC、空闲客户端、漂移修正、无重复写入和正常退出。前后观察的真实 `all/default/lo/wlan0` 值一致，测试目录已清理。

这不是全局 IPv6 关闭或微信业务验收。模块未安装；WebUI 仅验证原生桥接协议与超时处理，未在管理器实测界面。网络矩阵中的 A/B/A 业务仍待人工完成。

旧模块的全接口策略仍包含 `all/default/lo`，启动从 `all` 采用目标，`ENABLE` 写 0 而非恢复快照。本轮没有增加目标持久化、默认不管理或改写此策略；这些属于后续策略设计，实机继续使用第一阶段单接口 trial。Magisk/APatch 安装包兼容性也未验收，不发布新的可刷 ZIP。
