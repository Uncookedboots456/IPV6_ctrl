#!/bin/sh
set -eu
script=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)/app/src/main/assets/system-ipv6.sh
scratch=$(mktemp -d)
trap 'rm -rf "$scratch"' EXIT
conf=$scratch/conf
state=$scratch/state
boot=$scratch/boot
mkdir -p "$conf"
echo test-boot > "$boot"
node() { mkdir -p "$conf/$1"; echo "$2" > "$conf/$1/disable_ipv6"; }
run() { sh "$script" --test "$conf" "$state" "$boot" "$1"; }
expect() { [ "$(cat "$conf/$1/disable_ipv6")" = "$2" ]; }
node all 0; node default 0; node lo 0; node wlan0 1
output=$(run status); printf '%s\n' "$output" | grep -qx state=mixed
output=$(run toggle); printf '%s\n' "$output" | grep -qx state=blocked
[ -f "$state/snapshot" ]
cp "$state/snapshot" "$scratch/original"
output=$(run enable); printf '%s\n' "$output" | grep -qx state=enabled
expect wlan0 0; expect all 0
cmp "$state/snapshot" "$scratch/original"
output=$(run toggle); printf '%s\n' "$output" | grep -qx state=blocked
# Partial drift while managed must turn ON, not double-toggle back to OFF.
node wlan0 0
output=$(run toggle); printf '%s\n' "$output" | grep -qx state=enabled
[ ! -f "$state/managed" ]
cmp "$state/snapshot" "$scratch/original"
run block >/dev/null
node rmnet0 1
output=$(run block); printf '%s\n' "$output" | grep -qx state=blocked
cmp "$state/snapshot" "$scratch/original"
output=$(run restore); printf '%s\n' "$output" | grep -qx state=mixed
expect all 0; expect default 0; expect lo 0; expect wlan0 1; expect rmnet0 0
[ ! -f "$state/snapshot" ]
if run restore; then echo 'must reject missing snapshot'; exit 1; fi
run block >/dev/null
mkdir "$state/lock-test-boot"
if run restore; then echo 'must reject concurrent operation'; exit 1; fi
rmdir "$state/lock-test-boot"
# A failed restore must preserve the original snapshot and permit a retry.
rm "$conf/wlan0/disable_ipv6"
mkdir "$conf/wlan0/disable_ipv6"
if run block; then echo 'must report failed block/rollback'; exit 1; fi
[ -f "$state/snapshot" ]
if run enable; then echo 'must report enable failure'; exit 1; fi
[ ! -f "$state/managed" ]
if run restore; then echo 'must report restore failure'; exit 1; fi
[ -f "$state/snapshot" ]
rmdir "$conf/wlan0/disable_ipv6"
echo 1 > "$conf/wlan0/disable_ipv6"
run restore >/dev/null
expect wlan0 1
run block >/dev/null
echo next-boot > "$boot"
output=$(run status); printf '%s\n' "$output" | grep -qx restorable=false
if run restore; then echo 'must reject old boot snapshot'; exit 1; fi
rm "$conf/all/disable_ipv6"
if run block; then echo 'must reject incomplete snapshot'; exit 1; fi
echo 'System IPv6 snapshot/block/restore checks passed (temporary files only)'
