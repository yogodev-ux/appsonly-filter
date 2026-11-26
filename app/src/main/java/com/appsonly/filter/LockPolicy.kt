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
        // The real Settings app is hidden; these are a second wall in case any part of it
        // is ever reachable:
        UserManager.DISALLOW_FACTORY_RESET,
        UserManager.DISALLOW_SAFE_BOOT,
        UserManager.DISALLOW_ADD_USER,
        UserManager.DISALLOW_REMOVE_USER,
        UserManager.DISALLOW_MODIFY_ACCOUNTS,
        UserManager.DISALLOW_CONFIG_VPN,
        UserManager.DISALLOW_CONFIG_DATE_TIME,
        UserManager.DISALLOW_CONFIG_CREDENTIALS,
        UserManager.DISALLOW_NETWORK_RESET,
        UserManager.DISALLOW_CONFIG_PRIVATE_DNS, // keep DNS going through our web filter
    )

    /**
     * Blocks Developer options and USB debugging. Left off in debug (test) builds,
     * because testing and the developer unlock need adb.
     */
    const val DEBUG_RESTRICTION = UserManager.DISALLOW_DEBUGGING_FEATURES

    private fun isTestBuild(ctx: Context) =
        (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private fun activeRestrictions(ctx: Context) =
        if (isTestBuild(ctx)) RESTRICTIONS else RESTRICTIONS + DEBUG_RESTRICTION

    fun dpm(ctx: Context): DevicePolicyManager =
        ctx.getSystemService(DevicePolicyManager::class.java)

    fun isDeviceOwner(ctx: Context) = dpm(ctx).isDeviceOwnerApp(ctx.packageName)

    /** Packages the customer can see and use once locked. */
    fun visiblePackages(ctx: Context, cfg: LockConfig): Set<String> {
        // Payment gate (step 4): nothing opens until the phone is paid.
        if (cfg.locked && !License.isPaid(ctx)) return emptySet()
        val result = cfg.allowedPackages.toMutableSet()
        if (cfg.allowPhone) result += AppScanner.phonePackages(ctx)
        if (cfg.allowSms) result += AppScanner.smsPackages(ctx)
        // Browsers are never allowed, even if they also act as a dialer or similar.
        return result - AppScanner.browsers(ctx)
    }

    /** Apps hidden during the current enforce run (logged as tamper events after the first lock). */
    private val lastHidden = mutableListOf<String>()

    @Synchronized
    fun enforce(ctx: Context) {
        val cfg = Prefs.load(ctx)
        if (!cfg.locked || !isDeviceOwner(ctx)) return
        if (BuildConfig.DEBUG) {
            // Which code path asked for this run (helps the automated "no loop" check).
            val from = Throwable().stackTrace.drop(1).take(4)
                .joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}" }
            Log.d(TAG, "enforce from $from")
        }
        lastHidden.clear()
        try { enforceLocked(ctx, cfg) } finally {
            if (lastHidden.isNotEmpty() && Prefs.lockedOnce(ctx)) {
                TamperLog.record(ctx, "new_app_hidden", lastHidden.joinToString())
            }
            Prefs.markLockedOnce(ctx)
        }
    }

    private fun enforceLocked(ctx: Context, cfg: LockConfig) {
        val dpm = dpm(ctx)
        val admin = AdminReceiver.component(ctx)

        // 1. User restrictions. Account changes stay allowed only while the Google
        //    sign-in started from our Settings screen is open.
        for (r in activeRestrictions(ctx)) {
            if (r == UserManager.DISALLOW_MODIFY_ACCOUNTS && AccountFlow.active) continue
            try { dpm.addUserRestriction(admin, r) } catch (e: Exception) { Log.w(TAG, "restriction $r", e) }
        }

        // 2. We are the only home screen, and we can't be uninstalled.
        setAsHome(ctx, dpm, admin)
        dpm.setUninstallBlocked(admin, ctx.packageName, true)

        // 3. Hide / suspend everything that isn't allowed.
        val visible = visiblePackages(ctx, cfg)
        val untouchable = AppScanner.untouchable(ctx)
        val webView = AppScanner.webViewPackage()
        // The real Settings app is hidden too; our own Settings screen replaces it.
        // Any app a user installed is a target too, even one without an icon (for example one
        // installed remotely from the Play Store website), so it can't run in the background.
        val targets = AppScanner.launchable(ctx) + AppScanner.browsers(ctx) +
            AppScanner.homeApps(ctx) + AppScanner.PLAY_STORE + AppScanner.settingsPackage(ctx) +
            AppScanner.userInstalled(ctx)

        val toSuspend = mutableListOf<String>()
        for (pkg in targets) {
            if (pkg in visible || pkg in untouchable) continue
            // The Play Store must keep running so it can update the allowed apps, so it is
            // never hidden (see below). If a browser is also the WebView provider, suspend it
            // instead of hiding it so web content inside allowed apps keeps working.
            when (pkg) {
                AppScanner.PLAY_STORE -> toSuspend += pkg
                webView -> toSuspend += pkg
                else -> hide(dpm, admin, pkg)
            }
        }
        if (toSuspend.isNotEmpty()) {
            val failed = try {
                dpm.setPackagesSuspended(admin, toSuspend.toTypedArray(), true).toList()
            } catch (e: Exception) {
                Log.w(TAG, "suspend failed", e); toSuspend
            }
            // Many phones refuse to suspend the Play Store (it is the system's app verifier).
            // Then it stays enabled, but it has no icon and every link into it ("market://",
            // play.google.com) opens our "not available" screen instead. Its internet is
            // limited to Google/Play addresses by the web filter.
            if (AppScanner.PLAY_STORE in failed) {
                Log.i(TAG, "Play Store can't be suspended here; using store guard instead")
                try {
                    if (dpm.isApplicationHidden(admin, AppScanner.PLAY_STORE)) {
                        dpm.setApplicationHidden(admin, AppScanner.PLAY_STORE, false)
                    }
                } catch (e: Exception) { Log.w(TAG, "unhide store", e) }
            }
        }
        StoreGuard.apply(ctx, dpm, admin)
        KioskMode.configure(ctx, dpm, admin, visible)
        TamperGuard.apply(ctx, dpm, admin)

        // 4. Device Owner extras for our own Settings screen and web filter.
        DeviceControls.prepare(ctx)
        WebFilter.makeAlwaysOn(ctx)

        // 5. Allowed apps stay put: make sure they are visible and can't be uninstalled.
        for (pkg in visible) {
            try {
                if (dpm.isApplicationHidden(admin, pkg)) dpm.setApplicationHidden(admin, pkg, false)
                dpm.setUninstallBlocked(admin, pkg, true)
            } catch (e: Exception) { Log.w(TAG, "allow $pkg", e) }
        }
    }

    private fun hide(dpm: DevicePolicyManager, admin: ComponentName, pkg: String) {
        try {
            if (dpm.isApplicationHidden(admin, pkg)) return
            if (dpm.setApplicationHidden(admin, pkg, true)) lastHidden += pkg
            else Log.w(TAG, "could not hide $pkg")
        } catch (e: Exception) {
            Log.w(TAG, "hide $pkg", e)
        }
    }

    private fun setAsHome(ctx: Context, dpm: DevicePolicyManager, admin: ComponentName) {
        val filter = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        val home = ComponentName(ctx, MainActivity::class.java)
        @Suppress("DEPRECATION")
        val current = ctx.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
        if (current?.activityInfo?.name == home.className && current.activityInfo.packageName == home.packageName) return
        dpm.addPersistentPreferredActivity(admin, filter, home)
    }

    /**
     * Undo everything. Called by License.removeFilter: when the portal sends a signed "removed"
     * license, or the customer types the right uninstall code. Also the developer unlock in
     * debug builds.
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
        for (r in RESTRICTIONS + DEBUG_RESTRICTION) {
            try { dpm.clearUserRestriction(admin, r) } catch (e: Exception) { Log.w(TAG, "clear $r", e) }
        }
        dpm.clearPackagePersistentPreferredActivities(admin, ctx.packageName)
        KioskMode.clear(dpm, admin)
        TamperGuard.cancelWatchdog(ctx)
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            try { dpm.setUserControlDisabledPackages(admin, emptyList()) } catch (e: Exception) { Log.w(TAG, "user control", e) }
            try { dpm.setFactoryResetProtectionPolicy(admin, null) } catch (e: Exception) { Log.w(TAG, "frp clear", e) }
        }
        try { dpm.setAlwaysOnVpnPackage(admin, null, false) } catch (e: Exception) { Log.w(TAG, "vpn off", e) }
        Prefs.clear(ctx)
        dpm.clearDeviceOwnerApp(ctx.packageName)
    }
}
