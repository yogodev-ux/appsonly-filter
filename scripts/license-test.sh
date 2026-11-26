#!/usr/bin/env bash
# Step 4 and the portals: the payment gate, tamper alerts and filter removal against the real license server, which runs on the CI machine
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
adb shell screenrecord --bit-rate 2000000 --time-limit 180 /sdcard/license.mp4 </dev/null >/dev/null 2>&1 & REC=$!
adb logcat -c; adb logcat -v time </dev/null > "$OUT/logcat.txt" 2>&1 & LOGCAT=$!

curl -sf $SERVER/health >/dev/null && pass "License server is up" || fail "License server is up"

echo "== set up and lock, without paying"
adb install -r -t "$APK" >/dev/null
sh_ dpm set-device-owner --user 0 $PKG/.AdminReceiver | grep -q Success && pass "Becomes Device Owner" || fail "Becomes Device Owner"
APPOK=com.google.android.deskclock
# What the Windows installer does after Device Owner: hand over the customer's details.
CUSTB64=$(printf '{"name":"Dana Test","phone":"+1 212 555 0100","email":"dana@example.com","removalCode":"ci-removal-42"}' | base64 -w0)
R=$(sh_ am broadcast -n $PKG/.SetupInfoReceiver --es data "$CUSTB64")
echo "$R" | grep -q 'data="saved"' && pass "Phone accepts the customer details from the installer" || fail "Phone accepts the customer details from the installer" "$R"
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
DEV=$(curl -s -H "authorization: Bearer ci-admin" $SERVER/v1/admin/devices)
echo "$DEV" | grep -q '"name":"Dana Test"' && echo "$DEV" | grep -q '"hasRemovalCode":true' && pass "Server has the customer details and the removal code" || fail "Server has the customer details and the removal code" "$DEV"
echo "$DEV" | grep -q '"hasUninstallCode":true' && pass "Phone sent its offline uninstall code" || fail "Phone sent its offline uninstall code" "$DEV"
echo "$DEV" | grep -q "ci-removal-42" && fail "Removal code is never shown in plain text" || pass "Removal code is never shown in plain text"
R=$(sh_ am broadcast -n $PKG/.SetupInfoReceiver --es data "$CUSTB64")
echo "$R" | grep -q "already locked" && pass "A locked phone refuses new customer details" || fail "A locked phone refuses new customer details" "$R"

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

