package com.appsonly.installer

import java.io.File

interface SetupListener {
    fun step(text: String) {}
    fun info(text: String) {}
    fun warn(text: String) {}
    fun error(text: String) {}
}

data class PhoneCheck(
    val serial: String = "",
    val manufacturer: String = "",
    val model: String = "",
    val androidVersion: String = "",
    val sdk: Int = 0,
    val alreadyInstalled: Boolean = false,
    /** Apps that own accounts on the phone; switched off for a moment during setup. */
    val accountApps: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val blockers: List<String> = emptyList(),
) {
    val canInstall get() = blockers.isEmpty()
    val name get() = "$manufacturer $model (Android $androidVersion)".trim()
}

/**
 * The setup itself, shared by the window and the command line:
 *  1. find exactly one phone with USB debugging allowed
 *  2. check Android version, model list, other management apps, extra users, accounts
 *  3. install the Apps Only app
 *  4. switch off the apps that own accounts for a moment (the "account trick"), so Android
 *     allows Device Owner without a factory reset, then switch them straight back on
 *  5. make the app Device Owner and open it; the customer picks their apps on the phone
 *  6. save the log on this PC and upload it (once the server exists)
 */
class SetupEngine(
    private val adb: Adb,
    private val config: Config = Config.bundled(),
    private val models: SupportedModels = SupportedModels.bundled(),
    private val log: SetupLog = SetupLog(SetupLog.defaultDir()),
    private val stateDir: File = SetupLog.defaultDir().parentFile,
    private val listener: SetupListener = object : SetupListener {},
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
) {
    val setupLog get() = log

    private fun step(t: String) { log.add("STEP  $t"); listener.step(t) }
    private fun info(t: String) { log.add("INFO  $t"); listener.info(t) }
    private fun warn(t: String) { log.add("WARN  $t"); listener.warn(t) }
    private fun error(t: String) { log.add("ERROR $t"); listener.error(t) }

    /** Picks the phone to work on, or returns why it can't. */
    fun findPhone(): Pair<String?, String?> {
        adb.run("start-server", timeoutSec = 60)
        val list = Parsers.devices(adb.run("devices").out)
        val ready = list.filter { it.state == "device" }
        return when {
            ready.size == 1 -> ready[0].serial to null
            ready.size > 1 -> null to "More than one phone is connected. Connect only the phone you want to set up."
            list.any { it.state == "unauthorized" } ->
                null to "On the phone, tap \"Allow\" on the USB debugging message (tick \"Always allow from this computer\")."
            list.any { it.state == "offline" } ->
                null to "The phone is connected but not answering. Unplug it, plug it back in, and try again."
            else -> null to "No phone found. Connect it with a USB cable and turn on USB debugging " +
                "(Settings > About phone > tap Build number 7 times, then Developer options > USB debugging)."
        }
    }

    fun check(): PhoneCheck {
        step("Checking the phone")
        val (serial, problem) = findPhone()
        if (serial == null) {
            error(problem!!)
            return PhoneCheck(blockers = listOf(problem))
        }
        adb.serial = serial
        restorePendingApps(serial)

        val warnings = mutableListOf<String>()
        val blockers = mutableListOf<String>()
        val manufacturer = adb.prop("ro.product.manufacturer")
        val model = adb.prop("ro.product.model")
        val release = adb.prop("ro.build.version.release")
        val sdk = adb.prop("ro.build.version.sdk").toIntOrNull() ?: 0
        info("Phone: $manufacturer $model, Android $release (SDK $sdk)")

        if (sdk < models.minSdk) blockers += "This phone runs Android $release. Apps Only needs Android 10 or newer."

        val verdict = models.check(manufacturer, model)
        info("Model check: ${verdict.support.name.lowercase()}${if (verdict.note.isNotBlank()) " - ${verdict.note}" else ""}")
        when (verdict.support) {
            Support.BLOCKED -> blockers += "This model can't be used: ${verdict.note}"
            Support.WARNING, Support.UNKNOWN -> if (verdict.note.isNotBlank()) warnings += verdict.note
            Support.SUPPORTED -> if (verdict.note.isNotBlank()) info(verdict.note)
        }

        val owner = Parsers.deviceOwnerPackage(adb.shell("dumpsys device_policy").out)
        val already = owner == config.pkg
        when {
            already -> info("Apps Only is already set up on this phone; setup will update the app.")
            owner != null -> blockers += "Another management app ($owner) already controls this phone. It must be removed first."
        }

        var accountApps = emptyList<String>()
        if (!already) {
            val users = Parsers.userIds(adb.shell("pm list users").out)
            if (users.any { it != 0 }) {
                blockers += "The phone has more than one user, a guest, a work profile or a second space " +
                    "(${users.size} in total). Remove the extra ones, then run setup again."
            }
            val dump = adb.shell("dumpsys account").out
            val types = Parsers.accountTypes(dump)
            val owners = Parsers.authenticators(dump)
            if (types.isNotEmpty()) {
                info("Accounts on the phone: " + types.entries.joinToString { "${it.value} x ${it.key}" })
                val missing = types.keys.filter { owners[it] == null }
                if (missing.isNotEmpty()) {
                    blockers += "Some accounts can't be handled automatically (${missing.joinToString()}). " +
                        "Remove them in Settings > Accounts, then run setup again."
                }
                accountApps = types.keys.mapNotNull { owners[it] }.filter { it != "android" }.distinct()
                if (accountApps.isNotEmpty()) info("Will switch off for a moment: ${accountApps.joinToString()}")
            } else {
                info("No accounts on the phone.")
            }
        }

        warnings.forEach { warn(it) }
        blockers.forEach { error(it) }
        return PhoneCheck(serial, manufacturer, model, release, sdk, already, accountApps, warnings, blockers)
    }

    /** Runs the whole setup. Returns true when the phone is ready for the customer. */
    fun install(apk: File): Boolean {
        log.add("Apps Only setup, APK ${apk.name} (${apk.length()} bytes)")
        val result = try { doInstall(apk) } catch (e: Exception) {
            error("Unexpected problem: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
        log.add(if (result) "RESULT success" else "RESULT failed")
        info(log.upload(config.uploadUrl))
        log.save()?.let { info("Log saved: ${it.absolutePath}") }
        return result
    }

    private fun doInstall(apk: File): Boolean {
        if (!apk.isFile) { error("The Apps Only app file is missing: ${apk.absolutePath}"); return false }
        val phone = check()
        if (!phone.canInstall) return false

        step("Installing the Apps Only app")
        val inst = adb.run("install", "-r", "-t", apk.absolutePath, timeoutSec = 300)
        if (!inst.ok || !inst.out.contains("Success")) {
            error("Could not install the app: ${inst.out.lines().lastOrNull() ?: inst.out}")
            return false
        }
        info("App installed.")

        if (phone.alreadyInstalled) {
            openApp()
            info("Done. The app was updated; the phone stays locked as before.")
            return true
        }

        step("Making Apps Only the phone's manager (Device Owner)")
        val disabled = mutableListOf<String>()
        val ownerOk: Boolean
        try {
            if (phone.accountApps.isNotEmpty()) {
                savePending(phone.serial, phone.accountApps)
                for (pkg in phone.accountApps) {
                    val r = adb.shell("pm disable-user --user 0 $pkg")
                    if (r.out.contains("disabled-user")) {
                        disabled += pkg
                        info("Switched off for a moment: $pkg")
                    } else {
                        error("Could not switch off $pkg (${r.out}). Remove the accounts in Settings > Accounts, then run setup again.")
                        return false
                    }
                }
                sleeper(3000) // let Android notice the accounts are gone
            }
            ownerOk = setDeviceOwner()
        } finally {
            restoreApps(phone.serial, disabled)
        }
        if (!ownerOk) return false

        val owner = Parsers.deviceOwnerPackage(adb.shell("dumpsys device_policy").out)
        if (owner != config.pkg) { error("Device Owner did not stick (owner is ${owner ?: "nobody"})."); return false }
        info("Apps Only is now the phone's manager.")

        openApp()
        info("Done. On the phone, pick the apps to allow, then tap Lock.")
        return true
    }

    private fun setDeviceOwner(): Boolean {
        var last = ""
        for (attempt in 1..2) {
            val r = adb.shell("dpm set-device-owner --user 0 ${config.adminComponent}")
            last = r.out
            val summary = r.out.lines().filter { it.isNotBlank() && !it.trimStart().startsWith("at ") }.take(4).joinToString(" / ")
            log.add("dpm: $summary")
            if (!r.out.contains("Success")) info("Phone said: $summary")
            if (r.out.contains("Success") || r.out.contains("Active admin set")) return true
            // Android can take a moment to notice the accounts are gone; try once more.
            if (attempt == 1 && r.out.lowercase().contains("account")) sleeper(4000) else break
        }
        error(Parsers.explainDpmError(last))
        return false
    }

    private fun openApp() {
        adb.shell("am start -n ${config.mainComponent}")
    }

    // If setup is interrupted while account apps are switched off, the next run switches them back on.
    private fun pendingFile(serial: String) = File(stateDir, "pending-${serial.replace(Regex("[^A-Za-z0-9._-]"), "_")}.txt")

    private fun savePending(serial: String, pkgs: List<String>) {
        try { stateDir.mkdirs(); pendingFile(serial).writeText(pkgs.joinToString("\n")) } catch (_: Exception) {}
    }

    private fun restorePendingApps(serial: String) {
        val f = pendingFile(serial)
        if (!f.isFile) return
        val pkgs = f.readLines().filter { it.isNotBlank() }
        warn("A previous setup was interrupted; switching these back on: ${pkgs.joinToString()}")
        restoreApps(serial, pkgs)
    }

    private fun restoreApps(serial: String, pkgs: List<String>) {
        var allOk = true
        for (pkg in pkgs) {
            val r = adb.shell("pm enable --user 0 $pkg")
            if (r.out.contains("enabled")) info("Switched back on: $pkg")
            else { allOk = false; error("Could not switch $pkg back on: ${r.out}") }
        }
        if (allOk) pendingFile(serial).delete()
    }
}
