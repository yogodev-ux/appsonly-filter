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
 *  - Google's sign-in opens in its own task, so Home always shows our home screen; leaving
 *    closes that task, so a Google page can't jump back in front,
 *  - after [timeoutMs] we close the sign-in window and come back on our own.
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
    private var accountsBefore = 0
    private val timeout = Runnable {
        val ctx = appCtx ?: return@Runnable
        if (!active) return@Runnable
        Log.i(TAG, "Google sign-in timed out, returning to our screen")
        end(ctx)
        Toast.makeText(ctx, "Google sign-in closed", Toast.LENGTH_SHORT).show()
        returnToUs(ctx)
    }

    fun addGoogleAccount(activity: Activity, failed: () -> Unit) {
        val ctx = activity.applicationContext
        appCtx = ctx
        active = true
        accountsBefore = googleAccountNames(ctx).size
        setAllowed(activity, true)
        showBackNotification(ctx)
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, timeoutMs)
        // We ask for Google's sign-in screen and open it ourselves in its OWN task. If Android
        // opened it on top of our home screen (same task), the Home button would just show
        // Google's page again, and the customer would have no way out.
        AccountManager.get(activity).addAccount(
            GOOGLE, null, null, null, null,
            { future ->
                val intent = try {
                    @Suppress("DEPRECATION")
                    future.result?.getParcelable<Intent>(AccountManager.KEY_INTENT)
                } catch (e: Exception) { Log.w(TAG, "Google sign-in", e); null }
                if (intent == null) {
                    end(ctx)
                    failed()
                    return@addAccount
                }
                try {
                    // Google's pages go into a task that belongs to us (SignInHostActivity),
                    // so we can close all of them at once when the customer leaves.
                    activity.startActivity(
                        Intent(activity, SignInHostActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            .putExtra(SignInHostActivity.EXTRA_INTENT, intent)
                    )
                    Log.i(TAG, "Google sign-in opened")
                } catch (e: Exception) {
                    Log.w(TAG, "Google sign-in start", e)
                    end(ctx)
                    failed()
                }
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
        closeSignInTask(ctx)
        try { ctx.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID) } catch (e: Exception) { }
        val added = googleAccountNames(ctx).size > accountsBefore
        Log.i(TAG, "Google sign-in closed (account added: $added), account changes blocked again")
        if (added) Toast.makeText(ctx, "Google account added", Toast.LENGTH_SHORT).show()
    }

    /**
     * Removes the task holding Google's sign-in pages. Without this, a Google page that was
     * still loading could jump back in front after the customer pressed Home.
     */
    private fun closeSignInTask(ctx: Context) {
        try {
            val host = SignInHostActivity::class.java.name
            for (task in ctx.getSystemService(android.app.ActivityManager::class.java).appTasks) {
                val info = try { task.taskInfo } catch (e: Exception) { continue }
                if (info.baseActivity?.className == host) task.finishAndRemoveTask()
            }
        } catch (e: Exception) { Log.w(TAG, "close sign-in task", e) }
    }

    private fun backIntent(ctx: Context) = Intent(ctx, SettingsActivity::class.java)
        .putExtra(SettingsActivity.EXTRA_ACCOUNT_ONLY, true)
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
