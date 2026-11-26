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
    @Synchronized
    fun refresh(ctx: Context): Boolean {
        val before = isPaid(ctx)
        val p = sp(ctx)
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
        } catch (e: Exception) {
            Log.w(TAG, "license check failed: ${e.message}")
            p.edit().putString("last_error", e.message ?: e.javaClass.simpleName).commit()
        }
        val after = isPaid(ctx)
        if (before != after) TamperLog.record(ctx, if (after) "license_paid" else "license_unpaid")
        return before != after
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
