package com.felix.faze

import org.json.JSONArray
import org.json.JSONObject

object OpenRouter {
    const val BASE = "https://openrouter.ai/api/v1"
}

object GeminiApi {
    const val BASE = "https://generativelanguage.googleapis.com/v1beta/models"
    const val DEFAULT_MODEL = "gemini-3.1-flash-lite"
}

/** Turns the app's OpenAI-style conversation into Gemini's request, and Gemini's answer back into a [ChatResult]. */
object GeminiWire {
    /** The thinking level picked in the model sheet, as Gemini names it ("" = leave it to the model). */
    fun thinkingFor(level: String): String = when (level) {
        "off" -> "minimal"
        "low", "medium", "high" -> level
        "max" -> "high"
        else -> ""
    }

    private val dropped = setOf("additionalProperties", "\$schema", "strict")

    private fun clean(schema: Any?): Any? = when (schema) {
        is JSONObject -> JSONObject().also { o -> for (k in schema.keys()) if (k !in dropped) o.put(k, clean(schema.get(k))) }
        is JSONArray -> JSONArray().also { a -> for (i in 0 until schema.length()) a.put(clean(schema.get(i))) }
        else -> schema
    }

    /** A call's id carries Gemini's thought signature after a colon ("call_0:abc"), so it can be sent back with the call. */
    private fun signatureOf(id: String): String? = id.substringAfter(':', "").ifEmpty { null }

    fun body(messages: JSONArray, tools: JSONArray?, thinking: String): JSONObject {
        val contents = JSONArray()
        val system = StringBuilder()
        val names = HashMap<String, String>() // tool_call id -> tool name, for the answers that follow
        var i = 0
        while (i < messages.length()) {
            val m = messages.getJSONObject(i)
            when (m.optString("role")) {
                "system" -> { if (system.isNotEmpty()) system.append("\n\n"); system.append(m.optString("content")) }
                "assistant" -> {
                    val parts = JSONArray()
                    val text = if (m.isNull("content")) "" else m.optString("content", "")
                    if (text.isNotEmpty()) parts.put(JSONObject().put("text", text))
                    val calls = m.optJSONArray("tool_calls")
                    if (calls != null) for (k in 0 until calls.length()) {
                        val c = calls.getJSONObject(k)
                        val fn = c.getJSONObject("function")
                        val id = c.optString("id")
                        names[id] = fn.getString("name")
                        val args = try { JSONObject(fn.optString("arguments", "{}")) } catch (e: Exception) { JSONObject() }
                        val part = JSONObject().put("functionCall", JSONObject().put("name", fn.getString("name")).put("args", args))
                        signatureOf(id)?.let { part.put("thoughtSignature", it) }
                        parts.put(part)
                    }
                    if (parts.length() > 0) contents.put(JSONObject().put("role", "model").put("parts", parts))
                }
                "tool" -> { // several answers in a row go together in one turn, in the order the calls were made
                    val parts = JSONArray()
                    while (i < messages.length() && messages.getJSONObject(i).optString("role") == "tool") {
                        val t = messages.getJSONObject(i)
                        parts.put(
                            JSONObject().put(
                                "functionResponse",
                                JSONObject().put("name", names[t.optString("tool_call_id")] ?: "tool").put("response", JSONObject().put("result", t.optString("content"))),
                            ),
                        )
                        i++
                    }
                    i--
                    contents.put(JSONObject().put("role", "user").put("parts", parts))
                }
                else -> contents.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", m.optString("content")))))
            }
            i++
        }
        val body = JSONObject().put("contents", contents)
        if (system.isNotEmpty()) body.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system.toString()))))
        if (tools != null && tools.length() > 0) {
            val decls = JSONArray()
            for (k in 0 until tools.length()) {
                val fn = tools.getJSONObject(k).getJSONObject("function")
                decls.put(
                    JSONObject().put("name", fn.getString("name")).put("description", fn.optString("description", ""))
                        .put("parameters", clean(fn.optJSONObject("parameters") ?: JSONObject().put("type", "object").put("properties", JSONObject()))),
                )
            }
            body.put("tools", JSONArray().put(JSONObject().put("functionDeclarations", decls)))
        }
        if (thinking.isNotEmpty()) body.put("generationConfig", JSONObject().put("thinkingConfig", JSONObject().put("thinkingLevel", thinking)))
        return body
    }

    fun parse(json: String): ChatResult {
        val data = try { JSONObject(json) } catch (e: Exception) { throw RemoteError("server", "Gemini sent something that is not JSON") }
        val block = data.optJSONObject("promptFeedback")?.optString("blockReason", "").orEmpty()
        if (block.isNotEmpty()) throw RemoteError("bad_request", "Gemini refused that request ($block)")
        val cand = data.optJSONArray("candidates")?.optJSONObject(0) ?: throw RemoteError("empty", "Gemini sent no answer")
        val parts = cand.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
        val text = StringBuilder()
        val calls = ArrayList<ToolCall>()
        for (k in 0 until parts.length()) {
            val p = parts.getJSONObject(k)
            val fc = p.optJSONObject("functionCall")
            if (fc != null) {
                val sig = p.optString("thoughtSignature", "")
                calls.add(ToolCall("call_${calls.size}" + (if (sig.isNotEmpty()) ":$sig" else ""), fc.getString("name"), (fc.optJSONObject("args") ?: JSONObject()).toString()))
            } else if (!p.optBoolean("thought", false)) {
                text.append(p.optString("text", ""))
            }
        }
        if (calls.isEmpty() && text.isEmpty()) throw RemoteError("empty", "Gemini returned no text and no tool call (${cand.optString("finishReason", "unknown")})")
        return ChatResult(text.toString(), calls, false)
    }
}

/** Gemini over its plain REST API (the official SDK isn't needed). Not streamed: the whole answer arrives at once. */
class GeminiClient(private val transport: Transport, private val key: () -> String, private val thinking: () -> String = { "" }) : ChatApi {
    override fun chat(model: String, messages: JSONArray, tools: JSONArray?, onToken: (String) -> Unit, cancelled: () -> Boolean): ChatResult {
        val apiKey = key().trim()
        if (apiKey.isEmpty()) throw RemoteError("auth", "Gemini has no key (add it under Keys)")
        val body = GeminiWire.body(messages, tools, GeminiWire.thinkingFor(thinking())).toString().toByteArray(Charsets.UTF_8)
        val res = transport.post("${GeminiApi.BASE}/$model:generateContent", mapOf("x-goog-api-key" to apiKey), body, "application/json", 30000)
        if (cancelled()) throw Cancelled()
        if (res.status != 200) throw classify(res.status, res.body, res.headers)
        return GeminiWire.parse(res.body)
    }
}
