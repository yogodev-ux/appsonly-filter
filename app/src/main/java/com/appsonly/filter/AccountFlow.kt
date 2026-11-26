package com.appsonly.filter

import android.accounts.AccountManager
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.util.Log
import android.widget.Toast

/**
 * Adding a Google account without opening the phone's Settings.
 *
 * Account changes are normally blocked. When the customer taps "Add Google account" we lift
 * that block, open Google's own sign-in directly (no Settings, no other account types), and
 * put the block back the moment the sign-in finishes, is cancelled, or the customer leaves it.
 *
 * Google's sign-in can end on a page with no buttons (for example "Couldn't sign in" when
 * there is no internet). So there is always a way back to our screens:
 *  - a notification "Back to home" for the whole time the sign-in is open,
 *  - when Google reports the sign-in finished or failed, we come back to the front ourselves,
 *  - after [timeoutMs] we close the sign-in window and come back on our own,
 *  - the Home button works in kiosk mode and also ends the sign-in.
 */
object AccountFlow {
    const val GOOGLE = "com.google"
    private const val TAG = "AppsOnly"
    private const val CHANNEL = "signin"
    private const val NOTIFICATION_ID = 41

    /** How long the sign-in may stay open before we take the customer back. Debug builds can change it. */
    @Volatile
    var timeoutMs = 5 * 60_000L

    @Volatile
    var active = false
        private set

    private val main = Handler(Looper.getMainLooper())
    private var appCtx: Context? = null
    private val timeout = Runnable {
        val ctx = appCtx ?: return@Runnable
        if (!active) return@Runnable
        Log.i(TAG, "Google sign-in timed out, returning to our screen")
        end(ctx)
        Toast.makeText(ctx, "Google sign-in closed", Toast.LENGTH_SHORT).show()
        returnToUs(ctx)
    }

    fun addGoogleAccount(activity: Activity, done: (added: Boolean) -> Unit) {
        val ctx = activity.applicationContext
        appCtx = ctx
        active = true
        setAllowed(activity, true)
        showBackNotification(ctx)
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, timeoutMs)
        Log.i(TAG, "Google sign-in opened")
        AccountManager.get(activity).addAccount(
            GOOGLE, null, null, null, activity,
            { future ->
                val added = try { future.result != null } catch (e: Exception) { false }
                Log.i(TAG, "Google sign-in finished, added=$added")
                val wasActive = active
                end(ctx)
                done(added)
                // Google may leave its own page on screen (e.g. after an error): come back.
                if (wasActive) returnToUs(ctx)
            },
            main,
        )
    }

    /** Puts the block back. Called when the flow ends and whenever our screens come back. */
    fun end(ctx: Context) {
        main.removeCallbacks(timeout)
        if (!active) return
        active = false
        setAllowed(ctx, false)
        try { ctx.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID) } catch (e: Exception) { }
        Log.i(TAG, "Google sign-in closed, account changes blocked again")
    }

    private fun backIntent(ctx: Context) = Intent(ctx, SettingsActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)

    /** Brings our Settings screen (and so our task) back in front of Google's page. */
    private fun returnToUs(ctx: Context) {
        try { ctx.startActivity(backIntent(ctx)) } catch (e: Exception) { Log.w(TAG, "return from sign-in", e) }
    }

    private fun showBackNotification(ctx: Context) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Google sign-in", NotificationManager.IMPORTANCE_HIGH)
                    .apply { setSound(null, null) }
            )
            val pi = PendingIntent.getActivity(
                ctx, 0, backIntent(ctx), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle("Google sign-in is open")
                .setContentText("Stuck? Tap here to go back.")
                .setContentIntent(pi)
                .addAction(Notification.Action.Builder(null, "Back to home", pi).build())
                .setOngoing(true)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIFICATION_ID, n)
        } catch (e: Exception) {
            Log.w(TAG, "sign-in notification", e)
        }
    }

    private fun setAllowed(ctx: Context, allowed: Boolean) {
        if (!LockPolicy.isDeviceOwner(ctx) || !Prefs.load(ctx).locked) return
        val dpm = LockPolicy.dpm(ctx)
        val admin = AdminReceiver.component(ctx)
        try {
            if (allowed) dpm.clearUserRestriction(admin, UserManager.DISALLOW_MODIFY_ACCOUNTS)
            else dpm.addUserRestriction(admin, UserManager.DISALLOW_MODIFY_ACCOUNTS)
        } catch (e: Exception) {
            Log.w(TAG, "account restriction", e)
        }
    }

    fun googleAccountNames(ctx: Context): List<String> = try {
        AccountManager.get(ctx).getAccountsByType(GOOGLE).map { it.name }
    } catch (e: Exception) { emptyList() }
}
