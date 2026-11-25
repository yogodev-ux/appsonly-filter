package com.appsonly.filter

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.UserManager
import android.util.Log

/**
 * Applies the lock. Safe to run again and again: every run brings the phone back to the
 * locked state (for example after a reboot or when a new app shows up).
 */
object LockPolicy {
    private const val TAG = "AppsOnly"

    val RESTRICTIONS = listOf(
        UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
        UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY,
        UserManager.DISALLOW_UNINSTALL_APPS,
        UserManager.DISALLOW_APPS_CONTROL, // no force-stop, clear data or disable from Settings
    )

    fun dpm(ctx: Context): DevicePolicyManager =
        ctx.getSystemService(DevicePolicyManager::class.java)

    fun isDeviceOwner(ctx: Context) = dpm(ctx).isDeviceOwnerApp(ctx.packageName)

    /** Packages the customer can see and use once locked. */
    fun visiblePackages(ctx: Context, cfg: LockConfig): Set<String> {
        val result = cfg.allowedPackages.toMutableSet()
        if (cfg.allowPhone) result += AppScanner.phonePackages(ctx)
        if (cfg.allowSms) result += AppScanner.smsPackages(ctx)
        // Browsers are never allowed, even if they also act as a dialer or similar.
        return result - AppScanner.browsers(ctx)
    }

    @Synchronized
    fun enforce(ctx: Context) {
        val cfg = Prefs.load(ctx)
        if (!cfg.locked || !isDeviceOwner(ctx)) return
        val dpm = dpm(ctx)
        val admin = AdminReceiver.component(ctx)

        // 1. User restrictions.
        for (r in RESTRICTIONS) {
            try { dpm.addUserRestriction(admin, r) } catch (e: Exception) { Log.w(TAG, "restriction $r", e) }
        }

        // 2. We are the only home screen, and we can't be uninstalled.
        setAsHome(ctx, dpm, admin)
        dpm.setUninstallBlocked(admin, ctx.packageName, true)

        // 3. Hide / suspend everything that isn't allowed.
        val visible = visiblePackages(ctx, cfg)
        val untouchable = AppScanner.untouchable(ctx)
        val settings = AppScanner.settingsPackage(ctx)
        val webView = AppScanner.webViewPackage()
        val targets = AppScanner.launchable(ctx) + AppScanner.browsers(ctx) +
            AppScanner.homeApps(ctx) + AppScanner.PLAY_STORE + settings

        val toSuspend = mutableListOf<String>()
        for (pkg in targets) {
            if (pkg in visible || pkg in untouchable) continue
            // The Play Store stays installed and running (suspended, so the customer can't
            // open it) so it can keep updating the allowed apps in the background.
            // Settings is suspended rather than hidden because system dialogs live in it.
            // If a browser is also the WebView provider, suspend it so web content keeps working.
            if (pkg == AppScanner.PLAY_STORE || pkg == settings || pkg == webView) {
                toSuspend += pkg
            } else {
                hide(dpm, admin, pkg)
            }
        }
        if (toSuspend.isNotEmpty()) {
            val failed = try {
                dpm.setPackagesSuspended(admin, toSuspend.toTypedArray(), true).toList()
            } catch (e: Exception) {
                Log.w(TAG, "suspend failed", e); toSuspend
            }
            // Couldn't suspend? Hide instead (never the WebView provider).
            failed.filter { it != webView }.forEach { hide(dpm, admin, it) }
        }

        // 4. Allowed apps stay put: make sure they are visible and can't be uninstalled.
        for (pkg in visible) {
            try {
                if (dpm.isApplicationHidden(admin, pkg)) dpm.setApplicationHidden(admin, pkg, false)
                dpm.setUninstallBlocked(admin, pkg, true)
            } catch (e: Exception) { Log.w(TAG, "allow $pkg", e) }
        }
    }

    private fun hide(dpm: DevicePolicyManager, admin: ComponentName, pkg: String) {
        try {
            if (!dpm.setApplicationHidden(admin, pkg, true)) Log.w(TAG, "could not hide $pkg")
        } catch (e: Exception) {
            Log.w(TAG, "hide $pkg", e)
        }
    }

    private fun setAsHome(ctx: Context, dpm: DevicePolicyManager, admin: ComponentName) {
        val filter = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        dpm.clearPackagePersistentPreferredActivities(admin, ctx.packageName)
        dpm.addPersistentPreferredActivity(admin, filter, ComponentName(ctx, MainActivity::class.java))
    }

    /**
     * Undo everything. Used only by the developer unlock in debug builds; the release app
     * has no on-phone way to call this (later: the support portal).
     */
    @Suppress("DEPRECATION")
    fun removeEverything(ctx: Context) {
        if (!isDeviceOwner(ctx)) return
        val dpm = dpm(ctx)
        val admin = AdminReceiver.component(ctx)
        val all = ctx.packageManager
            .getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES)
            .map(ApplicationInfo::packageName)
        for (pkg in all) {
            try {
                if (dpm.isApplicationHidden(admin, pkg)) dpm.setApplicationHidden(admin, pkg, false)
                if (dpm.isUninstallBlocked(admin, pkg)) dpm.setUninstallBlocked(admin, pkg, false)
            } catch (e: Exception) { Log.w(TAG, "restore $pkg", e) }
        }
        try { dpm.setPackagesSuspended(admin, all.toTypedArray(), false) } catch (e: Exception) { Log.w(TAG, "unsuspend", e) }
        for (r in RESTRICTIONS) {
            try { dpm.clearUserRestriction(admin, r) } catch (e: Exception) { Log.w(TAG, "clear $r", e) }
        }
        dpm.clearPackagePersistentPreferredActivities(admin, ctx.packageName)
        Prefs.clear(ctx)
        dpm.clearDeviceOwnerApp(ctx.packageName)
    }
}
