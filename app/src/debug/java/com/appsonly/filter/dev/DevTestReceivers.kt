package com.appsonly.filter.dev

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.appsonly.filter.AdminReceiver
import com.appsonly.filter.License
import com.appsonly.filter.LockPolicy
import com.appsonly.filter.TamperGuard
import com.appsonly.filter.TamperLog
import com.appsonly.filter.Prefs
import com.appsonly.filter.WebFilter

/**
 * DEBUG BUILDS ONLY: hooks for the automated emulator test (scripts/emulator-test.sh).
 * Protected by the DUMP permission, so only adb can send them.
 *
 * Lock without tapping through the setup screen:
 *   adb shell am broadcast -n com.appsonly.filter/.dev.DevLockReceiver \
 *       --es allowed com.google.android.deskclock,com.google.android.calculator --ez phone true
 */
class DevLockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        val allowed = intent.getStringExtra("allowed").orEmpty()
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        // --ez paid true skips the payment gate (step 4) for tests of the lock itself.
        License.devSetPaid(ctx, intent.getBooleanExtra("paid", false))
        Prefs.saveLocked(ctx, allowed, intent.getBooleanExtra("phone", false), intent.getBooleanExtra("sms", false))
        val pending = goAsync()
        Thread {
            try {
                LockPolicy.enforce(ctx)
                // Optional: test Factory Reset Protection with a dummy account ID.
                intent.getStringExtra("frp")?.let {
                    TamperGuard.applyFrp(ctx, LockPolicy.dpm(ctx), AdminReceiver.component(ctx), listOf(it))
                }
                Log.i("AppsOnly", "DEV_LOCK done: $allowed")
                pending.resultData = "LOCKED"
            } finally {
                pending.finish()
            }
        }.start()
    }
}

/**
 * Asks the web filter whether a package may look up a host name:
 *   adb shell am broadcast -n com.appsonly.filter/.dev.DevCheckReceiver --es pkg com.google.android.gms --es host example.com
 * Result data is ALLOWED or BLOCKED.
 */
class DevCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pkg = intent.getStringExtra("pkg").orEmpty()
        val host = intent.getStringExtra("host").orEmpty()
        val uid = try { context.packageManager.getPackageUid(pkg, 0) } catch (e: Exception) { -1 }
        val verdict = if (WebFilter.allowed(context, uid, host)) "ALLOWED" else "BLOCKED"
        Log.i("AppsOnly", "CHECK $pkg $host -> $verdict")
        resultData = verdict
    }
}

/**
 * Returns the tamper log (last 30 lines) as result data:
 *   adb shell am broadcast -n com.appsonly.filter/.dev.DevTamperLogReceiver
 */
class DevTamperLogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // --es event <name>: record a test tamper event first (to test the reports to the server).
        intent.getStringExtra("event")?.let { TamperLog.record(context, it, "test") }
        resultData = TamperLog.read(context).takeLast(30).joinToString(" | ")
    }
}

/**
 * Reports protection state the shell can't read reliably on every Android version:
 *   adb shell am broadcast -n com.appsonly.filter/.dev.DevStatusReceiver
 */
class DevStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val dpm = LockPolicy.dpm(context)
        val admin = AdminReceiver.component(context)
        val userControl = if (android.os.Build.VERSION.SDK_INT >= 30)
            try { dpm.getUserControlDisabledPackages(admin).joinToString(",") } catch (e: Exception) { "error:${e.javaClass.simpleName}" }
        // Android 10 has no per-app call; there the "no app control" restriction stops force-stop
        // and clear-data in Settings for every app, ours included.
        else if (dpm.getUserRestrictions(admin).getBoolean(android.os.UserManager.DISALLOW_APPS_CONTROL))
            "apps_control_restriction:${context.packageName}"
        else "unsupported"
        val um = context.getSystemService(android.os.UserManager::class.java)
        resultData = "user_control_disabled=$userControl install_apps_blocked=" +
            um.hasUserRestriction(android.os.UserManager.DISALLOW_INSTALL_APPS)
    }
}

/**
 * Google sign-in escape test hook. Optionally shortens the sign-in timeout, and reports
 * whether the sign-in window is still open:
 *   adb shell am broadcast -n com.appsonly.filter/.dev.DevSignInReceiver --el timeout_ms 20000
 * Result data is "active=true|false timeout_ms=N".
 */
class DevSignInReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val t = intent.getLongExtra("timeout_ms", -1L)
        if (t > 0) com.appsonly.filter.AccountFlow.timeoutMs = t
        resultData = "active=${com.appsonly.filter.AccountFlow.active} timeout_ms=${com.appsonly.filter.AccountFlow.timeoutMs}"
    }
}

/**
 * Pretends to be a slow phone: after the next boot, kiosk mode starts only once the phone
 * has been up for this many milliseconds (0 turns it off):
 *   adb shell am broadcast -n com.appsonly.filter/.dev.DevKioskDelayReceiver --el ms 60000
 */
class DevKioskDelayReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ms = intent.getLongExtra("ms", 0L)
        com.appsonly.filter.KioskMode.testPrefs(context).edit()
            .putLong(com.appsonly.filter.KioskMode.TEST_DELAY_KEY, ms).commit()
        resultData = "DELAY=$ms"
    }
}

/**
 * Scans an app's install files for the websites it needs and returns a summary:
 *   adb shell am broadcast -n com.appsonly.filter/.dev.DevScanReceiver --es pkg com.example
 * Result data: "own=a.com,b.net exact=12 dropped=3". The full lists go to logcat ("SCAN").
 */
class DevScanReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pkg = intent.getStringExtra("pkg").orEmpty()
        val pending = goAsync()
        Thread {
            val r = com.appsonly.filter.AppDomains.get(context, pkg)
            if (r == null) {
                pending.resultData = "none"
            } else {
                Log.i("AppsOnly", "SCAN $pkg own: ${r.own.sorted().joinToString()}")
                Log.i("AppsOnly", "SCAN $pkg exact: ${r.exact.sorted().take(200).joinToString()}")
                Log.i("AppsOnly", "SCAN $pkg dropped: ${r.dropped.sorted().take(200).joinToString()}")
                pending.resultData = "own=${r.own.sorted().joinToString(",")} exact=${r.exact.size} dropped=${r.dropped.size}"
            }
            pending.finish()
        }.start()
    }
}
