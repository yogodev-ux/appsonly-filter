package com.appsonly.filter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log
import org.json.JSONObject

/**
 * The Windows installer sends the customer's name, phone, email and removal code here:
 *   adb shell am broadcast -n com.appsonly.filter/.SetupInfoReceiver --es data <base64 JSON>
 * Only adb can call it (DUMP permission). It's accepted only while we're Device Owner and the
 * phone isn't locked yet, so nobody can swap the removal code on a customer's locked phone.
 * The details wait on the phone until License sends them to the server, then the removal
 * code is deleted from the phone.
 * Result data: "saved", or why not.
 */
class SetupInfoReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        resultData = try {
            when {
                !LockPolicy.isDeviceOwner(ctx) -> "not device owner"
                Prefs.load(ctx).locked -> "phone is already locked"
                else -> {
                    val json = JSONObject(String(Base64.decode(intent.getStringExtra("data") ?: "", Base64.DEFAULT), Charsets.UTF_8))
                    val problem = Customer.problem(json)
                    if (problem != null) problem else {
                        Customer.savePending(ctx, json)
                        Log.i("AppsOnly", "customer details saved; waiting to send them to the server")
                        // Try right away if there's internet; otherwise License sends them later.
                        (ctx as? FilterApp)?.worker?.execute { License.refresh(ctx) }
                        "saved"
                    }
                }
            }
        } catch (e: Exception) {
            "bad data"
        }
    }
}

/** The customer's details waiting to be sent to the server. */
object Customer {
    private const val FILE = "customer"

    private fun sp(ctx: Context) =
        ctx.createDeviceProtectedStorageContext().getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun problem(o: JSONObject): String? {
        val name = o.optString("name").trim()
        val email = o.optString("email").trim()
        val code = o.optString("removalCode")
        return when {
            name.length !in 2..100 -> "bad name"
            o.optString("phone").count { it.isDigit() } !in 7..15 -> "bad phone"
            !email.contains('@') || email.length > 200 -> "bad email"
            code.length !in 6..64 || code.any { it.isWhitespace() } -> "bad removal code"
            else -> null
        }
    }

    fun savePending(ctx: Context, o: JSONObject) {
        sp(ctx).edit().putString("pending", o.toString()).commit()
    }

    /** The details still to send, or null when the server already has them. */
    fun pending(ctx: Context): JSONObject? = sp(ctx).getString("pending", null)?.let { JSONObject(it) }

    /** The server has them: forget the removal code (and the rest) on the phone. */
    fun sent(ctx: Context) {
        sp(ctx).edit().remove("pending").putBoolean("sent", true).commit()
    }

    fun wasSent(ctx: Context) = sp(ctx).getBoolean("sent", false)
}
