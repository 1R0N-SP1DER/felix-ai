package com.felix.faze

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LinkSyncTest {
    @get:Rule val tmp = TemporaryFolder()
    private var t = 1000L
    private fun stores() = ChatStore(tmp.newFolder("chats"), { t++ }) to MemoryStore(java.io.File(tmp.root, "memory.json"), { t++ })

    private fun chat(id: String, vararg lines: Pair<String, String>) =
        JSONObject().put("id", id).put("messages", JSONArray().also { a -> lines.forEach { a.put(JSONObject().put("role", it.first).put("text", it.second)) } })

    @Test fun takesInAChatFromTheComputerAndDoesNotDuplicate() {
        val (chats, memory) = stores()
        var changes = 0
        val sync = LinkSync(chats, memory, { t }, { changes++ })
        val c = chat("abc123", "user" to "hello there", "assistant" to "hi")
        assertEquals(2, sync.handle(JSONObject().put("t", "sync_put_chat").put("chat", c))!!.getInt("added"))
        assertEquals(0, sync.handle(JSONObject().put("t", "sync_put_chat").put("chat", c))!!.getInt("added"))
        assertEquals(1, changes)
        assertEquals(listOf("hello there", "hi"), chats.load("abc123")!!.messages.map { it.text })
    }

    @Test fun mergesNewLinesIntoAChatThePhoneAlreadyHas() {
        val (chats, memory) = stores()
        chats.save("c1", listOf(ChatMessage(1, "user", "one"), ChatMessage(2, "assistant", "two")))
        val sync = LinkSync(chats, memory, { t })
        sync.handle(JSONObject().put("t", "sync_put_chat").put("chat", chat("c1", "user" to "one", "assistant" to "two", "user" to "three")))
        assertEquals(listOf("one", "two", "three"), chats.load("c1")!!.messages.map { it.text })
    }

    @Test fun listsOnlyChatsChangedSinceAndGivesBackItsOwnClock() {
        val (chats, memory) = stores()
        chats.save("old", listOf(ChatMessage(1, "user", "a")))
        val mid = t; t += 10
        chats.save("new", listOf(ChatMessage(1, "user", "b")))
        val r = LinkSync(chats, memory, { 5000 }).handle(JSONObject().put("t", "sync_list").put("since", mid))!!
        assertEquals(5000L, r.getLong("now"))
        val ids = (0 until r.getJSONArray("chats").length()).map { r.getJSONArray("chats").getJSONObject(it).getString("id") }
        assertEquals(listOf("new"), ids)
    }

    @Test fun sendsAChatAsPlainText() {
        val (chats, memory) = stores()
        chats.save("c1", listOf(ChatMessage(1, "user", "q"), ChatMessage(2, "assistant", "a", steps = listOf("ran a tool"))))
        val c = LinkSync(chats, memory).handle(JSONObject().put("t", "sync_get").put("id", "c1"))!!.getJSONObject("chat")
        assertEquals(2, c.getJSONArray("messages").length())
        assertEquals("a", c.getJSONArray("messages").getJSONObject(1).getString("text"))
    }

    @Test fun notesMergeByTextAndSecretsStayOut() {
        val (chats, memory) = stores()
        memory.add("likes tea in the morning")
        val sync = LinkSync(chats, memory)
        val r = sync.handle(JSONObject().put("t", "sync_put_facts").put("facts", JSONArray(listOf("likes tea in the morning", "works at night", "api key is sk-abcdefghijklmnopqrstuvwx"))))!!
        assertEquals(1, r.getInt("added"))
        assertTrue(memory.list().any { it.text == "works at night" })
        assertEquals(2, memory.list().size)
    }

    @Test fun unknownMessagesAreIgnored() {
        val (chats, memory) = stores()
        assertNull(LinkSync(chats, memory).handle(JSONObject().put("t", "nope")))
    }
}
