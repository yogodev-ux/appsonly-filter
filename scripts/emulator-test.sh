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

echo "== lock"
LOCK=$(sh_ am broadcast -n $PKG/.dev.DevLockReceiver --es allowed "$ALLOWED" --ez phone true)
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

sleep 20 # let background apps make some lookups
grep "BLOCKED" "$OUT/logcat.txt" | sed 's/.*BLOCKED/BLOCKED/' | sort | uniq -c | sort -rn > "$OUT/blocked-lookups.txt" || true
echo "---- web filter log (first 80 lines) ----"
grep -E "AppsOnlyFilter|AppsOnly" "$OUT/logcat.txt" | head -80
echo "---- blocked lookups ----"
head -40 "$OUT/blocked-lookups.txt"

echo "== reboot keeps the lock"
adb reboot
adb wait-for-device
for i in $(seq 1 90); do [ "$(sh_ getprop sys.boot_completed)" = "1" ] && break; sleep 2; done
sleep 15
shot 05-after-reboot
st=$(hidden_state com.android.chrome)
[ "$st" = "hidden=true" ] || [ -z "$(sh_ pm list packages -u | grep -x package:com.android.chrome)" ] && pass "Lock survives reboot" || fail "Lock survives reboot" "$st"
resumed | grep -q "$PKG" && pass "Our home screen after reboot" || fail "Our home screen after reboot" "$(resumed)"

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
