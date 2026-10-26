#!/system/bin/sh
MODDIR=${0%/*}
# Restore through the native lock while binary/controller still exist; restore clears desired first.
# A failed restore keeps its snapshot and is never deleted outside the controller.
"$MODDIR/ipv6_daemon" --control restore >/dev/null 2>&1
