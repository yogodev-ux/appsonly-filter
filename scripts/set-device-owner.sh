#!/usr/bin/env bash
# Makes the app Device Owner on a test emulator, the way the Windows installer does:
# if accounts are already on the phone (newer Play Store emulator images ship with one),
# switch their owner apps off for a moment, set the owner, then switch them back on.
# Usage: scripts/set-device-owner.sh <package>/<admin receiver>   (prints dpm's answer)
set -u
ADMIN=$1
s() { adb shell "$@" 2>&1 | tr -d '\r'; }
try() { s dpm set-device-owner --user 0 "$ADMIN"; }
R=$(try)
if echo "$R" | grep -q Success || ! echo "$R" | grep -qi account; then echo "$R"; exit 0; fi
DUMP=$(s dumpsys account)
TYPES=$(echo "$DUMP" | grep -oE 'Account \{name=[^}]*, type=[^}[:space:]]+\}' | sed -E 's/.*type=([^}]+)\}/\1/' | sort -u)
OWNERS=""
for t in $TYPES; do
  o=$(echo "$DUMP" | grep -oE "AuthenticatorDescription \{type=$t\}, ComponentInfo\{[^/}]+" | head -1 | sed 's/.*ComponentInfo{//')
  [ -n "$o" ] && [ "$o" != android ] && OWNERS="$OWNERS $o"
done
OWNERS=$(echo $OWNERS | tr ' ' '\n' | sort -u | tr '\n' ' ')
echo "accounts on the image: $(echo $TYPES); switching off for a moment: $OWNERS" >&2
for p in $OWNERS; do s pm disable-user --user 0 "$p" >/dev/null; done
for i in $(seq 1 6); do
  sleep 5; R=$(try)
  echo "$R" | grep -q Success && break
done
for p in $OWNERS; do s pm enable --user 0 "$p" >/dev/null; done
echo "$R"
