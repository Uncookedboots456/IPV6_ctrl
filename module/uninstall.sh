#!/system/bin/sh
MODDIR=${0%/*}
# Restore while the binary/controller still exist; never discard a failed restore's snapshot.
"$MODDIR/ipv6_daemon" --control restore >/dev/null 2>&1
