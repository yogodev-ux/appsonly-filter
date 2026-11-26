package com.appsonly.filter

import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.UUID

/**
 * Step 4: the payment gate. Setup is free; until the phone is paid, every app stays hidden
 * (the home screen shows how to pay, and Settings for Wi-Fi).
 *
 * The server signs a small "license" (deviceId + status) with its private key; the app only
 * trusts licenses that verify against the public key built into it. Once a paid license is
 * saved, the phone stays unlocked offline: no internet is needed after paying.
 */
object License {
    private const val TAG = "AppsOnlyLicense"
    private const val FILE = "license"

    data class State(
        val paid: Boolean,
        val status: String,
        val payCode: String,
        val payUrl: String,
        val lastCheck: Long,
        val lastError: String,
    )

    private fun sp(ctx: Context) =
        ctx.createDeviceProtectedStorageContext().getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun state(ctx: Context): State {
        val p = sp(ctx)
        val verified = p.getString("license", null)?.let { verify(ctx, it) }
        val status = verified?.optString("status") ?: "unknown"
        val devOverride = BuildConfig.DEBUG && p.getBoolean("dev_paid", false)
        return State(
            paid = status == "paid" || devOverride,
            status = if (devOverride) "paid (test override)" else status,
            payCode = p.getString("pay_code", "") ?: "",
            payUrl = p.getString("pay_url", "") ?: "",
            lastCheck = p.getLong("last_check", 0),
            lastError = p.getString("last_error", "") ?: "",
        )
    }

    fun isPaid(ctx: Context) = state(ctx).paid

    /** Debug builds only: lets the automated tests skip payment. Ignored in release builds. */
    fun devSetPaid(ctx: Context, paid: Boolean) {
        if (BuildConfig.DEBUG) sp(ctx).edit().putBoolean("dev_paid", paid).commit()
    }

    private fun installId(ctx: Context): String {
        val p = sp(ctx)
        p.getString("install_id", null)?.let { return it }
        val id = UUID.randomUUID().toString()
        p.edit().putString("install_id", id).commit()
        return id
    }

    /**
     * Talks to the server: activates on first use, then fetches the current license.
     * Blocking; call from a background thread. Returns true if the paid state changed.
     */
    @Volatile private var lastAttempt = 0L

    /** True once the filter was removed (from the portal, or with the uninstall code). */
    fun wasRemoved(ctx: Context) = sp(ctx).getBoolean("removed", false)

    /** [refresh], but at most once per [maxAgeMs] (for screens that open often). */
    fun refreshIfStale(ctx: Context, maxAgeMs: Long): Boolean =
        if (System.currentTimeMillis() - lastAttempt < maxAgeMs) false else refresh(ctx)

    @Synchronized
    fun refresh(ctx: Context): Boolean {
        lastAttempt = System.currentTimeMillis()
        val before = isPaid(ctx)
        val p = sp(ctx)
        var removeNow = false
        try {
            var token: String? = p.getString("token", null)
            var reply: Pair<Int, String>? = token?.let { request("GET", "/v1/license", null, it) }
            if (reply == null || reply.first == 401) {
                val body = JSONObject()
                    .put("installId", installId(ctx))
                    .put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
                    .put("sdk", Build.VERSION.SDK_INT)
                    .put("appVersion", BuildConfig.VERSION_NAME)
                val activated = request("POST", "/v1/activate", body.toString(), null)
                if (activated.first == 200) token = JSONObject(activated.second).getString("token")
                reply = activated
            }
            val (code, text) = reply ?: error("no reply")
            if (code != 200) throw IllegalStateException("server said $code")
            val o = JSONObject(text)
            val license = o.getString("license")
            val payload = verify(ctx, license) ?: throw SecurityException("license signature is wrong")
            // The license must be for this phone (the device id the server gave us at activation).
            val deviceId = o.optString("deviceId").ifEmpty { p.getString("device_id", "") ?: "" }
            if (deviceId.isEmpty() || payload.optString("deviceId") != deviceId) {
                throw SecurityException("license is for another phone")
            }
            p.edit()
                .putString("token", token)
                .putString("device_id", deviceId)
                .putString("license", license)
                .putString("pay_code", o.optString("payCode"))
                .putString("pay_url", o.optString("payUrl"))
                .putLong("last_check", System.currentTimeMillis())
                .putString("last_error", "")
                .commit()
            Log.i(TAG, "license status=${payload.optString("status")}")
            sendCustomer(ctx, token)
            sendTamper(ctx, token)
            // Support removed the filter in the portal (a signed order for this phone).
            removeNow = payload.optString("status") == "removed" && LockPolicy.isDeviceOwner(ctx)
        } catch (e: Exception) {
            Log.w(TAG, "license check failed: ${e.message}")
            p.edit().putString("last_error", e.message ?: e.javaClass.simpleName).commit()
        }
        if (removeNow) {
            removeFilter(ctx, "by the support portal")
            return true
        }
        val after = isPaid(ctx)
        if (before != after) TamperLog.record(ctx, if (after) "license_paid" else "license_unpaid")
        return before != after
    }

