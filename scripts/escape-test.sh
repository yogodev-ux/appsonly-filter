#!/usr/bin/env bash
# "Try to break the filter" test (run by .github/workflows/escape-test.yml).
# Locks the phone, then tries every way we know of to get out of the allowed apps:
#   1. the boot window: the time after a (slow) boot before our app turns kiosk mode on
#   2. system screens and popups (notification shade, Quick Settings, power menu, assistant ...)
#   3. Settings / browser / store screens opened as a new window (like a notification would)
#   4. the same screens opened from INSIDE an allowed app (like a "Rate us" or help link)
# A check FAILS when a screen of a package that isn't allowed ends up in front.
set -u
APK=${1:-app/build/outputs/apk/debug/app-debug.apk}
PROBE_APK=${2:-testapps/escapeprobe/build/outputs/apk/debug/escapeprobe-debug.apk}
OUT=${OUT_DIR:-test-output}
mkdir -p "$OUT/shots"
PKG=com.appsonly.filter
PROBE=com.appsonly.probe
SUMMARY=${GITHUB_STEP_SUMMARY:-/dev/stdout}
FAILS=0
RESULTS=""
: > "$OUT/escapes.txt"

sh_() { adb shell "$@" < /dev/null 2>&1 | tr -d '\r'; }
pass() { RESULTS+="| ✅ | $1 |"$'\n'; echo "PASS: $1"; }
fail() { RESULTS+="| ❌ | $1 — ${2:-} |"$'\n'; echo "FAIL: $1 -- ${2:-}"; echo "$1 -- ${2:-}" >> "$OUT/escapes.txt"; FAILS=$((FAILS+1)); }
info() { RESULTS+="| ℹ️ | $1 |"$'\n'; echo "INFO: $1"; }
KNOWN=0
known() { RESULTS+="| ⚠️ | $1 — $2 (known gap, see below) |"$'\n'; echo "KNOWN: $1 -- $2"; echo "$1 -- $2" >> "$OUT/known-gaps.txt"; KNOWN=$((KNOWN+1)); }
slug() { echo "$1" | tr -c 'A-Za-z0-9._-' '_' | cut -c1-80; }
shot() { adb exec-out screencap -p > "$OUT/shots/$(slug "$1").png" 2>/dev/null || true; }
resumed() { sh_ dumpsys activity activities | grep -m1 -E "topResumedActivity=" | grep -oE '[a-zA-Z0-9_.]+/[a-zA-Z0-9_.$]+' | head -1; }
focus() { sh_ dumpsys window | grep mCurrentFocus | sed 's/.*mCurrentFocus=//' | sort -u | tr '\n' ' '; }
kiosk() { sh_ dumpsys activity activities | grep -m1 -i mLockTaskModeState | sed 's/.*=//'; }
# A stalled CI emulator sometimes shows "<app> isn't responding"; press Wait.
dismiss_anr() {
  sh_ uiautomator dump /sdcard/anr.xml >/dev/null
  local b; b=$(sh_ cat /sdcard/anr.xml | tr '>' '\n' | grep -m1 'text="Wait"' | grep -o 'bounds="[^"]*"' | grep -oE '[0-9]+' | tr '\n' ' ')
  [ -z "$b" ] && return 0
  echo "(closing an 'isn't responding' dialog)"; set -- $b; sh_ input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )) >/dev/null; sleep 1
}
home() { sh_ input keyevent KEYCODE_BACK >/dev/null; sh_ input keyevent KEYCODE_BACK >/dev/null; sh_ input keyevent KEYCODE_HOME >/dev/null; sleep 1.5; }
sbflags() { sh_ dumpsys statusbar | grep -m2 -iE "mDisabled[12]|disable1|disable2" | tr '\n' ' '; }

REC_PID=""
start_rec() {
  ( while true; do adb shell screenrecord --bit-rate 2000000 --time-limit 170 "/sdcard/rec-$(date +%s).mp4" >/dev/null 2>&1 || sleep 2; done ) &
  REC_PID=$!
}
stop_rec() {
  [ -n "$REC_PID" ] && kill $REC_PID 2>/dev/null; sh_ pkill -INT screenrecord >/dev/null; sleep 3
  mkdir -p "$OUT/video"
  for f in $(sh_ ls /sdcard/ | grep '^rec-.*\.mp4$'); do adb pull "/sdcard/$f" "$OUT/video/$f" >/dev/null 2>&1; sh_ rm "/sdcard/$f" >/dev/null; done
}
wait_boot() {
  adb wait-for-device
  for i in $(seq 1 150); do [ "$(sh_ getprop sys.boot_completed)" = "1" ] && return 0; sleep 1; done
}

