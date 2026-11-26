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
    companion object {
        const val EXTRA_ACCOUNT_ONLY = "account_only"
        const val EXTRA_REMOVE_ONLY = "remove_only"
    }

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
        if (intent.getBooleanExtra(EXTRA_REMOVE_ONLY, false)) {
            // From the home screen's "Remove the filter" link.
            removeSection()
        } else if (intent.getBooleanExtra(EXTRA_ACCOUNT_ONLY, false)) {
            // From the home screen's "Google account" tile: everything else is in the
            // phone's real Settings app.
            accountSection()
        } else {
            title("Settings", 24f)
            wifiSection()
            bluetoothSection()
            soundSection()
            displaySection()
            flashlightSection()
            lockSection()
            accountSection()
            removeSection()
        }
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
        val currentLabel = choices.firstOrNull { it.first == current }?.second ?: when {
            current >= 24 * 60 * 60 * 1000 || current < 0 -> "Never"
            current % 60_000 == 0 -> "${current / 60_000} minutes"
            else -> "${current / 1000} seconds"
        }
        row("Screen turns off after: $currentLabel  ›") {
            AlertDialog.Builder(this).setTitle("Screen turns off after")
                .setItems(choices.map { it.second }.toTypedArray()) { _, i ->
                    DeviceControls.setScreenTimeout(this, choices[i].first); build()
                }.show()
        }
    }

    private var torchOn = false

    private fun flashlightSection() {
        val cm = getSystemService(android.hardware.camera2.CameraManager::class.java) ?: return
        val id = try {
            cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (e: Exception) { null } ?: return
        title("Flashlight")
        switch("Flashlight", torchOn) { on ->
            try { cm.setTorchMode(id, on); torchOn = on } catch (e: Exception) { toast("Flashlight not available") }
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

    private fun removeSection() {
        if (!LockPolicy.isDeviceOwner(this) || !Uninstall.hasCode(this)) return
        title("Remove the filter")
        note("Call support. After they check your removal code, the filter is removed the next time the phone goes online. If the phone can't go online, support reads you an uninstall code to type here.")
        row("Type the uninstall code  ›") { askUninstallCode() }
    }

    private fun askUninstallCode() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            hint = "XXXX-XXXX-XXXX"
        }
        AlertDialog.Builder(this).setTitle("Uninstall code").setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Remove the filter") { _, _ ->
                when (val r = Uninstall.check(this, input.text.toString())) {
                    is Uninstall.Result.Right -> {
                        (application as FilterApp).worker.execute {
                            License.removeFilter(this, "with the uninstall code")
                            runOnUiThread {
                                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                                finish()
                            }
                        }
                    }
                    is Uninstall.Result.Wrong -> toast("That code is wrong. ${r.triesLeft} tries left.")
                    is Uninstall.Result.Blocked -> toast("Too many wrong codes. Try again in ${r.minutes} minutes.")
                    is Uninstall.Result.NoCode -> toast("This phone has no uninstall code.")
                }
            }.show()
    }

    private fun accountSection() {
        title("Google account")
        val names = AccountFlow.googleAccountNames(this)
        note(if (names.isEmpty()) "No Google account on this phone." else names.joinToString("\n"))
        row("Add Google account  ›") {
            AccountFlow.addGoogleAccount(this) {
                toast("Google sign-in couldn't open")
                build()
            }
        }
        note("Only Google sign-in opens. It can reach Google's sign-in pages and nothing else.")
    }
}
