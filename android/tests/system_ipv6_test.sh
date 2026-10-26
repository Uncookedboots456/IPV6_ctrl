#!/bin/sh
set -eu
script=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)/app/src/main/assets/system-ipv6.sh
scratch=$(mktemp -d)
trap 'rm -rf "$scratch"' EXIT
conf=$scratch/conf
state=$scratch/state
boot=$scratch/boot
module=$scratch/module
uptime=$scratch/uptime
mkdir -p "$conf"
echo test-boot > "$boot"
node() { mkdir -p "$conf/$1"; echo "$2" > "$conf/$1/disable_ipv6"; }
run() { "${TEST_SHELL:-sh}" "$script" --test "$conf" "$state" "$boot" "$@"; }
expect() { [ "$(cat "$conf/$1/disable_ipv6")" = "$2" ] || { echo "FAIL: $1 expected $2, got $(cat "$conf/$1/disable_ipv6")"; exit 1; }; }
assert_eq() { [ "$1" = "$2" ] || { echo "FAIL: $3: got '$1', want '$2'"; exit 1; }; }
assert_line() { printf '%s\n' "$1" | grep -qx "$2" || { echo "FAIL: missing $2 in output: $1"; exit 1; }; }
assert_absent() { [ ! -e "$1" ] || { echo "FAIL: unexpected $1"; exit 1; }; }
assert_present() { [ -e "$1" ] || { echo "FAIL: missing $1"; exit 1; }; }
boot_id() { cat "$boot"; }

node all 0; node default 0; node lo 0; node wlan0 1
output=$(run status)
assert_line "$output" state=mixed
assert_line "$output" desired=none
assert_line "$output" boot_apply=none
assert_line "$output" last_sweep=unknown
assert_line "$output" last_sweep_boottime=unknown
assert_line "$output" last_sweep_age=unknown
assert_line "$output" managed=false

# A first boot without persistent intent changes nothing.
output=$(run adopt)
assert_line "$output" state=mixed
assert_line "$output" desired=none
assert_absent "$state/snapshot"
assert_absent "$state/managed"
assert_absent "$state/boot_apply"
expect wlan0 1; expect all 0

# The ordinary toggle-off path is current-boot only; it never creates an intent.
output=$(run toggle)
assert_line "$output" state=blocked
assert_line "$output" managed=true
assert_line "$output" desired=none
assert_absent "$state/desired"
assert_present "$state/snapshot"
expect wlan0 1; expect all 1
# Explicit lock upgrades the same current-boot takeover without replacing its snapshot.
cp "$state/snapshot" "$scratch/current-snapshot"
output=$(run lock)
assert_line "$output" state=blocked
assert_line "$output" managed=true
assert_line "$output" desired=locked
assert_eq "$(cat "$state/desired")" ipv4-lock "upgraded desired content"
cmp "$state/snapshot" "$scratch/current-snapshot"
output=$(run pause)
assert_line "$output" managed=false
assert_line "$output" desired=none
assert_present "$state/snapshot"

# Explicit lock is the only path that creates a persistent intent.
node wlan0 0
output=$(run lock)
assert_line "$output" state=blocked
assert_line "$output" managed=true
assert_line "$output" desired=locked
assert_eq "$(cat "$state/desired")" ipv4-lock "desired content"
expect wlan0 1; expect all 1
# Toggle-on is an unlock: it clears the intent before stopping takeover.
output=$(run toggle)
assert_line "$output" state=enabled
assert_line "$output" managed=false
assert_line "$output" desired=none
assert_absent "$state/desired"
assert_present "$state/snapshot"
expect wlan0 0; expect all 0

# Cross-boot adoption captures a new baseline, applies it and commits this boot.
node wlan0 0
run lock >/dev/null
assert_eq "$(head -n 1 "$state/snapshot")" test-boot "first snapshot boot"
echo next-boot > "$boot"
node all 0; node default 0; node lo 0; node wlan0 0; node rmnet0 1
output=$(run adopt)
assert_line "$output" state=blocked
assert_line "$output" managed=true
assert_line "$output" desired=locked
assert_line "$output" boot_apply=applied
assert_line "$output" restorable=true
expect wlan0 1; expect rmnet0 1; expect all 1
assert_eq "$(head -n 1 "$state/snapshot")" next-boot "adopted snapshot boot"
grep -qx 'wlan0 0' "$state/snapshot"
cp "$state/snapshot" "$scratch/next-snapshot"
cp "$state/boot_apply" "$scratch/next-boot-apply"
# Repeating adoption in the same boot must not replace the original snapshot.
output=$(run adopt)
assert_line "$output" boot_apply=applied
assert_line "$output" managed=true
cmp "$state/snapshot" "$scratch/next-snapshot"
cmp "$state/boot_apply" "$scratch/next-boot-apply"
# Adoption never reapplies on a supported same-boot repeat; only the worker corrects drift.
node wlan0 0
run adopt >/dev/null
expect wlan0 0
cmp "$state/snapshot" "$scratch/next-snapshot"