start_rec
adb logcat -c
adb logcat -v time > "$OUT/logcat.txt" &
LOGCAT_PID=$!

echo "== install, Device Owner, lock"
adb install -r -t "$APK" | tail -1
adb install -r -t "$PROBE_APK" | tail -1
sh_ dpm set-device-owner --user 0 $PKG/.AdminReceiver | tail -1
# What the Windows installer does: allow the screen guard.
sh_ pm grant $PKG android.permission.WRITE_SECURE_SETTINGS
ALLOWED=""
for p in com.google.android.deskclock com.google.android.calculator com.android.deskclock; do
  sh_ pm list packages | grep -qx "package:$p" && { ALLOWED=$p; break; }
done
ALLOWED="${ALLOWED:+$ALLOWED,}$PROBE"
echo "allowed: $ALLOWED"
sh_ am broadcast -n $PKG/.dev.DevLockReceiver --es allowed "$ALLOWED" --ez phone true --ez paid true | tail -1
sleep 5
home; sleep 3
[ "$(kiosk)" = "LOCKED" ] && pass "Kiosk mode on after locking" || fail "Kiosk mode on after locking" "$(kiosk)"
# The allowed test app must really be usable (not hidden by the payment gate), or every
# "from inside an allowed app" check below would pass without testing anything.
sh_ am start -W -n $PROBE/.Probe >/dev/null; sleep 2
resumed | grep -q "$PROBE" && pass "Allowed test app opens" || fail "Allowed test app opens" "$(resumed) (hidden by the payment gate?)"
home
shot 00-home
guard_on() { sh_ settings get secure enabled_accessibility_services | grep -q "$PKG/"; }
guard_on && pass "Screen guard is on" || fail "Screen guard is on" "$(sh_ settings get secure enabled_accessibility_services)"
# Tamper: switch it off from the computer; the app must switch it back on by itself.
sh_ settings put secure enabled_accessibility_services "" >/dev/null
home; sh_ am start -n $PKG/.MainActivity >/dev/null; sleep 6
guard_on && pass "Screen guard comes back on after being switched off" || fail "Screen guard comes back on after being switched off"

