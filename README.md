# apps-only-filter

Android app that turns a phone into an "allowed apps only" device. It runs as **Device Owner**,
lets the customer pick the allowed apps once during setup, then hides everything else and acts
as the home screen.

Android 10+ (minSdk 29). Kotlin, no third-party libraries.

## Step 1 status (this version)

What it does once locked:

- Shows a simple home screen with only the allowed apps (and the phone / SMS apps if chosen).
- Hides every other app with an icon, every browser, and every other home-screen app.
  The system WebView is kept so allowed apps that show web content still work.
- Keeps the Play Store running in the background so it can keep **updating allowed apps**, but
  the customer can't open it: it is suspended where the phone allows, and otherwise (many phones
  refuse because the store is the system's app verifier) it has no icon and every link into it
  (`market://`, `play.google.com`) opens a "not available" screen instead.
- **Hides the phone's Settings app** and replaces it with our own Settings screen (the "Settings"
  tile). Every control there is done by this app itself, so there are no links into the system:
  Wi-Fi (on/off, network list, connect with password, forget), Bluetooth on/off, volume,
  vibrate, brightness, screen timeout, set/remove screen-lock PIN, add Google account.
- **Add Google account** opens only Google's sign-in. Account changes are unlocked only while
  that sign-in is open and locked again the moment it finishes or is cancelled.
- **Always-on web filter** (a local VPN that carries only DNS lookups, so normal traffic is not
  slowed down). Each app may only look up the web addresses on its allow-list in
  `app/src/main/assets/web_rules.json`; anything else gets "no such site". This blocks in-app
  browsers, links inside Google sign-in, and help pages. Google sign-in (Google Play services)
  may only reach Google's sign-in/infrastructure addresses. With `STRICT_FOR_ALL_APPS = true`
  (in `WebFilter.kt`) an allowed app with no list gets no internet until one is added.
  Debug builds log every blocked address: `adb logcat -s AppsOnlyFilter`.
- Second wall, in case any part of Settings is ever reachable: factory reset, safe mode,
  adding/removing users, changing accounts, VPN, private DNS, date/time, certificates and
  network reset are blocked. Developer options and USB debugging are also blocked in release
  builds (left open in debug builds so you can test with adb).
- Blocks installing from unknown sources, uninstalling apps, and force-stop / clear data.
- Blocks uninstalling this app and the allowed apps.
- New apps that show up later (for example a remote install from the Play website) are hidden
  automatically whenever the home screen is shown, after a reboot, or right away while the app is running.
- The allowed list can't be changed on the phone. (Changes will come from the support portal in a later step.)

No contacts, microphone or camera access. **Location** is granted to the app only because
Android shows the Wi-Fi network list only to apps with location access; it is not used for
anything else. Bluetooth permissions are for the Bluetooth switch.

Not in this step: FRP and the rest of the tamper protection (step 2), Windows
installer (step 3), payment gate (step 4), portals (step 5).

## Get the APK

Every push builds a debug APK on GitHub Actions: open the **Actions** tab, pick the latest
"Build APK" run, and download the `apps-only-debug-apk` artifact (a zip containing `app-debug.apk`).

To build yourself: install Android Studio (or the Android SDK + JDK 17) and run `./gradlew assembleDebug`.
The APK ends up in `app/build/outputs/apk/debug/app-debug.apk`.

Debug builds are all signed with the same key (`app/debug.keystore`), so a new build installs
over the previous one with `adb install -r -t app-debug.apk`, no uninstall needed. (Builds made
before this key was added must be removed once with the developer unlock below.)

## Test on a phone

Use a test phone, not your daily phone.

1. On the phone: **Settings > About phone**, tap **Build number** 7 times, then turn on
   **Developer options > USB debugging**.
