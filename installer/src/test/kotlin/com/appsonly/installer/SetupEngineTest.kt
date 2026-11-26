package com.appsonly.installer

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ACCOUNTS = """
User UserInfo{0:Owner:c13}:
  Accounts: 2
    Account {name=someone@gmail.com, type=com.google}
    Account {name=tester, type=com.appsonly.testaccount}

  RegisteredServicesCache: 2 services
    ServiceInfo: AuthenticatorDescription {type=com.google}, ComponentInfo{com.google.android.gms/com.google.android.gms.auth.account.authenticator.GoogleAccountAuthenticatorService}, uid 10123
    ServiceInfo: AuthenticatorDescription {type=com.appsonly.testaccount}, ComponentInfo{com.appsonly.testaccount/com.appsonly.testaccount.Authenticator}, uid 10200
"""

private const val OWNER_DUMP = """
Current Device Policy Manager state:
  Immutable state:
    mHasFeature=true
  Device Owner:
    admin=ComponentInfo{com.appsonly.filter/com.appsonly.filter.AdminReceiver}
    name=
    package=com.appsonly.filter
"""

/** A pretend phone. */
open class FakeAdb(
    var accounts: String = ACCOUNTS,
    var owner: String? = null,
    var users: String = "Users:\n\tUserInfo{0:Owner:c13} running",
    var sdk: String = "33",
    var manufacturer: String = "Google",
    var model: String = "Pixel 6",
    var devices: String = "List of devices attached\nABC123\tdevice\n",
) : Adb("adb") {
    val calls = mutableListOf<String>()
    val disabled = mutableSetOf<String>()

    override fun run(vararg args: String, timeoutSec: Long): CmdResult {
        val cmd = args.joinToString(" ")
        calls += cmd
        val sh = if (args.firstOrNull() == "shell") args.drop(1).joinToString(" ") else null
        return when {
            cmd == "start-server" -> CmdResult(0, "")
            cmd == "devices" -> CmdResult(0, devices)
            args.firstOrNull() == "install" -> CmdResult(0, "Performing Streamed Install\nSuccess")
            sh == null -> CmdResult(0, "")
            sh == "getprop ro.build.version.sdk" -> CmdResult(0, sdk)
            sh == "getprop ro.build.version.release" -> CmdResult(0, "13")
            sh == "getprop ro.product.manufacturer" -> CmdResult(0, manufacturer)
            sh == "getprop ro.product.model" -> CmdResult(0, model)
            sh == "dumpsys device_policy" -> CmdResult(0, owner?.let { OWNER_DUMP.replace("com.appsonly.filter", it) } ?: "Current Device Policy Manager state:\n")
            sh == "pm list users" -> CmdResult(0, users)
            sh == "dumpsys account" -> CmdResult(0, accounts)
            sh.startsWith("pm disable-user") -> { val p = sh.split(" ").last(); disabled += p; CmdResult(0, "Package $p new state: disabled-user") }
            sh.startsWith("pm enable") -> { val p = sh.split(" ").last(); disabled -= p; CmdResult(0, "Package $p new state: enabled") }
            sh.startsWith("dpm set-device-owner") ->
                if (disabled.containsAll(listOf("com.google.android.gms", "com.appsonly.testaccount")) || !accounts.contains("Account {")) {
                    owner = "com.appsonly.filter"; CmdResult(0, "Success: Device owner set to package com.appsonly.filter\nActive admin set to component {com.appsonly.filter/com.appsonly.filter.AdminReceiver}")
                } else CmdResult(255, "java.lang.IllegalStateException: Not allowed to set the device owner because there are already some accounts on the device")
            sh.startsWith("am start") -> CmdResult(0, "Starting: Intent")
            else -> CmdResult(0, "")
        }
    }
}

class SetupEngineTest {
    private val tmp: File = Files.createTempDirectory("aos").toFile()
    private val apk = File(tmp, "apps-only.apk").apply { writeText("x") }
    private fun engine(adb: FakeAdb) = SetupEngine(adb, log = SetupLog(File(tmp, "logs")), stateDir = tmp, sleeper = {})

    @Test fun parsesAccountsWithoutNames() {
        assertEquals(mapOf("com.google" to 1, "com.appsonly.testaccount" to 1), Parsers.accountTypes(ACCOUNTS))
        assertEquals("com.google.android.gms", Parsers.authenticators(ACCOUNTS)["com.google"])
    }

    @Test fun parsesOwnerAndUsers() {
        assertEquals("com.appsonly.filter", Parsers.deviceOwnerPackage(OWNER_DUMP))
        assertNull(Parsers.deviceOwnerPackage("nothing here"))
        assertEquals(listOf(0, 10), Parsers.userIds("UserInfo{0:Owner:c13} running\nUserInfo{10:Work:1030}"))
    }

    @Test fun accountTrickSwitchesAppsOffAndBackOn() {
        val adb = FakeAdb()
        assertTrue(engine(adb).install(apk))
        assertEquals("com.appsonly.filter", adb.owner)
        assertTrue(adb.disabled.isEmpty(), "account apps must be switched back on")
        assertFalse(File(tmp, "pending-ABC123.txt").exists())
        val log = File(tmp, "logs").listFiles()!!.single().readText()
        assertFalse(log.contains("someone@gmail.com"), "account names must never be logged")
        assertTrue(log.contains("RESULT success"))
    }

    @Test fun appsComeBackOnEvenWhenDpmFails() {
        val adb = object : FakeAdb() {
            override fun run(vararg args: String, timeoutSec: Long): CmdResult =
                if (args.joinToString(" ").contains("dpm set-device-owner")) CmdResult(255, "already several users")
                else super.run(*args, timeoutSec = timeoutSec)
        }
        assertFalse(engine(adb).install(apk))
        assertTrue(adb.disabled.isEmpty())
    }

    @Test fun blocksOldAndroidOtherOwnerAndExtraUsers() {
        assertFalse(engine(FakeAdb(sdk = "28")).check().canInstall)
        assertFalse(engine(FakeAdb(owner = "com.other.mdm")).check().canInstall)
        assertFalse(engine(FakeAdb(users = "UserInfo{0:Owner:c13}\nUserInfo{999:XSpace:801010}")).check().canInstall)
        assertFalse(engine(FakeAdb(manufacturer = "HUAWEI", model = "P40")).check().canInstall)
    }

    @Test fun noPhoneOrUnauthorized() {
        assertFalse(engine(FakeAdb(devices = "List of devices attached\n")).check().canInstall)
        val c = engine(FakeAdb(devices = "List of devices attached\nABC\tunauthorized\n")).check()
        assertTrue(c.blockers.single().contains("Allow"))
    }

    @Test fun alreadyInstalledJustUpdates() {
        val adb = FakeAdb(owner = "com.appsonly.filter")
        assertTrue(engine(adb).install(apk))
        assertTrue(adb.calls.none { it.contains("disable-user") || it.contains("set-device-owner") })
    }

    @Test fun modelList() {
        val m = SupportedModels.bundled()
        assertEquals(Support.SUPPORTED, m.check("Google", "Pixel 7a").support)
        assertEquals(Support.WARNING, m.check("Xiaomi", "2201117TG").support)
        assertEquals(Support.UNKNOWN, m.check("Acme", "Phone").support)
        assertEquals(Support.SUPPORTED, m.check("Google", "sdk_gphone64_x86_64").support)
    }
}
