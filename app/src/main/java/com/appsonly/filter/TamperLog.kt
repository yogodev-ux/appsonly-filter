package com.appsonly.filter

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Local record of tamper attempts (the support portal will read these in step 5).
 * Stored in device-protected storage as one JSON object per line; capped in size.
 */
object TamperLog {
    private const val TAG = "AppsOnlyTamper"
    private const val FILE = "tamper.log"
    private const val MAX_BYTES = 256 * 1024

    private fun file(ctx: Context) = File(ctx.createDeviceProtectedStorageContext().filesDir, FILE)

    @Synchronized
    fun record(ctx: Context, event: String, detail: String = "") {
        Log.w(TAG, "$event $detail")
        try {
            val f = file(ctx)
            if (f.length() > MAX_BYTES) {
                val keep = f.readLines().takeLast(500)
                f.writeText(keep.joinToString("\n", postfix = "\n"))
            }
            val line = JSONObject()
                .put("time", System.currentTimeMillis())
                .put("event", event)
                .put("detail", detail)
                .toString()
            f.appendText(line + "\n")
        } catch (e: Exception) {
            Log.w(TAG, "write failed", e)
        }
    }

    fun read(ctx: Context): List<String> = try { file(ctx).readLines() } catch (e: Exception) { emptyList() }
}
