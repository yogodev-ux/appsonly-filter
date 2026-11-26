package com.appsonly.filter

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.admin.DevicePolicyManager
import android.app.admin.FactoryResetProtectionPolicy
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * Step 2: tamper protection on top of the basic lock.
 *  - Factory Reset Protection tied to the company account (recovery-mode resets)
 *  - automatic date/time and time zone, locked
 *  - USB debugging forced off in release builds
 *  - the app can't be force-stopped or have its data cleared by the user
 *  - a watchdog job re-applies everything (and restarts the web filter) every 15 minutes
 *  - tamper attempts are written to [TamperLog]
 */
object TamperGuard {
    private const val TAG = "AppsOnly"
    private const val JOB_ID = 4242
    private const val PREF_LAST_EXIT_CHECK = "last_exit_check"

    fun isTestBuild(ctx: Context) = (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    fun apply(ctx: Context, dpm: DevicePolicyManager, admin: ComponentName) {
        // The user can't force-stop us or clear our data (Android 11+).
        if (Build.VERSION.SDK_INT >= 30) {
            try { dpm.setUserControlDisabledPackages(admin, listOf(ctx.packageName)) }
            catch (e: Exception) { Log.w(TAG, "user control", e) }
        }
        // Date and time always come from the network.
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                if (!dpm.getAutoTimeEnabled(admin)) {
                    dpm.setAutoTimeEnabled(admin, true)
                    TamperLog.record(ctx, "auto_time_restored")
                }
                if (!dpm.getAutoTimeZoneEnabled(admin)) dpm.setAutoTimeZoneEnabled(admin, true)
            } catch (e: Exception) { Log.w(TAG, "auto time", e) }
        } else {
            try { dpm.setGlobalSetting(admin, Settings.Global.AUTO_TIME, "1") } catch (e: Exception) { Log.w(TAG, "auto time", e) }
        }
        // USB debugging off in release builds (test builds keep it for adb).
        if (!isTestBuild(ctx)) {
            val adbOn = Settings.Global.getInt(ctx.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
            if (adbOn) {
                TamperLog.record(ctx, "usb_debugging_turned_off")
                try { dpm.setGlobalSetting(admin, Settings.Global.ADB_ENABLED, "0") } catch (e: Exception) { Log.w(TAG, "adb off", e) }
            }
        }
        applyFrp(ctx, dpm, admin, TamperConfig.FRP_ACCOUNT_IDS)
        scheduleWatchdog(ctx)
        checkHowWeLastStopped(ctx)
    }

    fun applyFrp(ctx: Context, dpm: DevicePolicyManager, admin: ComponentName, accounts: List<String>) {
        if (accounts.isEmpty() || Build.VERSION.SDK_INT < 30) return
        try {
            val policy = FactoryResetProtectionPolicy.Builder()
                .setFactoryResetProtectionAccounts(accounts)
                .setFactoryResetProtectionEnabled(true)
                .build()
            dpm.setFactoryResetProtectionPolicy(admin, policy)
            Log.i(TAG, "FRP set for ${accounts.size} account(s)")
        } catch (e: UnsupportedOperationException) {
            Log.i(TAG, "FRP not supported on this device")
        } catch (e: Exception) {
            Log.w(TAG, "FRP", e)
        }
    }

    /** Logs it if the app was force-stopped or killed since we last looked (Android 11+). */
    private fun checkHowWeLastStopped(ctx: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        val prefs = ctx.createDeviceProtectedStorageContext().getSharedPreferences("tamper", Context.MODE_PRIVATE)
        val since = prefs.getLong(PREF_LAST_EXIT_CHECK, 0L)
        try {
            val am = ctx.getSystemService(ActivityManager::class.java)
            for (info in am.getHistoricalProcessExitReasons(ctx.packageName, 0, 10)) {
                if (info.timestamp <= since) continue
                val what = when (info.reason) {
                    ApplicationExitInfo.REASON_USER_REQUESTED -> "app_force_stopped"
                    ApplicationExitInfo.REASON_USER_STOPPED -> "app_stopped_by_user"
                    ApplicationExitInfo.REASON_SIGNALED -> "app_killed"
                    ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "app_permission_changed"
                    else -> null
                }
                if (what != null) TamperLog.record(ctx, what, info.description ?: "")
            }
        } catch (e: Exception) { Log.w(TAG, "exit reasons", e) }
        prefs.edit().putLong(PREF_LAST_EXIT_CHECK, System.currentTimeMillis()).apply()
    }

    private fun scheduleWatchdog(ctx: Context) {
        val js = ctx.getSystemService(JobScheduler::class.java)
        if (js.getPendingJob(JOB_ID) != null) return
        val job = JobInfo.Builder(JOB_ID, ComponentName(ctx, WatchdogJob::class.java))
            .setPeriodic(15 * 60 * 1000L)
            .setPersisted(true)
            .build()
        js.schedule(job)
    }

    fun cancelWatchdog(ctx: Context) {
        ctx.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
    }
}

/** Every 15 minutes: re-apply the lock and make sure the web filter runs. */
class WatchdogJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            try {
                LockPolicy.enforce(applicationContext)
            } finally {
                jobFinished(params, false)
            }
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters) = true
}
