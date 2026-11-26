#!/usr/bin/env bash
# Steps 4 and 5: the payment gate and the dashboard/support portal against the real license server, which runs on the CI machine
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
# Started directly (not in a subshell) so $! is the server itself and nothing else keeps running.
ADMIN_TOKEN=ci-admin OWNER_PASSWORD=ci-owner-password DATA_FILE="$OUT/server-data.json" nohup node server/src/node-server.js </dev/null >> "$OUT/server.log" 2>&1 &
echo $! > "$OUT/server.pid"
for i in $(seq 1 20); do curl -sf $SERVER/health >/dev/null && break; sleep 0.5; done
ID=$(curl -s -H "authorization: Bearer ci-admin" $SERVER/v1/admin/devices | grep -o '"id":"dev_[^"]*"' | head -1 | cut -d'"' -f4)
curl -s -o /dev/null -X POST -H "authorization: Bearer ci-admin" -H "content-type: application/json" -d '{"status":"unpaid"}' "$SERVER/v1/admin/devices/$ID/status"
L=$(lic); echo "license: $L"
echo "$L" | grep -q "paid=false" && [ "$(hidden $APPOK)" = "hidden=true" ] && pass "Staff can close the apps again" || fail "Staff can close the apps again" "$L $(hidden $APPOK)"

echo "== step 5: owner dashboard and staff support portal $(date +%T)"
J1="$OUT/owner.cookies"; J2="$OUT/staff.cookies"; rm -f "$J1" "$J2"
form() { curl -s -o /dev/null -w '%{http_code} %{redirect_url}' -b "$1" -c "$1" -X POST --data-urlencode "${@:3}" "$SERVER$2"; }
get() { curl -s -b "$1" -c "$1" "$SERVER$2"; }
R=$(form "$J1" /login "username=owner" --data-urlencode "password=ci-owner-password")
echo "$R" | grep -q "/admin" && pass "Owner signs in to the dashboard" || fail "Owner signs in to the dashboard" "$R"
get "$J1" /admin > "$OUT/portal-dashboard.html"
grep -q "$CODE" "$OUT/portal-dashboard.html" && pass "Dashboard lists the phone" || fail "Dashboard lists the phone"
R=$(form "$J1" /admin/staff "username=helpdesk" --data-urlencode "password=ci-staff-password")
echo "$R" | grep -q "ok=Added" && pass "Owner adds a staff account" || fail "Owner adds a staff account" "$R"
R=$(form "$J2" /login "username=helpdesk" --data-urlencode "password=ci-staff-password")
echo "$R" | grep -q "/support" && pass "Staff signs in to the support portal" || fail "Staff signs in to the support portal" "$R"
[ "$(curl -s -o /dev/null -w '%{http_code}' -b "$J2" $SERVER/admin)" = 403 ] && pass "Staff can't open the owner dashboard" || fail "Staff can't open the owner dashboard"
R=$(curl -s -o /dev/null -w '%{redirect_url}' -b "$J2" "$SERVER/support/find?code=$CODE")
echo "$R" | grep -q "/support/phone/$CODE" && pass "Staff finds the phone by its code" || fail "Staff finds the phone by its code" "$R"
get "$J2" "/support/phone/$CODE" > "$OUT/portal-phone.html"

R=$(form "$J2" "/portal/device/$ID/unlock" "reason=Paid by phone (CI test)" --data-urlencode "back=/support/phone/$CODE")
L=$(lic); echo "license: $L"
echo "$L" | grep -q "paid=true" && [ "$(hidden $APPOK)" = "hidden=false" ] && pass "Staff unlocks the phone from the portal" || fail "Staff unlocks the phone from the portal" "$R / $L"

R=$(form "$J2" "/portal/device/$ID/remove" "reason=Customer asked (CI test)" --data-urlencode "confirm=AOS-0000-0000" --data-urlencode "back=/support/phone/$CODE")
L=$(lic)
echo "$R" | grep -q "err=" && echo "$L" | grep -q "removed=false" && pass "Removal refused when the typed code is wrong" || fail "Removal refused when the typed code is wrong" "$R / $L"

owner_of_phone() { sh_ dumpsys device_policy | grep -A3 "Device Owner" | grep -q "$PKG"; }
owner_of_phone && pass "Still Device Owner before removal" || fail "Still Device Owner before removal"
R=$(form "$J2" "/portal/device/$ID/remove" "reason=Customer asked (CI test)" --data-urlencode "confirm=$CODE" --data-urlencode "back=/support/phone/$CODE")
echo "$R" | grep -q "ok=Removal" && pass "Staff sends 'remove the filter'" || fail "Staff sends 'remove the filter'" "$R"
L=$(lic); echo "license: $L"; sleep 3
echo "$L" | grep -q "removed=true" && pass "Phone obeys the removal" || fail "Phone obeys the removal" "$L"
owner_of_phone && fail "Device Owner is gone after removal" || pass "Device Owner is gone after removal"
[ "$(hidden com.android.vending)" = "hidden=false" ] && pass "Hidden apps are back (Play Store)" || fail "Hidden apps are back (Play Store)" "$(hidden com.android.vending)"
sh_ am start -n $PKG/.MainActivity >/dev/null; sleep 3
adb exec-out screencap -p > "$OUT/3-filter-removed.png"
screen_has "The filter is removed" && pass "Phone says the filter is removed" || fail "Phone says the filter is removed"
get "$J1" "/admin/device/$ID" > "$OUT/portal-phone-after.html"
grep -q "confirmed the removal" "$OUT/portal-phone-after.html" && pass "Portal shows the phone confirmed the removal" || fail "Portal shows the phone confirmed the removal"
get "$J1" /admin/audit > "$OUT/portal-action-log.html"
grep -q "helpdesk" "$OUT/portal-action-log.html" && grep -q "Customer asked" "$OUT/portal-action-log.html" && pass "Action log records who did it and why" || fail "Action log records who did it and why"
rm -f "$J1" "$J2"

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
