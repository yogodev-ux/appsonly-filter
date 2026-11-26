package com.appsonly.filter

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The one-time uninstall code, for removing the filter from a phone that can't go online.
 *
 * Made on the phone at setup. The phone sends it to the server once (with the customer's
 * details) and then keeps only a salted hash. Support sees it in the portal only after they
 * removed the filter there (with the customer's removal code, or the owner), and reads it to
 * the customer, who types it in Settings > Remove the filter.
 *
 * 5 wrong codes block the box for an hour.
 */
object Uninstall {
    private const val FILE = "uninstall"
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // no 0/O/1/I
    private const val MAX_WRONG = 5
    private const val BLOCK_MS = 60 * 60 * 1000L

    sealed class Result {
        object Right : Result()
        data class Wrong(val triesLeft: Int) : Result()
        data class Blocked(val minutes: Long) : Result()
        object NoCode : Result()
    }

    private fun sp(ctx: Context) =
        ctx.createDeviceProtectedStorageContext().getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * The code to send to the server. Made once; kept in plain text only until the server has
     * it. Returns null when the server already has it (then only the hash is left).
     */
    @Synchronized
    fun codeToSend(ctx: Context): String? {
        val p = sp(ctx)
        p.getString("plain", null)?.let { return it }
        if (p.contains("hash")) return null
        val r = SecureRandom()
        val raw = (1..12).map { ALPHABET[r.nextInt(ALPHABET.length)] }.joinToString("")
        val code = raw.chunked(4).joinToString("-")
        val salt = ByteArray(16).also { r.nextBytes(it) }.toHex()
        p.edit().putString("plain", code).putString("salt", salt).putString("hash", hash(salt, code)).commit()
        return code
    }

    /** The server has the code: keep only the hash on the phone. */
    fun sent(ctx: Context) {
        sp(ctx).edit().remove("plain").commit()
    }

    fun hasCode(ctx: Context) = sp(ctx).contains("hash")

    @Synchronized
    fun check(ctx: Context, typed: String, now: Long = System.currentTimeMillis()): Result {
        val p = sp(ctx)
        val salt = p.getString("salt", null)
        val stored = p.getString("hash", null)
        if (salt == null || stored == null) return Result.NoCode
        val until = p.getLong("blocked_until", 0)
        if (until > now) return Result.Blocked((until - now + 59_999) / 60_000)
        if (MessageDigest.isEqual(hash(salt, normalize(typed)).toByteArray(), stored.toByteArray())) {
            p.edit().putInt("wrong", 0).commit()
            return Result.Right
        }
        val wrong = p.getInt("wrong", 0) + 1
        TamperLog.record(ctx, "wrong_uninstall_code")
        return if (wrong >= MAX_WRONG) {
            p.edit().putInt("wrong", 0).putLong("blocked_until", now + BLOCK_MS).commit()
            Result.Blocked(BLOCK_MS / 60_000)
        } else {
            p.edit().putInt("wrong", wrong).commit()
            Result.Wrong(MAX_WRONG - wrong)
        }
    }

    /** Spaces, dashes and lower case are fine when typing it. */
    private fun normalize(s: String): String =
        s.uppercase().filter { it in ALPHABET }.chunked(4).joinToString("-")

    private fun hash(salt: String, code: String): String =
        MessageDigest.getInstance("SHA-256").digest("$salt:$code".toByteArray()).toHex()

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
