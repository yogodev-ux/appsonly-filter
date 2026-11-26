#!/usr/bin/env bash
# Automated smoke test on an Android emulator (run by .github/workflows/emulator-test.yml).
# Installs the debug APK, makes it Device Owner, locks it with a test allow-list and checks
# that the lock behaves. Writes a PASS/FAIL table to $GITHUB_STEP_SUMMARY (or stdout).
set -u
APK=${1:-app/build/outputs/apk/debug/app-debug.apk}
OUT=${OUT_DIR:-test-output}
mkdir -p "$OUT"
PKG=com.appsonly.filter
SUMMARY=${GITHUB_STEP_SUMMARY:-/dev/stdout}
FAILS=0
RESULTS=""

sh_() { adb shell "$@" 2>&1 | tr -d '\r'; }
pass() { RESULTS+="| ✅ PASS | $1 |"$'\n'; echo "PASS: $1"; }
fail() { RESULTS+="| ❌ FAIL | $1 — ${2:-} |"$'\n'; echo "FAIL: $1 -- ${2:-}"; FAILS=$((FAILS+1)); }
check() { # check "name" <command that must succeed>
  local name=$1; shift
  if "$@"; then pass "$name"; else fail "$name"; fi
}
shot() { adb exec-out screencap -p > "$OUT/$1.png" 2>/dev/null || true; }
resumed() { sh_ dumpsys activity activities | grep -m1 -E "mResumedActivity|topResumedActivity" ; }
home() { sh_ input keyevent KEYCODE_HOME >/dev/null; sleep 2; }

# Screen recording, in segments (Android stops a recording after 3 minutes).
REC_N=0
start_rec() {
  ( while true; do
      REC_N=$((REC_N+1))
      adb shell screenrecord --bit-rate 2000000 --time-limit 170 "/sdcard/rec-$(date +%s).mp4" >/dev/null 2>&1 || sleep 2
    done ) &
  REC_PID=$!
}
stop_rec() { # stop recording and pull the videos
  kill $REC_PID 2>/dev/null; sh_ pkill -INT screenrecord >/dev/null; sleep 3
  mkdir -p "$OUT/video"
  for f in $(sh_ ls /sdcard/ | grep '^rec-.*\.mp4$'); do adb pull "/sdcard/$f" "$OUT/video/$f" >/dev/null 2>&1; sh_ rm "/sdcard/$f" >/dev/null; done
}
start_rec

adb logcat -c
adb logcat -v time > "$OUT/logcat.txt" &
LOGCAT_PID=$!

echo "== install"
adb install -r -t "$APK" | tee "$OUT/install.txt"
grep -q Success "$OUT/install.txt" && pass "APK installs" || fail "APK installs" "$(tail -1 "$OUT/install.txt")"

echo "== device owner"
DO=$(sh_ dpm set-device-owner --user 0 $PKG/.AdminReceiver)
echo "$DO" > "$OUT/device-owner.txt"
echo "$DO" | grep -q Success && pass "Becomes Device Owner" || fail "Becomes Device Owner" "$DO"

echo "== pick a test allow-list from what is installed"
ALLOWED=""
for p in com.google.android.deskclock com.google.android.calculator com.android.deskclock com.google.android.youtube; do
  if sh_ pm list packages | grep -qx "package:$p"; then ALLOWED="${ALLOWED:+$ALLOWED,}$p"; fi
  [ "$(echo "$ALLOWED" | tr ',' '\n' | grep -c .)" -ge 2 ] && break
done
echo "allowed: $ALLOWED" | tee "$OUT/allowed.txt"
FIRST_ALLOWED=${ALLOWED%%,*}

echo "== setup screen"
sh_ am start -n $PKG/.MainActivity >/dev/null; sleep 6; shot 00-setup
sh_ uiautomator dump /sdcard/ui.xml >/dev/null; sh_ cat /sdcard/ui.xml > "$OUT/setup-ui.xml"
grep -q 'text="Allow phone calls"' "$OUT/setup-ui.xml" && fail "Setup has no separate calls/texts switches" || pass "Setup has no separate calls/texts switches"
grep -qE 'text="(Phone|Messages)"' "$OUT/setup-ui.xml" && pass "Phone/Messages are normal entries in the setup list" || fail "Phone/Messages are normal entries in the setup list" "not visible on the first screen"
sh_ input keyevent KEYCODE_BACK >/dev/null

