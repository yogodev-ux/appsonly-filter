package com.appsonly.filter.dev

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.appsonly.filter.License
import com.appsonly.filter.LockPolicy

/**
 * DEBUG BUILDS ONLY (adb, DUMP permission): asks the license server now and reports the result.
 *   adb shell am broadcast -n com.appsonly.filter/.dev.DevLicenseReceiver
 * Result: "paid=<true|false> status=<..> code=<pay code> removed=<true|false> error=<..>"
 */
class DevLicenseReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                if (intent.getBooleanExtra("refresh", true)) {
                    License.refresh(ctx)
                    if (LockPolicy.isDeviceOwner(ctx)) LockPolicy.enforce(ctx)
                }
                val s = License.state(ctx)
                pending.resultData = "paid=${s.paid} status=${s.status} code=${s.payCode} removed=${License.wasRemoved(ctx)} error=${s.lastError}"
            } finally {
                pending.finish()
            }
        }.start()
    }
}
