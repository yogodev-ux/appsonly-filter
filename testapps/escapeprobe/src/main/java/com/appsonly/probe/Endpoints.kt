package com.appsonly.probe

/**
 * TEST ONLY: web addresses built into this app's code, the way a real app has its servers
 * built in. The escape test checks that the filter's app scan finds and sorts them:
 * its own domain (with sub-addresses), a delivery-network name, Facebook's sign-in service
 * (allowed) and general web pages (blocked).
 */
object Endpoints {
    @JvmField val ALL = listOf(
        "https://api.appsonlyprobe.com/v2/sync",
        "https://d1x2y3z4.cloudfront.net/assets/",
        "https://www.facebook.com/sharer/sharer.php",
        "https://graph.facebook.com/v18.0/",
        "https://en.wikipedia.org/wiki/Main_Page",
        "telemetry.probevendor-metrics.io",
    )
}
