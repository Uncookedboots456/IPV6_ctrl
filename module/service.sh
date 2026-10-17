#!/system/bin/sh
MODDIR=${0%/*}
if [ -e "$MODDIR/disable" ] || [ -e "$MODDIR/remove" ]; then exit 0; fi
"$MODDIR/ipv6_daemon" >/dev/null 2>&1 &
