package com.appsonly.filter

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Process
import android.util.Log
import org.json.JSONObject

/**
 * Decides which web addresses (host names) each app may reach.
 *
 * Every app's DNS lookups go through [FilterVpnService]. A lookup for a name that isn't on
 * that app's allow-list gets "no such site", so in-app browsers, Google sign-in links and the
 * like can't open other sites. Lists live in assets/web_rules.json (later: from the server).
 */
object WebFilter {
    const val TAG = "AppsOnlyFilter"

    /**
     * true  = every allowed app may only reach the names on its own list (plus common services);
     *         an app with no list gets no internet until one is added.
     * false = allowed apps are unfiltered; Google sign-in and system apps are still restricted.
     */
    const val STRICT_FOR_ALL_APPS = true

    private val GOOGLE_UID_PACKAGES = setOf("com.google.android.gms", "com.google.android.gsf")

    private class Rules(
        val googleCore: List<Regex>,
        val playStore: List<Regex>,
        val common: List<Regex>,
        val apps: Map<String, List<Regex>>,
    )

    @Volatile private var rules: Rules? = null

    private fun compile(pattern: String): Regex {
        val p = pattern.trim().lowercase()
        val sub = p.startsWith("*.")
        val body = (if (sub) p.substring(2) else p)
            .split('.').joinToString("\\.") { label ->
                label.split('*').joinToString("[a-z0-9-]*") { Regex.escape(it) }
            }
        return Regex(if (sub) "^(.+\\.)?$body$" else "^$body$")
    }

    private fun list(o: JSONObject, key: String): List<Regex> {
        val a = o.optJSONArray(key) ?: return emptyList()
        return (0 until a.length()).map { compile(a.getString(it)) }
    }

    private fun rules(ctx: Context): Rules = rules ?: synchronized(this) {
        rules ?: run {
            val json = JSONObject(ctx.assets.open("web_rules.json").bufferedReader().use { it.readText() })
            val appsObj = json.optJSONObject("apps") ?: JSONObject()
            val apps = appsObj.keys().asSequence().associateWith { pkg ->
                val a = appsObj.getJSONArray(pkg)
                (0 until a.length()).map { compile(a.getString(it)) }
            }
            Rules(list(json, "google_core"), list(json, "play_store"), list(json, "common_for_allowed_apps"), apps)
                .also { rules = it }
        }
    }

    private fun List<Regex>.matches(host: String) = any { it.matches(host) }

    /** Should [uid] be allowed to look up [host]? */
    fun allowed(ctx: Context, uid: Int, host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        // System services (network stack, time sync, ...) are not apps and can't browse.
        if (uid in 0 until Process.FIRST_APPLICATION_UID) return true
        val r = rules(ctx)
        val pkgs = if (uid < 0) null else ctx.packageManager.getPackagesForUid(uid)
        if (pkgs == null) return r.googleCore.matches(h)
        if (ctx.packageName in pkgs) return true

        // Google Play services hosts Google sign-in: Google's sign-in/infrastructure only.
        if (pkgs.any { it in GOOGLE_UID_PACKAGES }) return r.googleCore.matches(h)
        if (AppScanner.PLAY_STORE in pkgs) return r.playStore.matches(h)

        val cfg = Prefs.load(ctx)
        val visible = LockPolicy.visiblePackages(ctx, cfg)
        val allowedPkgs = pkgs.filter { it in visible }
        if (allowedPkgs.isNotEmpty()) {
            if (!STRICT_FOR_ALL_APPS) return true
            if (r.common.matches(h)) return true
            return allowedPkgs.any { r.apps[it]?.matches(h) == true }
        }
        // Anything else (background system apps): Google infrastructure only.
        val isSystem = pkgs.any { pkg ->
            try {
                (ctx.packageManager.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_SYSTEM) != 0
            } catch (e: Exception) { false }
        }
        return isSystem && r.googleCore.matches(h)
    }

    fun logBlocked(ctx: Context, uid: Int, host: String) {
        if ((ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
        val who = ctx.packageManager.getPackagesForUid(uid)?.joinToString() ?: "uid $uid"
        Log.i(TAG, "BLOCKED $host  ($who)")
    }

    /** Make our filter an always-on VPN the customer can't switch off. */
    fun makeAlwaysOn(ctx: Context) {
        try {
            val dpm = LockPolicy.dpm(ctx)
            val admin = AdminReceiver.component(ctx)
            if (dpm.getAlwaysOnVpnPackage(admin) != ctx.packageName) {
                // Lockdown is off because the filter only carries DNS lookups; normal traffic
                // goes straight out. The customer can't turn it off (VPN settings are blocked).
                dpm.setAlwaysOnVpnPackage(admin, ctx.packageName, false)
            }
            ctx.startService(android.content.Intent(ctx, FilterVpnService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "always-on VPN", e)
        }
    }
}
