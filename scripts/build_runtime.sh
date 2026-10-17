#!/bin/sh
set -eu
# Same arm64/API30 toolchain and hardening flags as PR #1's daemon build.
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to the Android NDK r29 directory}"
root=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
tools="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
"$tools/aarch64-linux-android30-clang" -Os -Wall -Wextra -Werror -fPIE -pie \
    -Wl,-z,relro,-z,now "$root/module/daemon.c" -o "$root/module/ipv6_daemon"
"$tools/llvm-strip" "$root/module/ipv6_daemon"
