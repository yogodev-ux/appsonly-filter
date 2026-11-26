package com.appsonly.filter

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * Device Owner powers used by our own Settings screen, so the phone's Settings app can stay hidden.
 */
object DeviceControls {
    private const val TAG = "AppsOnly"

    /** Runtime permissions we grant ourselves (Wi-Fi scanning needs location; Bluetooth list; sign-in notification). */
    private val SELF_PERMISSIONS = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun prepare(ctx: Context) {
        val dpm = LockPolicy.dpm(ctx)
        val admin = AdminReceiver.component(ctx)
        for (perm in SELF_PERMISSIONS) {
            try {
                // Only grant once: every grant of location makes Android show the
                // "Apps Only has location access" notification again, and this runs on
                // every home-screen visit.
                if (dpm.getPermissionGrantState(admin, ctx.packageName, perm) ==
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED) continue
                dpm.setPermissionGrantState(admin, ctx.packageName, perm,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
                Log.i(TAG, "granted $perm")
            } catch (e: Exception) { Log.w(TAG, "grant $perm", e) }
        }
        // Wi-Fi scan results need location turned on.
        if (Build.VERSION.SDK_INT >= 30) {
            try { dpm.setLocationEnabled(admin, true) } catch (e: Exception) { Log.w(TAG, "location", e) }
        }
        // Token that lets us set/remove the screen-lock PIN (becomes active after the next unlock
        // if a lock already exists, immediately otherwise).
        try {
            if (!dpm.isResetPasswordTokenActive(admin)) dpm.setResetPasswordToken(admin, Prefs.pinToken(ctx))
        } catch (e: Exception) { Log.w(TAG, "pin token", e) }
        // Private DNS would bypass our web filter.
        try { dpm.setGlobalPrivateDnsModeOpportunistic(admin) } catch (e: Exception) { Log.w(TAG, "private dns", e) }
    }

    fun setBrightness(ctx: Context, value0to255: Int) = setSystem(ctx, Settings.System.SCREEN_BRIGHTNESS, value0to255.coerceIn(5, 255).toString())

    fun setAutoBrightness(ctx: Context, auto: Boolean) =
        setSystem(ctx, Settings.System.SCREEN_BRIGHTNESS_MODE,
            if (auto) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC.toString()
            else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL.toString())

    fun setScreenTimeout(ctx: Context, millis: Int) = setSystem(ctx, Settings.System.SCREEN_OFF_TIMEOUT, millis.toString())

    private fun setSystem(ctx: Context, key: String, value: String): Boolean = try {
        LockPolicy.dpm(ctx).setSystemSetting(AdminReceiver.component(ctx), key, value); true
    } catch (e: Exception) { Log.w(TAG, "system setting $key", e); false }

    fun readSystemInt(ctx: Context, key: String, def: Int) =
        try { Settings.System.getInt(ctx.contentResolver, key) } catch (e: Exception) { def }

    /** Sets a screen-lock PIN, or removes it when [pin] is empty. */
    fun setPin(ctx: Context, pin: String): Boolean = try {
        val dpm = LockPolicy.dpm(ctx)
        val admin = AdminReceiver.component(ctx)
        dpm.resetPasswordWithToken(admin, pin.ifEmpty { null }, Prefs.pinToken(ctx), 0)
    } catch (e: Exception) { Log.w(TAG, "pin", e); false }

    fun pinReady(ctx: Context) = try {
        LockPolicy.dpm(ctx).isResetPasswordTokenActive(AdminReceiver.component(ctx))
    } catch (e: Exception) { false }
}
