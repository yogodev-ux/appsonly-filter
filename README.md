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
- Runs in Android's **kiosk mode** (lock task): only allowed apps, our own screens and a few system
  helpers (permission prompts, file/photo pickers, share sheet, calls, Google sign-in) can open at
  all. Links or notifications that try to open anything else (Play Store, Settings, camera, ...)
  do nothing. Quick Settings is turned off; the notification shade, Home, recent apps, power menu
  and screen lock still work.
- **Hides the phone's Settings app** and replaces it with our own Settings screen (the "Settings"
  tile). Every control there is done by this app itself, so there are no links into the system:
  Wi-Fi (on/off, network list, connect with password, forget), Bluetooth on/off, volume,
  vibrate, brightness, screen timeout, flashlight, set/remove screen-lock PIN, add Google account.
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

## Step 2: tamper protection

- Factory reset from Settings, safe mode, adding/removing users, changing date/time: blocked.
- **Factory Reset Protection**: after a reset from recovery mode (buttons at power-on, which no
  app can block), the phone can only be set up again with the company Google account.
  Set the account's numeric Google ID in `TamperConfig.kt` (`FRP_ACCOUNT_IDS`); empty = off for now.
- Automatic date, time and time zone are forced on.
- USB debugging and Developer options: blocked and forced off in **release** builds
  (debug builds keep them so adb testing works).
- The customer can't force-stop the app or clear its data (Android 11+).
- The web filter is an always-on VPN, so Android restarts it if it is killed; a watchdog job
  also re-applies the whole lock every 15 minutes and after reboot.
- **Tamper log** (on the phone, for the support portal in step 5): wrong PINs, leaving kiosk
  mode, the app being force-stopped or killed, the web filter being revoked, time settings
  changed, new apps appearing (hidden right away), USB debugging found on.
  Debug builds: `adb shell am broadcast -n com.appsonly.filter/.dev.DevTamperLogReceiver`,
  or `adb logcat -s AppsOnlyTamper`.

## Automated emulator test

`.github/workflows/emulator-test.yml` runs `scripts/emulator-test.sh` on every push: an Android 13
emulator with the Play Store installs the debug APK, makes it Device Owner, locks it with a test
allow-list and checks the lock, the web filter, tamper protection and a reboot. The run's
**emulator-test** artifact has screen recordings, screenshots, logcat and a pass/fail summary.

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

## Step 3: Windows installer

`installer/` is the PC setup program (Kotlin, its own small build: `./gradlew -p installer test fatJar`).
CI packages it as `AppsOnlySetup-windows` (artifact of the "Windows installer" workflow): a folder with
`AppsOnlySetup.exe`, its own Java, `platform-tools` (adb) and `apps-only.apk`. Nothing to install on the PC.

What it does, in order:
1. Finds exactly one phone with USB debugging allowed (tells the person what to do if not).
2. Checks Android 10+, the model list (`installer/src/main/resources/supported-models.json`),
   other management apps, extra users / work profile / second space, and accounts.
3. Installs the app.
4. **Account trick:** switches off the apps that own the phone's accounts (for example Google Play
   services) for a few seconds, so Android allows Device Owner without a factory reset, then switches
   them straight back on. If setup is interrupted, the next run switches them back on first.
5. Makes the app Device Owner and opens it; the customer picks the allowed apps on the phone.
6. Saves a log on the PC (`%LOCALAPPDATA%\AppsOnly\setup-logs`) and uploads it once the server
   exists (step 4; `log_upload_url` in `installer-config.json`). Logs never contain account names.

Running it on a phone that's already set up just updates the app.
Command line (used by CI): `java -jar apps-only-setup.jar --cli [--check] [--yes] [--apk FILE] [--adb FILE]`.

## Step 4: license server and payment gate

Setup is free; after locking, **every app stays closed until the phone is paid**. The home
screen shows a web address and a phone code (like `AOS-7K3D-9QXW`) to pay from a computer or
another phone (the phone itself can't browse), plus Settings for Wi-Fi. The phone checks every
30 seconds while that screen is open, and every 15 minutes in the background.

- `server/` is the server (Node 18+, no dependencies, Web Fetch API so it can move to Cloudflare
  Workers or Firebase; hosting is chosen later). `cd server && npm test`, `npm start`.
- The server signs a small license (device id + status) with ECDSA P-256. The app only trusts
  licenses that verify with the public key built into it, and only for its own device id.
- Once paid, the phone stays unlocked with no internet. Staff can set a phone back to unpaid
  (for example after a refund) through the admin API, which the step 5 portal will use.
- Payments: `PAYMENTS=test` (default, a "Mark as paid" button) or `PAYMENTS=stripe`
  (Stripe Checkout + signed webhook).
- `server/test-keys.json` is a TEST key pair, committed on purpose like the debug keystore.
  Debug builds trust it and talk to `http://10.0.2.2:8787` (the PC, seen from the emulator).
  Release builds need `-PlicenseServer=https://... -PlicensePublicKey=...` and the real private
  key in the server's `LICENSE_PRIVATE_JWK` secret (`npm run keys` makes a pair).
- Test builds can skip payment with `--ez paid true` on the dev lock broadcast.

## Portals, part 1: customer details and removal code (installer)

Plan: see the "Portals plan" doc. Part 1 is done here:

- The Windows installer now asks for the customer's full name, phone number, email, and a removal
  code they choose (typed twice, 6+ characters, no spaces). A new setup won't start without them.
  Command line: `--name --phone --email --removal-code`.
- After Device Owner is set, the installer hands these to the app (`SetupInfoReceiver`, adb-only,
  refused once the phone is locked). Nothing of it goes into the setup log.
- The app sends them to the server (`POST /v1/customer`) when it's online, then deletes them from
  the phone. The server keeps the removal code only as a PBKDF2 hash and never shows it.

## Portals, part 2: server side (no web pages yet)

- One sign-in for everyone (`auth.js`). The owner signs in as `owner` with `OWNER_PASSWORD`;
  staff accounts are stored on the server. The role decides which portal opens (parts 3 and 4).
  Password only, 5 wrong tries lock that user name for 15 minutes, sessions last 12 hours.
- What the portals can do (`services.js`), each step written to the action log with who and why:
  search customers by name, phone or email; remove the filter with the customer's removal code
  (5 wrong codes block removal for that phone for an hour and alert the owner); owner only:
  remove without a code, mark paid, lock again, mark alerts handled.
- Code reset by email: support sends the customer a one-time link (24 hours) at `/reset/<token>`
  to choose a new removal code. Emails are only queued in the log until an email service is picked.
- Alerts for the owner: tamper reports from the phone (`POST /v1/tamper`), failed setups (from the
  installer log), phones unpaid for 3 days, too many wrong removal codes, and code resets.

## Portals, part 3: owner portal

- One sign-in page at `/login` for everyone. The owner lands in the owner portal (`/owner`), staff
  in the staff portal (`/staff`, built in part 4); neither can open the other's pages.
- The owner portal opens on **Alerts that need you**, with Sales, Customers, Staff, Setup logs and
  Action log one tap away. On a customer: mark paid, lock again, remove without the code (type the
  phone code to confirm), and send a code-reset email. Staff accounts: add, turn off, new password.
- Pages are plain server-rendered HTML with no scripts, a strict content policy, and same-site checks
  on every form.
