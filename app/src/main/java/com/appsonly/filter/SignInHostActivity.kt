package com.appsonly.filter

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * Invisible screen at the bottom of the Google sign-in task. Google's pages open on top of it,
 * inside a task that belongs to us, so [AccountFlow] can close all of them at once. When Google
 * finishes (account added, or cancelled) this comes back to the front and closes itself.
 */
class SignInHostActivity : Activity() {
    companion object { const val EXTRA_INTENT = "google_intent" }

    private var launched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launched = savedInstanceState?.getBoolean("launched") == true
        if (launched) return
        @Suppress("DEPRECATION")
        val google = intent.getParcelableExtra<Intent>(EXTRA_INTENT)
        if (google == null) { done(); return }
        try {
            startActivity(google)
            launched = true
        } catch (e: Exception) {
            Log.w("AppsOnly", "Google sign-in start", e)
            done()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("launched", launched)
    }

    private var resumedOnce = false

    override fun onResume() {
        super.onResume()
        // The first resume is before Google's page shows; any later one means Google is done.
        if (resumedOnce) done() else resumedOnce = true
    }

    private fun done() {
        AccountFlow.end(this)
        finishAndRemoveTask()
    }
}
