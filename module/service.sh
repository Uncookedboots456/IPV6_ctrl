#!/system/bin/sh
MODDIR=${0%/*}
enabled() { [ ! -e "$MODDIR/disable" ] && [ ! -e "$MODDIR/remove" ]; }
enabled || exit 0
# Apply the persistent intent once per boot through the native control lock.
"$MODDIR/ipv6_daemon" --control adopt >/dev/null 2>&1
restarts=0
while enabled; do
    "$MODDIR/ipv6_daemon" >/dev/null 2>&1 &
    worker=$!
    wait "$worker"
    enabled || exit 0
    [ "$restarts" -lt 2 ] || exit 0
    restarts=$((restarts + 1))
    if [ "$restarts" = 1 ]; then sleep 5; else sleep 15; fi
done
