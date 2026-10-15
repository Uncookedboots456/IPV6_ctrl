#!/system/bin/sh

# Android IPv6 control helper.
# Usage: sh ipv6_manager.sh [disable|enable|status|json-status|online-check|json-online-check]

SCRIPT_DIR="$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)"
MODDIR="$(dirname "$SCRIPT_DIR")"
PROC_IPV6_CONF="/proc/sys/net/ipv6/conf"
DAEMON_BIN="$MODDIR/ipv6_daemon"
DAEMON_SOCK="$MODDIR/ipv6_daemon.sock"
DAEMON_NC=""

require_root() {
    if [ "$(id -u)" -ne 0 ]; then
        echo "[!] Root permission is required." >&2
        return 1
    fi
}

find_daemon_nc() {
    command -v timeout >/dev/null 2>&1 || return 1
    if command -v nc >/dev/null 2>&1; then
        DAEMON_NC=nc
    elif command -v toybox >/dev/null 2>&1; then
        DAEMON_NC=toybox
    elif command -v busybox >/dev/null 2>&1; then
        DAEMON_NC=busybox
    else
        return 1
    fi
}

run_daemon_nc() {
    case "$DAEMON_NC" in
        nc) timeout 3 nc -U -w 2 "$DAEMON_SOCK" ;;
        toybox) timeout 3 toybox nc -U -w 2 "$DAEMON_SOCK" ;;
        busybox) timeout 3 busybox nc -U -w 2 "$DAEMON_SOCK" ;;
        *) return 1 ;;
    esac
}

start_daemon() {
    [ -x "$DAEMON_BIN" ] || return 1
    "$DAEMON_BIN" "$DAEMON_SOCK" >/dev/null 2>&1 &
    tries=0
    while [ "$tries" -lt 10 ]; do
        [ -S "$DAEMON_SOCK" ] && return 0
        sleep 0.2
        tries=$((tries + 1))
    done
    return 1
}

daemon_request() {
    command="$1"
    require_root || return 1
    find_daemon_nc || return 1
    if [ ! -S "$DAEMON_SOCK" ]; then
        [ "$command" != GET_STATUS ] || return 1
        start_daemon || return 1
    fi

    response="$(printf '%s\n' "$command" | run_daemon_nc 2>/dev/null)"
    if [ -z "$response" ] && [ "$command" != GET_STATUS ]; then
        # The daemon owns socket cleanup under its lock; never unlink it here.
        start_daemon || return 1
        response="$(printf '%s\n' "$command" | run_daemon_nc 2>/dev/null)"
    fi
    case "$response" in
        '{"ok":'*) printf '%s\n' "$response" ;;
        *) return 1 ;;
    esac
}

extract_json_field() {
    key="$1"
    printf '%s' "$2" | sed -n "s/.*\"$key\":\"\\([^\"]*\\)\".*/\\1/p" | head -n 1
}

extract_json_bool() {
    key="$1"
    printf '%s' "$2" | sed -nE "s/.*\"$key\":(true|false).*/\\1/p" | head -n 1
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
    echo "[i] Global all/disable_ipv6=$global_status; inspect each interface below."

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
        case "$json" in
            '{"ok":true,'*) return 0 ;;
            *) return 1 ;;
        esac
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

    for item in "$PROC_IPV6_CONF"/*/disable_ipv6; do
        [ -e "$item" ] || continue
        value="$(cat "$item" 2>/dev/null)"
        case "$value" in
            0|1) [ "$value" = "$global_status" ] || state=mixed ;;
            *) printf '{"ok":false,"error":"Unreadable IPv6 interface state"}\n'; return 2 ;;
        esac
    done
    printf '{"ok":true,"state":"%s","target":"unmanaged","target_enforced":false,"interfaces":[' "$state"
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
    if json="$(daemon_request "$1")"; then
        printf '%s\n' "$json"
        case "$json" in
            '{"ok":true,'*) return 0 ;;
            *) return 1 ;;
        esac
    fi
    printf '{"ok":false,"error":"Daemon unavailable; no fallback network writes attempted"}\n'
    return 1
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
