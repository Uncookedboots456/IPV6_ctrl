#!/system/bin/sh
set -eu
root=$1
case "$root" in /data/local/tmp/ipv6ctrl-runtime-*) ;; *) exit 2;; esac
bin=$root/module/ipv6_daemon
# Refuse production binaries or a test binary compiled for any other directory.
info=$("$bin" --test-info)
expected=$(printf 'conf=%s\nstate=%s\nboot=%s\nmodule=%s' "$root/conf" "$root/state" "$root/boot" "$root/module")
[ "$info" = "$expected" ] || { echo 'FAIL unsafe binary paths'; exit 2; }
[ ! -e "$root/conf" ] && [ ! -e "$root/state" ] || exit 2
mkdir "$root/conf" "$root/state"
echo test-boot > "$root/boot"
pid=
stop() { if [ -n "$pid" ]; then kill "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true; pid=; fi; }
trap stop EXIT
node() { mkdir -p "$root/conf/$1"; printf '%s\n' "$2" > "$root/conf/$1/disable_ipv6"; }
value() { cat "$root/conf/$1/disable_ipv6"; }
wait_value() {
    attempts=0
    while [ "$(value "$1")" != "$2" ]; do
        attempts=$((attempts + 1)); [ "$attempts" -lt 40 ] || { echo "FAIL timeout $1"; exit 1; }
        sleep .2
    done
}
control() { "$bin" --control "$1"; }
start() {
    "$bin" > "$root/worker.log" 2>&1 & pid=$!
    printf '%s\n' "$pid" > "$root/worker.pid"
    attempts=0
    until "$bin" --check; do
        attempts=$((attempts + 1)); [ "$attempts" -lt 30 ] || exit 1; sleep .1
    done
}
node all 0; node default 0; node lo 0; node wlan0 1
if control block > "$root/result"; then echo 'FAIL block without worker'; exit 1; fi
start
result=$(control status)
printf '%s\n' "$result" | grep -qx managed=false
[ "$(value all)" = 0 ]
if "$bin"; then echo 'FAIL duplicate worker'; exit 1; fi
echo 'PASS idle/no-worker/singleton'
control block > "$root/result"
cp "$root/state/snapshot" "$root/snapshot.saved"
control block > "$root/result"
cmp "$root/state/snapshot" "$root/snapshot.saved"
node wlan0 0; node rmnet0 0
wait_value wlan0 1; wait_value rmnet0 1
echo 'PASS repeated block/single-interface drift/new interface'
control enable > "$root/result"
grep -qx state=enabled "$root/result"
[ ! -e "$root/state/managed" ]
cmp "$root/state/snapshot" "$root/snapshot.saved"
sleep 3.5; [ "$(value wlan0)" = 0 ]
control toggle > "$root/result"
grep -qx state=blocked "$root/result"
node wlan0 0
control toggle > "$root/result"
grep -qx state=enabled "$root/result"
[ ! -e "$root/state/managed" ]
cmp "$root/state/snapshot" "$root/snapshot.saved"
control toggle > "$root/result"
echo 'PASS explicit enable/atomic toggle/partial drift/snapshot retained'
control pause > "$root/result"
node wlan0 0; sleep 3.5
[ "$(value wlan0)" = 0 ]
control restore > "$root/result"
[ "$(value all)" = 0 ] && [ "$(value wlan0)" = 1 ] && [ "$(value rmnet0)" = 0 ]
[ ! -e "$root/state/snapshot" ]
echo 'PASS pause/restore without keeper race'
control block > "$root/result"
rm "$root/conf/wlan0/disable_ipv6"; mkdir "$root/conf/wlan0/disable_ipv6"
if control enable > "$root/result" 2>&1; then echo 'FAIL invalid enable'; exit 1; fi
[ -e "$root/state/snapshot" ] && [ ! -e "$root/state/managed" ]
if control restore > "$root/result" 2>&1; then echo 'FAIL invalid restore'; exit 1; fi
[ -e "$root/state/snapshot" ] && [ ! -e "$root/state/managed" ]
rmdir "$root/conf/wlan0/disable_ipv6"; node wlan0 1
control restore > "$root/result"
echo 'PASS failed restore keeps snapshot/stops takeover'
control block > "$root/result"
kill -9 "$pid"; wait "$pid" 2>/dev/null || true; pid=
if "$bin" --check; then echo 'FAIL dead worker reported running'; exit 1; fi
node wlan0 0
start; wait_value wlan0 1
control restore > "$root/result"
echo 'PASS worker kill/restart and kernel lock release'
control block > "$root/result"
stop
echo next-boot > "$root/boot"; node wlan0 0
start; sleep 3.5
[ "$(value wlan0)" = 0 ]
result=$(control status)
printf '%s\n' "$result" | grep -qx managed=false
printf '%s\n' "$result" | grep -qx restorable=false
echo 'PASS changed boot never adopts old target'
control block > "$root/result"
touch "$root/module/disable"
attempts=0
while "$bin" --check; do
    attempts=$((attempts + 1)); [ "$attempts" -lt 40 ] || exit 1; sleep .2
done
wait "$pid"; pid=
control restore > "$root/result"
[ "$(value wlan0)" = 0 ]
echo 'PASS module disable/restore'
