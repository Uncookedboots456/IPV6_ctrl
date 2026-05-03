#!/system/bin/sh

# Android IPv6 control helper.
# Usage: sh ipv6_manager.sh [disable|enable|status|json-status|online-check|json-online-check]

PROC_IPV6_CONF="/proc/sys/net/ipv6/conf"

require_root() {
    if [ "$(id -u)" -ne 0 ]; then
        echo "[!] Root permission is required."
        exit 1
    fi
}

write_value() {
    target="$1"
    value="$2"

    if [ -w "$target" ]; then
        echo "$value" > "$target" 2>/dev/null
        return $?
    fi

    return 1
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

set_ipv6() {
    value="$1"
    label="$2"
    count=0
    failed=0

    require_root

    sysctl -w "net.ipv6.conf.all.disable_ipv6=$value" >/dev/null 2>&1
    sysctl -w "net.ipv6.conf.default.disable_ipv6=$value" >/dev/null 2>&1

    for item in "$PROC_IPV6_CONF"/*/disable_ipv6; do
        [ -e "$item" ] || continue
        if write_value "$item" "$value"; then
            count=$((count + 1))
        else
            failed=$((failed + 1))
        fi
    done

    echo "[+] IPv6 $label on $count interface(s)."
    if [ "$failed" -gt 0 ]; then
        echo "[!] Failed to update $failed interface(s)."
        exit 2
    fi
}

status_text() {
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
        set_ipv6 1 "disabled"
        ;;
    enable|-e|on)
        set_ipv6 0 "enabled"
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