# Pause is an unlock: the next boot must stay passive.
run pause >/dev/null
assert_absent "$state/desired"
assert_absent "$state/managed"
assert_present "$state/snapshot"
echo boot-three > "$boot"
node all 0; node default 0; node lo 0; node wlan0 0; node rmnet0 0
output=$(run adopt)
assert_line "$output" desired=none
assert_line "$output" managed=false
assert_absent "$state/managed"
assert_absent "$state/snapshot"
expect wlan0 0; expect rmnet0 0

# Every unlock entry clears the intent; a later boot does not revive it.
node wlan0 1
run lock >/dev/null
run enable >/dev/null
assert_absent "$state/desired"
assert_absent "$state/managed"
assert_present "$state/snapshot"
expect wlan0 0; expect all 0
echo boot-four > "$boot"
node all 0; node default 0; node lo 0; node wlan0 0
run adopt >/dev/null
expect wlan0 0
assert_absent "$state/managed"

node wlan0 1
run lock >/dev/null
run pause >/dev/null
assert_absent "$state/desired"
echo boot-five > "$boot"
node all 0; node default 0; node lo 0; node wlan0 0
run adopt >/dev/null
expect wlan0 0
assert_absent "$state/managed"

node wlan0 0
echo boot-six > "$boot"
run lock >/dev/null
run restore >/dev/null
assert_absent "$state/desired"
assert_absent "$state/managed"
assert_absent "$state/snapshot"
expect wlan0 0
echo boot-seven > "$boot"
node all 0; node default 0; node lo 0; node wlan0 0
run adopt >/dev/null
expect wlan0 0
assert_absent "$state/managed"

node wlan0 1
run lock >/dev/null
run toggle >/dev/null
assert_absent "$state/desired"
assert_absent "$state/managed"
expect wlan0 0; expect all 0
echo boot-eight > "$boot"
node all 0; node default 0; node lo 0; node wlan0 0
run adopt >/dev/null
expect wlan0 0
assert_absent "$state/managed"

# A failed automatic apply is recorded once, keeps snapshot/intent and never retries.
node wlan0 0; node rmnet0 0
echo boot-nine > "$boot"
run lock >/dev/null
rm "$state/managed"
rm "$conf/wlan0/disable_ipv6"; mkdir "$conf/wlan0/disable_ipv6"
if output=$(run adopt 2>&1); then echo 'FAIL: failed adoption reported success'; exit 1; fi
assert_line "$output" error=boot-apply-failed
assert_present "$state/snapshot"
assert_absent "$state/managed"
assert_eq "$(head -n 1 "$state/boot_apply")" "$(boot_id)" "failed marker boot"
assert_eq "$(sed -n '2p' "$state/boot_apply")" failed "failed marker state"
grep -Eq '^(apply-failed|apply-restore-failed)$' "$state/boot_apply"
assert_eq "$(cat "$state/desired")" ipv4-lock "intent after failed adoption"
rmdir "$conf/wlan0/disable_ipv6"
node wlan0 0
if output=$(run adopt 2>&1); then echo 'FAIL: interrupted attempt must not retry'; exit 1; fi
assert_line "$output" error=boot-apply-already-attempted
expect wlan0 0
run restore >/dev/null
assert_absent "$state/snapshot"
assert_absent "$state/desired"

# An interrupted marker blocks automatic retry for the rest of the boot.
rm -f "$state/boot_apply"
printf '%s\ninterrupted\napplying\n' "$(boot_id)" > "$state/boot_apply"
printf 'ipv4-lock\n' > "$state/desired"
if output=$(run adopt 2>&1); then echo 'FAIL: interrupted marker retried'; exit 1; fi
assert_line "$output" error=boot-apply-already-attempted
assert_absent "$state/snapshot"
assert_absent "$state/managed"
expect wlan0 0
output=$(run status)
assert_line "$output" boot_apply=interrupted
run pause >/dev/null

