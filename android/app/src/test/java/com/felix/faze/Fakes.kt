package com.felix.faze

import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

class FakeTransport : Transport {
    data class Call(val method: String, val url: String, val headers: Map<String, String>, val body: String)

    val calls = ArrayList<Call>()
    var onGet: (String) -> HttpResult = { HttpResult(404, emptyMap(), "") }
    var onPost: (String, String) -> HttpResult = { _, _ -> HttpResult(404, emptyMap(), "") }
    var streamLines: List<String> = emptyList()
    var streamStatus = 200
    var streamBody = ""
    var streamHeaders: Map<String, String> = emptyMap()
    var streamError: IOException? = null

    override fun get(url: String, headers: Map<String, String>, timeoutMs: Int): HttpResult {
        calls.add(Call("GET", url, headers, ""))
        return onGet(url)
    }

    override fun post(url: String, headers: Map<String, String>, body: ByteArray, contentType: String, timeoutMs: Int): HttpResult {
        calls.add(Call("POST", url, headers, String(body, Charsets.UTF_8)))
        return onPost(url, String(body, Charsets.UTF_8))
    }

    override fun postStream(url: String, headers: Map<String, String>, json: String, timeoutMs: Int, onLine: (String) -> Boolean): HttpResult {
        calls.add(Call("STREAM", url, headers, json))
        streamError?.let { throw it }
        if (streamStatus != 200) return HttpResult(streamStatus, streamHeaders, streamBody)
        for (line in streamLines) {
            if (!onLine(line)) break
        }
        return HttpResult(200, emptyMap(), "")
    }
}

fun sse(vararg chunks: String): List<String> = chunks.map { "data: $it" } + "data: [DONE]"

fun delta(content: String? = null, toolCalls: List<JSONObject>? = null): String {
    val d = JSONObject()
    if (content != null) d.put("content", content)
    if (toolCalls != null) d.put("tool_calls", JSONArray(toolCalls))
    return JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", d))).toString()
}

fun tc(index: Int, name: String = "", args: String = "", id: String = ""): JSONObject {
    val fn = JSONObject()
    if (name.isNotEmpty()) fn.put("name", name)
    if (args.isNotEmpty()) fn.put("arguments", args)
    val out = JSONObject().put("index", index).put("function", fn)
    if (id.isNotEmpty()) out.put("id", id)
    return out
}

/** A chat backend that plays a script: a ChatResult, a list of pieces to stream, or an exception to throw. */
class FakeChat(vararg items: Any) : ChatApi {
    class Streamed(val pieces: List<String>)

    class Call(val model: String, val messages: JSONArray, val tools: JSONArray?)

    private val script = ArrayList<Any>(items.toList())
    val calls = ArrayList<Call>()

    override fun chat(model: String, messages: JSONArray, tools: JSONArray?, onToken: (String) -> Unit, cancelled: () -> Boolean): ChatResult {
        calls.add(Call(model, JSONArray(messages.toString()), tools))
        return when (val item = script.removeAt(0)) {
            is RemoteError -> throw item
            is Cancelled -> throw item
            is Streamed -> {
                for (p in item.pieces) onToken(p)
                ChatResult(item.pieces.joinToString(""), emptyList(), true)
            }
            is ChatResult -> item
            else -> throw IllegalStateException("bad script item")
        }
    }
}

fun text(content: String, streamed: Boolean = false) = ChatResult(content, emptyList(), streamed)

fun calls(vararg calls: Pair<String, String>, content: String = ""): ChatResult =
    ChatResult(content, calls.mapIndexed { i, c -> ToolCall("id$i", c.first, c.second) }, content.isNotEmpty())

class FakeTools(private val results: Map<String, ToolResult> = emptyMap()) : ToolRunner {
    val executed = ArrayList<Pair<String, JSONObject>>()

    override fun schema(): JSONArray {
        val out = JSONArray()
        for (name in listOf("get_current_datetime", "get_battery_status", "get_weather")) {
            out.put(JSONObject().put("type", "function").put("function", JSONObject().put("name", name).put("description", "d")))
        }
        return out
    }

    override fun execute(name: String, args: JSONObject): ToolResult {
        executed.add(Pair(name, args))
        return results[name] ?: ToolResult(true, "half past four")
    }
}

class Events {
    val tokens = ArrayList<String>()
    var resets = 0
    val tools = ArrayList<Pair<String, Boolean>>()
    val events = AgentEvents({ tokens.add(it) }, { resets++ }, { n, ok -> tools.add(Pair(n, ok)) })
}

class FakeDevice : DeviceApi {
    val log = ArrayList<String>()
    var failWith: Exception? = null

    private fun hit(what: String, answer: String): String {
        failWith?.let { throw it }
        log.add(what)
        return answer
    }

    override fun now() = hit("now", "Wednesday, 28 October 2026, 10:00")
    override fun battery() = hit("battery", "Battery is at 76%, not charging")
    override fun wifi() = hit("wifi", "Connected to Wi-Fi.")
    override fun setVolume(level: Int) = hit("volume=$level", "Media volume set to $level of 15.")
    override fun openApp(name: String) = hit("open:$name", "Opened $name.")
    override fun listApps(search: String) = hit("apps:$search", "Installed apps (2): Chrome, YouTube.")
    override fun openUrl(url: String) = hit("url:$url", "Opened $url.")
    override fun readClipboard() = hit("clip-read", "hello")
    override fun writeClipboard(text: String) = hit("clip-write:$text", "Copied to the clipboard.")
    override fun vibrate() = hit("vibrate", "Vibrated.")
}

class FakeSettings(var tavily: String = "tvly-test", var city: String = "") : ToolSettings {
    override fun tavilyKey() = tavily
    override fun weatherLocation() = city
}
