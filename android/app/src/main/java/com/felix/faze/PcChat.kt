package com.felix.faze

import org.json.JSONArray
import org.json.JSONObject

/**
 * The paired computer's Ollama as one more provider: the phone sends the conversation over the link and the PC answers with its own
 * model. Used by Auto mode when a computer is connected, so a stronger model can answer without a key or a data plan. The answer
 * arrives in one piece, and no tools are offered to it: the phone's tools stay on the phone.
 */
class PcChat(private val ask: (JSONObject, Long, () -> Boolean) -> JSONObject, private val timeoutMs: Long = 120_000) : ChatApi {
    override fun chat(model: String, messages: JSONArray, tools: JSONArray?, onToken: (String) -> Unit, cancelled: () -> Boolean): ChatResult {
        val deep = AutoMode.kindOf(AutoMode.lastUserText(messages)) != Task.CASUAL
        val reply = try {
            ask(JSONObject().put("t", "pc_chat").put("messages", messages).put("deep", deep).apply { if (model.isNotBlank() && model != "ollama") put("model", model) }, timeoutMs, cancelled)
        } catch (e: Cancelled) {
            throw e
        } catch (e: Exception) {
            throw RemoteError("network", "the computer could not be reached: ${e.message ?: ""}")
        }
        if (reply.optString("t") == "error") throw RemoteError("server", reply.optString("message", "the computer could not answer"))
        val text = reply.optString("content").trim()
        if (text.isEmpty()) throw RemoteError("empty", "the computer's model gave an empty answer")
        onToken(text)
        return ChatResult(text, emptyList(), false)
    }
}
