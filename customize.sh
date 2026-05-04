#!/system/bin/sh

SKIPMOUNT=true
PROPFILE=false
POSTFSDATA=false
LATESTARTSERVICE=false

ui_print "- Installing IPV6 Ctrl"
ui_print "- KernelSU WebUI enabled; daemon boot service will be installed"

set_perm_recursive "$MODPATH/scripts" 0 0 0755 0755
set_perm "$MODPATH/ipv6_daemon" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755
