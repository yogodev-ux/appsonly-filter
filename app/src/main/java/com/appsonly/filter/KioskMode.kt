package com.appsonly.filter

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.util.Log

/**
 * Android "lock task" (kiosk) mode. While it is on, ONLY activities from the packages listed
 * here can open. This closes every side door at once: links into the Play Store, Play Store
 * notifications, Settings shortcuts, and any other app an allowed app tries to open.
 * Quick Settings is also disabled by Android in this mode.
 */
object KioskMode {
    private const val TAG = "AppsOnly"

    /** System screens allowed apps legitimately need (permission prompts, file/photo pickers, share sheet). */
    private val SYSTEM_HELPERS = setOf(
        "android", // share sheet / "open with" chooser
        "com.android.systemui",
        "com.google.android.permissioncontroller", "com.android.permissioncontroller",
        "com.google.android.documentsui", "com.android.documentsui",
        "com.google.android.providers.media.module", "com.android.providers.media.module",
        "com.android.server.telecom", "com.android.phone", // calls
        "com.google.android.gms", // Google sign-in (its internet is limited by the web filter)
    )

    private const val FEATURES =
        DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO or      // clock, battery, signal
            DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS or // notification shade (no Quick Settings)
            DevicePolicyManager.LOCK_TASK_FEATURE_HOME or          // Home button
            DevicePolicyManager.LOCK_TASK_FEATURE_OVERVIEW or      // recent apps
            DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS or // power menu
            DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD          // screen lock / PIN

    fun configure(ctx: Context, dpm: DevicePolicyManager, admin: ComponentName, visible: Set<String>) {
        val pkgs = (visible + SYSTEM_HELPERS + AppScanner.keyboards(ctx) + ctx.packageName).toTypedArray()
        try {
            dpm.setLockTaskPackages(admin, pkgs)
            dpm.setLockTaskFeatures(admin, FEATURES)
        } catch (e: Exception) {
            Log.w(TAG, "lock task setup", e)
        }
    }

    /** Called by the home screen whenever it is shown while locked. */
    private fun bootCount(ctx: Context) =
        android.provider.Settings.Global.getInt(ctx.contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1)

    private fun prefs(ctx: Context) =
        ctx.createDeviceProtectedStorageContext().getSharedPreferences("kiosk", Context.MODE_PRIVATE)

    /** True if kiosk mode was on earlier since this boot and is now off (someone turned it off). */
    fun isOff(activity: Activity): Boolean =
        activity.getSystemService(ActivityManager::class.java).lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE &&
            prefs(activity).getInt("entered_boot", -2) == bootCount(activity)

    fun enter(activity: Activity) {
        val am = activity.getSystemService(ActivityManager::class.java)
        if (am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE) return
        // Test builds only: pretend to be a slow phone, so the escape test can try to get
        // around the lock in the time before kiosk mode starts.
        val wait = testDelayLeft(activity)
        if (wait > 0) {
            activity.window.decorView.postDelayed({ if (!activity.isFinishing) enter(activity) }, wait)
            return
        }
        val dpm = LockPolicy.dpm(activity)
        if (!dpm.isLockTaskPermitted(activity.packageName)) return
        try {
            activity.startLockTask()
            prefs(activity).edit().putInt("entered_boot", bootCount(activity)).apply()
        } catch (e: Exception) { Log.w(TAG, "startLockTask", e) }
    }

    /** Milliseconds left of the test-only "slow phone" delay after boot (0 in real builds). */
    private fun testDelayLeft(ctx: Context): Long {
        if ((ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) return 0
        val delay = prefs(ctx).getLong(TEST_DELAY_KEY, 0L)
        return (delay - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0)
    }

    /** Key read by [testDelayLeft]; written only by the debug-build test hook. */
    const val TEST_DELAY_KEY = "test_kiosk_delay_ms"

    fun testPrefs(ctx: Context) = prefs(ctx)

    fun clear(dpm: DevicePolicyManager, admin: ComponentName) {
        try { dpm.setLockTaskPackages(admin, emptyArray()) } catch (e: Exception) { Log.w(TAG, "lock task clear", e) }
    }
}
