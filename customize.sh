#!/system/bin/sh

SKIPMOUNT=true
PROPFILE=false
POSTFSDATA=false
LATESTARTSERVICE=false

ui_print "- Installing IPV6 Ctrl"
ui_print "- Manual start only; no boot service will be installed"

set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm_recursive "$MODPATH/scripts" 0 0 0755 0755