# Snapshot failure is fixed, keeps intent and writes no parameters.
echo boot-ten > "$boot"
rm -f "$state/boot_apply" "$state/snapshot" "$state/desired"
node all 0; node default 0; node lo 0; node wlan0 0
rm "$conf/all/disable_ipv6"
printf 'ipv4-lock\n' > "$state/desired"
if output=$(run adopt 2>&1); then echo 'FAIL: snapshot failure reported success'; exit 1; fi
assert_line "$output" error=snapshot-failed
assert_eq "$(sed -n '2p' "$state/boot_apply")" failed "snapshot failure marker"
assert_eq "$(sed -n '3p' "$state/boot_apply")" snapshot-failed "snapshot failure token"
assert_present "$state/desired"
assert_absent "$state/managed"
assert_absent "$state/snapshot"
expect default 0; expect wlan0 0
node all 0
rm -f "$state/boot_apply"
run pause >/dev/null

# A corrupt intent is reported but never applied.
rm -f "$state/desired" "$state/snapshot" "$state/managed" "$state/boot_apply"
printf 'ipv4-lock extra\n' > "$state/desired"
output=$(run adopt)
assert_line "$output" desired=unknown
assert_line "$output" boot_apply=none
assert_absent "$state/snapshot"
assert_absent "$state/managed"
expect all 0; expect wlan0 0
printf 'ipv4-lock\n\n' > "$state/desired"
output=$(run adopt)
assert_line "$output" desired=unknown
assert_absent "$state/snapshot"
rm -f "$state/desired"

# Failure to clear the intent aborts before stopping takeover.
echo boot-eleven > "$boot"
rm -f "$state/boot_apply" "$state/snapshot" "$state/managed" "$state/desired"
node all 0; node default 0; node lo 0; node wlan0 0
run block >/dev/null
mkdir "$state/desired"
if output=$(run pause 2>&1); then echo 'FAIL: desired-clear failure reported success'; exit 1; fi
assert_line "$output" error=desired-clear-failed
assert_present "$state/managed"
rmdir "$state/desired"
run pause >/dev/null
assert_absent "$state/managed"

# A disabled or removed module cannot adopt, even with a stored intent.
echo boot-twelve > "$boot"
rm -f "$state/boot_apply" "$state/snapshot" "$state/managed" "$state/desired"
node all 0; node default 0; node lo 0; node wlan0 0
mkdir "$module"
printf 'ipv4-lock\n' > "$state/desired"
touch "$module/disable"
if output=$(run adopt "$module" 2>&1); then echo 'FAIL: disabled module adopted'; exit 1; fi
assert_line "$output" error=runtime-module-disabled
rm "$module/disable"
touch "$module/remove"
if output=$(run adopt "$module" 2>&1); then echo 'FAIL: removed module adopted'; exit 1; fi
assert_line "$output" error=runtime-module-disabled
assert_absent "$state/snapshot"
assert_absent "$state/managed"
expect all 0; expect wlan0 0
rm "$module/remove"
rmdir "$module"
run pause >/dev/null

# Boottime timestamp and age degrade explicitly when unavailable.
printf '%s\nok\n100\n' "$(boot_id)" > "$state/daemon.status"
printf '107.8 1.0\n' > "$uptime"
output=$(run status "" "$uptime")
assert_line "$output" last_sweep=ok
assert_line "$output" last_sweep_boottime=100
assert_line "$output" last_sweep_age=7
printf '%s\nidle\n' "$(boot_id)" > "$state/daemon.status"
output=$(run status)
assert_line "$output" last_sweep=idle
assert_line "$output" last_sweep_boottime=unknown
assert_line "$output" last_sweep_age=unknown
rm "$state/daemon.status"

# An old boot snapshot is deleted, never restored.
echo boot-thirteen > "$boot"
node all 0; node default 0; node lo 0; node wlan0 1
run block >/dev/null
echo boot-fourteen > "$boot"
node all 0; node default 0; node lo 0; node wlan0 0
if output=$(run restore 2>&1); then echo 'FAIL: old snapshot restored'; exit 1; fi
assert_line "$output" error=restore-failed-snapshot-kept
assert_absent "$state/snapshot"
expect wlan0 0; expect all 0


