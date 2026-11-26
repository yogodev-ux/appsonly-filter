package com.appsonly.filter

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.TextView

/**
 * Sends every link into the Play Store (market://..., play.google.com/...) to our own
 * "not available" screen, so the store can't be opened from inside an allowed app.
 * The store itself keeps running in the background to update allowed apps.
 */
object StoreGuard {
    fun filters(): List<IntentFilter> {
        val market = IntentFilter(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addCategory(Intent.CATEGORY_BROWSABLE)
            addDataScheme("market")
        }
        val web = IntentFilter(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addCategory(Intent.CATEGORY_BROWSABLE)
            addDataScheme("http")
            addDataScheme("https")
            addDataAuthority("play.google.com", null)
            addDataAuthority("market.android.com", null)
        }
        return listOf(market, web)
    }

    fun apply(ctx: Context, dpm: DevicePolicyManager, admin: ComponentName) {
        val target = ComponentName(ctx, BlockedActivity::class.java)
        for (f in filters()) {
            try { dpm.addPersistentPreferredActivity(admin, f, target) } catch (e: Exception) {
                Log.w("AppsOnly", "store guard", e)
            }
        }
    }
}

/** Shown instead of the Play Store or any other blocked screen. */
class BlockedActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = "This isn't available on this phone."
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            setOnClickListener { finish() }
        })
    }
}
