package com.appsonly.filter

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/**
 * Works out which websites an allowed app needs by reading the app's own install files on
 * the phone: no testing, and the customer doesn't have to open the app.
 *
 * Every website name inside the app (code, text, settings, deep links, built-in libraries)
 * is found and sorted:
 *  - the app's own domains (whatsapp.com for com.whatsapp): allowed with every sub-address,
 *    so servers the app picks while running are covered too;
 *  - general websites (social media, search, news, ...; assets/general_sites.json): dropped,
 *    unless they are the app's own. Only their service addresses (api., graph., cdn., ...)
 *    are kept, which aren't web pages;
 *  - anything else found: allowed by that exact name only.
 *
 * Results are kept per app version, so an update is scanned again.
 */
object AppDomains {
    private const val TAG = WebFilter.TAG

    class Result(val version: Long, val own: Set<String>, val exact: Set<String>, val dropped: Set<String>) {
        fun matches(host: String): Boolean =
            host in exact || own.any { host == it || host.endsWith(".$it") }

        fun toJson() = JSONObject()
            .put("version", version)
            .put("own", JSONArray(own.sorted()))
            .put("exact", JSONArray(exact.sorted()))
            .put("dropped", JSONArray(dropped.sorted()))

        companion object {
            fun fromJson(o: JSONObject): Result {
                fun set(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()
                return Result(o.getLong("version"), set("own"), set("exact"), set("dropped"))
            }
        }
    }

    private val cache = ConcurrentHashMap<String, Result>()
    private val scanLocks = ConcurrentHashMap<String, Any>()

    /** Does [pkg]'s scan allow [host]? Scans the app first if needed (takes a few seconds). */
    fun matches(ctx: Context, pkg: String, host: String): Boolean =
        get(ctx, pkg)?.matches(host) == true

    /** The scan for the installed version of [pkg], from memory, from storage, or fresh. */
    fun get(ctx: Context, pkg: String): Result? {
        // The phone's Settings app is always there but isn't an app the customer picked.
        if (pkg == AppScanner.settingsPackage(ctx)) return null
        val version = versionOf(ctx, pkg) ?: return null
        cache[pkg]?.takeIf { it.version == version }?.let { return it }
        synchronized(scanLocks.getOrPut(pkg) { Any() }) {
            cache[pkg]?.takeIf { it.version == version }?.let { return it }
            val file = File(dir(ctx), "$pkg.json")
            val stored = try { Result.fromJson(JSONObject(file.readText())) } catch (e: Exception) { null }
            if (stored != null && stored.version == version) { cache[pkg] = stored; return stored }
            val fresh = try { scan(ctx, pkg, version) } catch (e: Exception) { Log.w(TAG, "scan $pkg", e); return null }
            try { file.writeText(fresh.toJson().toString()) } catch (e: Exception) { Log.w(TAG, "save scan $pkg", e) }
            cache[pkg] = fresh
            Log.i(TAG, "Scanned $pkg: ${fresh.own.size} own domains, ${fresh.exact.size} other names, ${fresh.dropped.size} dropped")
            return fresh
        }
    }

    /** Scans every allowed app in the background, so the first lookups don't wait. */
    fun prescan(ctx: Context) {
        val app = ctx.applicationContext
        Thread({
            val cfg = Prefs.load(app)
            for (pkg in LockPolicy.visiblePackages(app, cfg)) get(app, pkg)
        }, "app-domain-scan").start()
    }

    private fun dir(ctx: Context) =
        File(ctx.createDeviceProtectedStorageContext().filesDir, "app_domains").also { it.mkdirs() }

    @Suppress("DEPRECATION")
    private fun versionOf(ctx: Context, pkg: String): Long? = try {
        val pi = ctx.packageManager.getPackageInfo(pkg, 0)
        // Version code plus install time: a reinstall of the same version is scanned again too.
        (pi.longVersionCode shl 20) xor pi.lastUpdateTime
    } catch (e: PackageManager.NameNotFoundException) { null }

