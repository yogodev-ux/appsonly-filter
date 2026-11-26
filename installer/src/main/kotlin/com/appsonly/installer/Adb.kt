package com.appsonly.installer

import java.io.File
import java.util.concurrent.TimeUnit

data class CmdResult(val exit: Int, val out: String) {
    val ok get() = exit == 0
}

/** Thin wrapper around the adb program that ships next to the installer. */
open class Adb(val adbPath: String, var serial: String? = null) {

    open fun run(vararg args: String, timeoutSec: Long = 120): CmdResult {
        val cmd = mutableListOf(adbPath)
        serial?.let { cmd += listOf("-s", it) }
        cmd += args
        return try {
            val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val out = StringBuilder()
            val reader = Thread { p.inputStream.bufferedReader().forEachLine { out.appendLine(it) } }
            reader.start()
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                reader.join(2000)
                return CmdResult(-1, out.toString() + "\n(timed out after ${timeoutSec}s)")
            }
            reader.join(5000)
            CmdResult(p.exitValue(), out.toString().replace("\r", "").trim())
        } catch (e: Exception) {
            CmdResult(-2, "could not run adb: ${e.message}")
        }
    }

    fun shell(command: String, timeoutSec: Long = 120) = run("shell", command, timeoutSec = timeoutSec)

    fun prop(name: String) = shell("getprop $name").out.trim()

    companion object {
        /** Finds adb: next to the installer first, then on PATH. */
        fun locate(baseDirs: List<File>): String? {
            val exe = if (System.getProperty("os.name").lowercase().contains("win")) "adb.exe" else "adb"
            for (dir in baseDirs) {
                val f = File(dir, "platform-tools/$exe")
                if (f.isFile) return f.absolutePath
            }
            System.getenv("PATH")?.split(File.pathSeparator)?.forEach { dir ->
                val f = File(dir, exe)
                if (f.isFile && f.canExecute()) return f.absolutePath
            }
            return null
        }
    }
}
