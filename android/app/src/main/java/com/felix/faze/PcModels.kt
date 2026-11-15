package com.felix.faze

import org.json.JSONObject

object PcModels {
    /** The model names in a computer's {"t":"pc_models_ok","models":[...]} answer. An error or odd answer gives an empty list. */
    fun parse(reply: JSONObject): List<String> {
        if (reply.optString("t") != "pc_models_ok") return emptyList()
        val a = reply.optJSONArray("models") ?: return emptyList()
        return (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
    }
}
