#!/system/bin/sh

# Android IPv6 control helper.
# Usage: sh ipv6_manager.sh [disable|enable|status|json-status|online-check|json-online-check]

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
MODDIR="$(dirname "$SCRIPT_DIR")"
PROC_IPV6_CONF="/proc/sys/net/ipv6/conf"
APPLY_PASSES="${APPLY_PASSES:-3}"
DAEMON_BIN="$MODDIR/ipv6_daemon"
DAEMON_SOCK="$MODDIR/ipv6_daemon.sock"
DAEMON_NC=""

require_root() {
    if [ "$(id -u)" -ne 0 ]; then
        echo "[!] Root permission is required."
        exit 1
    fi
}

find_daemon_nc() {
    if [ -n "$DAEMON_NC" ]; then
        return 0
    fi

    if command -v nc >/dev/null 2>&1; then
        DAEMON_NC="nc"
        return 0
    fi

    if command -v toybox >/dev/null 2>&1 && toybox nc -h >/dev/null 2>&1; then
        DAEMON_NC="toybox"
        return 0
    fi

    if command -v busybox >/dev/null 2>&1 && busybox nc --help >/dev/null 2>&1; then
        DAEMON_NC="busybox"
        return 0
    fi

    return 1
}

run_daemon_nc() {
    case "$DAEMON_NC" in
        nc)
            nc -U "$DAEMON_SOCK"
            ;;
        toybox)
            toybox nc -U "$DAEMON_SOCK"
            ;;
        busybox)
            busybox nc -U "$DAEMON_SOCK"
            ;;
        *)
            return 1
            ;;
    esac
}

daemon_sleep() {
    sleep 0.2 2>/dev/null || sleep 1
}

start_daemon() {
    [ -x "$DAEMON_BIN" ] || return 1

    chmod 0755 "$DAEMON_BIN" 2>/dev/null
    "$DAEMON_BIN" >/dev/null 2>&1 &

    tries=0
    while [ "$tries" -lt 10 ]; do
        [ -S "$DAEMON_SOCK" ] && return 0
        daemon_sleep
        tries=$((tries + 1))
    done

    return 1
}

ensure_daemon() {
    require_root

    [ -x "$DAEMON_BIN" ] || return 1
    find_daemon_nc || return 1

    [ -S "$DAEMON_SOCK" ] || start_daemon || return 1
    return 0
}

daemon_request() {
    command="$1"

    ensure_daemon || return 1

    response="$(printf '%s\n' "$command" | run_daemon_nc 2>/dev/null)"
    if [ -n "$response" ]; then
        printf '%s\n' "$response"
        return 0
    fi

    rm -f "$DAEMON_SOCK" 2>/dev/null
    start_daemon || return 1
    response="$(printf '%s\n' "$command" | run_daemon_nc 2>/dev/null)"
    [ -n "$response" ] || return 1
    printf '%s\n' "$response"
    return 0
}

extract_json_field() {
    key="$1"
    printf '%s' "$2" | sed -n "s/.*\"$key\":\"\\([^\"]*\\)\".*/\\1/p" | head -n 1
}

extract_json_bool() {
    key="$1"
    printf '%s' "$2" | sed -n "s/.*\"$key\":\\(true\\|false\\).*/\\1/p" | head -n 1
}

write_value() {
    target="$1"
    value="$2"

    if [ ! -e "$target" ]; then
        return 2
    fi

    if echo "$value" > "$target" 2>/dev/null; then
        return $?
    fi

    return 1
}

sleep_between_passes() {
    sleep 0.25 2>/dev/null || sleep 1
}

fetch_url() {
    url="$1"

    if command -v curl >/dev/null 2>&1; then
        curl -fsSL --connect-timeout 5 --max-time 10 "$url" 2>/dev/null
        return $?
    fi

    if command -v wget >/dev/null 2>&1; then
        wget -qO- "$url" 2>/dev/null
        return $?
    fi

    return 127
}

extract_ip() {
    printf '%s' "$1" | sed -n 's/.*"ip"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -n 1
}

json_escape() {
    printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'
}

write_required_value() {
    label="$1"
    target="$2"
    value="$3"

    if write_value "$target" "$value"; then
        echo "    [ok] $label -> $value"
        return 0
    fi

    rc=$?
    if [ "$rc" -eq 2 ]; then
        echo "    [error] $label missing: $target"
    else
        echo "    [error] $label write failed: $target"
    fi
    return 1
}

