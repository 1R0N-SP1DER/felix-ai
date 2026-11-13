package com.felix.faze

import org.json.JSONArray
import org.json.JSONObject

/** Where part of an answer came from: a web page, something read on the phone, or the saved notes about the person. */
data class Source(val kind: String, val title: String, val url: String = "") {
    companion object {
        const val WEB = "web"
        const val DEVICE = "device"
        const val MEMORY = "memory"

        fun toJson(list: List<Source>): JSONArray {
            val a = JSONArray()
            for (s in list) a.put(JSONObject().put("kind", s.kind).put("title", s.title).put("url", s.url))
            return a
        }

        fun fromJson(a: JSONArray?): List<Source> =
            if (a == null) emptyList() else (0 until a.length()).map { val o = a.getJSONObject(it); Source(o.optString("kind"), o.optString("title"), o.optString("url")) }
    }
}

/** Works out the sources behind a tool result, so the Sources button can show where an answer came from. */
object SourceTracker {
    private val DEVICE_TOOLS = mapOf(
        "get_current_datetime" to "Phone clock",
        "get_battery_status" to "Battery status",
        "get_wifi_status" to "Wi-Fi status",
        "read_clipboard" to "Clipboard",
        "read_screen" to "Text on the screen",
        "look_at_screen" to "Picture of the screen",
        "list_apps" to "Installed apps",
        "list_scheduled" to "Scheduled tasks",
    )

    fun from(tool: String, args: JSONObject, result: ToolResult): List<Source> {
        if (!result.ok) return emptyList()
        DEVICE_TOOLS[tool]?.let { return listOf(Source(Source.DEVICE, it)) }
        return when (tool) {
            "search_web" -> pages(result.text)
            "get_weather" -> listOf(Source(Source.WEB, "Weather forecast", "https://open-meteo.com"))
            "recall" -> listOf(Source(Source.MEMORY, "Saved notes" + args.optString("category", "").trim().let { if (it.isEmpty()) "" else ": $it" }))
            else -> emptyList()
        }
    }

    /** The numbered results of a web search: "1. Title: snippet" followed by an indented address. */
    fun pages(text: String): List<Source> {
        val out = ArrayList<Source>()
        val lines = text.lines()
        for (i in lines.indices) {
            val m = Regex("^\\d+\\.\\s+(.*)$").find(lines[i]) ?: continue
            val url = lines.getOrNull(i + 1)?.trim().orEmpty()
            if (!url.startsWith("http")) continue
            val title = m.groupValues[1].substringBefore(": ").trim().take(90).ifEmpty { url }
            out.add(Source(Source.WEB, title, url))
        }
        return out
    }
}
