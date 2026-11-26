package com.appsonly.filter

import android.content.Context

/** The customer's one-time choices. Written once at lock time, then read-only on the phone. */
data class LockConfig(
    val locked: Boolean,
    val allowedPackages: Set<String>,
    val allowPhone: Boolean,
    val allowSms: Boolean,
)

object Prefs {
    private const val FILE = "lock_config"
    private const val KEY_LOCKED = "locked"
    private const val KEY_ALLOWED = "allowed"
    private const val KEY_PHONE = "allow_phone"
    private const val KEY_SMS = "allow_sms"

    // Device-protected storage: readable right after a reboot, before the first unlock,
    // so the home screen and lock work from the start.
    private fun sp(ctx: Context) =
        ctx.createDeviceProtectedStorageContext().getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(ctx: Context): LockConfig {
        val p = sp(ctx)
        return LockConfig(
            locked = p.getBoolean(KEY_LOCKED, false),
            allowedPackages = p.getStringSet(KEY_ALLOWED, emptySet())!!.toSet(),
            allowPhone = p.getBoolean(KEY_PHONE, false),
            allowSms = p.getBoolean(KEY_SMS, false),
        )
    }

    /** Saves the choices and marks the phone as locked. There is no on-phone way back. */
    fun saveLocked(ctx: Context, allowed: Set<String>, allowPhone: Boolean, allowSms: Boolean) {
        sp(ctx).edit()
            .putStringSet(KEY_ALLOWED, allowed)
            .putBoolean(KEY_PHONE, allowPhone)
            .putBoolean(KEY_SMS, allowSms)
            .putBoolean(KEY_LOCKED, true)
            .commit()
    }

    private const val KEY_PIN_TOKEN = "pin_token"

    /** Random token that lets us set or remove the screen-lock PIN as Device Owner. */
    fun pinToken(ctx: Context): ByteArray {
        val p = sp(ctx)
        p.getString(KEY_PIN_TOKEN, null)?.let { return android.util.Base64.decode(it, 0) }
        val token = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        p.edit().putString(KEY_PIN_TOKEN, android.util.Base64.encodeToString(token, 0)).commit()
        return token
    }

    fun clear(ctx: Context) {
        sp(ctx).edit().clear().commit()
    }
}
