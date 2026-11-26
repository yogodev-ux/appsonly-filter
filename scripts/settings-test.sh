# Sourced by scripts/emulator-test.sh (uses its helpers: sh_, pass, fail, shot, home, resumed, tap_text).
# The phone's real Settings app is open to the customer. Its risky parts must be locked by
# Android's user restrictions or closed by the screen guard. Each check runs several rounds.

echo "== real Settings app: loopholes"
# What the Windows installer does: allow the screen guard, then let the app switch it on.
sh_ pm grant $PKG android.permission.WRITE_SECURE_SETTINGS >/dev/null
home; sleep 6
sh_ settings get secure enabled_accessibility_services | grep -q "$PKG" \
  && pass "Screen guard is on" || fail "Screen guard is on" "$(sh_ settings get secure enabled_accessibility_services)"

sh_ dumpsys user > "$OUT/dumpsys-user-settings.txt"
for r in no_user_switch no_config_mobile_networks no_bluetooth_sharing no_config_tethering no_apps_control no_modify_accounts no_factory_reset no_add_user no_config_vpn disallow_config_private_dns no_config_date_time no_install_unknown_sources no_network_reset no_config_credentials; do
  grep -qw "$r" "$OUT/dumpsys-user-settings.txt" && pass "Settings lock: $r" || fail "Settings lock: $r" "not set"
done
st=$(sh_ dumpsys package com.android.settings | grep -m1 -o "hidden=[a-z]*")
[ "$st" = "hidden=false" ] && pass "Settings app is not hidden" || fail "Settings app is not hidden" "$st"

# A risky screen counts as locked if we end up back home, or on Android's
# "blocked by your organization" message.
locked_top() { resumed | grep -qE "$PKG/|ActionDisabledByAdmin|AdminSupport|ShowAdminSupport"; }

risky_open() { # name, am start args...
  local name=$1; shift
  home; sh_ am start "$@" >/dev/null 2>&1; sleep 3
  if locked_top; then pass "Settings: $name is locked (round $ROUND)"
  else shot "settings-risky-$ROUND-$(echo "$name" | tr ' /&' '___')"; fail "Settings: $name is locked (round $ROUND)" "$(resumed)"; fi
}
safe_open() { # name, am start args...
  local name=$1; shift
  home; sh_ am start "$@" >/dev/null 2>&1; sleep 3
  resumed | grep -q "com.android.settings/" && pass "Settings: $name opens" || fail "Settings: $name opens" "$(resumed)"
}
# Tap through Settings like a person: open Settings, scroll to an entry, tap it.
risky_tap() { # entry text
  home; sh_ am start -a android.settings.SETTINGS >/dev/null; sleep 3
  local found=0
  for i in 1 2 3 4 5 6 7; do
    tap_text "$1" && { found=1; break; }
    sh_ input swipe 540 1800 540 800 300 >/dev/null; sleep 1
  done
  if [ $found = 0 ]; then echo "INFO: Settings entry '$1' not found on this phone"; return; fi
  sleep 3
  if locked_top; then pass "Settings: tapping '$1' is closed (round $ROUND)"
  else shot "settings-tap-$ROUND-$(echo "$1" | tr ' /&' '___')"; fail "Settings: tapping '$1' is closed (round $ROUND)" "$(resumed)"; fi
}

for ROUND in 1 2 3; do
  risky_open "Accessibility" -a android.settings.ACCESSIBILITY_SETTINGS
  risky_open "Apps list" -a android.settings.MANAGE_ALL_APPLICATIONS_SETTINGS
  risky_open "Our app's info page" -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:$PKG
  risky_open "Play Store's info page" -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:com.android.vending
  risky_open "Default apps" -a android.settings.MANAGE_DEFAULT_APPS_SETTINGS
  risky_open "Home app" -a android.settings.HOME_SETTINGS
  risky_open "Accounts" -a android.settings.SYNC_SETTINGS
  risky_open "Add account" -a android.settings.ADD_ACCOUNT_SETTINGS
  risky_open "Developer options" -a android.settings.APPLICATION_DEVELOPMENT_SETTINGS
  risky_open "Users" -a android.settings.USER_SETTINGS
  risky_open "VPN" -a android.settings.VPN_SETTINGS
  risky_open "Date & time" -a android.settings.DATE_SETTINGS
  risky_open "Install unknown apps" -a android.settings.MANAGE_UNKNOWN_APP_SOURCES
  risky_open "Display over other apps" -a android.settings.action.MANAGE_OVERLAY_PERMISSION
  risky_open "Usage access" -a android.settings.USAGE_ACCESS_SETTINGS
  risky_open "Notification access" -a android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
  risky_open "Modify system settings" -a android.settings.action.MANAGE_WRITE_SETTINGS
  risky_open "Device admin apps" -n com.android.settings/.Settings\$DeviceAdminSettingsActivity
  risky_open "Reset options" -n com.android.settings/.Settings\$ResetDashboardActivity
  risky_open "Factory reset" -n com.android.settings/.Settings\$FactoryResetActivity
  risky_tap "Accessibility"
  risky_tap "Apps"
  risky_tap "Passwords"
done

safe_open "Main screen" -a android.settings.SETTINGS
safe_open "Wi-Fi" -a android.settings.WIFI_SETTINGS
safe_open "Bluetooth" -a android.settings.BLUETOOTH_SETTINGS
safe_open "Display" -a android.settings.DISPLAY_SETTINGS
safe_open "Sound" -a android.settings.SOUND_SETTINGS
safe_open "About phone" -a android.settings.DEVICE_INFO_SETTINGS

# Attacks that don't need the screen: these must fail because of the restrictions.
USERS_BEFORE=$(sh_ pm list users | grep -c UserInfo)
sh_ pm create-user intruder >/dev/null 2>&1
USERS_AFTER=$(sh_ pm list users | grep -c UserInfo)
[ "$USERS_AFTER" = "$USERS_BEFORE" ] && pass "A second user can't be added" || { fail "A second user can't be added" "$USERS_BEFORE -> $USERS_AFTER"; sh_ pm remove-user "$(sh_ pm list users | grep intruder | grep -oE '\{[0-9]+' | tr -d '{')" >/dev/null; }
[ -z "$(sh_ pm list packages -d | grep -x package:com.android.vending)" ] && pass "Play Store still enabled after the Settings tests" || fail "Play Store still enabled after the Settings tests"
sh_ pidof $PKG >/dev/null && pass "Our app still running after the Settings tests" || fail "Our app still running after the Settings tests"
echo "---- Settings screens the guard saw (title) ----"
grep -E "Settings screen:|Screen guard closed com.android.settings" "$OUT/logcat.txt" | sed 's/.*AppsOnly[^:]*: //' | sort | uniq -c | sort -rn | head -40
home
