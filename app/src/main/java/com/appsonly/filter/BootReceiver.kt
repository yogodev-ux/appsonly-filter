package com.appsonly.filter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Re-applies the lock after a reboot or an update of this app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try {
                LockPolicy.enforce(context.applicationContext)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
