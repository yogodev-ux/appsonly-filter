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
            override fun onReceive(context: Context, intent: Intent) = enforceAsync()
        }
        registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        enforceAsync()
    }

    fun enforceAsync(done: (() -> Unit)? = null) {
        worker.execute {
            LockPolicy.enforce(this)
            done?.invoke()
        }
    }
}
