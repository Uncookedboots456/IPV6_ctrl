#!/system/bin/sh

MODDIR="${0%/*}"
DAEMON_BIN="$MODDIR/ipv6_daemon"

chmod 0755 "$DAEMON_BIN" 2>/dev/null
chmod 0755 "$MODDIR"/scripts/*.sh 2>/dev/null

if [ -x "$DAEMON_BIN" ]; then
    pidof ipv6_daemon >/dev/null 2>&1 || "$DAEMON_BIN" >/dev/null 2>&1 &
fi
