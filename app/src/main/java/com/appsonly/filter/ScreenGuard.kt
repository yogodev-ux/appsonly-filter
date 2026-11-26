package com.appsonly.filter

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * The screen guard. Kiosk mode only checks which app a WINDOW (task) belongs to, so an
 * allowed app can still open another app's screen inside its own window (phone call
 * settings, keyboard settings, Google account screens ...). The guard sees every screen
 * that comes to the front and sends anything that isn't allowed straight back home.
 *
 * It is an accessibility service. The Windows installer gives the app the right to switch
 * it on (WRITE_SECURE_SETTINGS); after that the app keeps it on by itself ([ensureOn]).
 */
class ScreenGuard : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString() ?: return
        if (pkg == "com.android.systemui" && !KioskMode.isOn(this) && Prefs.load(this).locked) {
            // Before kiosk mode is on (slow boot) the shade must stay shut. The status bar is
            // already disabled then; if System UI ever lets it open anyway, close it and
            // disable it again.
            Log.i(TAG, "Screen guard closed the shade before kiosk mode")
            KioskMode.setShadeAllowed(this, false)
            if (android.os.Build.VERSION.SDK_INT >= 31) performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
            else performGlobalAction(GLOBAL_ACTION_BACK)
            logOnce("shade_before_kiosk")
            return
        }
        if (pkg == "com.google.android.gms" && (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) Log.d(TAG, "guard saw $pkg/$cls")
        if (!blocked(this, pkg, cls)) {
            // A pop-up of the blocked app itself doesn't count as being back somewhere allowed.
            if (pkg != blockedPkg) lastAllowedAt = SystemClock.elapsedRealtime()
            return
        }
        Log.i(TAG, "Screen guard closed $pkg/$cls")
        // Back closes the screen itself (Home alone only hides it, and it comes back when
        // the app is opened again). If Back shows another blocked screen, that one is
        // closed the same way, so a pile of them is cleared. If nothing allowed shows up
        // shortly after (the screen ignored Back), go Home.
        val at = SystemClock.elapsedRealtime()
        performGlobalAction(GLOBAL_ACTION_BACK)
        main.removeCallbacks(homeIfStuck)
        blockedAt = at
        blockedPkg = pkg
        main.postDelayed(homeIfStuck, 700)
        logOnce("$pkg/$cls")
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var lastAllowedAt = 0L
    @Volatile private var blockedAt = 0L
    @Volatile private var blockedPkg = ""
    private val homeIfStuck = Runnable { if (lastAllowedAt < blockedAt) performGlobalAction(GLOBAL_ACTION_HOME) }

    override fun onInterrupt() {}

    private var lastLogged = ""
    private var lastLoggedAt = 0L
    private fun logOnce(what: String) {
        val now = SystemClock.elapsedRealtime()
        if (what == lastLogged && now - lastLoggedAt < 60_000) return
        lastLogged = what; lastLoggedAt = now
        TamperLog.record(this, "screen_blocked", what)
    }

    companion object {
        private const val TAG = "AppsOnly"

        /** System parts allowed apps need. Their screens are allowed, minus the settings-like ones below. */
        private val HELPERS = setOf(
            "android", "com.android.systemui", "com.android.intentresolver",
            "com.google.android.permissioncontroller", "com.android.permissioncontroller",
            "com.google.android.documentsui", "com.android.documentsui",
            "com.google.android.providers.media.module", "com.android.providers.media.module",
            "com.android.server.telecom", "com.android.phone",
            "com.google.android.gms",
        )

        /** Screens inside the helpers that lead to settings or account management. */
        private val HELPER_DENY = listOf(
            // permission app: "Default apps", "Home app", app permission manager
            Regex("""permissioncontroller\.(role|permission\.ui\.(ManagePermissions|AppPermission))"""),
            // phone service: call / SIM / radio settings (the emergency dialer stays allowed)
            Regex("""com\.android\.phone\..*(Setting|Options|IccPin|RadioInfo|BandMode|Fdn)"""),
            // Play services: Google Account, Google settings, Find My Device, parental controls
            // (any Play services screen with "settings" in its name, e.g. googlesettings.ui.GoogleSettingsActivity)
            Regex("""com\.google\.android\.gms\..*([Ss]ettings|\.kids\.|\.mdm\.|\.googlehelp\.|\.feedback\.)"""),
        )

        /** Adding an account, allowed only while our own "Add Google account" is open. */
        private val ADD_ACCOUNT = Regex("""com\.google\.android\.gms\.auth\.uiflows\.addaccount\.""")

        private class Allowed(val apps: Set<String>, val keyboards: Set<String>)
        @Volatile private var cache: Pair<Long, Allowed>? = null

        private fun allowed(ctx: Context): Allowed {
            val now = SystemClock.elapsedRealtime()
            cache?.let { if (now - it.first < 10_000) return it.second }
            val a = Allowed(LockPolicy.visiblePackages(ctx, Prefs.load(ctx)) + ctx.packageName, AppScanner.keyboards(ctx))
            cache = now to a
            return a
        }

        /** True if this screen must be closed. */
        fun blocked(ctx: Context, pkg: String, cls: String): Boolean {
            if (!Prefs.load(ctx).locked) return false
            val a = allowed(ctx)
            if (pkg in a.apps) return false
            if (!isActivity(ctx, pkg, cls)) return false // pop-ups, overlays, the keyboard itself, the shade
            if (pkg in a.keyboards) return true // keyboard settings screens
            if (pkg in HELPERS) {
                if (ADD_ACCOUNT.containsMatchIn(cls)) return !AccountFlow.active
                return HELPER_DENY.any { it.containsMatchIn(cls) }
            }
            return true
        }

        private fun isActivity(ctx: Context, pkg: String, cls: String): Boolean = try {
            ctx.packageManager.getActivityInfo(ComponentName(pkg, cls), PackageManager.MATCH_DISABLED_COMPONENTS)
            true
        } catch (e: Exception) { false }

        fun component(ctx: Context) = ComponentName(ctx, ScreenGuard::class.java)

        /** Switches the guard on (and back on) if the installer gave us the right to. */
        fun ensureOn(ctx: Context) {
            val me = component(ctx).flattenToString()
            val cr = ctx.contentResolver
            val current = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val on = current.split(':').any { ComponentName.unflattenFromString(it) == component(ctx) } &&
                Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
            if (on) return
            if (ctx.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Screen guard is off: setup didn't give the app permission to switch it on")
                return
            }
            try {
                val list = (current.split(':').filter { it.isNotBlank() } + me).distinct().joinToString(":")
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list)
                Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                if (Prefs.lockedOnce(ctx) && current.isNotEmpty()) TamperLog.record(ctx, "screen_guard_restored")
                Log.i(TAG, "Screen guard switched on")
            } catch (e: Exception) { Log.w(TAG, "screen guard on", e) }
        }

        fun isOn(ctx: Context): Boolean =
            Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
                .split(':').any { ComponentName.unflattenFromString(it) == component(ctx) }
    }
}
