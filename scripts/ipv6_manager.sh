#!/system/bin/sh

# Android IPv6 control helper.
# Usage: sh ipv6_manager.sh [disable|enable|status|json-status|online-check|json-online-check]

PROC_IPV6_CONF="/proc/sys/net/ipv6/conf"
APPLY_PASSES="${APPLY_PASSES:-3}"

require_root() {
    if [ "$(id -u)" -ne 0 ]; then
        echo "[!] Root permission is required."
        exit 1
    fi
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

set_ipv6() {
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
