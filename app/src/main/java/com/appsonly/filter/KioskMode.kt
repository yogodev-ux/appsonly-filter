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
        // Permission prompts and the photo picker are NOT listed: they open inside the app
        // that asks, which is all they need. Listed, the permission app's "Default apps" and
        // "Home app" screens could be opened on their own.
        "com.google.android.documentsui", "com.android.documentsui",
        "com.android.server.telecom", "com.android.phone", // calls
        "com.android.intentresolver", // share sheet on Android 13+
        // Google Play services is NOT listed. Its screens still open inside an allowed app or
        // inside our own sign-in window (same task), which is all sign-in needs. Listed, it
        // could open its own window from a notification or link: the full "Google Account"
        // and "Google settings" screens, which lead on to other Google pages.
    )

    private const val FEATURES =
        DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO or      // clock, battery, signal
            DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS or // notification shade (no Quick Settings)
            DevicePolicyManager.LOCK_TASK_FEATURE_HOME or          // Home button
            DevicePolicyManager.LOCK_TASK_FEATURE_OVERVIEW or      // recent apps
            DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS or // power menu
            DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD          // screen lock / PIN

    fun configure(ctx: Context, dpm: DevicePolicyManager, admin: ComponentName, visible: Set<String>) {
        hideSideDoors(ctx, dpm, admin, visible)
        ScreenGuard.ensureOn(ctx)
        val pkgs = (visible + SYSTEM_HELPERS + AppScanner.keyboards(ctx) + ctx.packageName).toTypedArray()
        try {
            dpm.setLockTaskPackages(admin, pkgs)
            dpm.setLockTaskFeatures(admin, FEATURES)
        } catch (e: Exception) {
            Log.w(TAG, "lock task setup", e)
        }
        // Outside kiosk mode (for example right after a reboot, before our home screen has
        // started it) the notification shade and Quick Settings stay switched off.
        if (ctx.getSystemService(ActivityManager::class.java).lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE) {
            setShadeAllowed(ctx, false)
        }
    }

    /**
     * Kiosk mode itself keeps Quick Settings off and allows only the notification list, but
     * kiosk mode ends with every reboot. So the status bar is switched off completely
     * whenever kiosk mode is off; Android remembers this across a reboot, so a phone starts
     * up with the status bar already off. It is switched back on once kiosk mode is on.
     */
    fun setShadeAllowed(ctx: Context, allowed: Boolean) {
        try {
            val dpm = LockPolicy.dpm(ctx)
            if (!dpm.isDeviceOwnerApp(ctx.packageName)) return
            if (!dpm.setStatusBarDisabled(AdminReceiver.component(ctx), !allowed)) Log.w(TAG, "status bar not changed")
        } catch (e: Exception) { Log.w(TAG, "status bar", e) }
    }

    fun isOn(ctx: Context) =
        ctx.getSystemService(ActivityManager::class.java).lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE

    /**
     * System apps with no home-screen icon whose screens an allowed app could still open
     * inside its own window (the escape test found each of these). None is needed on a
     * locked phone, so they are hidden like any other app. Emergency alerts, the phone
     * service, keyboards and accessibility are deliberately left alone.
     */
    val SIDE_DOORS = setOf(
        "com.android.egg",                          // Android version easter egg / games
        "com.google.android.apps.restore",          // "copy data / WhatsApp import" screens
        "com.google.android.apps.wallpaper",        // wallpaper & style picker
        "com.android.wallpaper.livepicker",
        "com.google.android.apps.wellbeing",        // Digital Wellbeing settings
        "com.google.android.settings.intelligence", // Settings search and suggestions
        "com.google.android.projection.gearhead",   // Android Auto
        "com.google.android.apps.tips",
        "com.google.android.feedback",
        "com.android.traceur",                      // system tracing (developer tool)
        "com.google.android.apps.safetyhub",
    )

    private fun hideSideDoors(ctx: Context, dpm: DevicePolicyManager, admin: ComponentName, visible: Set<String>) {
        for (pkg in SIDE_DOORS - visible) {
            try {
                ctx.packageManager.getApplicationInfo(pkg, android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES)
                if (!dpm.isApplicationHidden(admin, pkg) && !dpm.setApplicationHidden(admin, pkg, true)) Log.w(TAG, "could not hide $pkg")
            } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
            } catch (e: Exception) { Log.w(TAG, "hide $pkg", e) }
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
            if (isOn(activity)) setShadeAllowed(activity, true)
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
        try { dpm.setStatusBarDisabled(admin, false) } catch (e: Exception) { Log.w(TAG, "status bar clear", e) }
    }
}
