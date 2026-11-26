package com.appsonly.installer

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class Config(json: String) {
    private val o = JSONObject(json)
    val uploadUrl: String = o.optString("log_upload_url", "")
    val pkg: String = o.optString("package", "com.appsonly.filter")
    val adminComponent = "$pkg/" + o.optString("admin_receiver", ".AdminReceiver")
    val mainComponent = "$pkg/" + o.optString("main_activity", ".MainActivity")

    companion object {
        fun bundled() = Config(Config::class.java.getResource("/installer-config.json")!!.readText())
    }
}

/**
 * Everything the installer did, with times. Saved on the PC and (once the server exists)
 * uploaded so support can see why a setup failed. No account names, only account types.
 */
class SetupLog(private val dir: File) {
    private val lines = mutableListOf<String>()
    private val stamp = DateTimeFormatter.ofPattern("HH:mm:ss")
    val started: LocalDateTime = LocalDateTime.now()
    var file: File? = null
        private set

    @Synchronized
    fun add(line: String) {
        lines += "${LocalDateTime.now().format(stamp)} $line"
    }

    fun text(): String = synchronized(this) { lines.joinToString("\n", postfix = "\n") }

    fun save(): File? = try {
        dir.mkdirs()
        val f = File(dir, "setup-" + started.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".log")
        f.writeText(text())
        file = f
        f
    } catch (e: Exception) { null }

    /** Returns a short status for the log itself. */
    fun upload(url: String): String {
        if (url.isBlank()) return "Log upload not set up yet (server comes in a later step)."
        return try {
            val c = URI(url).toURL().openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            c.outputStream.use { it.write(text().toByteArray()) }
            val code = c.responseCode
            c.disconnect()
            if (code in 200..299) "Log uploaded." else "Log upload failed (HTTP $code)."
        } catch (e: Exception) {
            "Log upload failed (${e.javaClass.simpleName}). It is saved on this PC."
        }
    }

    companion object {
        fun defaultDir(): File {
            val base = System.getenv("LOCALAPPDATA")?.let { File(it) } ?: File(System.getProperty("user.home"), ".local/share")
            return File(base, "AppsOnly/setup-logs")
        }
    }
}