echo "== lock"
LOCK=$(sh_ am broadcast -n $PKG/.dev.DevLockReceiver --es allowed "$ALLOWED" --ez phone true --es frp 123456789012345678901)
echo "$LOCK" > "$OUT/lock.txt"
echo "$LOCK" | grep -q LOCKED && pass "Locks with allow-list ($ALLOWED)" || fail "Locks with allow-list" "$LOCK"
sleep 5
sh_ am start -n $PKG/.MainActivity >/dev/null
sleep 5
home
shot 01-home

echo "== restrictions"
sh_ dumpsys user > "$OUT/dumpsys-user.txt"
for r in no_install_unknown_sources no_uninstall_apps no_factory_reset no_safe_boot no_add_user no_modify_accounts no_config_vpn no_config_date_time disallow_config_private_dns; do
  grep -q "$r" "$OUT/dumpsys-user.txt" && pass "Restriction $r set" || fail "Restriction $r set"
done
grep -qw no_install_apps "$OUT/dumpsys-user.txt" && fail "Play updates not blocked (no_install_apps absent)" || pass "Play updates not blocked (no_install_apps absent)"

echo "== kiosk mode"
LT=$(sh_ dumpsys activity activities | grep -m1 -i "mLockTaskModeState")
echo "$LT" > "$OUT/locktask.txt"
echo "$LT" | grep -q LOCKED && pass "Kiosk (lock task) mode on" || fail "Kiosk (lock task) mode on" "$LT"

echo "== hidden apps"
hidden_state() { sh_ dumpsys package "$1" | grep -m1 -o "hidden=[a-z]*"; }
for p in com.android.chrome com.android.settings com.google.android.googlequicksearchbox; do
  if sh_ pm list packages -u | grep -qx "package:$p"; then
    st=$(hidden_state "$p"); [ "$st" = "hidden=true" ] && pass "$p hidden" || fail "$p hidden" "$st"
  fi
done
if sh_ pm list packages -u | grep -qx "package:com.android.vending"; then
  st=$(hidden_state com.android.vending); [ "$st" = "hidden=false" ] && pass "Play Store installed and not hidden (can update)" || fail "Play Store not hidden" "$st"
fi
for p in ${ALLOWED//,/ }; do
  st=$(hidden_state "$p"); [ "$st" = "hidden=false" ] && pass "Allowed app $p visible" || fail "Allowed app $p visible" "$st"
done

echo "== nothing opens a browser"
R=$(sh_ cmd package resolve-activity -a android.intent.action.VIEW -c android.intent.category.BROWSABLE -d https://example.com)
echo "$R" > "$OUT/resolve-web.txt"
echo "$R" | grep -qiE "No activity found|chrome" && { echo "$R" | grep -qi chrome && fail "No browser for https links" "$R" || pass "No browser for https links"; } || pass "No browser for https links"

blocked_open() { # name, am start args...
  local name=$1; shift
  home
  sh_ am start "$@" >/dev/null; sleep 3
  local top; top=$(resumed)
  shot "open-$(echo "$name" | tr ' /:' '___')"
  if echo "$top" | grep -qE "com.android.vending|com.android.settings|com.android.chrome"; then fail "$name is blocked" "$top"; else pass "$name is blocked"; fi
}
blocked_open "market:// link" -a android.intent.action.VIEW -d "market://details?id=com.whatsapp"
blocked_open "play.google.com link" -a android.intent.action.VIEW -d "https://play.google.com/store/apps/details?id=com.whatsapp"
blocked_open "Settings app" -a android.settings.SETTINGS
blocked_open "Wi-Fi settings page" -a android.settings.WIFI_SETTINGS
blocked_open "Play Store main screen" -n com.android.vending/com.google.android.finsky.activities.MainActivity

echo "== our own screens open"
tap_text() { # tap the first on-screen element whose text starts with $1
  sh_ uiautomator dump /sdcard/ui.xml >/dev/null
  local b; b=$(sh_ cat /sdcard/ui.xml | tr '>' '\n' | grep -m1 "text=\"$1" | grep -o 'bounds="[^"]*"' | grep -oE '[0-9]+' | tr '\n' ' ')
  [ -z "$b" ] && return 1
  set -- $b
  sh_ input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )) >/dev/null
}
home
tap_text "Settings"; sleep 3; shot 02-settings
resumed | grep -q "$PKG/.SettingsActivity" && pass "Our Settings screen opens" || fail "Our Settings screen opens" "$(resumed)"
tap_text "Wi-Fi networks"; sleep 4; shot 03-wifi
resumed | grep -q "$PKG/.WifiActivity" && pass "Our Wi-Fi screen opens" || fail "Our Wi-Fi screen opens" "$(resumed)"
if [ -n "$FIRST_ALLOWED" ]; then
  home
  sh_ monkey -p "$FIRST_ALLOWED" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 4; shot 04-allowed-app
  resumed | grep -q "$FIRST_ALLOWED" && pass "Allowed app $FIRST_ALLOWED opens" || fail "Allowed app opens" "$(resumed)"
