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

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

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

    private const val KEY_ACCOUNT_WINDOW = "account_window_until"

    /** Until when (epoch ms) adding an account is temporarily allowed. 0 = closed. */
    fun accountWindowUntil(ctx: Context): Long = sp(ctx).getLong(KEY_ACCOUNT_WINDOW, 0L)

    fun setAccountWindowUntil(ctx: Context, until: Long) {
        sp(ctx).edit().putLong(KEY_ACCOUNT_WINDOW, until).commit()
    }

    fun clear(ctx: Context) {
        sp(ctx).edit().clear().commit()
    }
}
