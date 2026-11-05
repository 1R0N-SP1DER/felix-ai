package com.felix.faze

import org.json.JSONObject

/** One model a provider offers: [id] is what is sent to the API, [note] a short hint such as "free". */
data class ModelOption(val id: String, val label: String, val note: String = "")

/** Reads each provider's own model list, so the picker shows what your key can actually use. */
object ModelLists {
    private val notChat = listOf("whisper", "tts", "guard", "orpheus", "embed", "playai", "moderation")
    private val notGemini = listOf("embedding", "imagen", "image", "tts", "live", "audio", "aqa", "robotics", "veo")

    fun groq(json: String): List<ModelOption> {
        val data = JSONObject(json).optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { data.optJSONObject(it) }
            .filter { it.optBoolean("active", true) }
            .map { it.optString("id") }
            .filter { id -> id.isNotEmpty() && notChat.none { id.contains(it, ignoreCase = true) } }
            .sorted().map { ModelOption(it, it.substringAfterLast('/')) }
    }

    fun gemini(json: String): List<ModelOption> {
        val models = JSONObject(json).optJSONArray("models") ?: return emptyList()
        val out = ArrayList<ModelOption>()
        for (i in 0 until models.length()) {
            val m = models.optJSONObject(i) ?: continue
            val id = m.optString("name").removePrefix("models/")
            val methods = m.optJSONArray("supportedGenerationMethods")
            var chat = false
            if (methods != null) for (k in 0 until methods.length()) if (methods.optString(k) == "generateContent") chat = true
            if (!chat || !id.startsWith("gemini") || notGemini.any { id.contains(it) }) continue
            out.add(ModelOption(id, id, m.optString("displayName", "")))
        }
        return out.sortedBy { it.id }
    }

    /** Models that support tools, free ones first (the price shown as a note), then the rest by name. */
    fun openrouter(json: String): List<ModelOption> {
        val data = JSONObject(json).optJSONArray("data") ?: return emptyList()
        val out = ArrayList<Pair<Boolean, ModelOption>>()
        for (i in 0 until data.length()) {
            val m = data.optJSONObject(i) ?: continue
            val id = m.optString("id")
            val params = m.optJSONArray("supported_parameters")
            var tools = false
            if (params != null) for (k in 0 until params.length()) if (params.optString(k) == "tools") tools = true
            if (id.isEmpty() || !tools) continue
            val price = m.optJSONObject("pricing")
            val free = price != null && price.optString("prompt", "x").toDoubleOrNull() == 0.0 && price.optString("completion", "x").toDoubleOrNull() == 0.0
            out.add(free to ModelOption(id, id, if (free) "free" else ""))
        }
        return out.sortedWith(compareBy<Pair<Boolean, ModelOption>> { !it.first }.thenBy { it.second.id }).map { it.second }
    }

    fun filter(options: List<ModelOption>, query: String, limit: Int = 60): List<ModelOption> {
        val q = query.trim().lowercase()
        return (if (q.isEmpty()) options else options.filter { it.id.lowercase().contains(q) || it.note.lowercase().contains(q) }).take(limit)
    }
}

/** Asks a provider which models this key can use. Keys go in headers, never in the address. */
class ModelCatalog(private val transport: Transport, private val key: (String) -> String) {
    fun fetch(provider: String): List<ModelOption> {
        val k = key(provider).trim()
        if (k.isEmpty() && provider != "openrouter") throw RemoteError("auth", "add a ${ModelSheet.label(provider)} key first (Settings > Advanced)")
        val res = when (provider) {
            "groq" -> transport.get("${Groq.BASE}/models", mapOf("Authorization" to "Bearer $k"), 15000)
            "gemini" -> transport.get("${GeminiApi.BASE}?pageSize=200", mapOf("x-goog-api-key" to k), 15000)
            "openrouter" -> transport.get("${OpenRouter.BASE}/models", if (k.isEmpty()) emptyMap() else mapOf("Authorization" to "Bearer $k"), 15000)
            else -> throw RemoteError("model", "${ModelSheet.label(provider)} is not in the app yet")
        }
        if (res.status != 200) throw classify(res.status, res.body, res.headers)
        val list = try {
            when (provider) { "groq" -> ModelLists.groq(res.body); "gemini" -> ModelLists.gemini(res.body); else -> ModelLists.openrouter(res.body) }
        } catch (e: Exception) {
            throw RemoteError("server", "${ModelSheet.label(provider)} sent a model list I could not read")
        }
        if (list.isEmpty()) throw RemoteError("empty", "${ModelSheet.label(provider)} listed no usable models for this key")
        return list
    }
}
