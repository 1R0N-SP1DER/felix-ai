package com.felix.faze

/** Built-in services that speak the OpenAI chat format. Each has its own key and model, set in Settings. */
object OpenAiProviders {
    data class Info(val id: String, val label: String, val base: String, val defaultModel: String)

    val all = linkedMapOf(
        "nvidia" to Info("nvidia", "NVIDIA", "https://integrate.api.nvidia.com/v1", "meta/llama-3.3-70b-instruct"),
    )

    fun has(id: String): Boolean = id in all
}
