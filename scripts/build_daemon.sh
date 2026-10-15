#!/bin/sh
set -eu

# Linux/WSL; optional output path followed by extra compiler flags for isolated tests.
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to the Android NDK r29 directory}"
root=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
tools="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
output=${1:-"$root/ipv6_daemon"}
if [ "$#" -gt 0 ]; then
    shift
fi
"$tools/aarch64-linux-android30-clang" -Os -Wall -Wextra -Werror -fPIE -pie \
    -Wl,-z,relro,-z,now "$@" "$root/daemon.c" -o "$output"
"$tools/llvm-strip" "$output"
