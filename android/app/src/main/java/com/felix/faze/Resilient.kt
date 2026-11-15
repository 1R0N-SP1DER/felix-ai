package com.felix.faze

import org.json.JSONArray
import org.json.JSONObject

data class Attempt(val provider: String, val model: String)

object Fallbacks {
    /** Other model ids to try on the same provider when the chosen one is overloaded or gone. */
    val alternates = mapOf("gemini" to listOf("gemini-flash-lite-latest", "gemini-2.5-flash-lite"))
    private val order = listOf("groq", "gemini", "openrouter", "nvidia")

    /**
     * What to try, in order: the chosen model, its same-provider alternates, then every other provider that has a key.
     * The chosen provider always comes first, even without a key, so the person is told what is wrong with it.
     */
    fun plan(selected: String, keys: Set<String>, model: (String) -> String): List<Attempt> {
        if (selected == "offline" || selected == "pc") return listOf(Attempt(selected, model(selected))) // offline means offline, and a chosen PC model means that PC: never quietly send it elsewhere
        val out = ArrayList<Attempt>()
        out.add(Attempt(selected, model(selected)))
        if (selected in keys) for (alt in alternates[selected].orEmpty()) if (alt != model(selected)) out.add(Attempt(selected, alt))
        for (p in order) if (p != selected && p in keys) out.add(Attempt(p, model(p)))
        return out
    }
}

/**
 * Wraps the real providers with patience: a busy or rate-limited provider is tried again once after a pause, and if it still
 * fails the next choice in the plan answers instead, with a notice saying so. It never retries after text has started to
 * arrive (that would repeat words), and never for a refused request (that is a bug to fix, not to hide).
 */
class ResilientChat(
    private val chats: Map<String, ChatApi>,
    private val plan: () -> List<Attempt>,
    private val notice: (String) -> Unit,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val retries: Int = 1,
    private val pauseMs: Long = 2500,
    private val planFor: ((JSONArray) -> List<Attempt>?)? = null,
    private val onLimit: (String, RemoteError) -> Unit = { _, _ -> },
) : ChatApi {
    private val retryKinds = setOf("server", "rate_limit", "network")
    private val fallbackKinds = setOf("server", "rate_limit", "network", "model", "tool_failed", "empty", "auth")

    private fun pause(ms: Long, cancelled: () -> Boolean) {
        var left = ms
        while (left > 0) {
            if (cancelled()) throw Cancelled()
            val slice = minOf(left, 100L)
            sleep(slice)
            left -= slice
        }
    }

    override fun chat(model: String, messages: JSONArray, tools: JSONArray?, onToken: (String) -> Unit, cancelled: () -> Boolean): ChatResult {
        var last: RemoteError? = null
        var previous: Attempt? = null
        for (a in (planFor?.invoke(messages) ?: plan())) {
            val api = chats[a.provider] ?: continue
            var tries = 0
            while (true) {
                if (cancelled()) throw Cancelled()
                var emitted = false
                try {
                    if (previous != null && tries == 0) notice(describeSwitch(previous, a, last))
                    return api.chat(a.model, messages, tools, { emitted = true; onToken(it) }, cancelled)
                } catch (e: RemoteError) {
                    last = e
                    if (emitted || e.kind !in fallbackKinds) throw e
                    if (e.kind in retryKinds && tries < retries) {
                        notice("${ModelSheet.label(a.provider)} is busy, trying again…")
                        pause(if (e.retryAfter > 0) minOf((e.retryAfter * 1000).toLong(), 8000L) else pauseMs, cancelled)
                        tries++
                        continue
                    }
                    onLimit(a.provider, e)
                    break
                }
            }
            previous = a
        }
        throw last ?: RemoteError("model", "no model is available (add a key in Settings > Advanced)")
    }

    private fun describeSwitch(from: Attempt, to: Attempt, why: RemoteError?): String {
        val reason = when (why?.kind) {
            "auth" -> "has no working key"
            "model" -> "can't use that model"
            "rate_limit" -> "is rate limited"
            else -> "is busy"
        }
        return if (from.provider == to.provider) "${ModelSheet.label(from.provider)} $reason; trying ${to.model}"
        else "${ModelSheet.label(from.provider)} $reason; answering with ${ModelSheet.label(to.provider)}"
    }
}

/** Finding a free OpenRouter model that can call tools, from OpenRouter's public model list. */
object OpenRouterModels {
    private val preferred = listOf("gpt-oss-120b", "gpt-oss-20b", "qwen3", "kimi", "glm", "llama-3.3", "gemini", "deepseek", "mistral")

    /** Free (zero price) models that support tools, best first: preferred families, then the longest context. */
    fun free(json: String): List<String> {
        val data = try { JSONObject(json).optJSONArray("data") } catch (e: Exception) { null } ?: return emptyList()
        val found = ArrayList<Triple<String, Int, Int>>()
        for (i in 0 until data.length()) {
            val m = data.optJSONObject(i) ?: continue
            val id = m.optString("id", "")
            val price = m.optJSONObject("pricing")
            val zero = price != null && isZero(price.optString("prompt", "x")) && isZero(price.optString("completion", "x"))
            val params = m.optJSONArray("supported_parameters")
            var tools = false
            if (params != null) for (k in 0 until params.length()) if (params.optString(k) == "tools") tools = true
            if (id.isEmpty() || !zero || !tools) continue
            val rank = preferred.indexOfFirst { id.contains(it, ignoreCase = true) }.let { if (it < 0) preferred.size else it }
            found.add(Triple(id, rank, m.optInt("context_length", 0)))
        }
        return found.sortedWith(compareBy<Triple<String, Int, Int>> { it.second }.thenByDescending { it.third }).map { it.first }
    }

    private fun isZero(s: String): Boolean = s.toDoubleOrNull() == 0.0
}

/**
 * OpenRouter with a free model chosen for you. If a model id is set, it is used as is. If it is blank, the free tool-capable
 * models are listed, tried best first (some free models refuse some keys), and the first that works is remembered.
 */
class OpenRouterChat(
    private val inner: ChatApi,
    private val fetchModels: () -> String,
    private val save: (String) -> Unit,
    private val maxTries: Int = 4,
) : ChatApi {
    private val bad = HashSet<String>()

    override fun chat(model: String, messages: JSONArray, tools: JSONArray?, onToken: (String) -> Unit, cancelled: () -> Boolean): ChatResult {
        if (model.isNotBlank()) return inner.chat(model, messages, tools, onToken, cancelled)
        val candidates = OpenRouterModels.free(fetchModels()).filter { it !in bad }.take(maxTries)
        if (candidates.isEmpty()) throw RemoteError("model", "OpenRouter lists no free model with tool support right now; type a model id in Settings")
        var last: RemoteError? = null
        for (id in candidates) {
            if (cancelled()) throw Cancelled()
            try {
                val r = inner.chat(id, messages, tools, onToken, cancelled)
                save(id)
                return r
            } catch (e: RemoteError) {
                last = e
                if (e.kind != "model" && e.kind != "tool_failed") throw e
                bad.add(id)
            }
        }
        throw last ?: RemoteError("model", "no free OpenRouter model worked")
    }
}
