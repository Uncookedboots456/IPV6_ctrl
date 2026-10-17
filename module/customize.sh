#!/system/bin/sh
[ "$ARCH" = arm64 ] || abort "Only arm64 is supported by this build."
[ "$API" -ge 30 ] || abort "Android 11 or newer is required by the runtime binary."
# A live worker may still execute the previous binary/controller. Upgrade after restore + reboot.
if [ -r /data/adb/ipv6ctrl-system/managed ] &&
   [ "$(cat /data/adb/ipv6ctrl-system/managed)" = "$(cat /proc/sys/kernel/random/boot_id)" ]; then
    abort "Restore or pause takeover in the app before updating the module."
fi
ui_print "- Independent worker; no network changes until enabled in the app"
ui_print "- Restore/remove old IPv6 modules first; reboot after install"
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/ipv6_daemon" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755
