package com.appsonly.installer

import java.awt.GraphicsEnvironment
import java.io.File
import kotlin.system.exitProcess

/** Folders to look in for adb and the app file: where the installer lives, and one up. */
fun baseDirs(): List<File> {
    val dirs = mutableListOf<File>()
    try {
        val here = File(SetupEngine::class.java.protectionDomain.codeSource.location.toURI()).parentFile
        dirs += here
        here.parentFile?.let { dirs += it }
    } catch (_: Exception) {}
    dirs += File(System.getProperty("user.dir"))
    return dirs.distinct()
}

fun findApk(dirs: List<File>): File? =
    dirs.map { File(it, "apps-only.apk") }.firstOrNull { it.isFile }

/**
 * Apps Only setup.
 *   Window (default):  AppsOnlySetup.exe
 *   Command line:      java -jar apps-only-setup.jar --cli [--yes] [--apk FILE] [--adb FILE] [--log-dir DIR]
 *                      --name NAME --phone NUMBER --email ADDRESS --removal-code CODE (needed for a new setup)
 *                      --check only checks the phone.
 */
fun main(args: Array<String>) {
    fun opt(name: String) = args.indexOf(name).takeIf { it >= 0 && it + 1 < args.size }?.let { args[it + 1] }
    val dirs = baseDirs()
    val adbPath = opt("--adb") ?: Adb.locate(dirs)
    val apk = opt("--apk")?.let { File(it) } ?: findApk(dirs)
    val logDir = opt("--log-dir")?.let { File(it) } ?: SetupLog.defaultDir()
    val cli = "--cli" in args || "--check" in args || GraphicsEnvironment.isHeadless()

    if (!cli) {
        Gui.show(adbPath, apk, logDir)
        return
    }

    val listener = object : SetupListener {
        override fun step(text: String) = println("== $text")
        override fun info(text: String) = println("   $text")
        override fun warn(text: String) = println("!! $text")
        override fun error(text: String) = println("XX $text")
    }
    if (adbPath == null) { println("XX adb not found (expected platform-tools next to the installer)."); exitProcess(3) }
    val engine = SetupEngine(Adb(adbPath), log = SetupLog(logDir), stateDir = logDir.parentFile ?: logDir, listener = listener)

    if ("--check" in args) {
        val c = engine.check()
        println(if (c.canInstall) "CHECK OK" else "CHECK FAILED")
        exitProcess(if (c.canInstall) 0 else 1)
    }
    if (apk == null) { println("XX apps-only.apk not found; pass --apk FILE."); exitProcess(3) }
    if ("--yes" !in args) {
        print("This sets up Apps Only on the connected phone. After the customer picks their apps, they can't be changed. Continue? [y/N] ")
        if (readlnOrNull()?.trim()?.lowercase() != "y") exitProcess(2)
    }
    val name = opt("--name"); val phone = opt("--phone"); val email = opt("--email"); val code = opt("--removal-code")
    val customer = if (name != null || phone != null || email != null || code != null)
        CustomerInfo(name ?: "", phone ?: "", email ?: "", code ?: "") else null
    customer?.problems()?.let { p -> if (p.isNotEmpty()) { p.forEach { println("XX $it") }; exitProcess(2) } }
    val ok = engine.install(apk, customer)
    println(if (ok) "SETUP OK" else "SETUP FAILED")
    exitProcess(if (ok) 0 else 1)
}