# A verified explicit lock retires only this boot's old automatic-apply failure.
echo boot-fifteen > "$boot"
rm -f "$state/boot_apply" "$state/snapshot" "$state/managed" "$state/desired"
node all 0; node default 0; node lo 0; node wlan0 0
printf '%s\nfailed\napply-failed\n' "$(boot_id)" > "$state/boot_apply"
output=$(run lock)
assert_line "$output" boot_apply=manual
assert_eq "$(sed -n '1p' "$state/boot_apply")" "$(boot_id)" "manual marker boot"
assert_eq "$(sed -n '2p' "$state/boot_apply")" manual "manual marker state"
assert_eq "$(sed -n '3p' "$state/boot_apply")" manual-lock "manual marker reason"
assert_present "$state/snapshot"
cp "$state/snapshot" "$scratch/manual-snapshot"
# Repeating lock and a same-boot adopt stay idempotent and keep the manual record.
output=$(run lock)
assert_line "$output" boot_apply=manual
cmp "$state/snapshot" "$scratch/manual-snapshot"
output=$(run adopt)
assert_line "$output" boot_apply=manual
assert_line "$output" managed=true
cmp "$state/snapshot" "$scratch/manual-snapshot"
expect wlan0 1; expect all 1
run pause >/dev/null

# The interrupted marker is resolved the same way by a verified manual lock.
printf '%s\ninterrupted\napplying\n' "$(boot_id)" > "$state/boot_apply"
output=$(run lock)
assert_line "$output" boot_apply=manual
assert_eq "$(sed -n '2p' "$state/boot_apply")" manual "interrupted marker resolved"
cmp "$state/snapshot" "$scratch/manual-snapshot"
run pause >/dev/null

# A failed lock apply keeps the old failure marker and the original snapshot.
printf '%s\nfailed\napply-failed\n' "$(boot_id)" > "$state/boot_apply"
cp "$state/snapshot" "$scratch/failed-snapshot"
rm "$conf/wlan0/disable_ipv6"; mkdir "$conf/wlan0/disable_ipv6"
if output=$(run lock 2>&1); then echo 'FAIL: failed lock apply reported success'; exit 1; fi
case "$output" in
    *error=lock-failed-rolled-back*|*error=lock-failed-restore-required*) ;;
    *) echo "FAIL: unexpected lock apply error: $output"; exit 1;;
esac
assert_eq "$(sed -n '2p' "$state/boot_apply")" failed "failed apply marker kept"
assert_eq "$(sed -n '3p' "$state/boot_apply")" apply-failed "failed apply reason kept"
assert_present "$state/snapshot"
cmp "$state/snapshot" "$scratch/failed-snapshot"
assert_absent "$state/desired"
assert_absent "$state/managed"
rmdir "$conf/wlan0/disable_ipv6"; node wlan0 0

# A persistent-intent write failure cannot retire the old failure marker.
mkdir "$state/desired.pending"
if output=$(run lock 2>&1); then echo 'FAIL: failed intent write reported success'; exit 1; fi
assert_line "$output" error=lock-persist-failed-current-only
assert_eq "$(sed -n '2p' "$state/boot_apply")" failed "intent failure kept old marker"
assert_eq "$(sed -n '3p' "$state/boot_apply")" apply-failed "intent failure kept old reason"
assert_present "$state/snapshot"
cmp "$state/snapshot" "$scratch/failed-snapshot"
assert_present "$state/managed"
assert_absent "$state/desired"
rmdir "$state/desired.pending"

# Failing to rewrite the resolved marker reports an error and keeps the lock and snapshot.
mkdir "$state/boot_apply.pending"
if output=$(run lock 2>&1); then echo 'FAIL: failed marker rewrite reported success'; exit 1; fi
assert_line "$output" error=lock-resolve-failed
assert_eq "$(sed -n '2p' "$state/boot_apply")" failed "unresolved marker kept"
assert_eq "$(sed -n '3p' "$state/boot_apply")" apply-failed "unresolved reason kept"
assert_present "$state/desired"
assert_present "$state/managed"
assert_present "$state/snapshot"
cmp "$state/snapshot" "$scratch/failed-snapshot"
rmdir "$state/boot_apply.pending"
# Retrying after the marker write is fixed resolves it without changing the snapshot.
output=$(run lock)
assert_line "$output" boot_apply=manual
cmp "$state/snapshot" "$scratch/failed-snapshot"
run pause >/dev/null

echo 'System IPv6 persistent-intent/snapshot checks passed (temporary files only)'