# Packages that may be in front: ours, the allowed ones and what comes with them (here the
# phone app and contacts, because calls are allowed), which is every app that still has an
# icon, plus the call screen.
VISIBLE=$(sh_ cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER | grep / | cut -d/ -f1 | sort -u | tr '\n' ' ')
DIALERS=$(sh_ cmd package query-activities --brief -a android.intent.action.DIAL | grep / | cut -d/ -f1 | sort -u | tr '\n' ' ')
OK_PKGS=" $PKG ${ALLOWED//,/ } $VISIBLE $DIALERS com.android.server.telecom "
echo "packages allowed in front: $OK_PKGS" | tee "$OUT/ok-packages.txt"
is_ok_pkg() { case "$OK_PKGS" in *" $1 "*) return 0;; esac; return 1; }
# Screens that are fine on purpose: Android's "pick an app" list (whatever is picked must
# still be allowed) and the emergency dialer (emergency calls must always work).
is_ok_top() {
  case "$1" in
    android/com.android.internal.app.ResolverActivity|android/com.android.internal.app.ChooserActivity|com.android.intentresolver/*) return 0;;
    com.android.phone/.EmergencyDialer|com.android.phone/com.android.phone.EmergencyDialer) return 0;;
    # An allowed app asking for a permission (e.g. Contacts on first start).
    *permissioncontroller/*GrantPermissionsActivity) return 0;;
    # Play services' invisible add-account starter: it shows nothing (the screen stays on the
    # allowed app), adding accounts is blocked by the no_modify_accounts restriction, and the
    # visible add-account pages after it are closed by the guard.
    com.google.android.gms/.auth.uiflows.addaccount.AccountIntroActivity) return 0;;
  esac
  is_ok_pkg "${1%%/*}"
}

# check_front <name>: fails if a package that isn't allowed is in front, or the shade is open.
check_front() {
  local name=$1 top top2 f p
  sleep 2.5
  f=$(focus); echo "$f" | grep -q "Application Not Responding" && { dismiss_anr; sleep 1; }
  top=$(resumed); f=$(focus); p=${top%%/*}
  shot "$name"
  if echo "$f" | grep -qiE "NotificationShade|StatusBar.*expanded|GlobalActions|ActionsDialog"; then
    fail "$name" "system window open: $f"
  elif [ -n "$p" ] && ! is_ok_top "$top"; then
    # The guard may need a moment more on a slow screen (Play services loads slowly):
    # closed within ~4 s more still counts, and is reported.
    sleep 4; top2=$(resumed)
    if [ "${SAME_TASK:-0}" = 1 ] && is_ok_top "$top2"; then
      pass "$name"; info "$name: closed by the guard after ~6 s ($top)"
    else
      fail "$name" "opened $top"; [ "${SAME_TASK:-0}" = 1 ] && sh_ am force-stop "$p" >/dev/null
    fi
  else
    pass "$name"
  fi
}

# ------------------------------------------------------------------ 2. system screens
echo "== system screens while locked"
home; sh_ cmd statusbar expand-settings >/dev/null; sleep 2
f=$(focus); shot quick-settings
sh_ uiautomator dump /sdcard/ui.xml >/dev/null; sh_ cat /sdcard/ui.xml > "$OUT/qs-ui.xml"
grep -qE 'text="(Internet|Bluetooth|Flashlight|Airplane mode|Do Not Disturb|Auto-rotate)"' "$OUT/qs-ui.xml" \
  && fail "Quick Settings can't be opened" "tiles visible" || pass "Quick Settings can't be opened"
sh_ cmd statusbar collapse >/dev/null
home; sh_ cmd statusbar expand-notifications >/dev/null; sleep 2; shot notification-shade
info "Notification shade (allowed in kiosk mode): focus=$(focus)"
sh_ cmd statusbar collapse >/dev/null
for k in KEYCODE_APP_SWITCH KEYCODE_ASSIST KEYCODE_VOICE_ASSIST KEYCODE_SEARCH KEYCODE_EXPLORER KEYCODE_SETTINGS KEYCODE_CAMERA KEYCODE_CALCULATOR KEYCODE_CONTACTS KEYCODE_ENVELOPE KEYCODE_MUSIC KEYCODE_CALENDAR; do
  home; sh_ input keyevent $k >/dev/null; check_front "Key $k"
done
home; sh_ input keyevent --longpress KEYCODE_HOME >/dev/null; check_front "Long-press Home (assistant)"
home; sh_ input keyevent --longpress KEYCODE_POWER >/dev/null; sleep 2; shot power-menu
sh_ uiautomator dump /sdcard/ui.xml >/dev/null; sh_ cat /sdcard/ui.xml > "$OUT/power-menu-ui.xml"
info "Power menu items: $(grep -oE 'text="[^"]+"' "$OUT/power-menu-ui.xml" | sort -u | tr '\n' ' ')"
sh_ input keyevent KEYCODE_BACK >/dev/null
home

# ------------------------------------------------------------------ 3. new-window launches
echo "== screens opened in a new window (like from a notification)"
# name | am start arguments
INTENTS=$(cat <<'EOF'
Settings|-a android.settings.SETTINGS
Wi-Fi settings|-a android.settings.WIFI_SETTINGS
Wireless settings|-a android.settings.WIRELESS_SETTINGS
Bluetooth settings|-a android.settings.BLUETOOTH_SETTINGS
App info (ours)|-a android.settings.APPLICATION_DETAILS_SETTINGS -d package:com.appsonly.filter
All apps|-a android.settings.MANAGE_ALL_APPLICATIONS_SETTINGS
Security settings|-a android.settings.SECURITY_SETTINGS
Accessibility settings|-a android.settings.ACCESSIBILITY_SETTINGS
Date settings|-a android.settings.DATE_SETTINGS
Language settings|-a android.settings.LOCALE_SETTINGS
Keyboard settings|-a android.settings.INPUT_METHOD_SETTINGS
Display settings|-a android.settings.DISPLAY_SETTINGS
About phone|-a android.settings.DEVICE_INFO_SETTINGS
Add account|-a android.settings.ADD_ACCOUNT_SETTINGS
Accounts / sync|-a android.settings.SYNC_SETTINGS
Location settings|-a android.settings.LOCATION_SOURCE_SETTINGS
App notifications|-a android.settings.APP_NOTIFICATION_SETTINGS --es android.provider.extra.APP_PACKAGE com.appsonly.filter
Developer options|-a android.settings.APPLICATION_DEVELOPMENT_SETTINGS
Overlay permission|-a android.settings.action.MANAGE_OVERLAY_PERMISSION
Usage access|-a android.settings.USAGE_ACCESS_SETTINGS
VPN settings|-a android.net.vpn.SETTINGS
Default apps|-a android.settings.MANAGE_DEFAULT_APPS_SETTINGS
Home app picker|-a android.settings.HOME_SETTINGS
Internet panel|-a android.settings.panel.action.INTERNET_CONNECTIVITY
Volume panel|-a android.settings.panel.action.VOLUME
Set new screen lock|-a android.app.action.SET_NEW_PASSWORD
Biometric enroll|-a android.settings.BIOMETRIC_ENROLL
Battery usage|-a android.intent.action.POWER_USAGE_SUMMARY
Cast settings|-a android.settings.CAST_SETTINGS
Do Not Disturb|-a android.settings.ZEN_MODE_SETTINGS
Google settings (Play services)|-n com.google.android.gms/.app.settings.GoogleSettingsLink
Google account (Play services)|-a com.google.android.gms.accountsettings.action.VIEW_SETTINGS
Play services app info|-a android.settings.APPLICATION_DETAILS_SETTINGS -d package:com.google.android.gms
Web page https|-a android.intent.action.VIEW -d https://example.com
Web page http|-a android.intent.action.VIEW -d http://example.com
Web search|-a android.intent.action.WEB_SEARCH --es query test
Assistant|-a android.intent.action.ASSIST
Voice command|-a android.intent.action.VOICE_COMMAND
Speech recognizer|-a android.speech.action.RECOGNIZE_SPEECH
Browser (category)|-a android.intent.action.MAIN -c android.intent.category.APP_BROWSER
Market (category)|-a android.intent.action.MAIN -c android.intent.category.APP_MARKET
Gallery (category)|-a android.intent.action.MAIN -c android.intent.category.APP_GALLERY
Email (category)|-a android.intent.action.MAIN -c android.intent.category.APP_EMAIL
Files (category)|-a android.intent.action.MAIN -c android.intent.category.APP_FILES
Maps link|-a android.intent.action.VIEW -d geo:0,0?q=pizza
Email link|-a android.intent.action.VIEW -d mailto:a@example.com
YouTube link|-a android.intent.action.VIEW -d https://www.youtube.com/watch?v=dQw4w9WgXcQ
market:// link|-a android.intent.action.VIEW -d market://details?id=com.whatsapp
Play Store (explicit)|-n com.android.vending/com.google.android.finsky.activities.MainActivity
Play Store link to store pkg|-a android.intent.action.VIEW -d market://details?id=com.whatsapp -p com.android.vending
Emergency dialer|-a com.android.phone.EmergencyDialer.DIAL
Emergency info|-a android.settings.EDIT_EMERGENCY_INFO
Camera|-a android.media.action.STILL_IMAGE_CAMERA
Photo capture|-a android.media.action.IMAGE_CAPTURE
File picker|-a android.intent.action.GET_CONTENT -t */*
Document picker|-a android.intent.action.OPEN_DOCUMENT -t */* -c android.intent.category.OPENABLE
Photo picker|-a android.provider.action.PICK_IMAGES
Files app|-n com.google.android.documentsui/com.android.documentsui.files.FilesActivity
Share sheet|-a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT https://example.com
HTML file viewer|-a android.intent.action.VIEW -d content://media/external/file/1 -t text/html
Bluetooth turn-on popup|-a android.bluetooth.adapter.action.REQUEST_ENABLE
Bluetooth discoverable popup|-a android.bluetooth.adapter.action.REQUEST_DISCOVERABLE
Wallpaper picker|-a android.intent.action.SET_WALLPAPER
Work profile setup|-a android.app.action.PROVISION_MANAGED_PROFILE
Storage manager|-a android.os.storage.action.MANAGE_STORAGE
Wellbeing|-a android.settings.action.DIGITAL_WELLBEING
EOF
)
while IFS='|' read -r name args; do
  [ -z "$name" ] && continue
  home
  # shellcheck disable=SC2086
  sh_ am start $args >/dev/null
  check_front "New window: $name"
done <<< "$INTENTS"

# ------------------------------------------------------------------ 4. from inside an allowed app
echo "== same screens opened from inside an allowed app (its own window)"
probe() { # name, extras...
  local name=$1; shift
  home
  sh_ am start -S -W -n $PROBE/.Probe "$@" >/dev/null
  SAME_TASK=1 check_front "From allowed app: $name"
}
probe "Play Store main screen" --es cmp com.android.vending/com.google.android.finsky.activities.MainActivity
probe "Play Store details (Rate us)" --es action android.intent.action.VIEW --es data market://details?id=com.whatsapp --es pkg com.android.vending
probe "market:// link" --es action android.intent.action.VIEW --es data market://details?id=com.whatsapp
probe "Web page" --es action android.intent.action.VIEW --es data https://example.com
probe "Web search" --es action android.intent.action.WEB_SEARCH
probe "Settings" --es action android.settings.SETTINGS
probe "Google settings (Play services)" --es cmp com.google.android.gms/.app.settings.GoogleSettingsLink
probe "Google account (Play services)" --es action com.google.android.gms.accountsettings.action.VIEW_SETTINGS
probe "Google Help (Play services, shows web pages)" --es cmp com.google.android.gms/.googlehelp.helpactivities.HelpActivity
probe "Share sheet" --es action android.intent.action.SEND --es type text/plain
probe "File picker" --es action android.intent.action.GET_CONTENT --es type "*/*"

# Sweep: every exported "main" screen of every package that is still installed and visible.
echo "== sweep: every visible app screen, from inside an allowed app"
sh_ cmd package query-activities --brief -a android.intent.action.MAIN | grep / | sort -u > "$OUT/all-main-activities.txt"
SWEPT=0
while read -r cmp; do
  p=${cmp%%/*}
  is_ok_pkg "$p" && continue
  case "$p" in android|com.android.systemui) continue;; esac
  SWEPT=$((SWEPT+1)); [ $SWEPT -gt 150 ] && break
  home
  sh_ am start -S -W -n $PROBE/.Probe --es cmp "$cmp" >/dev/null
  sleep 1.5
  top=$(resumed); tp=${top%%/*}
  if [ -n "$tp" ] && ! is_ok_top "$top"; then
    # Android lets an allowed app open other apps' screens inside its own window (kiosk
    # mode only checks which app a window belongs to); the screen guard must close them.
    shot "sweep-$cmp"; fail "From allowed app (sweep): $cmp" "opened $top"
    sh_ am force-stop "$tp" >/dev/null
  fi
done < "$OUT/all-main-activities.txt"
info "Sweep tried $SWEPT screens of non-allowed packages"
# A screen we know the guard must close: a phone-service settings screen opened from
# inside the allowed app (the phone service itself is allowed, its settings are not).
PHONE_SET=$(sh_ dumpsys package com.android.phone | grep -oE 'com\.android\.phone/[A-Za-z0-9_.$]*(RadioInfo|CallFeaturesSetting|NetworkSetting)[A-Za-z0-9_.$]*' | head -1)
if [ -n "$PHONE_SET" ]; then
  home; sh_ am start -S -W -n $PROBE/.Probe --es cmp "$PHONE_SET" >/dev/null; sleep 6
  top=$(resumed)
  echo "$top" | grep -qE "RadioInfo|CallFeaturesSetting|NetworkSetting" \
    && fail "Guard closes phone settings opened from an allowed app ($PHONE_SET)" "still open: $top" \
    || pass "Guard closes phone settings opened from an allowed app ($PHONE_SET)"
  home
else
  info "No phone settings screen found to test the guard with"
fi
TL=$(sh_ am broadcast -n $PKG/.dev.DevTamperLogReceiver)
echo "$TL" | grep -q screen_blocked && pass "Tamper log records screens the guard closed" || fail "Tamper log records screens the guard closed" "$(echo "$TL" | tail -c 300)"
grep "Screen guard closed" "$OUT/logcat.txt" | sed 's/.*Screen guard closed //' | sort | uniq -c | sort -rn > "$OUT/guard-closed.txt"
info "Screen guard closed $(wc -l < "$OUT/guard-closed.txt") different screens (see guard-closed.txt)"
cat "$OUT/guard-closed.txt"
echo "--- guard events from Play services (class names the guard saw)"
grep "guard saw" "$OUT/logcat.txt" | sed 's/.*guard saw //' | sort | uniq -c | sort -rn | head -30
home

# ------------------------------------------------------------------ 1. boot window
boot_probe() { # label: sample the phone from the moment adb answers until kiosk mode is on
  local label=$1 t0 n=0 tapped=0 gapfirst="" gaplast="" escaped=0 b
  : > "$OUT/boot-$label.txt"
  wait_boot
  t0=$(date +%s)
  for i in $(seq 1 90); do
    local now k f top
    now=$(( $(date +%s) - t0 ))
    k=$(kiosk)
    # What a user could do right now: pull down Quick Settings, open recents / Play Store.
    sh_ cmd statusbar expand-settings >/dev/null 2>&1
    sleep 0.7
    f=$(focus)
    top=$(resumed)
    echo "t+${now}s kiosk=$k focus=$f top=$top sb=$(sbflags)" >> "$OUT/boot-$label.txt"
    # Once kiosk mode is on, the shade may open (notifications only), so that sample doesn't count.
    if [ "$k" != "LOCKED" ] && echo "$f" | grep -qi NotificationShade; then
      [ -z "$gapfirst" ] && { gapfirst=$now; shot "boot-$label-shade-open"; }
      gaplast=$now; escaped=1
    fi
    sh_ cmd statusbar collapse >/dev/null 2>&1
    if [ "$k" != "LOCKED" ]; then
      # Like tapping a Play Store notification before kiosk mode is on.
      sh_ am start -n com.android.vending/com.google.android.finsky.activities.MainActivity >/dev/null 2>&1
      sleep 1.5
      top=$(resumed)
      if echo "$top" | grep -q com.android.vending; then
        [ $n = 0 ] && shot "boot-$label-play-store"
        n=$((n+1))
      fi
      home
      # Tap the allowed test app on our home screen.
      sh_ uiautomator dump /sdcard/ui.xml >/dev/null
      b=$(sh_ cat /sdcard/ui.xml | tr '>' '\n' | grep -m1 'text="Escape probe"' | grep -o 'bounds="[^"]*"' | grep -oE '[0-9]+' | tr '\n' ' ')
      if [ -n "$b" ]; then
        set -- $b
        sh_ input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )) >/dev/null; sleep 1.5
        if resumed | grep -q $PROBE && [ "$(kiosk)" != "LOCKED" ]; then
          [ $tapped = 0 ] && shot "boot-$label-app-outside-kiosk"
          tapped=$((tapped+1))
        fi
        home
      fi
    else
      break
    fi
  done
  echo "kiosk on after ~$(( $(date +%s) - t0 ))s" >> "$OUT/boot-$label.txt"
  [ $escaped = 0 ] && pass "Boot window ($label): Quick Settings / shade can't be opened before kiosk mode" \
    || fail "Boot window ($label): Quick Settings / shade can't be opened before kiosk mode" "open from t+${gapfirst}s to t+${gaplast}s"
  [ "$escaped" = 1 ] && { echo "--- boot samples ($label)"; cat "$OUT/boot-$label.txt"; }
  # A window opened by the computer (adb) stands in for tapping a notification. With the
  # notification shade off there is no way for a person to do that, so this is only reported.
  info "Boot window ($label): Play Store opened by adb (stand-in for a notification tap) $n time(s) before kiosk mode"
  [ $tapped = 0 ] && pass "Boot window ($label): allowed app can't be opened outside kiosk mode" \
    || fail "Boot window ($label): allowed app can't be opened outside kiosk mode" "opened $tapped time(s) while kiosk mode was off"
  [ "$(kiosk)" = "LOCKED" ] && pass "Boot window ($label): kiosk mode comes on" || fail "Boot window ($label): kiosk mode comes on" "$(kiosk)"
}

echo "== boot window: normal boot"
stop_rec
kill $LOGCAT_PID 2>/dev/null
adb reboot
boot_probe normal
start_rec

echo "== boot window: slow phone (kiosk mode starts 60 s after boot)"
sh_ am broadcast -n $PKG/.dev.DevKioskDelayReceiver --el ms 60000 | tail -1
stop_rec
adb reboot
wait_boot
start_rec
boot_probe slow
# What else is reachable in that window? Tap the allowed app from our home screen, then
# try to open the Play Store from inside it.
sh_ am broadcast -n $PKG/.dev.DevKioskDelayReceiver --el ms 0 | tail -1

stop_rec
adb logcat -d -v time > "$OUT/logcat-end.txt" 2>/dev/null || true

{
  echo "## Escape test: $([ $FAILS -eq 0 ] && echo 'NO WAY OUT FOUND ✅' || echo "$FAILS WAY(S) OUT ❌") ($KNOWN known gap(s))"
  echo
  echo "| | Check |"
  echo "|---|---|"
  printf "%s" "$RESULTS"
} >> "$SUMMARY"
cp "$SUMMARY" "$OUT/summary.md" 2>/dev/null || true
exit $FAILS