    /** Sends the customer's details and removal code from the installer, once; then forgets them. */
    private fun sendCustomer(ctx: Context, token: String?) {
        val pending = Customer.pending(ctx) ?: return
        if (token == null) return
        try {
            val (code, text) = request("POST", "/v1/customer", JSONObject().put("customer", pending).toString(), token)
            if (code == 200) {
                Customer.sent(ctx)
                Log.i(TAG, "customer details sent to the server")
            } else Log.w(TAG, "server refused the customer details: $code $text")
        } catch (e: Exception) {
            Log.w(TAG, "customer details not sent yet: ${e.message}")
        }
    }

    /** Turns this back into a normal phone: every app visible, no restrictions, no Device Owner. */
    fun removeFilter(ctx: Context, why: String) {
        Log.i(TAG, "removing the filter ($why)")
        TamperLog.record(ctx, "filter_removed", why)
        sp(ctx).edit().putBoolean("removed", true).commit()
        try { ctx.stopService(android.content.Intent(ctx, FilterVpnService::class.java)) } catch (e: Exception) { Log.w(TAG, "stop web filter", e) }
        LockPolicy.removeEverything(ctx)
        Log.i(TAG, "filter removed; Device Owner is off")
    }

    /** Events that are not tamper attempts (they stay in the local log only). */
    private val NOT_TAMPER = setOf("license_paid", "license_unpaid", "filter_removed")

    /** Reports new tamper attempts to the server, which turns them into alerts for the owner. */
    private fun sendTamper(ctx: Context, token: String?) {
        if (token == null) return
        val p = sp(ctx)
        val since = p.getLong("tamper_sent_until", 0)
        var newest = since
        val events = org.json.JSONArray()
        for (line in TamperLog.read(ctx)) {
            val o = try { JSONObject(line) } catch (e: Exception) { continue }
            val t = o.optLong("time")
            if (t <= since || o.optString("event") in NOT_TAMPER) continue
            if (events.length() < 50) events.put(JSONObject().put("type", o.optString("event")).put("detail", o.optString("detail").take(200)))
            newest = maxOf(newest, t)
        }
        if (events.length() == 0) return
        try {
            val (code, _) = request("POST", "/v1/tamper", JSONObject().put("events", events).toString(), token)
            if (code == 200) p.edit().putLong("tamper_sent_until", newest).commit()
        } catch (e: Exception) {
            Log.w(TAG, "tamper report not sent yet: ${e.message}")
        }
    }

    private fun request(method: String, path: String, body: String?, token: String?): Pair<Int, String> {
        val c = URL(BuildConfig.LICENSE_SERVER + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            token?.let { c.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
            return code to text
        } finally {
            c.disconnect()
        }
    }

    /** Returns the license payload if the signature is valid and it's for this install. */
    private fun verify(ctx: Context, license: String): JSONObject? = try {
        val (body, sig) = license.split('.', limit = 2)
        val key = KeyFactory.getInstance("EC").generatePublic(
            X509EncodedKeySpec(Base64.decode(BuildConfig.LICENSE_PUBLIC_KEY, Base64.DEFAULT)))
        val v = Signature.getInstance("SHA256withECDSA")
        v.initVerify(key)
        v.update(body.toByteArray())
        val raw = Base64.decode(sig, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        if (!v.verify(rawToDer(raw))) null
        else JSONObject(String(Base64.decode(body, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))).takeIf {
            val mine = sp(ctx).getString("device_id", null)
            mine == null || it.optString("deviceId") == mine
        }
    } catch (e: Exception) {
        null
    }

    /** Web Crypto signs as raw r||s; Java wants ASN.1 DER. */
    private fun rawToDer(raw: ByteArray): ByteArray {
        require(raw.size == 64)
        fun der(i: ByteArray): ByteArray {
            val b = BigInteger(1, i).toByteArray()
            return byteArrayOf(0x02, b.size.toByte()) + b
        }
        val r = der(raw.copyOfRange(0, 32))
        val s = der(raw.copyOfRange(32, 64))
        val out = ByteArrayOutputStream()
        out.write(0x30); out.write(r.size + s.size); out.write(r); out.write(s)
        return out.toByteArray()
    }
}
