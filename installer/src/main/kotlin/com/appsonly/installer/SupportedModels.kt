package com.appsonly.installer

import org.json.JSONObject

enum class Support { SUPPORTED, WARNING, BLOCKED, UNKNOWN }

data class ModelVerdict(val support: Support, val note: String)

class SupportedModels(json: String) {
    private data class Rule(val regex: Regex, val support: Support, val note: String)

    val minSdk: Int
    private val rules: List<Rule>

    init {
        val o = JSONObject(json)
        minSdk = o.optInt("min_sdk", 29)
        val arr = o.getJSONArray("models")
        rules = (0 until arr.length()).map { i ->
            val m = arr.getJSONObject(i)
            Rule(
                globToRegex(m.getString("match").lowercase()),
                Support.valueOf(m.getString("status").uppercase()),
                m.optString("note", ""),
            )
        }
    }

    fun check(manufacturer: String, model: String): ModelVerdict {
        val key = "${manufacturer.trim().lowercase()}:${model.trim().lowercase()}"
        val rule = rules.firstOrNull { it.regex.matches(key) }
            ?: return ModelVerdict(Support.UNKNOWN, "This model hasn't been tested yet. Setup will try anyway.")
        return ModelVerdict(rule.support, rule.note)
    }

    companion object {
        fun bundled(): SupportedModels =
            SupportedModels(SupportedModels::class.java.getResource("/supported-models.json")!!.readText())

        private fun globToRegex(glob: String) =
            Regex(glob.split("*").joinToString(".*") { Regex.escape(it) })
    }
}
