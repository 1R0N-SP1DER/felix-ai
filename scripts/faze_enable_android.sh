#!/system/bin/sh
# Run as root, e.g.:  su -c "sh $HOME/felix-ai/scripts/faze_enable_android.sh"
# Allows "display over other apps" for the Faze app and switches on its volume-key accessibility service.
# Termux's LD_PRELOAD must not leak into Android's own tools (it crashes them), so clear it first.
unset LD_PRELOAD LD_LIBRARY_PATH
PKG=com.felix.faze
SVC=com.felix.faze/com.felix.faze.FazeAccessibilityService

/system/bin/appops set $PKG SYSTEM_ALERT_WINDOW allow
echo "overlay permission: $(/system/bin/appops get $PKG SYSTEM_ALERT_WINDOW 2>&1)"

cur=$(/system/bin/settings get secure enabled_accessibility_services)
case "$cur" in
  *"$SVC"*) echo "accessibility service already enabled" ;;
  ""|null) /system/bin/settings put secure enabled_accessibility_services "$SVC" ;;
  *) /system/bin/settings put secure enabled_accessibility_services "$cur:$SVC" ;;
esac
/system/bin/settings put secure accessibility_enabled 1
echo "accessibility services: $(/system/bin/settings get secure enabled_accessibility_services)"