fi

echo "== web filter"
grep -q "Web filter started" "$OUT/logcat.txt" && pass "Web filter (VPN) started" || fail "Web filter (VPN) started"
P=$(sh_ ping -c 1 -W 5 www.google.com | head -2)
echo "$P" > "$OUT/ping.txt"
echo "$P" | grep -qE "PING .*\([0-9.]+\)" && pass "DNS works through the filter (system lookup)" || fail "DNS works through the filter" "$P"

verdict() { sh_ am broadcast -n $PKG/.dev.DevCheckReceiver --es pkg "$1" --es host "$2" | grep -o 'data="[A-Z]*"' | tr -d '"' | cut -d= -f2; }
expect() { # pkg host expected
  local v; v=$(verdict "$1" "$2")
  [ "$v" = "$3" ] && pass "Filter: $1 → $2 is $3" || fail "Filter: $1 → $2 should be $3" "got '$v'"
}
expect com.google.android.gms accounts.google.com ALLOWED
expect com.google.android.gms www.googleapis.com ALLOWED
expect com.google.android.gms www.wikipedia.org BLOCKED
expect com.google.android.gms www.google.com BLOCKED
expect com.google.android.gms support.google.com BLOCKED
expect com.android.vending play.googleapis.com ALLOWED
expect com.android.vending www.wikipedia.org BLOCKED
[ -n "$FIRST_ALLOWED" ] && expect "$FIRST_ALLOWED" www.wikipedia.org BLOCKED
[ -n "$FIRST_ALLOWED" ] && expect "$FIRST_ALLOWED" firebaseinstallations.googleapis.com ALLOWED

BEFORE=$(grep -c "using store guard" "$OUT/logcat.txt")
sleep 20 # let background apps make some lookups
AFTER=$(grep -c "using store guard" "$OUT/logcat.txt")
[ $((AFTER-BEFORE)) -le 2 ] && pass "Lock check isn't running in a loop (battery)" || fail "Lock check isn't running in a loop (battery)" "$((AFTER-BEFORE)) runs in 20s"
grep "BLOCKED" "$OUT/logcat.txt" | sed 's/.*BLOCKED/BLOCKED/' | sort | uniq -c | sort -rn > "$OUT/blocked-lookups.txt" || true
echo "---- app log (errors and tamper events) ----"
grep -E "AppsOnlyTamper|W/AppsOnly|E/AppsOnly|FRP" "$OUT/logcat.txt" | grep -v "could not hide" | head -40
echo "---- blocked lookups ----"
head -40 "$OUT/blocked-lookups.txt"


echo "== step 2: tamper protection"
tamperlog() { sh_ am broadcast -n $PKG/.dev.DevTamperLogReceiver | tr -d '\r'; }
sh_ dumpsys device_policy > "$OUT/dumpsys-device-policy.txt"
STATUS=$(sh_ am broadcast -n $PKG/.dev.DevStatusReceiver | tr -d '\r' | grep -o 'user_control_disabled=[^"]*')
echo "$STATUS"
echo "$STATUS" | grep -q "$PKG" \
  && pass "App can't be force-stopped or cleared by the user" || fail "App can't be force-stopped or cleared by the user" "$STATUS"
[ "$(sh_ settings get global auto_time)" = "1" ] && pass "Automatic date/time on" || fail "Automatic date/time on" "$(sh_ settings get global auto_time)"
if grep -q "FRP set" "$OUT/logcat.txt"; then pass "Factory Reset Protection can be set (test account ID)"
elif grep -q "FRP not supported" "$OUT/logcat.txt"; then pass "Factory Reset Protection: not supported on this emulator (needs a real phone)"
else fail "Factory Reset Protection" "no FRP result in log"; fi

