package com.appsonly.filter

/** Settings for tamper protection. */
object TamperConfig {
    /**
     * Google account(s) allowed to set up the phone after a factory reset done from recovery
     * mode (Factory Reset Protection). This is the account's numeric Google ID, not the email.
     * Empty = FRP not set yet. TODO: fill in once the company Google account exists.
     */
    val FRP_ACCOUNT_IDS: List<String> = emptyList()
}
