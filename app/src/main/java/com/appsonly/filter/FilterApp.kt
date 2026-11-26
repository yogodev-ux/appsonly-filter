package com.appsonly.filter

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import java.util.concurrent.Executors

class FilterApp : Application() {

    /** Single background thread so enforcement runs never overlap. */
    val worker = Executors.newSingleThreadExecutor()

    override fun onCreate() {
        super.onCreate()
        // Android doesn't deliver "package added" to manifest receivers, so listen while
        // our process is alive (it normally is, because we are the home screen).
        // MainActivity also re-checks every time the home screen is shown.
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                // Our own policy changes also fire these; ignore those and coalesce bursts.
                val pkg = intent.data?.schemeSpecificPart ?: return
                if (pkg == packageName) return
                android.util.Log.i("AppsOnly", "package change from $pkg (${intent.action})")
                // A brand-new app that isn't allowed (e.g. installed remotely from the Play Store
                // website) is hidden right away. Updates of allowed apps are left alone.
                val newApp = intent.action == Intent.ACTION_PACKAGE_ADDED &&
                    !intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                if (newApp && pkg !in Prefs.load(context).allowedPackages) {
                    android.util.Log.i("AppsOnly", "new app installed: $pkg, checking now")
                    enforceAsync()
                } else scheduleEnforce()
            }
        }
        registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        // Switch the status bar off before the phone turns off, so it is already off while
        // the phone starts up again (kiosk mode does not survive a reboot).
        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (Prefs.load(context).locked) KioskMode.setShadeAllowed(context, false)
            }
        }, IntentFilter(Intent.ACTION_SHUTDOWN).apply { addAction(Intent.ACTION_REBOOT) }, Context.RECEIVER_EXPORTED)
        enforceAsync()
        // Step 4: keep the license fresh (also activates the phone with the server the first time).
        if (Prefs.load(this).locked) worker.execute { if (License.refresh(this)) LockPolicy.enforce(this) }
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val delayed = Runnable { enforceAsync() }

    /** Re-check a few seconds after the last package change. */
    fun scheduleEnforce() {
        main.removeCallbacks(delayed)
        main.postDelayed(delayed, 3000)
    }

    fun enforceAsync(done: (() -> Unit)? = null) {
        worker.execute {
            LockPolicy.enforce(this)
            done?.invoke()
        }
    }
}
