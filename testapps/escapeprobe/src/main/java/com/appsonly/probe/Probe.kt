package com.appsonly.probe

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.TextView

/**
 * Opens whatever the test asks for, from inside this (allowed) app:
 *   am start -n com.appsonly.probe/.Probe --es cmp pkg/cls        explicit screen
 *   am start -n com.appsonly.probe/.Probe --es action A --es data D --es pkg P
 *   --ez newtask true   adds FLAG_ACTIVITY_NEW_TASK (default: open inside this app's task)
 * Logs "PROBE ok|error <what>" (logcat tag EscapeProbe).
 *
 * Network checks (no screen opens):
 *   --es url https://host/     fetch it the way an app would (uses the phone's proxy setting)
 *   --es proxy 10.0.2.2:8899   with url: go through that proxy, like an app on a Wi-Fi with a proxy
 *   --es tcp 1.2.3.4:443       open a plain connection to that address
 * Log "NET ok <what> <detail>" or "NET error <what> <reason>".
 */
class Probe : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "Escape probe"; textSize = 24f })
        val x = intent
        x.getStringExtra("url")?.let { u -> net(u) { fetch(u, x.getStringExtra("proxy")) }; return }
        x.getStringExtra("tcp")?.let { a -> net(a) { connect(a) }; return }
        val what = x.getStringExtra("cmp") ?: x.getStringExtra("action") ?: return
        val i = Intent()
        x.getStringExtra("cmp")?.let { i.component = ComponentName.unflattenFromString(it) }
        x.getStringExtra("action")?.let { i.action = it }
        val data = x.getStringExtra("data")?.let(Uri::parse)
        val type = x.getStringExtra("type")
        when {
            data != null && type != null -> i.setDataAndType(data, type)
            data != null -> i.data = data
            type != null -> i.type = type
        }
        x.getStringExtra("pkg")?.let { i.setPackage(it) }
        x.getStringExtra("cat")?.let { i.addCategory(it) }
        if (x.getBooleanExtra("newtask", false)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(i)
            Log.i("EscapeProbe", "PROBE ok $what")
        } catch (e: Exception) {
            Log.i("EscapeProbe", "PROBE error $what ${e.javaClass.simpleName}")
        }
    }

    private fun net(what: String, block: () -> String) {
        Thread {
            val line = try { "NET ok $what ${block()}" } catch (e: Exception) { "NET error $what ${e.javaClass.simpleName}: ${e.message}" }
            Log.i("EscapeProbe", line)
        }.start()
    }

    private fun fetch(u: String, proxy: String?): String {
        val url = java.net.URL(u)
        val c = (if (proxy == null) url.openConnection() else {
            val (h, p) = proxy.split(":")
            url.openConnection(java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress(h, p.toInt())))
        }) as java.net.HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 10_000
        try { return "HTTP ${c.responseCode}" } finally { c.disconnect() }
    }

    private fun connect(a: String): String {
        val (h, p) = a.split(":")
        java.net.Socket().use { it.connect(java.net.InetSocketAddress(h, p.toInt()), 10_000) }
        return "connected"
    }
}
