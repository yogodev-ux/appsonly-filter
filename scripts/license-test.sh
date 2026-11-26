#!/usr/bin/env bash
# Step 4: the payment gate against the real license server, which runs on the CI machine
# (the emulator reaches it at 10.0.2.2:8787). Started by .github/workflows/license-test.yml.
set -u
APK=$1
SERVER=http://localhost:8787
OUT=${OUT_DIR:-test-output}
mkdir -p "$OUT/video"
PKG=com.appsonly.filter
SUMMARY=${GITHUB_STEP_SUMMARY:-/dev/stdout}
FAILS=0; RESULTS=""
sh_() { timeout 90 adb shell "$@" 2>&1 | tr -d '\r'; }
pass() { RESULTS+="| ✅ PASS | $1 |"$'\n'; echo "PASS: $1"; }
fail() { RESULTS+="| ❌ FAIL | $1 — ${2:-} |"$'\n'; echo "FAIL: $1 -- ${2:-}"; FAILS=$((FAILS+1)); }
lic() { sh_ am broadcast -n $PKG/.dev.DevLicenseReceiver | grep -o 'data="[^"]*"' | sed 's/^data="//;s/"$//'; }
hidden() { sh_ dumpsys package "$1" | grep -m1 -o "hidden=[a-z]*"; }
screen_has() { sh_ uiautomator dump /sdcard/ui.xml >/dev/null; sh_ cat /sdcard/ui.xml | grep -q "$1"; }
echo "== start $(date +%T)"
adb shell screenrecord --bit-rate 2000000 --time-limit 175 /sdcard/license.mp4 </dev/null >/dev/null 2>&1 & REC=$!
adb logcat -c; adb logcat -v time </dev/null > "$OUT/logcat.txt" 2>&1 & LOGCAT=$!

curl -sf $SERVER/health >/dev/null && pass "License server is up" || fail "License server is up"

echo "== set up and lock, without paying"
adb install -r -t "$APK" >/dev/null
sh_ dpm set-device-owner --user 0 $PKG/.AdminReceiver | grep -q Success && pass "Becomes Device Owner" || fail "Becomes Device Owner"
APPOK=com.google.android.deskclock
sh_ am broadcast -n $PKG/.dev.DevLockReceiver --es allowed $APPOK >/dev/null
sleep 5
L=$(lic); echo "license: $L"
CODE=$(echo "$L" | grep -o 'code=AOS-[A-Z0-9-]*' | cut -d= -f2)
[ -n "$CODE" ] && pass "Phone registers with the server (code $CODE)" || fail "Phone registers with the server" "$L"
echo "$L" | grep -q "paid=false" && pass "New phone is not paid yet" || fail "New phone is not paid yet" "$L"
[ "$(hidden $APPOK)" = "hidden=true" ] && pass "Allowed app stays closed until paid" || fail "Allowed app stays closed until paid" "$(hidden $APPOK)"
sh_ am start -n $PKG/.MainActivity >/dev/null; sleep 4
adb exec-out screencap -p > "$OUT/1-pay-screen.png"
screen_has "Almost done" && screen_has "$CODE" && pass "Phone shows how to pay, with its code" || fail "Phone shows how to pay, with its code"
curl -s "$SERVER/pay/$CODE" | grep -q "Pay now" && pass "Payment page opens for that code" || fail "Payment page opens for that code"

echo "== pay (test mode) $(date +%T)"
curl -s -o /dev/null -X POST "$SERVER/pay/$CODE/test"
curl -s "$SERVER/pay/$CODE" | grep -q "Paid, thank you" && pass "Server records the payment" || fail "Server records the payment"
# The phone checks every 30 s on the pay screen; wait for that instead of forcing it.
ok=0; for i in $(seq 1 25); do sleep 2; [ "$(hidden $APPOK)" = "hidden=false" ] && { ok=1; break; }; done
[ $ok = 1 ] && pass "Phone unlocks by itself after payment" || fail "Phone unlocks by itself after payment" "$(lic)"
sleep 3; sh_ am start -n $PKG/.MainActivity >/dev/null; sleep 3
adb exec-out screencap -p > "$OUT/2-home-after-paying.png"
screen_has "Almost done" && fail "Home screen after paying" "still on pay screen" || pass "Home screen after paying"

echo "== offline: server gone"
kill "$(cat "$OUT/server.pid")" 2>/dev/null; sleep 1
L=$(lic); echo "license: $L"
echo "$L" | grep -q "paid=true" && [ "$(hidden $APPOK)" = "hidden=false" ] && pass "Stays unlocked with no server or internet" || fail "Stays unlocked with no server or internet" "$L"

echo "== staff blocks it again (e.g. refund)"
( cd server && ADMIN_TOKEN=ci-admin DATA_FILE=../$OUT/server-data.json nohup node src/node-server.js </dev/null >> "../$OUT/server.log" 2>&1 & echo $! > "../$OUT/server.pid" )
for i in $(seq 1 20); do curl -sf $SERVER/health >/dev/null && break; sleep 0.5; done
ID=$(curl -s -H "authorization: Bearer ci-admin" $SERVER/v1/admin/devices | grep -o '"id":"dev_[^"]*"' | head -1 | cut -d'"' -f4)
curl -s -o /dev/null -X POST -H "authorization: Bearer ci-admin" -H "content-type: application/json" -d '{"status":"unpaid"}' "$SERVER/v1/admin/devices/$ID/status"
L=$(lic); echo "license: $L"
echo "$L" | grep -q "paid=false" && [ "$(hidden $APPOK)" = "hidden=true" ] && pass "Staff can close the apps again" || fail "Staff can close the apps again" "$L $(hidden $APPOK)"

echo "== installer logs reach the server"
curl -s -o /dev/null -w '%{http_code}' -X POST -H "content-type: text/plain" --data "STEP Checking the phone" $SERVER/v1/installer-logs | grep -q 200 \
  && pass "Server accepts installer logs" || fail "Server accepts installer logs"

echo "== done $(date +%T)"
sh_ pkill -INT screenrecord >/dev/null; sleep 3; kill $REC $LOGCAT 2>/dev/null
kill "$(cat "$OUT/server.pid")" 2>/dev/null
adb pull /sdcard/license.mp4 "$OUT/video/license.mp4" >/dev/null 2>&1 || true
grep -E "AppsOnlyLicense" "$OUT/logcat.txt" | tail -20
{
  echo "## Payment gate test: $([ $FAILS -eq 0 ] && echo 'ALL PASSED ✅' || echo "$FAILS FAILED ❌")"
  echo; echo "| Result | Check |"; echo "|---|---|"; printf "%s" "$RESULTS"
} >> "$SUMMARY"
exit $FAILS
