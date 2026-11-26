package com.appsonly.installer

import org.json.JSONObject
import java.util.Base64

/**
 * What the customer enters before setup: who they are, and the removal code they choose.
 * Support can remove the filter only with this code. It goes to the phone once, the phone
 * sends it to the server, and the server keeps only a scrambled (hashed) copy.
 */
data class CustomerInfo(val name: String, val phone: String, val email: String, val removalCode: String) {

    /** Problems to show the customer, in field order. Empty = fine. */
    fun problems(confirmCode: String = removalCode): List<String> {
        val p = mutableListOf<String>()
        if (name.trim().length < 2) p += "Enter the customer's full name."
        if (name.length > 100) p += "The name is too long."
        val digits = phone.count { it.isDigit() }
        if (digits < 7 || digits > 15 || !PHONE.matches(phone.trim())) p += "Enter a phone number (digits, spaces, +, - and brackets only)."
        if (!EMAIL.matches(email.trim()) || email.length > 200) p += "Enter a valid email address."
        if (removalCode.length < 6) p += "The removal code needs at least 6 characters."
        if (removalCode.length > 64) p += "The removal code is too long (64 characters at most)."
        if (removalCode.any { it.isWhitespace() }) p += "The removal code can't contain spaces."
        if (removalCode != confirmCode) p += "The two removal codes don't match."
        return p
    }

    /** Safe to pass on an adb command line: only base64 characters, no quoting needed. */
    fun toBase64(): String = Base64.getEncoder().encodeToString(
        JSONObject()
            .put("name", name.trim())
            .put("phone", phone.trim())
            .put("email", email.trim().lowercase())
            .put("removalCode", removalCode)
            .toString().toByteArray(Charsets.UTF_8)
    )

    companion object {
        private val PHONE = Regex("^\\+?[0-9 ()\\-.]{7,25}$")
        private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$")
    }
}
