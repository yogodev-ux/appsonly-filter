package com.appsonly.installer

/** Pure text parsing of adb output, kept separate so it can be unit-tested. */
object Parsers {

    data class DeviceLine(val serial: String, val state: String)

    /** `adb devices` output -> list of (serial, state). */
    fun devices(out: String): List<DeviceLine> = out.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("List of devices") && !it.startsWith("*") }
        .mapNotNull { line ->
            val parts = line.split(Regex("\\s+"))
            if (parts.size >= 2) DeviceLine(parts[0], parts[1]) else null
        }

    /** Account types present on the phone (names are never read: they are personal). */
    fun accountTypes(dumpsysAccount: String): Map<String, Int> {
        val counts = linkedMapOf<String, Int>()
        Regex("""Account \{name=.*?, type=([^}\s]+)\}""").findAll(dumpsysAccount).forEach {
            val t = it.groupValues[1]
            counts[t] = (counts[t] ?: 0) + 1
        }
        return counts
    }

    /** Account type -> package of the app that owns ("authenticates") it. */
    fun authenticators(dumpsysAccount: String): Map<String, String> {
        val map = linkedMapOf<String, String>()
        Regex("""AuthenticatorDescription \{type=([^}\s]+)\}, ComponentInfo\{([^/}]+)/""")
            .findAll(dumpsysAccount).forEach { map.putIfAbsent(it.groupValues[1], it.groupValues[2]) }
        return map
    }

    /** User ids from `pm list users`. */
    fun userIds(pmListUsers: String): List<Int> =
        Regex("""UserInfo\{(\d+):""").findAll(pmListUsers).map { it.groupValues[1].toInt() }.toList()

    /** Package of the current Device Owner, from `dumpsys device_policy`, or null. */
    fun deviceOwnerPackage(dumpsysDevicePolicy: String): String? {
        val i = dumpsysDevicePolicy.indexOf("Device Owner")
        if (i < 0) return null
        val section = dumpsysDevicePolicy.substring(i, minOf(dumpsysDevicePolicy.length, i + 600))
        return Regex("""admin=ComponentInfo\{([^/}]+)/""").find(section)?.groupValues?.get(1)
    }

    /** Turns dpm's error text into something a person can act on. */
    fun explainDpmError(out: String): String {
        val o = out.lowercase()
        return when {
            "accounts" in o -> "The phone still has accounts that block setup. Remove all accounts " +
                "(Settings > Accounts), then run setup again."
            "several users" in o || "multiple users" in o || "users" in o && "already" in o ->
                "The phone has more than one user or a work profile. Remove the extra users " +
                    "(Settings > System > Multiple users), then run setup again."
            "device owner is already set" in o || "already has a device owner" in o ->
                "Another management app already controls this phone. It must be removed first."
            "already provisioned" in o || "provisioned" in o ->
                "The phone was already set up in a way that blocks this. A factory reset is needed."
            "unknown admin" in o || "not found" in o -> "The Apps Only app did not install correctly."
            "being removed" in o -> "The phone is still busy from the last attempt. Wait a minute and run setup again."
            else -> "Setup was refused by the phone: " +
                (out.lines().firstOrNull { ("Exception" in it || "Error" in it) && "occurred while executing" !in it } ?: out.lines().firstOrNull { it.isNotBlank() } ?: out).trim()
        }
    }
}
