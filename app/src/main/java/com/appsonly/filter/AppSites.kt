package com.appsonly.filter

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Step 3 of the app website scan: websites an allowed app still asks for although the scan
 * didn't find them.
 *
 *  - Each blocked (app, website) pair is remembered and reported to the server once, at the
 *    next check-in: just the app and the website, nothing about the customer.
 *  - The owner approves or dismisses each in the owner portal. Approved websites come back to
 *    every phone, for that app only, and are saved here so they work offline too.
 */
object AppSites {
    private const val TAG = WebFilter.TAG
    private const val MAX_PENDING = 300

    private fun dir(ctx: Context) = ctx.createDeviceProtectedStorageContext().filesDir
    private fun pendingFile(ctx: Context) = File(dir(ctx), "blocked_sites.json")
    private fun approvedFile(ctx: Context) = File(dir(ctx), "approved_sites.json")

    @Volatile private var approved: Map<String, Set<String>>? = null
    private val lock = Any()
    // app|host pairs already queued or sent, so each is reported once.
    private var known: MutableSet<String>? = null

    // ------------------------------------------------------------------ approved

    fun approvedFor(ctx: Context, pkg: String, host: String): Boolean =
        approved(ctx)[pkg]?.contains(host) == true

    private fun approved(ctx: Context): Map<String, Set<String>> = approved ?: synchronized(lock) {
        approved ?: (try { parse(JSONObject(approvedFile(ctx).readText())) } catch (e: Exception) { emptyMap() })
            .also { approved = it }
    }

    private fun parse(o: JSONObject): Map<String, Set<String>> =
        o.keys().asSequence().associateWith { k ->
            val a = o.optJSONArray(k) ?: JSONArray()
            (0 until a.length()).map { a.getString(it).lowercase() }.toSet()
        }

    /** Saves the server's approved list ({"apps": {...}}). */
    fun saveApproved(ctx: Context, apps: JSONObject) {
        synchronized(lock) {
            approvedFile(ctx).writeText(apps.toString())
            approved = parse(apps)
        }
        Log.i(TAG, "approved app websites: ${apps.length()} apps")
    }

    // ------------------------------------------------------------------ reports

    /** An allowed app's lookup of [host] was blocked: remember it for the next check-in. */
    fun recordBlocked(ctx: Context, pkg: String, host: String) {
        val key = "$pkg|$host"
        synchronized(lock) {
            val k = known ?: loadKnown(ctx).also { known = it }
            if (key in k || k.size >= 5000) return
            k += key
            val pending = readPending(ctx)
            if (pending.length() >= MAX_PENDING) return
            pending.put(JSONObject().put("app", pkg).put("host", host))
            pendingFile(ctx).writeText(pending.toString())
        }
    }

    private fun knownFile(ctx: Context) = File(dir(ctx), "reported_sites.txt")
    private fun loadKnown(ctx: Context): MutableSet<String> =
        (try { knownFile(ctx).readLines() } catch (e: Exception) { emptyList() }).toMutableSet().also { set ->
            val p = readPending(ctx)
            for (i in 0 until p.length()) p.optJSONObject(i)?.let { set += "${it.optString("app")}|${it.optString("host")}" }
        }

    private fun readPending(ctx: Context): JSONArray =
        try { JSONArray(pendingFile(ctx).readText()) } catch (e: Exception) { JSONArray() }

    /** What to report now (may be empty). */
    fun pending(ctx: Context): JSONArray = synchronized(lock) { readPending(ctx) }

    /** The server got [sent]: don't send them again. */
    fun sent(ctx: Context, sent: JSONArray) {
        synchronized(lock) {
            val lines = (0 until sent.length()).mapNotNull { sent.optJSONObject(it) }.map { "${it.optString("app")}|${it.optString("host")}" }
            knownFile(ctx).appendText(lines.joinToString("\n", postfix = "\n"))
            val left = JSONArray()
            val done = lines.toSet()
            val p = readPending(ctx)
            for (i in 0 until p.length()) {
                val o = p.optJSONObject(i) ?: continue
                if ("${o.optString("app")}|${o.optString("host")}" !in done) left.put(o)
            }
            pendingFile(ctx).writeText(left.toString())
        }
    }
}
