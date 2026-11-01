package com.felix.faze

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

data class ChatRecord(val id: String, val title: String, val updated: Long, val messages: List<ChatMessage>)

/** Saved chats: one small JSON file per chat in the app's private folder. */
class ChatStore(private val dir: File, private val now: () -> Long = { System.currentTimeMillis() }) {
    init { dir.mkdirs() }

    /** Only letters, digits, '-' and '_' survive, so an id can never point outside [dir]. */
    private fun safe(id: String) = id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }

    private fun file(id: String) = File(dir, safe(id) + ".json")

    fun titleFor(messages: List<ChatMessage>): String {
        val first = messages.firstOrNull { it.role == "user" }?.text?.trim().orEmpty().replace(Regex("\\s+"), " ")
        return if (first.isEmpty()) "New chat" else if (first.length <= 40) first else first.take(40).trimEnd() + "…"
    }

    fun save(id: String, messages: List<ChatMessage>) {
        if (safe(id).isEmpty()) return // an id with nothing usable in it can't name a file
        val kept = messages.filter { it.text.isNotEmpty() || it.steps.isNotEmpty() }
        if (kept.none { it.role == "user" }) return // nothing worth keeping yet
        val arr = JSONArray()
        for (m in kept) arr.put(JSONObject().put("id", m.id).put("role", m.role).put("text", m.text).put("steps", JSONArray(m.steps)))
        val body = JSONObject().put("id", safe(id)).put("title", titleFor(kept)).put("updated", now()).put("messages", arr).toString()
        val tmp = File(dir, safe(id) + ".tmp")
        tmp.writeText(body)
        if (!tmp.renameTo(file(id))) { // some filesystems refuse to rename over a file
            file(id).delete()
            tmp.renameTo(file(id))
        }
    }

    fun load(id: String): ChatRecord? = read(file(id))

    private fun read(f: File): ChatRecord? {
        if (!f.isFile) return null
        return try {
            val o = JSONObject(f.readText())
            val a = o.getJSONArray("messages")
            val list = (0 until a.length()).map {
                val m = a.getJSONObject(it)
                val s = m.optJSONArray("steps")
                ChatMessage(m.getLong("id"), m.getString("role"), m.getString("text"), if (s == null) emptyList() else (0 until s.length()).map { i -> s.getString(i) })
            }
            ChatRecord(o.getString("id"), o.getString("title"), o.getLong("updated"), list)
        } catch (e: Exception) {
            null // a damaged file is skipped, not fatal
        }
    }

    /** Newest first. A non-empty [query] keeps chats whose title or any message contains it. */
    fun list(query: String = ""): List<ChatRecord> {
        val q = query.trim().lowercase()
        val all = (dir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray()).mapNotNull { read(it) }.sortedByDescending { it.updated }
        return if (q.isEmpty()) all else all.filter { r -> r.title.lowercase().contains(q) || r.messages.any { it.text.lowercase().contains(q) } }
    }

    fun delete(id: String) {
        file(id).delete()
    }
}
