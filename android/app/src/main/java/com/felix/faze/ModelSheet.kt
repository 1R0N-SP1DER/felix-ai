package com.felix.faze

/** One line of the model sheet. [dot] is "ok", "warn", "bad" or "" (grey); [available] is false for models the app can't run yet. */
data class ModelRow(val id: String, val name: String, val sub: String, val dot: String, val available: Boolean)

data class StatusChip(val text: String, val kind: String)

/** What the model sheet, the model chip and the thinking list say: the same names, order and wording as the web UI (app.js). */
object ModelSheet {
    val names = linkedMapOf("auto" to "Auto", "offline" to "Offline", "gemini" to "Gemini", "groq" to "Groq", "openrouter" to "OpenRouter", "nvidia" to "NVIDIA", "custom" to "Custom")
    val thinkLevels = listOf("auto" to "Auto", "off" to "Off", "low" to "Low", "medium" to "Medium", "high" to "High", "max" to "Max")
    const val NOT_YET = "not in the app yet"

    fun label(mode: String): String = names[mode] ?: if (mode == "pc") "Your PC" else mode

    val live = listOf("auto", "groq", "gemini", "openrouter", "nvidia", "custom", "offline")

    /** Every model of the web sheet in its order; Groq, Gemini and OpenRouter run in the app. [keys] are the providers that have a key. */
    fun rows(keys: Set<String>, models: Map<String, String>): List<ModelRow> = names.map { (id, name) ->
        if (id == "auto") ModelRow(id, name, "picks the best model for each request", if (keys.any { it != "auto" && it != "offline" } || models["offline"].orEmpty().isNotEmpty()) "ok" else "warn", true)
        else if (id == "offline") ModelRow(id, name, models[id].orEmpty().let { m -> if (m.isEmpty()) "pick a model (llama.cpp in Termux, needs root)" else LocalLlama.label(m) }, if (models[id].orEmpty().isEmpty()) "warn" else "ok", true)
        else if (id in live) ModelRow(id, name, if (id in keys) (models[id].orEmpty().let { m -> if (m.isEmpty()) "free model, picked for you" else ModelChoices.label(m) }) else (if (id == "custom") "add an address (Settings > Advanced)" else "no key yet (Settings > Advanced)"), if (id in keys) "ok" else "bad", true)
        else ModelRow(id, name, NOT_YET, "", false)
    }

    /** Thinking can't be set for online models (same rule and words as the web page). */
    fun thinkEnabled(mode: String): Boolean = mode != "lfm" && mode != "groq" && mode != "openrouter" && mode != "offline" && mode != "custom" && mode !in OpenAiProviders.all

    fun thinkHint(mode: String): String = when (mode) {
        "groq", "openrouter", "custom", "nvidia" -> "This online model has no thinking setting yet. It still applies when Qwen or Gemini answers."
        "offline" -> "Offline models have no thinking setting, and they can't use tools yet."
        "lfm" -> "LFM cannot think, so this does nothing while LFM is forced. It still applies when Qwen or Gemini answers."
        else -> "Applies to whichever model answers."
    }

    /** The small status chips of the top bar. On a phone the web page shows only the warning and bad ones, so only those are made. */
    fun chips(provider: String, hasKey: Boolean, wakeWanted: Boolean, wakeModelReady: Boolean): List<StatusChip> {
        val out = ArrayList<StatusChip>()
        if (!hasKey) out.add(StatusChip("$provider no key", "bad"))
        if (wakeWanted && !wakeModelReady) out.add(StatusChip("wake model needed", "warn"))
        return out
    }
}