    // ------------------------------------------------------------------ scanning

    private val SKIP_EXT = Regex("\\.(png|jpe?g|webp|gif|bmp|ico|mp3|ogg|wav|m4a|aac|flac|mp4|webm|mkv|ttf|otf|woff2?|tflite|bin|pb|zip|jar|gz)$", RegexOption.IGNORE_CASE)
    private const val MAX_ENTRY = 64L * 1024 * 1024

    fun scan(ctx: Context, pkg: String, version: Long): Result {
        val ai = ctx.packageManager.getApplicationInfo(pkg, 0)
        val files = listOfNotNull(ai.sourceDir) + (ai.splitSourceDirs?.toList() ?: emptyList())
        val hosts = HashSet<String>()
        for (path in files) {
            try {
                ZipFile(path).use { zip ->
                    for (e in zip.entries()) {
                        if (e.isDirectory || e.size > MAX_ENTRY || SKIP_EXT.containsMatchIn(e.name)) continue
                        val bytes = zip.getInputStream(e).use { it.readBytes() }
                        findHosts(bytes, hosts)
                    }
                }
            } catch (e: Exception) { Log.w(TAG, "scan $path: $e") }
        }
        return sort(ctx, pkg, version, hosts)
    }

    /** Collects website names from printable text runs (one-byte and two-byte text). */
    fun findHosts(b: ByteArray, out: MutableSet<String>) {
        val sb = StringBuilder()
        fun flush() {
            if (sb.length >= 4) {
                // In app code each text starts with its length. For texts of 33 to 126
                // letters that length byte is printable and gets glued to the front
                // ("Etelemetry.example-metrics.io" for a 69-letter text). When the first
                // character's code is exactly the length of the rest, leave it out.
                if (sb[0].code == sb.length - 1) hostsIn(sb.subSequence(1, sb.length), out)
                else hostsIn(sb, out)
            }
            sb.setLength(0)
        }
        // One-byte text (code, most settings files).
        for (x in b) {
            val c = x.toInt() and 0xff
            if (c in 0x21..0x7e) sb.append(c.toChar()) else flush()
        }
        flush()
        // Two-byte text (some resource and manifest strings): a printable byte, then 0.
        var i = 0
        while (i + 1 < b.size) {
            val c = b[i].toInt() and 0xff
            if (c in 0x21..0x7e && b[i + 1].toInt() == 0) { sb.append(c.toChar()); i += 2 } else { flush(); i++ }
        }
        flush()
    }

    private val URL = Regex("(?i)(?:https?|wss?)://([a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?)")
    private val BARE = Regex("(?<![A-Za-z0-9_.\$/@-])((?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,12})(?![A-Za-z0-9_\$(-])")
    private val COMMON_TLDS = setOf(
        // Not "app"/"dev": too many code names end in .app (android.app). Those are still
        // found when written as https:// addresses.
        "com", "net", "org", "io", "co", "me", "info", "biz", "tv", "ai", "gg", "ly",
        "cc", "xyz", "online", "site", "cloud", "tech", "live", "link", "us", "uk", "de", "fr", "il",
        "ru", "in", "br", "jp", "au", "ca", "eu", "es", "it", "nl", "pl", "se", "ch", "at", "be",
    )
    // First labels of code names that look like web addresses (java.io, kotlin.io, ...).
    private val CODE_FIRST = setOf(
        "android", "androidx", "java", "javax", "kotlin", "kotlinx", "dalvik", "sun", "com", "org",
        "net", "io", "junit", "okhttp3", "okio", "retrofit2", "dagger", "this", "self", "super",
        "window", "document", "console", "math", "json", "object", "string", "system", "intent",
        "context", "view", "app", "bundle", "file", "data", "config", "build", "manifest", "res",
    )

