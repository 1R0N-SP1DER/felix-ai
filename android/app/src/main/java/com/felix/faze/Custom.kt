package com.felix.faze

import org.json.JSONArray
import java.net.URI

/** A chat service of your own choosing that speaks the OpenAI format (OpenAI, DeepSeek, Mistral, Together, LM Studio, Ollama, vLLM, ...). */
object CustomEndpoint {
    /** The address to use for chat, or null when it can't be used; [problem] says why. Accepts the base ("https://api.x.com/v1") or the full chat address. */
    fun base(raw: String): String? = if (problem(raw) == null) clean(raw) else null

    private fun clean(raw: String): String = raw.trim().trimEnd('/').removeSuffix("/chat/completions").trimEnd('/')

    fun problem(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty()) return "add the address of the service (Settings > Advanced > Custom provider)"
        val uri = try { URI(clean(t)) } catch (e: Exception) { return "that address does not look right" }
        val host = uri.host ?: return "that address does not look right (it should start with https://)"
        return when {
            uri.scheme == "https" -> null
            uri.scheme == "http" && (host == "127.0.0.1" || host == "localhost") -> null
            uri.scheme == "http" -> "plain http is only allowed for 127.0.0.1 or localhost; use https:// so your key is not sent in the open"
            else -> "the address must start with https://"
        }
    }
}

/** Chats with the custom service using whatever address, key and model are set right now. The key is optional (a server on this phone needs none). */
class CustomChat(
    private val transport: Transport,
    private val url: () -> String,
    private val key: () -> String,
    private val name: () -> String,
) : ChatApi {
    override fun chat(model: String, messages: JSONArray, tools: JSONArray?, onToken: (String) -> Unit, cancelled: () -> Boolean): ChatResult {
        val label = name().ifBlank { "Custom" }
        val base = CustomEndpoint.base(url()) ?: throw RemoteError("auth", "$label: ${CustomEndpoint.problem(url())}")
        if (model.isBlank()) throw RemoteError("model", "$label has no model set: type the model name in Settings > Advanced > Custom provider")
        return RemoteClient(transport, base, key, label, RemoteClient.DEFAULT_MAX, null, keyRequired = false).chat(model, messages, tools, onToken, cancelled)
    }
}
