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
    fun enter(activity: Activity) {
        val am = activity.getSystemService(ActivityManager::class.java)
        if (am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE) return
        val dpm = LockPolicy.dpm(activity)
        if (!dpm.isLockTaskPermitted(activity.packageName)) return
        try { activity.startLockTask() } catch (e: Exception) { Log.w(TAG, "startLockTask", e) }
    }

    fun clear(dpm: DevicePolicyManager, admin: ComponentName) {
        try { dpm.setLockTaskPackages(admin, emptyArray()) } catch (e: Exception) { Log.w(TAG, "lock task clear", e) }
    }
}
