package com.appsonly.filter

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.UserHandle

class AdminReceiver : DeviceAdminReceiver() {
    companion object {
        fun component(ctx: Context) = ComponentName(ctx, AdminReceiver::class.java)
    }

    override fun onPasswordFailed(context: Context, intent: Intent, user: UserHandle) {
        TamperLog.record(context, "wrong_pin_entered")
    }

    override fun onLockTaskModeEntering(context: Context, intent: Intent, pkg: String) {
        if (Prefs.load(context).locked) KioskMode.setShadeAllowed(context, true)
    }

    override fun onLockTaskModeExiting(context: Context, intent: Intent) {
        if (!Prefs.load(context).locked) return
        KioskMode.setShadeAllowed(context, false)
        TamperLog.record(context, "kiosk_mode_exited", "admin callback")
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        TamperLog.record(context, "admin_disable_requested")
        return "This phone is managed by Apps Only."
    }
}
