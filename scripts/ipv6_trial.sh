#!/system/bin/sh

# Phase 1: one interface, one write, explicit restore. Never starts a daemon.
PROC_CONF=/proc/sys/net/ipv6/conf
NET_CLASS=/sys/class/net
BOOT_ID_FILE=/proc/sys/kernel/random/boot_id
STATE_DIR=/data/adb/ipv6_ctrl_trial
SNAPSHOT="$STATE_DIR/snapshot"
umask 077

fail() {
    printf '[error] %s\n' "$*" >&2
    exit 1
}

valid_iface() {
    case "$1" in
        ''|.|..|all|default|lo|*[!a-zA-Z0-9_.:-]*) return 1 ;;
    esac
}

read_value() (
    read_result=$(cat "$1" 2>/dev/null) || exit 1
    case "$read_result" in 0|1) printf '%s\n' "$read_result" ;; *) exit 1 ;; esac
)

check_daemon() {
    command -v pidof >/dev/null 2>&1 || fail 'pidof is required to check for the old daemon.'
    if pidof ipv6_daemon >/dev/null 2>&1; then
        fail 'ipv6_daemon is running. Disable the old module and reboot before this trial.'
    fi
}

lock_state() {
    [ ! -L "$STATE_DIR" ] || fail 'State directory must not be a symbolic link.'
    mkdir -p "$STATE_DIR" && chmod 700 "$STATE_DIR" || fail 'Cannot create private state directory.'
    mkdir "$STATE_DIR/lock" 2>/dev/null || fail 'Another trial is running, or a stale lock needs inspection.'
    trap 'rmdir "$STATE_DIR/lock" 2>/dev/null' 0
    trap 'exit 130' INT
    trap 'exit 143' TERM
}

status() {
    [ -d "$PROC_CONF" ] || fail 'IPv6 procfs is unavailable.'
    printf 'Per-interface values: 0=enabled, 1=disabled; all/default are reference values.\n'
    status_failed=0
    for node in "$PROC_CONF"/*/disable_ipv6; do
        [ -e "$node" ] || continue
        iface=${node%/disable_ipv6}
        iface=${iface##*/}
        if value=$(read_value "$node"); then
            printf '%-16s disable_ipv6=%s\n' "$iface" "$value"
        else
            printf '%-16s unreadable\n' "$iface"
            status_failed=1
        fi
    done
    if command -v pidof >/dev/null 2>&1 && pidof ipv6_daemon >/dev/null 2>&1; then
        printf '[warning] Old daemon is running; this is not an isolated trial.\n'
    fi
    if [ -f "$SNAPSHOT" ]; then
        printf 'Pending restore snapshot: %s\n' "$SNAPSHOT"
    fi
    return "$status_failed"
}

restore_state() {
    [ -f "$SNAPSHOT" ] || { printf 'No pending trial to restore.\n'; return 0; }
    IFS=' ' read -r saved_boot saved_iface saved_index saved_value extra < "$SNAPSHOT" || return 1
    valid_iface "$saved_iface" && [ -z "$extra" ] || return 1
    case "$saved_value" in 0|1) ;; *) return 1 ;; esac
    case "$saved_index" in ''|*[!0-9]*) return 1 ;; esac
    current_boot=$(cat "$BOOT_ID_FILE" 2>/dev/null) || return 1
    if [ "$saved_boot" != "$current_boot" ]; then
        printf '[error] Snapshot belongs to a previous boot; no values were written.\n' >&2
        return 1
    fi
    current_index=$(cat "$NET_CLASS/$saved_iface/ifindex" 2>/dev/null) || return 1
    if [ "$saved_index" != "$current_index" ]; then
        printf '[error] Interface was recreated; no values were written. Snapshot retained.\n' >&2
        return 1
    fi
    restore_node="$PROC_CONF/$saved_iface/disable_ipv6"
    current_value=$(read_value "$restore_node") || return 1
    if [ "$current_value" != "$saved_value" ]; then
        printf '%s\n' "$saved_value" > "$restore_node" || return 1
    fi
    [ "$(read_value "$restore_node")" = "$saved_value" ] || return 1
    rm "$SNAPSHOT" || return 1
    printf 'Restored %s: disable_ipv6=%s. Addresses and connections may need time to recover.\n' "$saved_iface" "$saved_value"
}

disable_once() {
    valid_iface "$1" || fail 'Specify one interface; all, default and lo are excluded.'
    [ ! -e "$SNAPSHOT" ] || fail 'A trial snapshot already exists. Restore it before another trial.'
    trial_iface=$1
    trial_node="$PROC_CONF/$trial_iface/disable_ipv6"
    original=$(read_value "$trial_node") || fail 'Cannot read the selected interface.'
    if [ "$original" = 1 ]; then
        printf '%s is already disabled; nothing changed and no snapshot created.\n' "$trial_iface"
        return 0
    fi
    boot_id=$(cat "$BOOT_ID_FILE" 2>/dev/null) || fail 'Cannot read boot ID.'
    [ -n "$boot_id" ] || fail 'Boot ID is empty.'
    index=$(cat "$NET_CLASS/$trial_iface/ifindex" 2>/dev/null) || fail 'Cannot read interface index.'
    case "$index" in ''|*[!0-9]*) fail 'Invalid interface index.' ;; esac
    printf '%s %s %s %s\n' "$boot_id" "$trial_iface" "$index" "$original" > "$SNAPSHOT.tmp" &&
        mv "$SNAPSHOT.tmp" "$SNAPSHOT" || fail 'Cannot save original state; no change applied.'
    if printf '1\n' > "$trial_node" && [ "$(read_value "$trial_node")" = 1 ]; then
        printf 'Applied once to %s: disable_ipv6=1. No background enforcement.\n' "$trial_iface"
        printf 'Use status to observe changes; use restore to restore the saved value.\n'
    else
        printf '[error] Apply/readback failed; attempting to restore original state.\n' >&2
        restore_state || printf '[error] Restore failed; snapshot retained at %s.\n' "$SNAPSHOT" >&2
        return 1
    fi
}

case "$1:$#" in
    status:1|restore:1|disable:2) ;;
    *) printf 'Usage: sh %s {status|disable INTERFACE|restore}\n' "$0" >&2; exit 2 ;;
esac
[ "$(id -u)" = 0 ] || fail 'Run from a root shell.'
case "$1" in
    status) status ;;
    disable|restore)
        check_daemon
        lock_state
        if [ "$1" = disable ]; then
            disable_once "$2"
        else
            restore_state || fail "Restore failed; inspect $SNAPSHOT before proceeding."
        fi
        ;;
esac
