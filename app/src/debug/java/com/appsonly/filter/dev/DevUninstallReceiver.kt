package com.appsonly.filter.dev

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.appsonly.filter.Uninstall

/**
 * DEBUG BUILDS ONLY (adb, DUMP permission): checks an uninstall code the same way the
 * Settings box does, without removing anything.
 *   adb shell am broadcast -n com.appsonly.filter/.dev.DevUninstallReceiver --es code XXXX-XXXX-XXXX
 * Result: "right", "wrong <tries left>", "blocked <minutes>" or "nocode".
 */
class DevUninstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        resultData = when (val r = Uninstall.check(context.applicationContext, intent.getStringExtra("code") ?: "")) {
            is Uninstall.Result.Right -> "right"
            is Uninstall.Result.Wrong -> "wrong ${r.triesLeft}"
            is Uninstall.Result.Blocked -> "blocked ${r.minutes}"
            is Uninstall.Result.NoCode -> "nocode"
        }
    }
}
