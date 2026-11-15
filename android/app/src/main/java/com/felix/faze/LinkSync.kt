package com.felix.faze

import org.json.JSONArray
import org.json.JSONObject

/**
 * The phone's side of sync with a computer. The phone is the hub and the source of truth; the computer asks, and the phone answers
 * from its own chats and notes and takes in what the computer has that it lacks. Nothing is ever deleted by a sync: chats are merged
 * message by message (the same role and text is the same message) and notes by their text. Only the text of a chat travels, not
 * attachments or tool steps.
 */
class LinkSync(
    private val store: ChatStore,
    private val memory: MemoryStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val changed: () -> Unit = {},
) {
    companion object {
        const val MAX_MESSAGES = 200
    }

    fun handle(msg: JSONObject): JSONObject? = when (msg.optString("t")) {
        "sync_list" -> list(msg.optLong("since", 0))
        "sync_get" -> get(msg.optString("id"))
        "sync_put_chat" -> putChat(msg.getJSONObject("chat"))
        "sync_put_facts" -> putFacts(msg.getJSONArray("facts"))
        else -> null
    }

    private fun list(since: Long): JSONObject {
        val facts = JSONArray()
        for (m in memory.list()) facts.put(JSONObject().put("text", m.text).put("category", m.category))
        val chats = JSONArray()
        for (c in store.list().filter { it.updated > since }) chats.put(JSONObject().put("id", c.id).put("title", c.title).put("updated", c.updated))
        return JSONObject().put("t", "sync_list_ok").put("now", now()).put("facts", facts).put("chats", chats)
    }

    private fun get(id: String): JSONObject {
        val record = store.load(id) ?: return JSONObject().put("t", "sync_chat").put("chat", JSONObject.NULL)
        val msgs = JSONArray()
        for (m in record.messages.filter { (it.role == "user" || it.role == "assistant") && it.text.isNotEmpty() }.takeLast(MAX_MESSAGES)) msgs.put(JSONObject().put("role", m.role).put("text", m.text))
        return JSONObject().put("t", "sync_chat").put("chat", JSONObject().put("id", record.id).put("title", record.title).put("updated", record.updated).put("messages", msgs))
    }

    private fun putChat(chat: JSONObject): JSONObject {
        val id = chat.getString("id")
        val existing = store.load(id)?.messages.orEmpty()
        val have = existing.map { it.role to it.text }.toHashSet()
        var next = (existing.maxOfOrNull { it.id } ?: 0L) + 1
        val added = ArrayList<ChatMessage>()
        val incoming = chat.getJSONArray("messages")
        for (i in 0 until incoming.length()) {
            val m = incoming.getJSONObject(i)
            val role = m.getString("role")
            val text = m.getString("text")
            if ((role != "user" && role != "assistant") || text.isEmpty() || !have.add(role to text)) continue
            added.add(ChatMessage(next++, role, text))
        }
        if (added.isNotEmpty()) { // nothing is saved when nothing is new, so a sync does not keep bumping the chat
            store.save(id, existing + added)
            changed()
        }
        return JSONObject().put("t", "ok").put("added", added.size)
    }

    private fun putFacts(facts: JSONArray): JSONObject {
        var added = 0
        for (i in 0 until facts.length()) {
            val text = facts.getString(i)
            val before = memory.list().size
            memory.add(text)
            if (memory.list().size > before) added++
        }
        if (added > 0) changed()
        return JSONObject().put("t", "ok").put("added", added)
    }
}
