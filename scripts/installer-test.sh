#!/usr/bin/env bash
# Runs the Windows installer's setup engine (same code, as a jar) against an emulator that
# already has an account on it, the case that normally needs a factory reset.
set -u
APK=$1; FAKE=$2; JAR=$3
OUT=${OUT_DIR:-test-output}
mkdir -p "$OUT/video"
PKG=com.appsonly.filter
SUMMARY=${GITHUB_STEP_SUMMARY:-/dev/stdout}
FAILS=0; RESULTS=""
sh_() { adb shell "$@" 2>&1 | tr -d '\r'; }
pass() { RESULTS+="| ✅ PASS | $1 |"$'\n'; echo "PASS: $1"; }
fail() { RESULTS+="| ❌ FAIL | $1 — ${2:-} |"$'\n'; echo "FAIL: $1 -- ${2:-}"; FAILS=$((FAILS+1)); }
( adb shell screenrecord --bit-rate 2000000 --time-limit 170 /sdcard/installer.mp4 >/dev/null 2>&1 ) & REC=$!

echo "== a phone with an account on it"
adb install -r "$FAKE" >/dev/null
R=$(sh_ am broadcast -n com.appsonly.testaccount/.AddAccountReceiver)
sh_ dumpsys account | grep -q "type=com.appsonly.testaccount" && pass "Test phone has an account" || fail "Test phone has an account" "$R"

# Without the trick Android refuses Device Owner, so this test really covers it.
adb install -r -t "$APK" >/dev/null
D=$(sh_ dpm set-device-owner --user 0 $PKG/.AdminReceiver)
echo "$D" | grep -v "^\s*at " | head -5
if echo "$D" | grep -qi "account"; then pass "Plain setup is refused because of the account (as expected)"
else fail "Plain setup is refused because of the account" "$(echo "$D" | head -2)"; fi
# Start the installer from a clean state: no leftover admin from the attempt above, app removed.
sh_ dpm remove-active-admin --user 0 $PKG/.AdminReceiver >/dev/null 2>&1
adb uninstall $PKG >/dev/null 2>&1

echo "== run the installer"
java -jar "$JAR" --cli --check --adb "$(command -v adb)" --log-dir "$OUT/installer-logs" | tee "$OUT/installer-check.txt"
grep -q "CHECK OK" "$OUT/installer-check.txt" && pass "Installer phone check passes" || fail "Installer phone check passes"
grep -q "Will switch off for a moment: com.appsonly.testaccount" "$OUT/installer-check.txt" && pass "Installer finds the app that owns the account" || fail "Installer finds the app that owns the account"

# A new setup without the customer's details must stop before touching the phone.
java -jar "$JAR" --cli --yes --apk "$APK" --adb "$(command -v adb)" --log-dir "$OUT/installer-logs" > "$OUT/installer-nodetails.txt" 2>&1
grep -q "SETUP FAILED" "$OUT/installer-nodetails.txt" && grep -q "removal code first" "$OUT/installer-nodetails.txt" \
  && pass "Setup needs the customer's details first" || fail "Setup needs the customer's details first" "$(tail -2 "$OUT/installer-nodetails.txt")"
CUST=(--name "Dana Test" --phone "+1 212 555 0100" --email "dana@example.com" --removal-code "ci-removal-42")
java -jar "$JAR" --cli --yes --apk "$APK" --adb "$(command -v adb)" --log-dir "$OUT/installer-logs" "${CUST[@]}" | tee "$OUT/installer-run.txt"
grep -q "SETUP OK" "$OUT/installer-run.txt" && pass "Installer finishes" || fail "Installer finishes" "$(tail -3 "$OUT/installer-run.txt")"
sh_ dumpsys device_policy | grep -A3 "Device Owner" | grep -q "$PKG" && pass "Apps Only is Device Owner without a factory reset" || fail "Apps Only is Device Owner"
sh_ dumpsys package $PKG | grep -q "android.permission.WRITE_SECURE_SETTINGS: granted=true" && pass "Screen guard allowed by the installer" || fail "Screen guard allowed by the installer"
sh_ pm list packages -d | grep -q com.appsonly.testaccount && fail "Account app switched back on" "still disabled" || pass "Account app switched back on"
sh_ dumpsys account | grep -q "type=com.appsonly.testaccount" && pass "The account is still there" || fail "The account is still there"
grep -q "Customer details and removal code saved" "$OUT/installer-run.txt" && pass "Customer details reach the phone" || fail "Customer details reach the phone"
sleep 4
sh_ dumpsys activity activities | grep -m1 -E "mResumedActivity|topResumedActivity" | grep -q $PKG && pass "Setup screen opens on the phone" || fail "Setup screen opens on the phone"
adb exec-out screencap -p > "$OUT/after-install.png" 2>/dev/null || true
LOG=$(ls "$OUT"/installer-logs/setup-*.log 2>/dev/null | tail -1)
[ -n "$LOG" ] && grep -q "RESULT success" "$LOG" && pass "Setup log saved" || fail "Setup log saved"
[ -n "$LOG" ] && ! grep -qw tester "$LOG" && pass "Log has no account names" || fail "Log has no account names"
[ -n "$LOG" ] && ! grep -qE "ci-removal-42|Dana|555 0100|example.com" "$LOG" && pass "Log has no customer details or removal code" || fail "Log has no customer details or removal code"

echo "---- setup log ----"; [ -n "$LOG" ] && cat "$LOG"
echo "== run it again (update)"
java -jar "$JAR" --cli --yes --apk "$APK" --adb "$(command -v adb)" --log-dir "$OUT/installer-logs" | tee "$OUT/installer-rerun.txt"
grep -q "already set up" "$OUT/installer-rerun.txt" && grep -q "SETUP OK" "$OUT/installer-rerun.txt" \
  && pass "Running it again just updates the app" || fail "Running it again just updates the app"

sh_ pkill -INT screenrecord >/dev/null; sleep 3; kill $REC 2>/dev/null
adb pull /sdcard/installer.mp4 "$OUT/video/installer.mp4" >/dev/null 2>&1 || true
{
  echo "## Installer test: $([ $FAILS -eq 0 ] && echo 'ALL PASSED ✅' || echo "$FAILS FAILED ❌")"
  echo; echo "| Result | Check |"; echo "|---|---|"; printf "%s" "$RESULTS"
} >> "$SUMMARY"
exit $FAILS
