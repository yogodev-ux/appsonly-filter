package com.appsonly.filter.dev

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.appsonly.filter.LockPolicy
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
