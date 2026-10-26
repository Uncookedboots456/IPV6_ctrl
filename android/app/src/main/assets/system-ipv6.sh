#!/system/bin/sh
# Root controller shared by the APK and module; native helper serializes keeper and UI writes.
umask 077
fail() { echo "error=$1"; exit 1; }
native_lock=false
module=
uptime=/proc/uptime
if [ "${1:-}" = "--test" ]; then
    # Isolated checks must explicitly supply all paths; never fall back to procfs.
    case "$#" in 5|6|7) ;; *) exit 2;; esac
    conf=$2 state=$3 boot=$4 action=$5
    module=${6:-}
    uptime=${7:-}
    case "$conf:$state:$boot:$module:$uptime" in *'/proc/'*|*'/sys/'*) exit 2;; esac
elif [ "${1:-}" = "--locked" ]; then
    [ "$#" = 6 ] || exit 2
    conf=$2 state=$3 boot=$4 module=$5 action=$6
    native_lock=true
else
    [ "$(id -u)" = 0 ] || { echo error=root-required; exit 1; }
    conf=/proc/sys/net/ipv6/conf
    state=/data/adb/ipv6ctrl-system
    boot=/proc/sys/kernel/random/boot_id
    action=${1:-status}
    module=/data/adb/modules/ipv6_ctrl
    # Accept the previous Runtime for recovery; toggle needs the new protocol.
    protocol=$(cat "$module/protocol" 2>/dev/null)
    case "$protocol" in ipv6ctrl-managed-v1|ipv6ctrl-managed-v2)
        case "$action:$protocol" in enable:ipv6ctrl-managed-v1|toggle:ipv6ctrl-managed-v1|lock:ipv6ctrl-managed-v1) fail runtime-update-required;; esac
        [ -x "$module/ipv6_daemon" ] && exec "$module/ipv6_daemon" --control "$action"
        ;;
    esac
    case "$action" in block|pause|enable|toggle|lock|adopt) fail runtime-module-required;; esac
    module=missing
fi
case "$action" in status|block|restore|pause|enable|toggle|lock|adopt) ;; *) fail invalid-action;; esac
[ -d "$conf" ] && [ -r "$boot" ] || fail ipv6-unavailable
mkdir -p "$state" || fail state-unavailable
chmod 700 "$state" 2>/dev/null || fail state-unavailable
snapshot=$state/snapshot
boot_id=$(cat "$boot")
[ -n "$boot_id" ] || fail boot-unavailable
case "$boot_id" in *[!a-zA-Z0-9-]*) fail invalid-boot-id;; esac
lock=$state/lock-$boot_id
if [ "$native_lock" != true ]; then
    mkdir "$lock" 2>/dev/null || fail operation-locked
    trap 'rmdir "$lock"' EXIT
    # A removed live module cannot be safely coordinated without its native lock helper.
    if [ "$module" = missing ] && [ "$action" = restore ] && [ "$(cat "$state/managed" 2>/dev/null)" = "$boot_id" ]; then
        fail runtime-module-required-for-restore
    fi
