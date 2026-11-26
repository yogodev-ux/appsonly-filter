package com.appsonly.filter.dev

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.appsonly.filter.AdminReceiver
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
        else "unsupported"
        resultData = "user_control_disabled=$userControl"
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
