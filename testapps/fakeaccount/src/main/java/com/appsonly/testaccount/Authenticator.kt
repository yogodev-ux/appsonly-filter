package com.appsonly.testaccount

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder

const val TYPE = "com.appsonly.testaccount"

class Authenticator : Service() {
    private val impl by lazy { Impl(this) }
    override fun onBind(intent: Intent): IBinder? = impl.iBinder

    private class Impl(ctx: Context) : AbstractAccountAuthenticator(ctx) {
        override fun editProperties(r: AccountAuthenticatorResponse?, t: String?): Bundle? = null
        override fun addAccount(r: AccountAuthenticatorResponse?, t: String?, a: String?, f: Array<out String>?, o: Bundle?): Bundle? = null
        override fun confirmCredentials(r: AccountAuthenticatorResponse?, a: Account?, o: Bundle?): Bundle? = null
        override fun getAuthToken(r: AccountAuthenticatorResponse?, a: Account?, t: String?, o: Bundle?): Bundle? = null
        override fun getAuthTokenLabel(t: String?): String? = null
        override fun updateCredentials(r: AccountAuthenticatorResponse?, a: Account?, t: String?, o: Bundle?): Bundle? = null
        override fun hasFeatures(r: AccountAuthenticatorResponse?, a: Account?, f: Array<out String>?): Bundle =
            Bundle().apply { putBoolean(AccountManager.KEY_BOOLEAN_RESULT, false) }
    }
}

/** adb shell am broadcast -n com.appsonly.testaccount/.AddAccountReceiver */
class AddAccountReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ok = AccountManager.get(context).addAccountExplicitly(Account("tester", TYPE), null, null)
        resultData = if (ok) "ADDED" else "EXISTS"
    }
}