fi
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
    # The first captured baseline of a boot is never replaced.
    [ ! -e "$snapshot" ] || return 1
    read_value "$conf/all/disable_ipv6" && read_value "$conf/default/disable_ipv6" || return 1
    printf '%s\n' "$boot_id" > "$state/snapshot.pending" || return 1
    count=0
    for path in "$conf"/*/disable_ipv6; do
        read_value "$path" || { rm -f "$state/snapshot.pending"; return 1; }
        name=${path%/disable_ipv6}; name=${name##*/}
        case "$name" in ''|*[!a-zA-Z0-9_.:-]*) rm -f "$state/snapshot.pending"; return 1;; esac
        printf '%s %s\n' "$name" "$value" >> "$state/snapshot.pending" || { rm -f "$state/snapshot.pending"; return 1; }
        count=$((count + 1))
    done
    if [ "$count" -lt 2 ] || ! mv "$state/snapshot.pending" "$snapshot"; then
        rm -f "$state/snapshot.pending"
        return 1
    fi
}
restore_values() {
    [ -f "$snapshot" ] && [ "$(head -n 1 "$snapshot")" = "$boot_id" ] || return 1
    failed=0
    # all propagates to every interface: restore it before individual values.
    for name in all default; do
        value=$(sed -n "s/^$name //p" "$snapshot")
        case "$value" in 0|1) ;; *) return 1;; esac
        printf '%s\n' "$value" > "$conf/$name/disable_ipv6" || failed=1
    done
    # Interfaces created while blocked have no snapshot: restore the old default.
    default_value=$(sed -n 's/^default //p' "$snapshot")
    case "$default_value" in 0|1) ;; *) return 1;; esac
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
    return 0
}
restore() {
    restore_values || return 1
    rm "$snapshot"
}
read_counts() {
    total=0 disabled=0
    for path in "$conf"/*/disable_ipv6; do
        read_value "$path" || return 1
        total=$((total + 1)); disabled=$((disabled + value))
    done
    [ "$total" -ge 2 ] || return 1
    result=mixed
    [ "$disabled" = 0 ] && result=enabled
    [ "$disabled" = "$total" ] && result=blocked
    return 0
}
write_atomic() {
    target=$1; shift
    [ ! -d "$target" ] || return 1
    printf '%s\n' "$@" > "$target.pending" || return 1
    mv -f "$target.pending" "$target" || { rm -f "$target.pending"; return 1; }
}
write_boot_apply() {
    write_atomic "$state/boot_apply" "$boot_id" "$1" "$2"
}
managed_current() {
    [ "$(cat "$state/managed" 2>/dev/null)" = "$boot_id" ]
}
desired_locked() {
    [ -f "$state/desired" ] || return 1
    [ "$(head -n 1 "$state/desired" 2>/dev/null)" = ipv4-lock ] || return 1
    [ -z "$(sed -n '2p' "$state/desired" 2>/dev/null)" ] || return 1
    lines=$(wc -l < "$state/desired" 2>/dev/null) || return 1
    case "$lines" in ''|*[!0-9]*) return 1;; esac
    [ "$lines" -le 1 ]
}
desired_value() {
    if [ ! -e "$state/desired" ]; then desired_field=none
    elif desired_locked; then desired_field=locked
    else desired_field=unknown
    fi
}
boot_apply_field_value() {
    boot_apply_field=none
    [ -e "$state/boot_apply" ] || return 0
    recorded=$(head -n 1 "$state/boot_apply" 2>/dev/null) || { boot_apply_field=unknown; return 0; }
    [ "$recorded" = "$boot_id" ] || return 0
    recorded=$(sed -n '2p' "$state/boot_apply" 2>/dev/null)
    case "$recorded" in applied|failed|interrupted|manual) boot_apply_field=$recorded;; *) boot_apply_field=unknown;; esac
}
boot_apply_attempted() {
    [ -f "$state/boot_apply" ] && [ "$(head -n 1 "$state/boot_apply" 2>/dev/null)" = "$boot_id" ]
}
resolve_boot_apply() {
    # Only a verified explicit lock may retire this boot's automatic-apply failure.
    [ -f "$state/boot_apply" ] || return 0
    [ "$(head -n 1 "$state/boot_apply" 2>/dev/null)" = "$boot_id" ] || return 0
    recorded=$(sed -n '2p' "$state/boot_apply" 2>/dev/null)
    case "$recorded" in failed|interrupted) write_boot_apply manual manual-lock;; *) return 0;; esac
}
clear_desired() {
    rm -f "$state/desired"
}
status() {
    read_counts || return 1
    desired_value
    boot_apply_field_value
    echo "state=$result"
    echo "disabled=$disabled"
    echo "total=$total"
    if [ -f "$snapshot" ]; then echo restorable=true; else echo restorable=false; fi
    if [ -z "$module" ]; then echo daemon=fixture
    elif [ "$module" = missing ]; then echo daemon=missing
    elif "$module/ipv6_daemon" --check; then echo daemon=running
    else echo daemon=stopped; fi
    if managed_current; then echo managed=true; else echo managed=false; fi
    last_sweep=unknown last_sweep_boottime=unknown last_sweep_age=unknown
    if [ -f "$state/daemon.status" ] && [ "$(head -n 1 "$state/daemon.status" 2>/dev/null)" = "$boot_id" ]; then
        last_sweep=$(sed -n '2p' "$state/daemon.status" 2>/dev/null)
        [ -n "$last_sweep" ] || last_sweep=unknown
        stamp=$(sed -n '3p' "$state/daemon.status" 2>/dev/null)
        case "$stamp" in
            ''|*[!0-9]*) ;;
            *)
                last_sweep_boottime=$stamp
                if [ -n "$uptime" ] && [ -r "$uptime" ]; then
                    now=$( { read -r now rest; printf '%s' "$now"; } < "$uptime" 2>/dev/null )
                    now=${now%%.*}
                    case "$now" in
                        ''|*[!0-9]*) ;;
                        *) if [ "$now" -ge "$stamp" ]; then last_sweep_age=$((now - stamp)); fi;;
                    esac
                fi
                ;;
        esac
    fi
    printf 'last_sweep=%s\n' "$last_sweep"
    printf 'last_sweep_boottime=%s\n' "$last_sweep_boottime"
    printf 'last_sweep_age=%s\n' "$last_sweep_age"
    printf 'desired=%s\n' "$desired_field"
    printf 'boot_apply=%s\n' "$boot_apply_field"
}
apply() {
    target=$1 failed=0
    # all propagates: write it first, then default and current interfaces; skip unchanged values.
    for name in all default; do
        read_value "$conf/$name/disable_ipv6" && [ "$value" = "$target" ] ||
            printf '%s\n' "$target" > "$conf/$name/disable_ipv6" || failed=1
    done
    for path in "$conf"/*/disable_ipv6; do
        read_value "$path" && [ "$value" = "$target" ] || printf '%s\n' "$target" > "$path" || failed=1
    done
    for path in "$conf"/*/disable_ipv6; do
        read_value "$path" && [ "$value" = "$target" ] || failed=1
    done
    [ "$failed" = 0 ]
}
if [ "$action" = toggle ]; then
    # Decide from real parameters while holding the SAME kernel lock as the write.
    read_counts || fail status-unavailable
    if [ "$result" != blocked ] && ! managed_current; then action=block
    else action=enable; fi
fi
case "$action" in
    block)
        if [ -n "$module" ]; then
            [ ! -e "$module/disable" ] && [ ! -e "$module/remove" ] || fail runtime-module-disabled
            "$module/ipv6_daemon" --check || fail runtime-daemon-not-running
        fi
        rm -f "$state/managed" || fail pause-failed
        if [ ! -f "$snapshot" ]; then capture || fail snapshot-failed; fi
        if ! apply 1; then
            if restore_values; then fail block-failed-rolled-back; else fail block-failed-restore-required; fi
        fi
        write_atomic "$state/managed" "$boot_id" || fail target-save-failed
        ;;
    lock)
        if [ -n "$module" ]; then
            [ ! -e "$module/disable" ] && [ ! -e "$module/remove" ] || fail runtime-module-disabled
            "$module/ipv6_daemon" --check || fail runtime-daemon-not-running
        fi
        rm -f "$state/managed" || fail pause-failed
        if [ ! -f "$snapshot" ]; then capture || fail snapshot-failed; fi
        if ! apply 1; then
            if restore_values; then fail lock-failed-rolled-back; else fail lock-failed-restore-required; fi
        fi
        if ! write_atomic "$state/managed" "$boot_id"; then
            if restore_values; then fail target-save-failed; else fail lock-failed-restore-required; fi
        fi
        if ! write_atomic "$state/desired" ipv4-lock; then
            # The current-boot takeover is real; only the persistent intent failed.
            status || true
            echo error=lock-persist-failed-current-only
            exit 1
        fi
        if ! resolve_boot_apply; then
            # Locking is real and persisted; only the old boot history could not be retired.
            status || true
            echo error=lock-resolve-failed
            exit 1
        fi
        ;;
    adopt)
        if [ -n "$module" ]; then
            [ ! -e "$module/disable" ] && [ ! -e "$module/remove" ] || fail runtime-module-disabled
        fi
        desired_value
        if [ "$desired_field" != locked ]; then
            # No persistent intent (or a corrupt one): read state, change nothing.
            status || true
            exit 0
        fi
        if managed_current && [ -f "$snapshot" ] && [ "$(head -n 1 "$snapshot")" = "$boot_id" ]; then
            # A completed same-boot adoption may be observed again; keep its snapshot.
            recorded=$(sed -n '2p' "$state/boot_apply" 2>/dev/null)
            case "$recorded" in
                failed|interrupted|manual)
                    # Never replace a failed or manually resolved boot record with a fake automatic success.
                    ;;
                *) write_boot_apply applied none || { status || true; fail boot-apply-save-failed; };;
            esac
            status || fail status-unavailable
            exit 0
        fi
        if managed_current; then
            write_boot_apply failed snapshot-missing || true
            status || true
            fail boot-apply-failed
        fi
        if boot_apply_attempted; then
            # One automatic application per boot, including interrupted attempts.
            status || true
            fail boot-apply-already-attempted
        fi
        if [ ! -f "$snapshot" ]; then
            if ! capture; then
                write_boot_apply failed snapshot-failed || true
                fail snapshot-failed
            fi
        fi
        if [ -n "$module" ]; then
            [ ! -e "$module/disable" ] && [ ! -e "$module/remove" ] || fail runtime-module-disabled
        fi
        # Record the attempt before touching parameters so a kill cannot cause a retry.
        write_boot_apply interrupted applying || fail boot-apply-save-failed
        if ! apply 1; then
            if restore_values; then
                write_boot_apply failed apply-failed || true
            else
                write_boot_apply failed apply-restore-failed || true
            fi
            fail boot-apply-failed
        fi
        if ! write_atomic "$state/managed" "$boot_id"; then
            restore_values || true
            write_boot_apply failed managed-save-failed || true
            fail boot-apply-failed
        fi
        write_boot_apply applied none || { status || true; fail boot-apply-save-failed; }
        ;;
    enable)
        clear_desired || fail desired-clear-failed
        rm -f "$state/managed" || fail pause-failed
        if [ ! -f "$snapshot" ]; then capture || fail snapshot-failed; fi
        apply 0 || fail enable-failed-snapshot-kept
        ;;
    pause)
        clear_desired || fail desired-clear-failed
        rm -f "$state/managed" || fail pause-failed
        ;;
    restore)
        clear_desired || fail desired-clear-failed
        rm -f "$state/managed" || fail pause-failed
        restore || fail restore-failed-snapshot-kept
        ;;
esac
status || fail status-unavailable
