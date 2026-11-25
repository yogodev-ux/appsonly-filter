package com.appsonly.filter

import android.app.Activity
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Our own Settings screen. It offers only the safe, everyday settings, each opening the
 * matching page of the phone's Settings directly. The full Settings app has no icon.
 * Risky options (factory reset, users, VPN, date/time, ...) stay blocked by Device Owner
 * restrictions even if someone navigates around inside one of these pages.
 */
class SettingsActivity : Activity() {

    private data class Item(val title: String, val subtitle: String, val intent: () -> Intent)

    private val items = listOf(
        Item("Wi-Fi", "Connect to a network") { Intent(Settings.ACTION_WIFI_SETTINGS) },
        Item("Mobile data", "Data, roaming, SIM") { Intent(Settings.ACTION_DATA_ROAMING_SETTINGS) },
        Item("Bluetooth", "Pair headphones, car, watch") { Intent(Settings.ACTION_BLUETOOTH_SETTINGS) },
        Item("Sound & volume", "Ringtone, vibration, volume") { Intent(Settings.ACTION_SOUND_SETTINGS) },
        Item("Display", "Brightness, font size, dark mode") { Intent(Settings.ACTION_DISPLAY_SETTINGS) },
        Item("Wallpaper", "Change the background") { Intent(Intent.ACTION_SET_WALLPAPER) },
        Item("Screen lock", "PIN, pattern or fingerprint") { Intent(DevicePolicyManager.ACTION_SET_NEW_PASSWORD) },
        Item("Battery", "Battery use and saver") { Intent(Intent.ACTION_POWER_USAGE_SUMMARY) },
        Item("Language", "Phone language") { Intent(Settings.ACTION_LOCALE_SETTINGS) },
        Item("Keyboard", "Keyboard options") { Intent(Settings.ACTION_INPUT_METHOD_SETTINGS) },
        Item("Accessibility", "Text size, hearing, vision") { Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS) },
    )

    private val handler = Handler(Looper.getMainLooper())

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(32), dp(20), dp(24))
        }
        col.addView(TextView(this).apply {
            text = "Settings"; textSize = 24f; typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(12))
        })
        for (item in items) {
            if (item.intent().resolveActivity(packageManager) == null) continue
            col.addView(row(item.title, item.subtitle) { open(item.intent()) })
        }
        col.addView(row("Add account", "Gmail, Outlook and other email accounts") { addAccount() })
        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        // Back from the add-account flow (or anything else): lock account changes again.
        LockPolicy.closeAccountWindow(this)
    }

    private fun row(title: String, subtitle: String, onClick: () -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(14), 0, dp(14))
            isClickable = true
            setBackgroundResource(android.R.drawable.list_selector_background)
            addView(TextView(this@SettingsActivity).apply { text = title; textSize = 18f })
            addView(TextView(this@SettingsActivity).apply { text = subtitle; textSize = 14f; alpha = 0.6f })
            setOnClickListener { onClick() }
        }

    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Not available on this phone", Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Toast.makeText(this, "Not available on this phone", Toast.LENGTH_SHORT).show()
        }
    }

    private fun addAccount() {
        AlertDialog.Builder(this)
            .setTitle("Add an account")
            .setMessage("Adding accounts is unlocked for up to 10 minutes. Come back here when you're done and it locks again.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Continue") { _, _ ->
                LockPolicy.openAccountWindow(this)
                // Safety net: lock again after the window even if the customer never comes back.
                handler.postDelayed({ (application as FilterApp).enforceAsync() }, LockPolicy.ACCOUNT_WINDOW_MS + 1000)
                open(Intent(Settings.ACTION_ADD_ACCOUNT))
            }
            .show()
    }
}
