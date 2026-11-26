@file:Suppress("DEPRECATION") // Wi-Fi config APIs are deprecated for apps but still allowed for Device Owners

package com.appsonly.filter

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Wi-Fi on/off, network list, connect with password, forget. No system Settings involved. */
@SuppressLint("UseSwitchCompatOrMaterialCode", "MissingPermission")
class WifiActivity : Activity() {

    private lateinit var wm: WifiManager
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wm = applicationContext.getSystemService(WifiManager::class.java)
    }

    override fun onResume() {
        super.onResume()
        val f = IntentFilter().apply {
            addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
        }
        registerReceiver(receiver, f)
        wm.startScan()
        render()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(receiver)
    }

    private fun currentSsid(): String? =
        wm.connectionInfo?.ssid?.trim('"')?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" }

    private fun render() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(32), dp(20), dp(32))
        }
        col.addView(TextView(this).apply { text = "Wi-Fi"; textSize = 24f; typeface = Typeface.DEFAULT_BOLD })
        col.addView(Switch(this).apply {
            text = "Wi-Fi"; textSize = 16f; isChecked = wm.isWifiEnabled
            setPadding(0, dp(12), 0, dp(12))
            setOnCheckedChangeListener { _, on ->
                if (!wm.setWifiEnabled(on)) toast("Couldn't change Wi-Fi")
            }
        })
        if (wm.isWifiEnabled) {
            val current = currentSsid()
            if (current != null) {
                col.addView(TextView(this).apply {
                    text = "Connected to $current"; textSize = 16f; typeface = Typeface.DEFAULT_BOLD
                    setPadding(0, dp(8), 0, dp(8))
                })
            }
            col.addView(TextView(this).apply { text = "Tap a network to connect, hold to forget it"; alpha = 0.65f })
            val networks = wm.scanResults
                .filter { it.SSID.isNotBlank() }
                .groupBy { it.SSID }
                .map { (_, list) -> list.maxBy { it.level } }
                .sortedByDescending { it.level }
            if (networks.isEmpty()) col.addView(TextView(this).apply { text = "Searching…"; setPadding(0, dp(12), 0, 0) })
            for (n in networks) {
                val bars = WifiManager.calculateSignalLevel(n.level, 4) + 1
                col.addView(TextView(this).apply {
                    text = "${n.SSID}   ${"▮".repeat(bars)}${if (isSecured(n)) "  🔒" else ""}"
                    textSize = 17f
                    setPadding(0, dp(14), 0, dp(14))
                    setBackgroundResource(android.R.drawable.list_selector_background)
                    setOnClickListener { connect(n) }
                    setOnLongClickListener { forget(n.SSID); true }
                })
            }
        }
        setContentView(ScrollView(this).apply { addView(col) })
    }

    private fun isSecured(n: ScanResult) =
        listOf("WEP", "PSK", "SAE", "EAP").any { n.capabilities.contains(it) }

    private fun connect(n: ScanResult) {
        val known = savedNetworkId(n.SSID)
        if (known != null) {
            enable(known); return
        }
        if (n.capabilities.contains("EAP")) {
            toast("Work/enterprise Wi-Fi isn't supported yet"); return
        }
        if (!isSecured(n)) {
            add(n, null); return
        }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "Password"
        }
        AlertDialog.Builder(this).setTitle(n.SSID).setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Connect") { _, _ -> add(n, input.text.toString()) }
            .show()
    }

    private fun add(n: ScanResult, password: String?) {
        val conf = WifiConfiguration().apply {
            SSID = "\"${n.SSID}\""
            when {
                password == null -> allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                n.capabilities.contains("WEP") -> {
                    wepKeys[0] = "\"$password\""; wepTxKeyIndex = 0
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                    allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.SHARED)
                }
                n.capabilities.contains("SAE") && !n.capabilities.contains("PSK") &&
                    android.os.Build.VERSION.SDK_INT >= 30 -> {
                    setSecurityParams(WifiConfiguration.SECURITY_TYPE_SAE)
                    preSharedKey = "\"$password\""
                }
                else -> preSharedKey = "\"$password\""
            }
        }
        val id = wm.addNetwork(conf)
        if (id == -1) toast("Couldn't add this network") else enable(id)
    }

    private fun enable(id: Int) {
        wm.disconnect()
        if (!wm.enableNetwork(id, true)) toast("Couldn't connect")
        wm.reconnect()
        toast("Connecting…")
    }

    private fun savedNetworkId(ssid: String): Int? = try {
        wm.configuredNetworks?.firstOrNull { it.SSID == "\"$ssid\"" }?.networkId
    } catch (e: SecurityException) { null }

    private fun forget(ssid: String) {
        val id = savedNetworkId(ssid) ?: run { toast("Not a saved network"); return }
        AlertDialog.Builder(this).setTitle("Forget $ssid?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Forget") { _, _ ->
                toast(if (wm.removeNetwork(id)) "Forgotten" else "Couldn't forget it")
                render()
            }.show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
