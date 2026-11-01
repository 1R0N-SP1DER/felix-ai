package com.felix.faze

import java.io.IOException
import org.json.JSONObject

/** Tavily web search: a short synthesized answer plus the sources, as text the model can answer from. */
object Search {
    private const val URL = "https://api.tavily.com/search"

    fun tavily(transport: Transport, key: String, query: String, maxResults: Int = 5): String {
        if (key.isBlank()) throw ToolFailure("web search has no key (add the Tavily key under Keys)")
        val body = JSONObject().put("query", query).put("max_results", maxResults).put("include_answer", true).put("search_depth", "basic")
        val r = try {
            transport.post(URL, mapOf("Authorization" to "Bearer ${key.trim()}"), body.toString().toByteArray(Charsets.UTF_8), "application/json", 15000)
        } catch (e: IOException) {
            throw ToolFailure("Tavily could not be reached (${e.javaClass.simpleName})")
        }
        when (r.status) {
            200 -> Unit
            401, 403 -> throw ToolFailure("Tavily refused the key")
            429, 432, 433 -> throw ToolFailure("Tavily limit reached (${r.status})")
            else -> throw ToolFailure("Tavily answered ${r.status}")
        }
        val data = try {
            JSONObject(r.body)
        } catch (e: Exception) {
            throw ToolFailure("Tavily sent something unreadable")
        }
        val results = data.optJSONArray("results")
        val answer = data.optString("answer", "").trim()
        if ((results == null || results.length() == 0) && answer.isEmpty()) throw ToolFailure("Tavily found nothing")
        val lines = ArrayList<String>()
        lines.add("Web search results for '$query' (answer from them and say if they don't settle it):")
        if (answer.isNotEmpty()) lines.add("Summary: $answer")
        if (results != null) {
            for (i in 0 until minOf(results.length(), maxResults)) {
                val item = results.optJSONObject(i) ?: continue
                val snippet = item.optString("content", "").replace(Regex("\\s+"), " ").trim().take(240)
                lines.add("${i + 1}. ${item.optString("title", "").trim()}" + (if (snippet.isNotEmpty()) ": $snippet" else "") + "\n   ${item.optString("url", "")}")
            }
        }
        return lines.joinToString("\n")
    }
}