    private fun hostsIn(s: CharSequence, out: MutableSet<String>) {
        for (m in URL.findAll(s)) clean(m.groupValues[1])?.let { out += it }
        for (m in BARE.findAll(s)) {
            val h = m.groupValues[1]
            val labels = h.split('.')
            if (labels.size < 2 || labels.last() !in COMMON_TLDS || labels.first() in CODE_FIRST) continue
            if (labels[labels.size - 2].length < 2) continue
            clean(h)?.let { out += it }
        }
    }

    private fun clean(raw: String): String? {
        val h = raw.lowercase().trim('.')
        if (h.length < 4 || '.' !in h || h.length > 253) return null
        if (h.all { it.isDigit() || it == '.' }) return null // a bare address: never allowed
        val tld = h.substringAfterLast('.')
        if (tld.length < 2 || tld.any { !it.isLetter() }) return null
        if (h.split('.').any { it.isEmpty() || it.startsWith('-') || it.endsWith('-') }) return null
        return h
    }

    // ------------------------------------------------------------------ sorting

    private val TWO_LEVEL = setOf(
        "co.uk", "org.uk", "ac.uk", "gov.uk", "com.au", "net.au", "org.au", "co.jp", "co.in", "com.br",
        "co.il", "org.il", "co.za", "com.cn", "com.mx", "com.tr", "co.nz", "com.sg", "com.hk", "co.kr",
    )
    /** The part of a name a company registers: api.whatsapp.net -> whatsapp.net. */
    fun registrable(host: String): String {
        val l = host.split('.')
        if (l.size <= 2) return host
        val last2 = l.takeLast(2).joinToString(".")
        return if (last2 in TWO_LEVEL && l.size >= 3) l.takeLast(3).joinToString(".") else last2
    }

    private val GENERIC_PKG = setOf(
        "com", "org", "net", "io", "co", "me", "app", "apps", "android", "mobile", "free", "pro",
        "lite", "client", "main", "prod", "release", "beta", "the", "and", "official", "plus",
        "google", "inc", "ltd", "llc", "de", "uk", "us", "il", "in", "br",
    )
    /** Names the app's company is likely to use: com.whatsapp -> whatsapp; com.foo.bar -> foo, bar, foobar. */
    fun brands(pkg: String): Set<String> {
        val parts = pkg.lowercase().split('.', '_').filter { it.length >= 4 && it !in GENERIC_PKG }
        return (parts + parts.joinToString("")).filter { it.length >= 4 }.toSet()
    }

    // Service addresses on general sites that aren't web pages (e.g. Facebook sign-in for apps).
    private val SERVICE_LABEL = Regex("^(api|apis|graph|b-graph|gateway|cdn|static|edge|sdk|connect|upload|rupload|lookaside|scontent|img|images|i)(-[a-z0-9-]+)?$|^api[0-9-]")

    private var general: Set<String>? = null
    private fun general(ctx: Context): Set<String> = general ?: synchronized(this) {
        general ?: run {
            val o = JSONObject(ctx.assets.open("general_sites.json").bufferedReader().use { it.readText() })
            val a = o.getJSONArray("sites")
            (0 until a.length()).map { a.getString(it).lowercase() }.toSet().also { general = it }
        }
    }

    fun sort(ctx: Context, pkg: String, version: Long, hosts: Set<String>): Result {
        val brand = brands(pkg)
        val gen = general(ctx)
        val own = HashSet<String>(); val exact = HashSet<String>(); val dropped = HashSet<String>()
        for (h in hosts) {
            val reg = registrable(h)
            val sld = reg.substringBefore('.')
            when {
                sld in brand -> own += reg
                reg in gen || h in gen ->
                    if (h != reg && SERVICE_LABEL.containsMatchIn(h.substringBefore('.'))) exact += h else dropped += h
                else -> exact += h
            }
        }
        exact.removeAll { e -> own.any { e == it || e.endsWith(".$it") } }
        return Result(version, own, exact, dropped)
    }
}
