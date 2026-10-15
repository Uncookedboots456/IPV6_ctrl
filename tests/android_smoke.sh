#!/system/bin/sh
set -eu

root=/data/local/tmp/ipv6-ctrl-smoke
[ "${0%/*}" = "$root" ] || exit 2
[ ! -e "$root/conf" ] || exit 2
for iface in all default lo wlan0 rmnet0; do
    mkdir -p "$root/conf/$iface"
    printf '0\n' > "$root/conf/$iface/disable_ipv6"
done
sock="$root/ipv6_daemon.sock"
daemon_pid=''
idle_pid=''
cleanup() {
    [ -z "$idle_pid" ] || kill "$idle_pid" 2>/dev/null || true
    if [ -n "$daemon_pid" ]; then
        kill "$daemon_pid" 2>/dev/null || true
        wait "$daemon_pid" 2>/dev/null || true
    fi
}
trap cleanup EXIT
"$root/ipv6_daemon" "$sock" &
daemon_pid=$!
tries=0
while [ ! -S "$sock" ]; do
    [ "$tries" -lt 20 ] || exit 1
    sleep 0.1
    tries=$((tries + 1))
done
[ "$(stat -c %a "$sock")" = 600 ]
if "$root/ipv6_daemon" "$sock"; then
    echo 'FAIL: second daemon acquired the socket' >&2
    exit 1
fi
status=$(sh "$root/scripts/ipv6_manager.sh" disable)
case "$status" in *'"ok":true'*'"target_enforced":true'*) ;; *) exit 1 ;; esac
written=$(stat -c %Y "$root/conf/all/disable_ipv6")
printf '0\n' > "$root/conf/rmnet0/disable_ipv6"
sleep 4
[ "$(cat "$root/conf/rmnet0/disable_ipv6")" = 1 ]
[ "$(stat -c %Y "$root/conf/all/disable_ipv6")" = "$written" ]
(sleep 3 | timeout 4 nc -U -w 3 "$sock" >/dev/null) &
idle_pid=$!
sleep 0.1
status=$(sh "$root/scripts/ipv6_manager.sh" json-status)
case "$status" in *'"ok":true'*) ;; *) exit 1 ;; esac
wait "$idle_pid" || true
idle_pid=''
kill "$daemon_pid"
wait "$daemon_pid"
daemon_pid=''
[ ! -e "$sock" ]
echo 'PASS: Android IPC, singleton, mode 600, bounded idle client, drift, no redundant writes, clean shutdown'