echo "== portals: tamper alert, staff removes with the customer's code $(date +%T)"
start_server() {
  ADMIN_TOKEN=ci-admin OWNER_PASSWORD=ci-owner-password DATA_FILE="$OUT/server-data.json" nohup node server/src/node-server.js </dev/null >> "$OUT/server.log" 2>&1 &
  echo $! > "$OUT/server.pid"
  for i in $(seq 1 20); do curl -sf $SERVER/health >/dev/null && break; sleep 0.5; done
}
J1="$OUT/owner.cookies"; J2="$OUT/staff.cookies"; rm -f "$J1" "$J2"
form() { local jar=$1 path=$2; shift 2; curl -s -o /dev/null -w '%{http_code} %{redirect_url}' -b "$jar" -c "$jar" -H "origin: $SERVER" -X POST "$@" "$SERVER$path"; }
get() { curl -s -b "$1" -c "$1" "$SERVER$2"; }
uninst() { sh_ am broadcast -n $PKG/.dev.DevUninstallReceiver --es code "$1" | grep -o 'data="[^"]*"' | sed 's/^data="//;s/"$//'; }
# Paid again first, so the pay screen stops checking every 30 s and only this script decides
# when the phone talks to the server.
curl -s -o /dev/null -X POST -H "authorization: Bearer ci-admin" -H "content-type: application/json" -d '{"status":"paid"}' "$SERVER/v1/admin/devices/$ID/status"
lic >/dev/null
# A tamper attempt on the phone (the web filter switched off), reported on the next check-in.
sh_ am broadcast -n $PKG/.dev.DevTamperLogReceiver --es event web_filter_revoked >/dev/null
lic >/dev/null
R=$(form "$J1" /login --data-urlencode "username=owner" --data-urlencode "password=ci-owner-password")
echo "$R" | grep -q "/owner" && pass "Owner signs in and lands in the owner portal" || fail "Owner signs in and lands in the owner portal" "$R"
get "$J1" /owner > "$OUT/portal-alerts.html"
grep -q "Tamper attempt" "$OUT/portal-alerts.html" && grep -q "Dana Test" "$OUT/portal-alerts.html" && pass "Owner portal shows the phone's tamper attempt" || fail "Owner portal shows the phone's tamper attempt"
R=$(form "$J1" /owner/staff --data-urlencode "username=helpdesk" --data-urlencode "password=ci-staff-password")
echo "$R" | grep -q "ok=Added" && pass "Owner adds a staff account" || fail "Owner adds a staff account" "$R"
R=$(form "$J2" /login --data-urlencode "username=helpdesk" --data-urlencode "password=ci-staff-password")
echo "$R" | grep -q "/staff" && pass "Staff signs in and lands in the staff portal" || fail "Staff signs in and lands in the staff portal" "$R"
[ "$(curl -s -o /dev/null -w '%{redirect_url}' -b "$J2" $SERVER/owner)" = "$SERVER/staff" ] && pass "Staff can't open the owner portal" || fail "Staff can't open the owner portal"
get "$J2" "/staff?q=dana" | grep -q "/staff/customers/$ID" && pass "Staff finds the customer by name" || fail "Staff finds the customer by name"
R=$(form "$J2" "/staff/customers/$ID/remove" --data-urlencode "code=wrong-code-1" --data-urlencode "reason=Customer asked (CI test)")
echo "$R" | grep -q "err=" && pass "Wrong customer code: nothing removed" || fail "Wrong customer code: nothing removed" "$R"
get "$J2" "/staff/customers/$ID" | grep -qE "[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}" && fail "No uninstall code shown before removal" || pass "No uninstall code shown before removal"
R=$(form "$J2" "/staff/customers/$ID/remove" --data-urlencode "code=ci-removal-42" --data-urlencode "reason=Customer asked (CI test)")
echo "$R" | grep -q "ok=" && pass "Staff removes the filter with the customer's code" || fail "Staff removes the filter with the customer's code" "$R"
get "$J2" "/staff/customers/$ID" > "$OUT/portal-staff-removed.html"
UCODE=$(grep -oE "[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}" "$OUT/portal-staff-removed.html" | head -1)
[ -n "$UCODE" ] && pass "Staff portal shows the offline uninstall code after removal" || fail "Staff portal shows the offline uninstall code after removal"
# The phone is offline now (server gone): the customer types the uninstall code support reads out.
kill "$(cat "$OUT/server.pid")" 2>/dev/null; sleep 1
R=$(uninst "AAAA-BBBB-CCCC"); echo "$R" | grep -q "^wrong 4" && pass "Phone refuses a wrong uninstall code" || fail "Phone refuses a wrong uninstall code" "$R"
R=$(uninst "$(echo "$UCODE" | tr 'A-Z' 'a-z')"); [ "$R" = right ] && pass "Phone accepts the uninstall code from the portal (offline)" || fail "Phone accepts the uninstall code from the portal (offline)" "$R"
# Back online: the phone gets the signed removal and turns back into a normal phone.
start_server
owner_of_phone() { sh_ dumpsys device_policy | grep -A3 "Device Owner" | grep -q "$PKG"; }
owner_of_phone && pass "Still Device Owner before the removal arrives" || fail "Still Device Owner before the removal arrives"
lic >/dev/null; sleep 3
# Removing Device Owner can end the app's process before that broadcast answers, so ask again
# without talking to the server.
L=$(sh_ am broadcast -n $PKG/.dev.DevLicenseReceiver --ez refresh false | grep -o 'data="[^"]*"'); echo "license: $L"
echo "$L" | grep -q "removed=true" && pass "Phone obeys the removal from the portal" || fail "Phone obeys the removal from the portal" "$L"
owner_of_phone && fail "Device Owner is gone after removal" || pass "Device Owner is gone after removal"
[ "$(hidden com.android.vending)" = "hidden=false" ] && pass "Hidden apps are back (Play Store)" || fail "Hidden apps are back (Play Store)" "$(hidden com.android.vending)"
sh_ am start -n $PKG/.MainActivity >/dev/null; sleep 3
adb exec-out screencap -p > "$OUT/3-filter-removed.png"
screen_has "The filter is removed" && pass "Phone says the filter is removed" || fail "Phone says the filter is removed"
get "$J1" "/owner/customers/$ID" | grep -q "The phone got the removal" && pass "Owner portal shows the phone got the removal" || fail "Owner portal shows the phone got the removal"
get "$J1" /owner/actions > "$OUT/portal-action-log.html"
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
