package com.felix.faze

import java.util.TreeMap
import org.json.JSONArray
import org.json.JSONObject

class AgentEvents(
    val onToken: (String) -> Unit,
    val onReset: () -> Unit,
    val onTool: (String, Boolean) -> Unit,
)

/**
 * A real tool loop for an OpenAI-format model: it picks tools, their results go back to it, and it writes the answer.
 * Same rules as the Termux version: at most 5 tool calls per round, an identical repeat is answered not re-run, and the
 * last round has no tools so the model must answer.
 */
class Agent(private val chat: ChatApi, private val tools: ToolRunner, private val maxRounds: Int = 5) {

    fun toolNames(): List<String> {
        val s = tools.schema()
        return (0 until s.length()).map { s.getJSONObject(it).getJSONObject("function").getString("name") }
    }

    private fun parseArgs(raw: String): JSONObject {
        if (raw.isBlank()) return JSONObject()
        return try {
            JSONObject(raw)
        } catch (e: Exception) {
            JSONObject()
        }
    }

    private fun canonical(args: JSONObject): String {
        val sorted = TreeMap<String, String>()
        val names = args.names()
        if (names != null) {
            for (i in 0 until names.length()) {
                val k = names.getString(i)
                sorted[k] = args.opt(k).toString()
            }
        }
        return sorted.entries.joinToString("&") { "${it.key}=${it.value}" }
    }

    fun run(model: String, system: String, history: List<Pair<String, String>>, events: AgentEvents, cancelled: () -> Boolean): String {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        for ((role, text) in history) messages.put(JSONObject().put("role", role).put("content", text))
        var schema: JSONArray? = tools.schema()
        val done = HashMap<String, String>()
        var malformed = 0

        for (round in 0 until maxRounds) {
            val last = round == maxRounds - 1
            val result = try {
                chat.chat(model, messages, if (last) null else schema, events.onToken, cancelled)
            } catch (e: RemoteError) {
                if (e.kind == "tool_failed" && malformed < 2) { // these models sometimes garble a tool call; a retry usually works
                    malformed++
                    if (malformed == 2) schema = null
                    continue
                }
                throw e
            }
            if (cancelled()) throw Cancelled()
            val calls = result.toolCalls.take(MAX_CALLS)
            if (calls.isEmpty()) {
                val text = result.content.trim()
                if (text.isEmpty()) throw RemoteError("empty", "the model sent an empty reply")
                return text
            }
            if (result.streamed) events.onReset()
            val callArray = JSONArray()
            calls.forEachIndexed { i, c ->
                callArray.put(
                    JSONObject().put("id", c.id.ifEmpty { "call_$i" }).put("type", "function")
                        .put("function", JSONObject().put("name", c.name).put("arguments", c.arguments.ifEmpty { "{}" })),
                )
            }
            messages.put(
                JSONObject().put("role", "assistant").put("content", if (result.content.isEmpty()) JSONObject.NULL else result.content)
                    .put("tool_calls", callArray),
            )
            calls.forEachIndexed { i, c ->
                val args = parseArgs(c.arguments)
                val key = c.name + "\u0000" + canonical(args)
                val text = done.getOrPut(key) {
                    val r = tools.execute(c.name, args)
                    events.onTool(c.name, r.ok)
                    (if (r.ok) "" else "Error: ") + r.text.take(4000)
                }
                messages.put(JSONObject().put("role", "tool").put("tool_call_id", c.id.ifEmpty { "call_$i" }).put("content", text))
            }
        }
        throw RemoteError("server", "it kept calling tools without answering")
    }

    companion object {
        const val MAX_CALLS = 5
    }
}