apply_interface_values() {
    value="$1"
    changed=0
    failed=0
    missing=0

    for item in "$PROC_IPV6_CONF"/*/disable_ipv6; do
        [ -e "$item" ] || {
            missing=$((missing + 1))
            continue
        }

        if write_value "$item" "$value"; then
            changed=$((changed + 1))
        else
            rc=$?
            if [ "$rc" -eq 2 ]; then
                missing=$((missing + 1))
                echo "    [warn] vanished before write: $item"
            else
                failed=$((failed + 1))
                echo "    [warn] interface write failed: $item"
            fi
        fi
    done

    echo "    [info] interfaces changed=$changed failed=$failed vanished=$missing"
}

verify_ipv6_state() {
    value="$1"
    verify_failed=0
    matched=0
    mismatched=0
    unreadable=0

    all_path="$PROC_IPV6_CONF/all/disable_ipv6"
    default_path="$PROC_IPV6_CONF/default/disable_ipv6"
    all_value="$(cat "$all_path" 2>/dev/null)"
    default_value="$(cat "$default_path" 2>/dev/null)"

    echo "[*] Final verification"
    echo "    [info] all=$all_value expected=$value"
    echo "    [info] default=$default_value expected=$value"

    if [ "$all_value" != "$value" ]; then
        echo "    [error] all mismatch: $all_path"
        verify_failed=1
    fi

    if [ "$default_value" != "$value" ]; then
        echo "    [error] default mismatch: $default_path"
        verify_failed=1
    fi

    for item in "$PROC_IPV6_CONF"/*/disable_ipv6; do
        [ -e "$item" ] || continue
        current="$(cat "$item" 2>/dev/null)"
        if [ -z "$current" ]; then
            unreadable=$((unreadable + 1))
            echo "    [warn] unreadable: $item"
        elif [ "$current" = "$value" ]; then
            matched=$((matched + 1))
        else
            mismatched=$((mismatched + 1))
            echo "    [warn] interface mismatch: $item is $current"
        fi
    done

    echo "    [info] interface verify matched=$matched mismatched=$mismatched unreadable=$unreadable"
    return "$verify_failed"
}

set_ipv6_fallback() {
    value="$1"
    label="$2"

    require_root

    if [ ! -d "$PROC_IPV6_CONF" ]; then
        echo "[!] IPv6 procfs is unavailable: $PROC_IPV6_CONF"
        exit 2
    fi

    echo "[*] Target: IPv6 $label (disable_ipv6=$value)"
    pass=1
    hard_failed=0

    while [ "$pass" -le "$APPLY_PASSES" ]; do
        echo "[*] Apply pass $pass/$APPLY_PASSES"
        write_required_value "all" "$PROC_IPV6_CONF/all/disable_ipv6" "$value" || hard_failed=1
        write_required_value "default" "$PROC_IPV6_CONF/default/disable_ipv6" "$value" || hard_failed=1
        apply_interface_values "$value"

        if [ "$pass" -lt "$APPLY_PASSES" ]; then
            sleep_between_passes
        fi
        pass=$((pass + 1))
    done

    if verify_ipv6_state "$value"; then
        if [ "$hard_failed" -eq 0 ]; then
            echo "[+] Success: IPv6 $label verified."
        else
            echo "[+] Success with warnings: final state verified after earlier write warnings."
        fi
        return 0
    fi

    echo "[!] Hard failure: global/default IPv6 state did not verify."
    exit 2
}

status_text() {
    if json="$(daemon_request "GET_STATUS")"; then
        state="$(extract_json_field "state" "$json")"
        target="$(extract_json_field "target" "$json")"
        enforced="$(extract_json_bool "target_enforced" "$json")"
        [ -n "$state" ] || state="unknown"
        [ -n "$target" ] || target="$state"
        [ -n "$enforced" ] || enforced="false"
        echo "[i] IPv6 is $state."
        echo " - target: $target"
        echo " - target_enforced: $enforced"
        return 0
    fi

    if [ ! -r "$PROC_IPV6_CONF/all/disable_ipv6" ]; then
        echo "[!] IPv6 procfs status is unavailable."
        exit 2
    fi

    global_status="$(cat "$PROC_IPV6_CONF/all/disable_ipv6" 2>/dev/null)"
    if [ "$global_status" = "1" ]; then
        echo "[i] IPv6 is disabled."
    else
        echo "[i] IPv6 is enabled."
    fi

    for item in "$PROC_IPV6_CONF"/*/disable_ipv6; do
        [ -e "$item" ] || continue
        iface="${item%/disable_ipv6}"
        iface="${iface##*/}"
        value="$(cat "$item" 2>/dev/null)"
        if [ "$value" = "1" ]; then
            echo " - $iface: disabled"
        else
            echo " - $iface: enabled"
        fi
    done
}

