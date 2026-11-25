package com.appsonly.filter

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.graphics.Typeface
import android.media.AudioManager
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * Our own Settings screen. Every control here is done by this app itself (as Device Owner),
 * never by opening the phone's Settings app, which stays hidden.
 */
@SuppressLint("UseSwitchCompatOrMaterialCode", "MissingPermission")
class SettingsActivity : Activity() {

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private lateinit var col: LinearLayout

    override fun onResume() {
        super.onResume()
        AccountFlow.end(this)
        build()
    }

    private fun build() {
        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(32), dp(20), dp(32))
        }
        title("Settings", 24f)
        wifiSection()
        bluetoothSection()
        soundSection()
        displaySection()
        lockSection()
        accountSection()
        setContentView(ScrollView(this).apply { addView(col) })
    }

    // ---------- building blocks ----------

    private fun title(s: String, size: Float = 18f) = col.addView(TextView(this).apply {
        text = s; textSize = size; typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(18), 0, dp(4))
    })

    private fun note(s: String) = col.addView(TextView(this).apply {
        text = s; textSize = 14f; alpha = 0.65f
    })

    private fun row(text: String, onClick: () -> Unit) = col.addView(TextView(this).apply {
        this.text = text; textSize = 16f
        setPadding(0, dp(12), 0, dp(12))
        setBackgroundResource(android.R.drawable.list_selector_background)
        setOnClickListener { onClick() }
    })

    private fun switch(text: String, checked: Boolean, onChange: (Boolean) -> Unit) = col.addView(Switch(this).apply {
        this.text = text; textSize = 16f; isChecked = checked
        setPadding(0, dp(8), 0, dp(8))
        setOnCheckedChangeListener { _, b -> onChange(b) }
    })

    private fun slider(label: String, max: Int, value: Int, onChange: (Int) -> Unit) {
        col.addView(TextView(this).apply { text = label; textSize = 15f; setPadding(0, dp(8), 0, 0) })
        col.addView(SeekBar(this).apply {
            this.max = max; progress = value
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) onChange(p) }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        })
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ---------- sections ----------

    private fun wifiSection() {
        title("Wi-Fi")
        row("Wi-Fi networks  ›") { startActivity(Intent(this, WifiActivity::class.java)) }
    }

    private fun bluetoothSection() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return
        title("Bluetooth")
        @Suppress("DEPRECATION")
        switch("Bluetooth", adapter.isEnabled) { on ->
            val ok = try { if (on) adapter.enable() else adapter.disable() } catch (e: Exception) { false }
            if (!ok) toast("Couldn't change Bluetooth on this phone")
        }
        val paired = try { adapter.bondedDevices?.map { it.name ?: it.address } } catch (e: SecurityException) { null }
        if (!paired.isNullOrEmpty()) note("Paired: " + paired.joinToString(", "))
    }

    private fun soundSection() {
        val am = getSystemService(AudioManager::class.java)
        title("Sound")
        for ((label, stream) in listOf(
            "Ringtone & notifications" to AudioManager.STREAM_RING,
            "Media" to AudioManager.STREAM_MUSIC,
            "Alarms" to AudioManager.STREAM_ALARM,
        )) {
            slider(label, am.getStreamMaxVolume(stream), am.getStreamVolume(stream)) { v ->
                try { am.setStreamVolume(stream, v, 0) } catch (e: SecurityException) {
                    toast("Turn on Do Not Disturb access to silence the ringer")
                }
            }
        }
        switch("Vibrate instead of ring", am.ringerMode == AudioManager.RINGER_MODE_VIBRATE) { on ->
            try { am.ringerMode = if (on) AudioManager.RINGER_MODE_VIBRATE else AudioManager.RINGER_MODE_NORMAL }
            catch (e: SecurityException) { toast("Not allowed on this phone") }
        }
    }

    private fun displaySection() {
        title("Display")
        val auto = DeviceControls.readSystemInt(this, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) ==
            Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        switch("Automatic brightness", auto) { DeviceControls.setAutoBrightness(this, it) }
        slider("Brightness", 255, DeviceControls.readSystemInt(this, Settings.System.SCREEN_BRIGHTNESS, 128)) {
            DeviceControls.setBrightness(this, it)
        }
        val choices = listOf(30_000 to "30 seconds", 60_000 to "1 minute", 120_000 to "2 minutes",
            300_000 to "5 minutes", 600_000 to "10 minutes")
        val current = DeviceControls.readSystemInt(this, Settings.System.SCREEN_OFF_TIMEOUT, 60_000)
        val currentLabel = choices.firstOrNull { it.first == current }?.second ?: "${current / 1000} seconds"
        row("Screen turns off after: $currentLabel  ›") {
            AlertDialog.Builder(this).setTitle("Screen turns off after")
                .setItems(choices.map { it.second }.toTypedArray()) { _, i ->
                    DeviceControls.setScreenTimeout(this, choices[i].first); build()
                }.show()
        }
    }

    private fun lockSection() {
        title("Screen lock")
        if (!DeviceControls.pinReady(this)) {
            note("Unlock the phone once more, then you can change the PIN here.")
            return
        }
        row("Set or change PIN  ›") { askPin() }
        row("Remove PIN") {
            AlertDialog.Builder(this).setTitle("Remove the PIN?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove") { _, _ ->
                    toast(if (DeviceControls.setPin(this, "")) "PIN removed" else "Couldn't remove the PIN")
                }.show()
        }
    }

    private fun askPin() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "4 or more digits"
        }
        AlertDialog.Builder(this).setTitle("New PIN").setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val pin = input.text.toString()
                when {
                    pin.length < 4 -> toast("Use at least 4 digits")
                    DeviceControls.setPin(this, pin) -> toast("PIN saved")
                    else -> toast("Couldn't set the PIN")
                }
            }.show()
    }

    private fun accountSection() {
        title("Google account")
        val names = AccountFlow.googleAccountNames(this)
        note(if (names.isEmpty()) "No Google account on this phone." else names.joinToString("\n"))
        row("Add Google account  ›") {
            AccountFlow.addGoogleAccount(this) { added ->
                toast(if (added) "Account added" else "No account added")
                build()
            }
        }
        note("Only Google sign-in opens. It can reach Google's sign-in pages and nothing else.")
    }
}
