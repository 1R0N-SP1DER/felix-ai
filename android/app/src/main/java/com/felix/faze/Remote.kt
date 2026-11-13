package com.felix.faze

import java.io.IOException
import java.util.TreeMap
import org.json.JSONArray
import org.json.JSONObject

/** kind: auth | rate_limit | model | tool_failed | bad_request | server | network | empty */
class RemoteError(val kind: String, message: String, val retryAfter: Double = 0.0) : Exception(message)

class Cancelled : Exception()

data class ToolCall(val id: String, val name: String, val arguments: String)

data class ChatResult(val content: String, val toolCalls: List<ToolCall>, val streamed: Boolean)

interface ChatApi {
    fun chat(model: String, messages: JSONArray, tools: JSONArray?, onToken: (String) -> Unit, cancelled: () -> Boolean): ChatResult
}

object Groq {
    const val BASE = "https://api.groq.com/openai/v1"
    const val DEFAULT_MODEL = "openai/gpt-oss-120b"
}

private class Slot(var id: String = "", var name: String = "", var args: String = "")

private val RESET_FORMAT = Regex("(?:(\\d+)h)?(?:(\\d+)m(?!s))?(?:([\\d.]+)s)?")

fun parseRetryAfter(headers: Map<String, String>): Double {
    headers["retry-after"]?.toDoubleOrNull()?.let { return maxOf(1.0, it) }
    for (name in listOf("x-ratelimit-reset-requests", "x-ratelimit-reset-tokens")) {
        val raw = headers[name]?.trim() ?: continue
        val m = RESET_FORMAT.matchEntire(raw) ?: continue
        if (raw.isEmpty() || m.groupValues.drop(1).all { it.isEmpty() }) continue
        val hours = m.groupValues[1].ifEmpty { "0" }.toDouble()
        val minutes = m.groupValues[2].ifEmpty { "0" }.toDouble()
        val seconds = m.groupValues[3].ifEmpty { "0" }.toDouble()
        return maxOf(1.0, hours * 3600 + minutes * 60 + seconds)
    }
    return 0.0
}

fun classify(status: Int, body: String, headers: Map<String, String>): RemoteError {
    var message = body.take(300)
    var code = ""
    try {
        val err = JSONObject(body).opt("error")
        if (err is JSONObject) {
            message = err.optString("message", message)
            code = err.optString("code", "")
        } else if (err != null && err != JSONObject.NULL) {
            message = err.toString()
        }
    } catch (e: Exception) {
        // not JSON: keep the raw text
    }
    message = message.replace(Regex("\\s+"), " ").trim().take(200)
    val restricted = Regex("only available|not available|restricted|agentic|access to (this |the )?model|not allowed", RegexOption.IGNORE_CASE)
    return when {
        status == 403 && restricted.containsMatchIn(message) -> RemoteError("model", "this model is not usable with this key ($status): $message")
        status == 401 || status == 403 -> RemoteError("auth", "the key was refused ($status): $message")
        status == 429 -> RemoteError("rate_limit", "rate limited: $message", parseRetryAfter(headers))
        status == 404 || (status == 400 && Regex("model", RegexOption.IGNORE_CASE).containsMatchIn(message) &&
            !Regex("tool", RegexOption.IGNORE_CASE).containsMatchIn(message)) -> RemoteError("model", "model problem: $message")
        status == 400 && (code == "tool_use_failed" || Regex("tool|function", RegexOption.IGNORE_CASE).containsMatchIn(message)) ->
            RemoteError("tool_failed", "the model's tool call was malformed: $message")
        status >= 500 -> RemoteError("server", "the provider had an error ($status): $message")
        else -> RemoteError("bad_request", "request refused ($status): $message")
    }
}

/** An OpenAI-format chat endpoint (Groq here; OpenRouter speaks the same way) with streaming and tool calls. */
class RemoteClient(
    private val transport: Transport,
    private val base: String,
    private val key: () -> String,
    private val label: String,
    private val maxTokens: Int = DEFAULT_MAX,
    private val extra: JSONObject? = null,
    private val keyRequired: Boolean = true,
) : ChatApi {
    companion object {
        /** How much a normal chat answer may write. The response length slider can lift or lower it for a turn. */
        const val DEFAULT_MAX = 2000
    }

    override fun chat(model: String, messages: JSONArray, tools: JSONArray?, onToken: (String) -> Unit, cancelled: () -> Boolean): ChatResult {
        val apiKey = key().trim()
        if (apiKey.isEmpty() && keyRequired) throw RemoteError("auth", "$label has no key (add it under Keys)")
        val payload = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("stream", true)
            .put("temperature", 0.3)
            .put("max_tokens", if (maxTokens == DEFAULT_MAX && ReplyLimit.tokens > 0) ReplyLimit.tokens else maxTokens)
        extra?.keys()?.forEach { payload.put(it, extra.get(it)) }
        if (tools != null && tools.length() > 0) {
            payload.put("tools", tools)
            payload.put("tool_choice", "auto")
        }

        val content = StringBuilder()
        val slots = TreeMap<Int, Slot>()
        var streamed = false
        val result = try {
            transport.postStream("$base/chat/completions", if (apiKey.isEmpty()) emptyMap() else mapOf("Authorization" to "Bearer $apiKey"), payload.toString(), 60000) { line ->
                if (cancelled()) return@postStream false
                if (!line.startsWith("data:")) return@postStream true
                val raw = line.substring(5).trim()
                if (raw == "[DONE]") return@postStream false
                val chunk = try {
                    JSONObject(raw)
                } catch (e: Exception) {
                    return@postStream true
                }
                chunk.optJSONObject("error")?.let { throw RemoteError("server", "$label stopped mid-reply: ${it.optString("message", "unknown")}".take(200)) }
                val delta = chunk.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
                if (delta != null) {
                    // a chunk with "content": null (llama-server sends one first) must not become the word "null"
                    val piece = if (delta.isNull("content")) "" else delta.optString("content", "")
                    if (piece.isNotEmpty()) {
                        content.append(piece)
                        streamed = true
                        onToken(piece)
                    }
                    val calls = delta.optJSONArray("tool_calls")
                    if (calls != null) {
                        for (i in 0 until calls.length()) {
                            val tc = calls.optJSONObject(i) ?: continue
                            val slot = slots.getOrPut(tc.optInt("index", 0)) { Slot() }
                            val id = if (tc.isNull("id")) "" else tc.optString("id", "")
                            if (id.isNotEmpty()) slot.id = id
                            val fn = tc.optJSONObject("function")
                            if (fn != null) {
                                if (!fn.isNull("name")) slot.name += fn.optString("name", "")
                                if (!fn.isNull("arguments")) slot.args += fn.optString("arguments", "")
                            }
                        }
                    }
                }
                true
            }
        } catch (e: IOException) {
            if (cancelled()) throw Cancelled()
            throw RemoteError("network", "$label: network error (${e.javaClass.simpleName})")
        }
        if (cancelled()) throw Cancelled()
        if (result.status != 200) throw classify(result.status, result.body, result.headers)
        val calls = slots.values.filter { it.name.isNotEmpty() }.map { ToolCall(it.id, it.name, it.args) }
        return ChatResult(content.toString(), calls, streamed)
    }
}
