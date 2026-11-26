package com.appsonly.filter

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

/**
 * One screen, three states:
 *  - not Device Owner yet  -> shows the adb command to run
 *  - Device Owner, not locked -> first-run setup (pick allowed apps; Phone and Messages are
 *    normal entries in the list)
 *  - locked -> the home screen with only the allowed apps
 */
class MainActivity : Activity() {

    private val app get() = application as FilterApp

    override fun onResume() {
        super.onResume()
        AccountFlow.end(this)
        render()
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(licensePoll)
        polling = false
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** While the phone is locked but not paid yet, ask the server every 30 seconds. */
    private var polling = false

    private val licensePoll = object : Runnable {
        override fun run() {
            val cfg = Prefs.load(this@MainActivity)
            if (!cfg.locked || License.isPaid(this@MainActivity)) { polling = false; return }
            checkLicense()
            main.postDelayed(this, 30_000)
        }
    }

    private fun checkLicense() {
        app.worker.execute {
            License.refresh(this)
            // Re-apply the lock: shows (or hides) the allowed apps to match.
            LockPolicy.enforce(this)
            runOnUiThread { if (!isFinishing) render() }
        }
    }

    private fun render() {
        val cfg = Prefs.load(this)
        when {
            !LockPolicy.isDeviceOwner(this) -> showNotOwner()
            !cfg.locked -> showSetup()
            !License.isPaid(this) -> {
                showPayGate()
                KioskMode.enter(this)
                if (!polling) { polling = true; main.post(licensePoll) }
            }
            else -> {
                showHome(cfg)
                // Backup for the admin callback: if kiosk mode was switched off behind our
                // back, record it before turning it on again.
                if (KioskMode.isOff(this)) TamperLog.record(this, "kiosk_mode_exited", "seen on home screen")
                KioskMode.enter(this)
                // Catch anything installed since last time, then refresh the icons.
                app.enforceAsync { runOnUiThread { if (!isFinishing) showHome(Prefs.load(this)) } }
            }
        }
    }

    @Deprecated("Back does nothing on the home screen")
    override fun onBackPressed() {
        if (!Prefs.load(this).locked) @Suppress("DEPRECATION") super.onBackPressed()
    }

    // ---------- helpers ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun column(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(32), dp(20), dp(16))
    }