json_status() {
    if json="$(daemon_request "GET_STATUS")"; then
        printf '%s\n' "$json"
        return 0
    fi

    if [ ! -r "$PROC_IPV6_CONF/all/disable_ipv6" ]; then
        printf '{"ok":false,"error":"IPv6 procfs status is unavailable"}\n'
        exit 2
    fi

    global_status="$(cat "$PROC_IPV6_CONF/all/disable_ipv6" 2>/dev/null)"
    if [ "$global_status" = "1" ]; then
        state="disabled"
    else
        state="enabled"
    fi

    printf '{"ok":true,"state":"%s","interfaces":[' "$state"
    first=1
    for item in "$PROC_IPV6_CONF"/*/disable_ipv6; do
        [ -e "$item" ] || continue
        iface="${item%/disable_ipv6}"
        iface="${iface##*/}"
        value="$(cat "$item" 2>/dev/null)"
        if [ "$value" = "1" ]; then
            iface_state="disabled"
        else
            iface_state="enabled"
        fi

        if [ "$first" -eq 0 ]; then
            printf ','
        fi
        first=0
        printf '{"name":"%s","state":"%s"}' "$iface" "$iface_state"
    done
    printf ']}\n'
}

daemon_action() {
    action="$1"
    if json="$(daemon_request "$action")"; then
        printf '%s\n' "$json"
        return 0
    fi

    if [ "$action" = "ENABLE" ]; then
        set_ipv6_fallback 0 "enabled"
        json_status
        return 0
    fi

    set_ipv6_fallback 1 "disabled"
    json_status
}

online_check_text() {
    if ! command -v curl >/dev/null 2>&1 && ! command -v wget >/dev/null 2>&1; then
        echo "[!] No HTTP client found. Install curl or wget to run online IPv6 checks."
        exit 127
    fi

    response="$(fetch_url "https://api6.ipify.org?format=json")"
    ip="$(extract_ip "$response")"
    if [ -n "$ip" ]; then
        echo "[+] Online IPv6 check passed via ipify: $ip"
        return
    fi

    response="$(fetch_url "https://ipv6.seeip.org/jsonip")"
    ip="$(extract_ip "$response")"
    if [ -n "$ip" ]; then
        echo "[+] Online IPv6 check passed via SeeIP: $ip"
        return
    fi

    response="$(fetch_url "https://ipv6.icanhazip.com/")"
    ip="$(printf '%s' "$response" | tr -d '\r\n' | sed -n '/:/p' | head -n 1)"
    if [ -n "$ip" ]; then
        echo "[+] Online IPv6 check passed via icanhazip: $ip"
        return
    fi

    echo "[!] Online IPv6 check failed across ipify, SeeIP, and icanhazip."
    exit 2
}

json_online_check() {
    if ! command -v curl >/dev/null 2>&1 && ! command -v wget >/dev/null 2>&1; then
        printf '{"ok":false,"error":"No HTTP client found. Install curl or wget to run online IPv6 checks."}\n'
        exit 127
    fi

    response="$(fetch_url "https://api6.ipify.org?format=json")"
    ip="$(extract_ip "$response")"
    if [ -n "$ip" ]; then
        printf '{"ok":true,"provider":"ipify","ipv6":"%s","output":"%s"}\n' "$ip" "$(json_escape "Online IPv6 check passed via ipify: $ip")"
        return
    fi

    response="$(fetch_url "https://ipv6.seeip.org/jsonip")"
    ip="$(extract_ip "$response")"
    if [ -n "$ip" ]; then
        printf '{"ok":true,"provider":"SeeIP","ipv6":"%s","output":"%s"}\n' "$ip" "$(json_escape "Online IPv6 check passed via SeeIP: $ip")"
        return
    fi

    response="$(fetch_url "https://ipv6.icanhazip.com/")"
    ip="$(printf '%s' "$response" | tr -d '\r\n' | sed -n '/:/p' | head -n 1)"
    if [ -n "$ip" ]; then
        printf '{"ok":true,"provider":"icanhazip","ipv6":"%s","output":"%s"}\n' "$ip" "$(json_escape "Online IPv6 check passed via icanhazip: $ip")"
        return
    fi

    printf '{"ok":false,"error":"Online IPv6 check failed across ipify, SeeIP, and icanhazip."}\n'
    exit 2
}

case "$1" in
    disable|-d|off)
        daemon_action "DISABLE"
        ;;
    enable|-e|on)
        daemon_action "ENABLE"
        ;;
    status|-s)
        status_text
        ;;
    json-status|json)
        json_status
        ;;
    online-check|online)
        online_check_text
        ;;
    json-online-check|json-online)
        json_online_check
        ;;
    *)
        echo "Usage: $0 {disable|enable|status|json-status|online-check|json-online-check}"
        exit 1
        ;;
esac
