#!/system/bin/sh

# Tiny root WebUI server for Android.
# Requires a netcat-compatible binary: nc, toybox nc, busybox nc, or ncat.

HOST="${HOST:-127.0.0.1}"
PORT="${PORT:-8766}"
BASE_DIR="${BASE_DIR:-$(cd "$(dirname "$0")/.." 2>/dev/null && pwd)}"
WEB_DIR="$BASE_DIR/webui"
MANAGER="$BASE_DIR/scripts/ipv6_manager.sh"

require_root() {
    if [ "$(id -u)" -ne 0 ]; then
        echo "[!] Root permission is required to start the IPv6 WebUI server."
        exit 1
    fi
}

find_nc() {
    for bin in nc ncat; do
        if command -v "$bin" >/dev/null 2>&1; then
            echo "$bin"
            return 0
        fi
    done

    if command -v toybox >/dev/null 2>&1; then
        echo "toybox nc"
        return 0
    fi

    if command -v busybox >/dev/null 2>&1; then
        echo "busybox nc"
        return 0
    fi

    return 1
}

http_header() {
    code="$1"
    type="$2"
    printf 'HTTP/1.1 %s\r\n' "$code"
    printf 'Content-Type: %s\r\n' "$type"
    printf 'Cache-Control: no-store\r\n'
    printf 'Connection: close\r\n'
    printf '\r\n'
}

json_escape() {
    printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'
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

online_check_json() {
    attempts=""

    if ! command -v curl >/dev/null 2>&1 && ! command -v wget >/dev/null 2>&1; then
        output="No HTTP client found. Install curl or wget to run online IPv6 checks."
        escaped="$(json_escape "$output")"
        printf '{"ok":false,"output":"%s","code":127}\n' "$escaped"
        return
    fi

    response="$(fetch_url "https://api6.ipify.org?format=json")"
    ip="$(extract_ip "$response")"
    if [ -n "$ip" ]; then
        output="Online IPv6 check passed via ipify: $ip"
        escaped="$(json_escape "$output")"
        printf '{"ok":true,"provider":"ipify","ipv6":"%s","output":"%s"}\n' "$ip" "$escaped"
        return
    fi
    attempts="${attempts}ipify failed; "

    response="$(fetch_url "https://ipv6.seeip.org/jsonip")"
    ip="$(extract_ip "$response")"
    if [ -n "$ip" ]; then
        output="Online IPv6 check passed via SeeIP: $ip"
        escaped="$(json_escape "$output")"
        printf '{"ok":true,"provider":"SeeIP","ipv6":"%s","output":"%s"}\n' "$ip" "$escaped"
        return
    fi
    attempts="${attempts}SeeIP failed; "

    response="$(fetch_url "https://ipv6.icanhazip.com/")"
    ip="$(printf '%s' "$response" | tr -d '\r\n' | sed -n '/:/p' | head -n 1)"
    if [ -n "$ip" ]; then
        output="Online IPv6 check passed via icanhazip: $ip"
        escaped="$(json_escape "$output")"
        printf '{"ok":true,"provider":"icanhazip","ipv6":"%s","output":"%s"}\n' "$ip" "$escaped"
        return
    fi
    attempts="${attempts}icanhazip failed"

    output="Online IPv6 check failed. $attempts"
    escaped="$(json_escape "$output")"
    printf '{"ok":false,"output":"%s","code":2}\n' "$escaped"
}

run_manager_json() {
    action="$1"
    output="$(sh "$MANAGER" "$action" 2>&1)"
    rc=$?
    compact="$(printf '%s' "$output" | tr '\n' ';')"
    escaped="$(json_escape "$compact")"
    if [ "$rc" -eq 0 ]; then
        printf '{"ok":true,"output":"%s"}\n' "$escaped"
    else
        printf '{"ok":false,"output":"%s","code":%s}\n' "$escaped" "$rc"
    fi
}

serve_file() {
    path="$1"
    type="$2"

    if [ -r "$path" ]; then
        http_header "200 OK" "$type"
        cat "$path"
    else
        http_header "404 Not Found" "application/json"
        printf '{"ok":false,"error":"not found"}\n'
    fi
}

handle_request() {
    read -r request_line
    method="$(printf '%s' "$request_line" | awk '{print $1}')"
    route="$(printf '%s' "$request_line" | awk '{print $2}')"

    while IFS= read -r header; do
        [ "$header" = "$(printf '\r')" ] && break
        [ -z "$header" ] && break
    done

    if [ "$method" != "GET" ] && [ "$method" != "POST" ]; then
        http_header "405 Method Not Allowed" "application/json"
        printf '{"ok":false,"error":"method not allowed"}\n'
        return
    fi

    case "$route" in
        /|/index.html)
            serve_file "$WEB_DIR/index.html" "text/html; charset=utf-8"
            ;;
        /app.js)
            serve_file "$WEB_DIR/app.js" "application/javascript; charset=utf-8"
            ;;
        /style.css)
            serve_file "$WEB_DIR/style.css" "text/css; charset=utf-8"
            ;;
        /api/status)
            http_header "200 OK" "application/json"
            sh "$MANAGER" json-status
            ;;
        /api/enable)
            http_header "200 OK" "application/json"
            run_manager_json enable
            ;;
        /api/disable)
            http_header "200 OK" "application/json"
            run_manager_json disable
            ;;
        /api/online-check)
            http_header "200 OK" "application/json"
            online_check_json
            ;;
        *)
            http_header "404 Not Found" "application/json"
            printf '{"ok":false,"error":"not found"}\n'
            ;;
    esac
}

serve_once() {
    nc_cmd="$1"
    tmp_root="${TMPDIR:-/data/local/tmp}"
    [ -d "$tmp_root" ] || tmp_root="$BASE_DIR"
    fifo="$tmp_root/ipv6ctrl_http_$$.fifo"
    rm -f "$fifo"

    if ! mkfifo "$fifo"; then
        echo "[!] Failed to create fifo: $fifo"
        exit 1
    fi

    if [ "$nc_cmd" = "ncat" ]; then
        cat "$fifo" | ncat -l "$HOST" "$PORT" | handle_request > "$fifo"
    else
        # Android toybox/busybox variants commonly support this form.
        cat "$fifo" | $nc_cmd -l -p "$PORT" -s "$HOST" | handle_request > "$fifo"
    fi

    rm -f "$fifo"
}

require_root

if [ ! -r "$MANAGER" ]; then
    echo "[!] Missing manager script: $MANAGER"
    exit 1
fi

NC_CMD="$(find_nc)" || {
    echo "[!] No netcat-compatible binary found. Install busybox or provide nc."
    exit 1
}

echo "[*] IPv6 WebUI listening on http://$HOST:$PORT"
echo "[*] Press Ctrl+C to stop."

while true; do
    serve_once "$NC_CMD"
done