# Tamper: try to turn automatic time off -> Android refuses (date/time settings are locked).
sh_ settings put global auto_time 0 >/dev/null 2>&1
sleep 2
[ "$(sh_ settings get global auto_time)" = "1" ] && pass "Automatic time can't be turned off" || fail "Automatic time can't be turned off" "$(sh_ settings get global auto_time)"

# Tamper: try to leave kiosk mode. Either Android refuses, or it is logged and Home brings it back.
kiosk_state() { sh_ dumpsys activity activities | grep -m1 -i mLockTaskModeState | tr -d '\r'; }
sh_ am task lock stop >/dev/null 2>&1; sleep 2
if kiosk_state | grep -q LOCKED; then
  pass "Kiosk mode can't be switched off (refused)"
else
  home; sleep 3
  tamperlog | grep -q kiosk_mode_exited && pass "Tamper log: leaving kiosk mode recorded" || fail "Tamper log: leaving kiosk mode recorded" "$(tamperlog)"
fi
home; sleep 3
kiosk_state | grep -q LOCKED && pass "Kiosk mode on after Home" || fail "Kiosk mode on after Home" "$(kiosk_state)"

# Tamper: force-stop the app. Android should refuse (user control disabled). If it is
# stopped anyway, the app, the lock and the web filter must come back as soon as Home is used.
PID_BEFORE=$(sh_ pidof $PKG | tr -d '\r')
STARTS_BEFORE=$(grep -c "Web filter started" "$OUT/logcat.txt")
sh_ am force-stop $PKG; sleep 3
PID_AFTER=$(sh_ pidof $PKG | tr -d '\r')
echo "pid before force-stop: $PID_BEFORE, after: $PID_AFTER"
if [ -n "$PID_BEFORE" ] && [ "$PID_BEFORE" = "$PID_AFTER" ]; then
  pass "Force-stop is refused (app keeps running)"
  sh_ dumpsys activity services $PKG/.FilterVpnService | grep -q ServiceRecord && pass "Web filter still running after force-stop attempt" || fail "Web filter still running after force-stop attempt"
else
  home
  ok=0
  for i in $(seq 1 15); do
    sleep 2
    [ "$(grep -c "Web filter started" "$OUT/logcat.txt")" -gt "$STARTS_BEFORE" ] && { ok=1; break; }
  done
  [ $ok = 1 ] && pass "Web filter comes back after the app is killed" || fail "Web filter comes back after the app is killed"
  tamperlog | grep -qE "app_force_stopped|app_stopped_by_user" && pass "Tamper log: force-stop recorded" || fail "Tamper log: force-stop recorded" "$(tamperlog)"
fi
home; sleep 3
resumed | grep -q "$PKG" && pass "Home screen back after force-stop attempt" || fail "Home screen back after force-stop attempt" "$(resumed)"
echo "---- tamper events in the app log ----"
grep -E "AppsOnlyTamper|W/AppsOnly|E/AppsOnly" "$OUT/logcat.txt" | grep -v "could not hide" | tail -30
tamperlog > "$OUT/tamper-log.txt"

echo "== reboot keeps the lock"
stop_rec
adb reboot
adb wait-for-device
for i in $(seq 1 90); do [ "$(sh_ getprop sys.boot_completed)" = "1" ] && break; sleep 2; done
start_rec
sleep 15
shot 05-after-reboot
st=$(hidden_state com.android.chrome)
[ "$st" = "hidden=true" ] || [ -z "$(sh_ pm list packages -u | grep -x package:com.android.chrome)" ] && pass "Lock survives reboot" || fail "Lock survives reboot" "$st"
resumed | grep -q "$PKG" && pass "Our home screen after reboot" || fail "Our home screen after reboot" "$(resumed)"

sleep 5
stop_rec
kill $LOGCAT_PID 2>/dev/null || true
adb logcat -d -v time > "$OUT/logcat-after-reboot.txt" 2>/dev/null || true

{
  echo "## Emulator test: $([ $FAILS -eq 0 ] && echo 'ALL PASSED ✅' || echo "$FAILS FAILED ❌")"
  echo
  echo "| Result | Check |"
  echo "|---|---|"
  printf "%s" "$RESULTS"
  echo
  echo "Blocked lookups seen (top):"
  echo '```'
  head -25 "$OUT/blocked-lookups.txt" 2>/dev/null
  echo '```'
} >> "$SUMMARY"
cp "$SUMMARY" "$OUT/summary.md" 2>/dev/null || true
exit $FAILS