2. Remove **every account** from the phone (Settings > Accounts / Passwords & accounts),
   including Google. Android refuses to set a Device Owner while accounts exist.
   (Step 3's installer will handle this automatically.) You can add the Google account back after step 4 below.
3. Connect the phone by USB and install the app (`-t` is needed because debug builds are test-only):

   ```
   adb install -t app-debug.apk
   ```

4. Make it Device Owner:

   ```
   adb shell dpm set-device-owner --user 0 com.appsonly.filter/.AdminReceiver
   ```

   (`--user 0` is needed on newer Android versions such as 14; without it you can get a
   misleading "there are already some accounts" error.)

   Expected output: `Success: Device owner set to package ComponentInfo{com.appsonly.filter/...}`.
   If it says there are already accounts or users on the device, go back to step 2.

5. Open **Apps Only** on the phone, switch phone calls / text messages on or off, tick the allowed
   apps, tap **Lock phone** and confirm. Press the Home button: only the allowed apps remain.

## Remove it from a test phone (developer only)

Debug builds include a developer unlock that unhides everything, clears all restrictions and
removes Device Owner. Only the computer's `adb` can trigger it (it is protected by a permission
normal apps can't get), and it is **not included in release builds**.

```
adb shell am broadcast -a com.appsonly.filter.DEV_UNLOCK -n com.appsonly.filter/.dev.DevUnlockReceiver
adb uninstall com.appsonly.filter
```

Logs while testing: `adb logcat -s AppsOnly`.

Last resort if the broadcast doesn't work: `adb shell dpm remove-active-admin com.appsonly.filter/.AdminReceiver`
removes Device Owner, but apps that were hidden stay hidden until the app is reinstalled,
made Device Owner again and unlocked with the broadcast above (or the phone is factory reset).

## Known limits to check while testing

- The Play Store can still show notifications (e.g. "apps updated"); tapping one may open the
  store. Please check this on the emulator.
- **App updates**: no "install apps" block is used. New apps from `adb install` or sideloading are
  blocked (unknown sources), and anything new that the Play Store installs is hidden right away.
  Updates of allowed apps through the (suspended, hidden) Play Store should keep working. To
  check: `adb shell dumpsys user | grep -i restriction` should **not** list `no_install_apps`,
  and `adb logcat -s AppsOnlyFilter` shows any Play Store download address the filter blocks.

- Pairing a **new** Bluetooth device may not work, because the pairing pop-up belongs to the
  hidden Settings app. Already-paired devices keep working.
- Hotel/airport Wi-Fi with a sign-in page won't work (that page is a browser).
- Enterprise (username + password) Wi-Fi isn't supported yet.
- Apps that hard-code their own DNS or use DNS-over-HTTPS skip the web filter. WebView-based
  in-app browsers use the normal system lookup, so they are filtered.
- If Google sign-in fails, check the blocked list in logcat; some sign-ins need extra Google
  addresses added to `google_core`.
- The per-app lists in `web_rules.json` are starting points; check them with the logcat output.
- **Calls switched off** hides the phone app; on some phones this may also hide the incoming-call screen.

## Project layout

```
app/src/main/java/com/appsonly/filter/
  MainActivity.kt   setup screen + locked home screen
  SettingsActivity.kt  our own Settings screen (Bluetooth, sound, display, PIN, account)
  WifiActivity.kt   Wi-Fi list / connect / forget
  DeviceControls.kt Device Owner powers behind the Settings screen
  AccountFlow.kt    add-Google-account flow (unlocks accounts only while sign-in is open)
  WebFilter.kt      per-app web allow-lists
  FilterVpnService.kt  always-on DNS filter
app/src/main/assets/web_rules.json  the allow-lists
  LockPolicy.kt     applies the lock (restrictions, hide/suspend, home screen)
  AppScanner.kt     finds launchable apps, browsers, launchers, phone/SMS apps, keyboards
  Prefs.kt          saved choices
  FilterApp.kt      re-applies the lock when apps are installed
  BootReceiver.kt   re-applies the lock after reboot
  AdminReceiver.kt  Device Owner receiver
app/src/debug/      developer unlock (debug builds only)
```
