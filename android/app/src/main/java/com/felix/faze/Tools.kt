package com.felix.faze

import org.json.JSONArray
import org.json.JSONObject

data class ToolResult(val ok: Boolean, val text: String)

/** An expected, explainable failure: the message goes back to the model as the tool's result. */
class ToolFailure(message: String) : Exception(message)

interface ToolRunner {
    fun schema(): JSONArray
    fun execute(name: String, args: JSONObject): ToolResult
}

interface ToolSettings {
    fun tavilyKey(): String
    fun weatherLocation(): String
}

/** Everything that needs a real phone. The tools above it are plain logic, tested without one. */
interface DeviceApi {
    fun now(): String
    fun battery(): String
    fun wifi(): String
    fun setVolume(level: Int): String
    fun openApp(name: String): String
    fun listApps(search: String): String
    fun openUrl(url: String): String
    fun readClipboard(): String
    fun writeClipboard(text: String): String
    fun vibrate(): String
}

object AppMatch {
    /** Best launcher app for what the user said: exact name, then name starts with it, then contains it. Apps are (label, package). */
    fun find(query: String, apps: List<Pair<String, String>>): Pair<String, String>? {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return null
        apps.firstOrNull { it.first.lowercase() == q }?.let { return it }
        apps.filter { it.first.lowercase().startsWith(q) }.minByOrNull { it.first.length }?.let { return it }
        apps.filter { it.first.lowercase().contains(q) }.minByOrNull { it.first.length }?.let { return it }
        return apps.filter { q.contains(it.first.lowercase()) && it.first.length >= 3 }.maxByOrNull { it.first.length }
    }
}

private class Param(val name: String, val type: String, val description: String)

class Tools(
    private val device: DeviceApi,
    private val transport: Transport,
    private val settings: ToolSettings,
    private val memory: MemoryStore? = null,
) : ToolRunner {

    private fun tool(name: String, description: String, vararg params: Param): JSONObject {
        val props = JSONObject()
        val required = JSONArray()
        for (p in params) {
            props.put(p.name, JSONObject().put("type", p.type).put("description", p.description))
            required.put(p.name)
        }
        val parameters = JSONObject().put("type", "object").put("properties", props).put("required", required)
        return JSONObject().put("type", "function").put("function", JSONObject().put("name", name).put("description", description).put("parameters", parameters))
    }

    override fun schema(): JSONArray {
        val out = JSONArray()
        out.put(tool("get_current_datetime", "Call this whenever the user asks for date or time. Returns current date and time. No arguments."))
        out.put(tool("get_battery_status", "Call this whenever the user asks about battery, charge level, or power status. Returns battery percentage. No arguments."))
        out.put(tool("set_volume", "Call this whenever the user asks to change, raise, lower, mute, or set phone volume. Sets media volume.",
            Param("level", "integer", "volume level, an integer from 0 to 15")))
        out.put(tool("get_wifi_status", "Call this whenever the user asks about WiFi connection or network status. Returns WiFi state. No arguments."))
        out.put(tool("read_clipboard", "Call this whenever the user asks about clipboard contents. Returns clipboard text. No arguments."))
        out.put(tool("write_clipboard", "Call this whenever the user asks to copy, save, or put text into clipboard. Saves text.",
            Param("text", "string", "the exact text to copy to the clipboard")))
        if (memory != null) out.put(tool("remember", "Save ONE short fact about the user for future chats (a lasting preference, name, plan or habit). Call it only when the user asks you to remember something or clearly states something lasting about themselves. Never save secrets, passwords, keys or numbers.", Param("fact", "string", "The fact, as one short sentence")))
        out.put(tool("vibrate", "Call this whenever the user asks to vibrate the phone or get attention physically. No arguments."))
        out.put(tool("search_web", "Call this for current, real-world information like news or live facts. Performs a web search. Never answer from memory.",
            Param("query", "string", "what to search for")))
        out.put(tool("open_url", "Call this when the user asks to open a link or website. Opens the http(s) link on the device.",
            Param("url", "string", "the full link, starting with https://")))
        out.put(tool("open_app", "Call this when the user asks to open, launch or start an app (YouTube, Spotify, Chrome, Settings...). Launches it by name.",
            Param("app", "string", "the app's name, for example YouTube or Spotify")))
        out.put(tool("list_apps", "Call this to see which apps are installed on the phone, optionally filtered by name. Use it when open_app cannot find an app.",
            Param("search", "string", "part of an app's name to filter by, or a single space for every app")))
        out.put(tool("get_weather", "Call this for the current weather or today's temperature, rain chance and conditions in a place. Fast and reliable; prefer it over search_web for weather. Pass an empty city for the user's default place.",
            Param("city", "string", "the place to get the weather for, for example Lagos; an empty string means the user's default place")))
        return out
    }

    fun names(): List<String> {
        val s = schema()
        return (0 until s.length()).map { s.getJSONObject(it).getJSONObject("function").getString("name") }
    }

    override fun execute(name: String, args: JSONObject): ToolResult {
        return try {
            when (name) {
                "remember" -> {
                    val r = memory?.add(args.optString("fact", "")) ?: MemoryResult(false, "memory is not available")
                    if (r.ok) ok(r.message) else fail(r.message)
                }
                "get_current_datetime" -> ok(device.now())
                "get_battery_status" -> ok(device.battery())
                "get_wifi_status" -> ok(device.wifi())
                "read_clipboard" -> ok(device.readClipboard())
                "vibrate" -> ok(device.vibrate())
                "write_clipboard" -> {
                    val text = args.optString("text", "")
                    if (text.isEmpty()) fail("there is nothing to copy") else ok(device.writeClipboard(text))
                }
                "set_volume" -> {
                    if (!args.has("level")) fail("level is required, an integer from 0 to 15")
                    else ok(device.setVolume(args.optInt("level", 0).coerceIn(0, 15)))
                }
                "open_url" -> {
                    val url = args.optString("url", "").trim()
                    if (!url.startsWith("https://") && !url.startsWith("http://")) fail("the link must start with https:// or http://")
                    else ok(device.openUrl(url))
                }
                "open_app" -> {
                    val app = args.optString("app", "").trim()
                    if (app.isEmpty()) fail("which app?") else ok(device.openApp(app))
                }
                "list_apps" -> ok(device.listApps(args.optString("search", "")))
                "get_weather" -> {
                    val city = args.optString("city", "").trim().ifEmpty { settings.weatherLocation().trim() }
                    ok(Weather.get(transport, city))
                }
                "search_web" -> {
                    val query = args.optString("query", "").trim()
                    if (query.isEmpty()) fail("what should I search for?") else ok(Search.tavily(transport, settings.tavilyKey(), query))
                }
                else -> fail("unknown tool: $name")
            }
        } catch (e: ToolFailure) {
            fail(e.message ?: "$name failed")
        } catch (e: Exception) {
            fail("$name failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun ok(text: String) = ToolResult(true, text)

    private fun fail(text: String) = ToolResult(false, text)
}
