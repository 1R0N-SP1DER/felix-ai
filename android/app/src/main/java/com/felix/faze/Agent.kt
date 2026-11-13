package com.felix.faze

import java.util.TreeMap
import org.json.JSONArray
import org.json.JSONObject

class AgentEvents(
    val onToken: (String) -> Unit,
    val onReset: () -> Unit,
    val onTool: (String, Boolean) -> Unit,
) {
    /** Called for each place an answer drew from (a web page, something read on the phone, saved notes). */
    var onSource: (Source) -> Unit = {}
}

/**
 * A real tool loop for an OpenAI-format model: it picks tools, their results go back to it, and it writes the answer.
 * Same rules as the Termux version: at most 5 tool calls per round, an identical repeat is answered not re-run, and the
 * last round has no tools so the model must answer.
 * With a [router], a request it recognises (built-in rule, learned shortcut, Needle) runs its tool first and the model is only asked to say
 * what was done; a tool call the model makes itself and that works is handed back to the router to learn from.
 */
class Agent(private val chat: ChatApi, private val tools: ToolRunner, private val maxRounds: Int = 5, private val router: FastRoute? = null) {

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

    /** A second pass for Max effort: the model reads its own draft back, looks for mistakes and writes the final answer. Falls back to the draft. */
    fun review(model: String, system: String, history: List<Pair<String, String>>, draft: String, events: AgentEvents, cancelled: () -> Boolean): String {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        for ((role, text) in history) messages.put(JSONObject().put("role", role).put("content", text))
        messages.put(JSONObject().put("role", "assistant").put("content", draft))
        messages.put(JSONObject().put("role", "user").put("content", Effort.REVIEW_PROMPT))
        return try {
            var started = false
            val result = chat.chat(model, messages, null, { piece ->
                if (!started) { started = true; events.onReset() }
                events.onToken(piece)
            }, cancelled)
            result.content.trim().ifEmpty { draft }
        } catch (e: RemoteError) {
            draft // the review failing never costs the person their answer
        }
    }

    fun run(model: String, system: String, history: List<Pair<String, String>>, events: AgentEvents, cancelled: () -> Boolean): String {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        for ((role, text) in history) messages.put(JSONObject().put("role", role).put("content", text))
        var schema: JSONArray? = tools.schema()
        val done = HashMap<String, String>()
        var malformed = 0
        val userText = history.lastOrNull { it.first == "user" }?.second.orEmpty()
        val executed = ArrayList<Triple<String, JSONObject, Boolean>>()

        val route = try { schema?.let { router?.route(userText, it) } } catch (e: Exception) { null }
        if (route != null) {
            val r = tools.execute(route.tool, route.args)
            events.onTool(route.tool, r.ok)
            for (s in SourceTracker.from(route.tool, route.args, r)) events.onSource(s)
            val declined = !r.ok && r.text.startsWith(PermissionPolicy.DECLINED)
            router?.outcome(route, r.ok || declined)
            if (r.ok || declined) {
                if (r.ok && route.source != "built-in") router?.learn(userText, route.tool, route.args)
                val id = "route_0"
                messages.put(
                    JSONObject().put("role", "assistant").put("content", JSONObject.NULL).put(
                        "tool_calls",
                        JSONArray().put(JSONObject().put("id", id).put("type", "function").put("function", JSONObject().put("name", route.tool).put("arguments", route.args.toString()))),
                    ),
                )
                messages.put(JSONObject().put("role", "tool").put("tool_call_id", id).put("content", (if (r.ok) "" else "Error: ") + r.text.take(4000)))
                val text = try {
                    chat.chat(model, messages, null, events.onToken, cancelled).content.trim()
                } catch (e: RemoteError) {
                    if (cancelled()) throw Cancelled()
                    "" // the tool already ran: say so plainly below instead of failing the whole turn
                }
                if (cancelled()) throw Cancelled()
                return text.ifEmpty { if (r.ok) r.text.ifBlank { "Done." } else r.text }
            }
        }

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
                if (executed.size == 1 && executed[0].third) router?.learn(userText, executed[0].first, executed[0].second)
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
                    for (s in SourceTracker.from(c.name, args, r)) events.onSource(s)
                    executed.add(Triple(c.name, args, r.ok))
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
