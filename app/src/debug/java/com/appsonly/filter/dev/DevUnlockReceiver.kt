package com.appsonly.filter.dev

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.appsonly.filter.LockPolicy

/**
 * DEBUG BUILDS ONLY (this file lives in src/debug, so it is not in release APKs).
 * Unhides every app, clears all restrictions and removes Device Owner, so a test phone
 * goes back to normal. Triggered from a computer with:
 *   adb shell am broadcast -a com.appsonly.filter.DEV_UNLOCK -n com.appsonly.filter/.dev.DevUnlockReceiver
 */
class DevUnlockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i("AppsOnly", "Developer unlock requested")
        LockPolicy.removeEverything(context.applicationContext)
        Log.i("AppsOnly", "Developer unlock done; Device Owner removed")
    }
}
