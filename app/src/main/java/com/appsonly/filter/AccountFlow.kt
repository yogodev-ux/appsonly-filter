package com.appsonly.filter

import android.accounts.AccountManager
import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.util.Log

/**
 * Adding a Google account without opening the phone's Settings.
 *
 * Account changes are normally blocked. When the customer taps "Add Google account" we lift
 * that block, open Google's own sign-in directly (no Settings, no other account types), and
 * put the block back the moment the sign-in finishes, is cancelled, or the customer leaves it.
 */
object AccountFlow {
    const val GOOGLE = "com.google"

    @Volatile
    var active = false
        private set

    fun addGoogleAccount(activity: Activity, done: (added: Boolean) -> Unit) {
        val ctx = activity.applicationContext
        active = true
        setAllowed(activity, true)
        AccountManager.get(activity).addAccount(
            GOOGLE, null, null, null, activity,
            { future ->
                val added = try { future.result != null } catch (e: Exception) { false }
                end(ctx)
                done(added)
            },
            Handler(Looper.getMainLooper()),
        )
    }

    /** Puts the block back. Called when the flow ends and whenever our screens come back. */
    fun end(ctx: android.content.Context) {
        if (!active) return
        active = false
        setAllowed(ctx, false)
    }

    private fun setAllowed(ctx: android.content.Context, allowed: Boolean) {
        if (!LockPolicy.isDeviceOwner(ctx) || !Prefs.load(ctx).locked) return
        val dpm = LockPolicy.dpm(ctx)
        val admin = AdminReceiver.component(ctx)
        try {
            if (allowed) dpm.clearUserRestriction(admin, UserManager.DISALLOW_MODIFY_ACCOUNTS)
            else dpm.addUserRestriction(admin, UserManager.DISALLOW_MODIFY_ACCOUNTS)
        } catch (e: Exception) {
            Log.w("AppsOnly", "account restriction", e)
        }
    }

    fun googleAccountNames(ctx: android.content.Context): List<String> = try {
        AccountManager.get(ctx).getAccountsByType(GOOGLE).map { it.name }
    } catch (e: Exception) { emptyList() }
}
