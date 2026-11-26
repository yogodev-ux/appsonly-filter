package com.appsonly.filter

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView

/** Finds the groups of installed apps the lock policy cares about. */
@Suppress("DEPRECATION") // int-flag PackageManager overloads are fine for minSdk 29
object AppScanner {

    const val PLAY_STORE = "com.android.vending"

    /** Packages that must never be hidden or suspended, or the phone stops working. */
    private val CORE = setOf(
        "android",
        "com.android.systemui",
        "com.android.phone",
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.android.webview",
        "com.google.android.webview",
        "com.google.android.trichromelibrary",
    )

    private fun query(ctx: Context, intent: Intent, flags: Int = 0): Set<String> =
        ctx.packageManager.queryIntentActivities(intent, flags)
            .map { it.activityInfo.packageName }
            .toSet()

    /** Apps that show an icon in a normal home screen. */
    fun launchable(ctx: Context): Set<String> =
        query(ctx, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER))

    /** Anything that can open an arbitrary web page. */
    fun browsers(ctx: Context): Set<String> {
        val result = mutableSetOf<String>()
        for (scheme in listOf("http", "https")) {
            val view = Intent(Intent.ACTION_VIEW, Uri.parse("$scheme://example.invalid/"))
                .addCategory(Intent.CATEGORY_BROWSABLE)
            result += query(ctx, view, PackageManager.MATCH_ALL)
        }
        result += query(
            ctx,
            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER),
            PackageManager.MATCH_ALL,
        )
        return result
    }

    /** Other home-screen apps (launchers). */
    fun homeApps(ctx: Context): Set<String> =
        query(
            ctx,
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_ALL,
        )

    fun settingsPackage(ctx: Context): String =
        ctx.packageManager.resolveActivity(Intent(Settings.ACTION_SETTINGS), 0)
            ?.activityInfo?.packageName ?: "com.android.settings"

    /** Keyboards must stay, or nobody can type. */
    fun keyboards(ctx: Context): Set<String> =
        ctx.getSystemService(InputMethodManager::class.java)
            ?.inputMethodList?.map { it.packageName }?.toSet() ?: emptySet()

    /** App installers (the system "install app?" screen etc.). */
    fun installers(ctx: Context): Set<String> {
        val install = Intent(Intent.ACTION_INSTALL_PACKAGE)
            .setDataAndType(Uri.parse("content://x/y.apk"), "application/vnd.android.package-archive")
        return query(ctx, install, PackageManager.MATCH_ALL)
    }

    fun phonePackages(ctx: Context): Set<String> {
        val result = mutableSetOf<String>()
        ctx.getSystemService(TelecomManager::class.java)?.defaultDialerPackage?.let { result += it }
        result += query(ctx, Intent(Intent.ACTION_DIAL), PackageManager.MATCH_DEFAULT_ONLY)
        result += query(
            ctx,
            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CONTACTS),
        )
        return result
    }

    fun smsPackages(ctx: Context): Set<String> {
        val result = mutableSetOf<String>()
        Telephony.Sms.getDefaultSmsPackage(ctx)?.let { result += it }
        result += query(
            ctx,
            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING),
        )
        return result
    }

    fun webViewPackage(): String? = WebView.getCurrentWebViewPackage()?.packageName

    /** Packages that are never touched at all. */
    fun untouchable(ctx: Context): Set<String> =
        CORE + keyboards(ctx) + setOfNotNull(webViewPackage()?.takeIf { it !in browsers(ctx) }) +
            ctx.packageName

    fun defaultDialer(ctx: Context): String? =
        ctx.getSystemService(TelecomManager::class.java)?.defaultDialerPackage

    fun defaultSms(ctx: Context): String? = Telephony.Sms.getDefaultSmsPackage(ctx)

    /**
     * Apps the customer may pick during setup. Browsers, the store, settings, installers and
     * launchers are left out. The phone and messages apps appear as normal entries; picking
     * them also brings what they need (contacts, call screen) behind the scenes, see
     * [LockPolicy.visiblePackages]. Other phone/SMS helpers (e.g. Contacts) aren't listed.
     */
    fun pickable(ctx: Context): List<AppEntry> {
        val launchable = launchable(ctx)
        val excluded = browsers(ctx) + homeApps(ctx) + installers(ctx) + phonePackages(ctx) +
            smsPackages(ctx) + untouchable(ctx) + PLAY_STORE + settingsPackage(ctx)
        val phoneAndSms = setOfNotNull(defaultDialer(ctx), defaultSms(ctx)).filter { it in launchable }
        return ((launchable - excluded) + phoneAndSms).map { AppEntry.of(ctx, it) }
            .sortedBy { it.label.lowercase() }
    }
}

data class AppEntry(val packageName: String, val label: String) {
    companion object {
        fun of(ctx: Context, pkg: String): AppEntry {
            val pm = ctx.packageManager
            val label = try {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (e: PackageManager.NameNotFoundException) {
                pkg
            }
            return AppEntry(pkg, label)
        }
    }
}