    private fun text(s: String, size: Float = 16f, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(6), 0, dp(6))
    }

    // ---------- locked but not paid yet (step 4) ----------

    private fun showPayGate() {
        val st = License.state(this)
        val col = column()
        col.addView(text("Almost done", 24f, bold = true))
        col.addView(text("Your apps open as soon as this phone is paid. It's a one-time payment, with no subscription."))
        if (st.payCode.isEmpty()) {
            col.addView(text("Connecting to Apps Only... Make sure the phone is on Wi-Fi (Settings below).", 16f))
        } else {
            col.addView(text("On a computer or another phone, go to:", 16f))
            col.addView(text(st.payUrl, 18f, bold = true).apply { setTextIsSelectable(true) })
            col.addView(text("Phone code: ${st.payCode}", 20f, bold = true).apply { tag = "pay_code" })
        }
        if (st.lastError.isNotEmpty()) {
            col.addView(text("Couldn't reach Apps Only. Check Wi-Fi in Settings. (${st.lastError})", 14f).apply {
                setTextColor(Color.rgb(176, 0, 32))
            })
        }
        col.addView(Button(this).apply {
            text = "Check again"
            setOnClickListener { isEnabled = false; checkLicense() }
        })
        col.addView(Button(this).apply {
            text = "Settings (Wi-Fi)"
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        })
        setContentView(col)
    }

    // ---------- state 1: not Device Owner ----------

    private fun showNotOwner() {
        val col = column()
        col.addView(text("Apps Only is not set up yet", 22f, bold = true))
        col.addView(text(
            "Connect this phone to a computer with USB debugging on, remove all accounts, " +
                "then run:\n\nadb shell dpm set-device-owner --user 0 com.appsonly.filter/.AdminReceiver\n\n" +
                "Then open this app again."
        ))
        setContentView(col)
    }

    // ---------- state 2: first-run setup ----------

    private fun showSetup() {
        val col = column()
        col.addView(text("Choose allowed apps", 22f, bold = true))
        col.addView(text("Only these apps will be usable. After you lock, this list can't be changed on the phone."))


        val loading = ProgressBar(this)
        col.addView(loading)
        val list = ListView(this)
        col.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val lock = Button(this).apply { text = "Lock phone"; isEnabled = false }
        col.addView(lock)
        setContentView(col)

        val chosen = mutableSetOf<String>()
        app.worker.execute {
            val entries = AppScanner.pickable(this)
            runOnUiThread {
                loading.visibility = View.GONE
                list.adapter = PickAdapter(this, entries, chosen)
                lock.isEnabled = true
            }
        }

        lock.setOnClickListener {
            // Picking the phone / messages app is what allows calls / texts.
            val allowPhone = AppScanner.defaultDialer(this)?.let { it in chosen } == true
            val allowSms = AppScanner.defaultSms(this)?.let { it in chosen } == true
            val summary = buildString {
                append("${chosen.size} app(s) allowed.\n")
                append("Phone calls: ${if (allowPhone) "allowed" else "blocked"}\n")
                append("Text messages: ${if (allowSms) "allowed" else "blocked"}\n\n")
                append("All other apps and browsers will be hidden, and new apps can't be installed. ")
                append("Allowed apps keep updating automatically. ")
                append("This can't be changed on the phone.")
            }
            AlertDialog.Builder(this)
                .setTitle("Lock this phone?")
                .setMessage(summary)
                .setNegativeButton("Go back", null)
                .setPositiveButton("Lock") { _, _ ->
                    Prefs.saveLocked(this, chosen.toSet(), allowPhone, allowSms)
                    setContentView(column().apply { addView(text("Locking…", 20f, bold = true)); addView(ProgressBar(this@MainActivity)) })
                    app.enforceAsync { runOnUiThread { render() } }
                }
                .show()
        }
    }

    private class PickAdapter(
        val ctx: Context,
        val items: List<AppEntry>,
        val chosen: MutableSet<String>,
    ) : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val item = items[position]
            val box = (convertView as? CheckBox) ?: CheckBox(ctx).apply {
                textSize = 16f
                compoundDrawablePadding = (12 * ctx.resources.displayMetrics.density).toInt()
                setPadding(0, 16, 0, 16)
            }
            box.setOnCheckedChangeListener(null)
            box.text = item.label
            box.isChecked = item.packageName in chosen
            box.setCompoundDrawablesRelative(null, null, icon(ctx, item.packageName, 40), null)
            box.setOnCheckedChangeListener { _, checked ->
                if (checked) chosen += item.packageName else chosen -= item.packageName
            }
            return box
        }
    }

    // ---------- state 3: locked home screen ----------

    private fun showHome(cfg: LockConfig) {
        // The phone's real Settings app is a normal icon (accounts are added there too).
        val entries = LockPolicy.visiblePackages(this, cfg)
                .filter { packageManager.getLaunchIntentForPackage(it) != null }
                .map { AppEntry.of(this, it) }
                .sortedBy { it.label.lowercase() }

        val col = column().apply { setBackgroundColor(Color.rgb(245, 246, 248)) }
        col.addView(text("Apps Only", 14f).apply { gravity = Gravity.CENTER; setTextColor(Color.GRAY) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val grid = GridView(this).apply {
            numColumns = 4
            verticalSpacing = dp(16)
            adapter = HomeAdapter(this@MainActivity, entries)
            setOnItemClickListener { _, _, pos, _ ->
                // Apps open only inside kiosk mode, so an app can't lead anywhere else
                // (for example while a slow phone is still starting up).
                if (!KioskMode.isOn(this@MainActivity)) {
                    KioskMode.enter(this@MainActivity)
                    if (!KioskMode.isOn(this@MainActivity)) {
                        Toast.makeText(this@MainActivity, "Starting up, try again in a moment", Toast.LENGTH_SHORT).show()
                        return@setOnItemClickListener
                    }
                }
                if (entries[pos].packageName == SETTINGS_TILE) {
                    startActivity(Intent(this@MainActivity, SettingsActivity::class.java)
                        .putExtra(SettingsActivity.EXTRA_ACCOUNT_ONLY, true))
                    return@setOnItemClickListener
                }
                val launch = packageManager.getLaunchIntentForPackage(entries[pos].packageName)
                if (launch == null) {
                    Toast.makeText(this@MainActivity, "This app isn't available", Toast.LENGTH_SHORT).show()
                } else {
                    startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }
        col.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(col)
    }

    private class HomeAdapter(val ctx: Context, val items: List<AppEntry>) : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val d = ctx.resources.displayMetrics.density
            val cell = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            cell.addView(ImageView(ctx).apply { setImageDrawable(icon(ctx, items[position].packageName, 56)) },
                LinearLayout.LayoutParams((56 * d).toInt(), (56 * d).toInt()))
            cell.addView(TextView(ctx).apply {
                text = items[position].label
                textSize = 12f
                gravity = Gravity.CENTER
                maxLines = 2
            })
            return cell
        }
    }

    companion object {
        /** Home-screen tile that opens our own Settings screen. */
        const val SETTINGS_TILE = "#settings"

        fun icon(ctx: Context, pkg: String, sizeDp: Int): Drawable? = if (pkg == SETTINGS_TILE) {
            ctx.getDrawable(android.R.drawable.ic_menu_preferences)?.also {
                val px = (sizeDp * ctx.resources.displayMetrics.density).toInt()
                it.setBounds(0, 0, px, px)
            }
        } else try {
            ctx.packageManager.getApplicationIcon(pkg).also {
                val px = (sizeDp * ctx.resources.displayMetrics.density).toInt()
                it.setBounds(0, 0, px, px)
            }
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }
}
