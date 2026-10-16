#!/system/bin/sh
# Runtime sysctl control; no daemon, boot script, firewall or app scope changes.
umask 077
if [ "${1:-}" = "--test" ]; then
    # Isolated checks must explicitly supply all paths; never fall back to procfs.
    [ "$#" = 5 ] || exit 2
    conf=$2 state=$3 boot=$4 action=$5
    case "$conf:$state:$boot" in *'/proc/'*|*'/sys/'*) exit 2;; esac
else
    [ "$(id -u)" = 0 ] || { echo error=root-required; exit 1; }
    conf=/proc/sys/net/ipv6/conf
    state=/data/adb/ipv6ctrl-system
    boot=/proc/sys/kernel/random/boot_id
    action=${1:-status}
fi
fail() { echo "error=$1"; exit 1; }
case "$action" in status|block|restore) ;; *) fail invalid-action;; esac
[ -d "$conf" ] && [ -r "$boot" ] || fail ipv6-unavailable
mkdir -p "$state" || fail state-unavailable
snapshot=$state/snapshot
boot_id=$(cat "$boot")
[ -n "$boot_id" ] || fail boot-unavailable
case "$boot_id" in *[!a-zA-Z0-9-]*) fail invalid-boot-id;; esac
lock=$state/lock-$boot_id
mkdir "$lock" 2>/dev/null || fail operation-locked
trap 'rmdir "$lock"' EXIT
trap 'exit 1' HUP INT TERM
if [ -f "$snapshot" ] && [ "$(head -n 1 "$snapshot")" != "$boot_id" ]; then
    # Reboot resets runtime settings; an old boot's values must never be restored.
    rm "$snapshot" || fail stale-snapshot
fi

read_value() {
    value=$(cat "$1" 2>/dev/null) || return 1
    case "$value" in 0|1) ;; *) return 1;; esac
}
capture() {
    read_value "$conf/all/disable_ipv6" && read_value "$conf/default/disable_ipv6" || return 1
    echo "$boot_id" > "$state/pending" || return 1
    count=0
    for path in "$conf"/*/disable_ipv6; do
        read_value "$path" || return 1
        name=${path%/disable_ipv6}; name=${name##*/}
        case "$name" in ''|*[!a-zA-Z0-9_.:-]*) return 1;; esac
        echo "$name $value" >> "$state/pending" || return 1
        count=$((count + 1))
    done
    [ "$count" -ge 2 ] && mv "$state/pending" "$snapshot"
}
restore() {
    [ -f "$snapshot" ] || return 1
    failed=0
    # all propagates to every interface: restore it before individual values.
    for name in all default; do
        value=$(sed -n "s/^$name //p" "$snapshot")
        case "$value" in 0|1) ;; *) return 1;; esac
        printf '%s\n' "$value" > "$conf/$name/disable_ipv6" || failed=1
    done
    # Interfaces created while blocked have no snapshot: restore the old default.
    default_value=$(sed -n 's/^default //p' "$snapshot")
    for path in "$conf"/*/disable_ipv6; do
        name=${path%/disable_ipv6}; name=${name##*/}
        if ! sed '1d' "$snapshot" | cut -d ' ' -f 1 | grep -Fx -- "$name" >/dev/null; then
            printf '%s\n' "$default_value" > "$path" || failed=1
        fi
    done
    while read -r name value; do
        case "$name" in all|default|"$boot_id") continue;; esac
        case "$name" in ''|*[!a-zA-Z0-9_.:-]*) failed=1; continue;; esac
        case "$value" in 0|1) ;; *) failed=1; continue;; esac
        # Removed interfaces are gone; new ones inherit the restored default.
        [ -e "$conf/$name/disable_ipv6" ] || continue
        printf '%s\n' "$value" > "$conf/$name/disable_ipv6" || failed=1
    done < "$snapshot"
    [ "$failed" = 0 ] || return 1
    for name in all default; do
        expected=$(sed -n "s/^$name //p" "$snapshot")
        read_value "$conf/$name/disable_ipv6" && [ "$value" = "$expected" ] || return 1
    done
    for path in "$conf"/*/disable_ipv6; do
        name=${path%/disable_ipv6}; name=${name##*/}
        if ! sed '1d' "$snapshot" | cut -d ' ' -f 1 | grep -Fx -- "$name" >/dev/null; then
            read_value "$path" && [ "$value" = "$default_value" ] || return 1
        fi
    done
    while read -r name expected; do
        case "$name" in all|default|"$boot_id") continue;; esac
        [ -e "$conf/$name/disable_ipv6" ] || continue
        read_value "$conf/$name/disable_ipv6" && [ "$value" = "$expected" ] || return 1
    done < "$snapshot"
    rm "$snapshot"
}
status() {
    total=0 disabled=0
    for path in "$conf"/*/disable_ipv6; do
        read_value "$path" || return 1
        total=$((total + 1)); disabled=$((disabled + value))
    done
    [ "$total" -ge 2 ] || return 1
    result=mixed
    [ "$disabled" = 0 ] && result=enabled
    [ "$disabled" = "$total" ] && result=blocked
    echo "state=$result"
    echo "disabled=$disabled"
    echo "total=$total"
    if [ -f "$snapshot" ]; then echo restorable=true; else echo restorable=false; fi
}
case "$action" in
    block)
        if [ ! -f "$snapshot" ]; then capture || fail snapshot-failed; fi
        failed=0
        for name in all default; do
            printf '1\n' > "$conf/$name/disable_ipv6" || failed=1
        done
        for path in "$conf"/*/disable_ipv6; do
            printf '1\n' > "$path" || failed=1
        done
        for path in "$conf"/*/disable_ipv6; do
            read_value "$path" && [ "$value" = 1 ] || failed=1
        done
        if [ "$failed" != 0 ]; then
            if restore; then fail block-failed-rolled-back; else fail block-failed-restore-required; fi
        fi;;
    restore) restore || fail restore-failed-snapshot-kept;;
esac
status || fail status-unavailable
