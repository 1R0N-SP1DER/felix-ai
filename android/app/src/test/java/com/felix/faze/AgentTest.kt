package com.felix.faze

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AgentTest {
    private val history = listOf(Pair("user", "hello there"))

    private fun run(chat: FakeChat, tools: FakeTools = FakeTools(), events: Events = Events(), rounds: Int = 5, cancelled: () -> Boolean = { false }): String =
        Agent(chat, tools, rounds).run("test-model", "SYSTEM", history, events.events, cancelled)

    private fun expectError(block: () -> Unit): RemoteError {
        try {
            block()
        } catch (e: RemoteError) {
            return e
        }
        fail("expected a RemoteError")
        throw IllegalStateException()
    }

    @Test
    fun aPlainAnswerWithTheRealPromptAndTools() {
        val chat = FakeChat(text("Hello from Groq."))
        assertEquals("Hello from Groq.", run(chat))
        val call = chat.calls[0]
        assertEquals("test-model", call.model)
        assertEquals("system", call.messages.getJSONObject(0).getString("role"))
        assertEquals("SYSTEM", call.messages.getJSONObject(0).getString("content"))
        assertEquals("hello there", call.messages.getJSONObject(1).getString("content"))
        assertNotNull(call.tools)
    }

    @Test
    fun theToolLoopFeedsResultsBack() {
        val chat = FakeChat(calls(Pair("get_current_datetime", "{}")), text("It is half past four."))
        val tools = FakeTools()
        assertEquals("It is half past four.", run(chat, tools))
        assertEquals(listOf("get_current_datetime"), tools.executed.map { it.first })
        val second = chat.calls[1].messages
        val assistant = second.getJSONObject(second.length() - 2)
        assertEquals("assistant", assistant.getString("role"))
        val call = assistant.getJSONArray("tool_calls").getJSONObject(0)
        assertEquals("id0", call.getString("id"))
        assertEquals("get_current_datetime", call.getJSONObject("function").getString("name"))
        val result = second.getJSONObject(second.length() - 1)
        assertEquals("tool", result.getString("role"))
        assertEquals("id0", result.getString("tool_call_id"))
        assertEquals("half past four", result.getString("content"))
        assertNotNull(chat.calls[1].tools)
    }

    @Test
    fun parallelCallsEachGetTheirResult() {
        val chat = FakeChat(calls(Pair("get_current_datetime", "{}"), Pair("get_battery_status", "{}")), text("Both done."))
        val tools = FakeTools()
        run(chat, tools)
        assertEquals(listOf("get_current_datetime", "get_battery_status"), tools.executed.map { it.first })
        val messages = chat.calls[1].messages
        val ids = (0 until messages.length()).map { messages.getJSONObject(it) }.filter { it.getString("role") == "tool" }.map { it.getString("tool_call_id") }
        assertEquals(listOf("id0", "id1"), ids)
    }

    @Test
    fun anIdenticalRepeatIsNotRunTwice() {
        val chat = FakeChat(calls(Pair("get_current_datetime", "{}")), calls(Pair("get_current_datetime", "{}")), text("Done."))
        val tools = FakeTools()
        run(chat, tools)
        assertEquals(1, tools.executed.size)
        val messages = chat.calls[2].messages
        assertEquals(2, (0 until messages.length()).count { messages.getJSONObject(it).getString("role") == "tool" })
    }

    @Test
    fun toolErrorsGoBackAsText() {
        val chat = FakeChat(calls(Pair("get_battery_status", "{}")), text("I couldn't read the battery."))
        val tools = FakeTools(mapOf("get_battery_status" to ToolResult(false, "no battery sensor")))
        val events = Events()
        run(chat, tools, events)
        val messages = chat.calls[1].messages
        assertEquals("Error: no battery sensor", messages.getJSONObject(messages.length() - 1).getString("content"))
        assertEquals(listOf(Pair("get_battery_status", false)), events.tools)
    }

    @Test
    fun theLastRoundHasNoToolsAndALoopIsCut() {
        val chat = FakeChat(calls(Pair("get_current_datetime", "{}")), calls(Pair("get_battery_status", "{}")))
        val e = expectError { run(chat, rounds = 2) }
        assertTrue(e.message!!.contains("kept calling tools"))
        assertNotNull(chat.calls[0].tools)
        assertNull(chat.calls[1].tools)
    }

    @Test
    fun aGarbledToolCallIsRetriedThenAskedWithoutTools() {
        val bad = RemoteError("tool_failed", "the model's tool call was malformed")
        assertEquals("Fine now.", run(FakeChat(bad, text("Fine now."))))
        val chat = FakeChat(bad, bad, text("Answered without tools."))
        assertEquals("Answered without tools.", run(chat))
        assertNull(chat.calls[2].tools)
        expectError { run(FakeChat(bad, bad, bad)) }
    }

    @Test
    fun malformedArgumentsBecomeEmptyOnes() {
        val chat = FakeChat(ChatResult("", listOf(ToolCall("x", "get_current_datetime", "{not json")), false), text("ok"))
        val tools = FakeTools()
        run(chat, tools)
        assertEquals(0, tools.executed[0].second.length())
    }

    @Test
    fun textBeforeAToolCallIsDroppedFromTheStream() {
        val events = Events()
        run(FakeChat(calls(Pair("get_current_datetime", "{}"), content = "Let me check. "), text("It is half past four.")), events = events)
        assertEquals(1, events.resets)
    }

    @Test
    fun streamedTokensReachTheHook() {
        val events = Events()
        run(FakeChat(FakeChat.Streamed(listOf("Hel", "lo"))), events = events)
        assertEquals(listOf("Hel", "lo"), events.tokens)
        assertEquals(0, events.resets)
    }

    @Test
    fun anEmptyReplyIsAnError() {
        assertEquals("empty", expectError { run(FakeChat(text("   "))) }.kind)
    }

    @Test
    fun stoppingCancelsTheTurn() {
        try {
            run(FakeChat(Cancelled()))
            fail("expected Cancelled")
        } catch (e: Cancelled) {
            assertTrue(true)
        }
        try {
            run(FakeChat(text("late")), cancelled = { true })
            fail("expected Cancelled")
        } catch (e: Cancelled) {
            assertFalse(false)
        }
    }

    @Test
    fun atMostFiveCallsRunInARound() {
        val many = (0 until 8).map { Pair("get_current_datetime", "{\"n\": $it}") }.toTypedArray()
        val tools = FakeTools()
        run(FakeChat(calls(*many), text("ok")), tools)
        assertEquals(5, tools.executed.size)
    }

    @Test
    fun toolNamesComeFromTheSchema() {
        assertEquals(listOf("get_current_datetime", "get_battery_status", "get_weather"), Agent(FakeChat(), FakeTools()).toolNames())
    }

    @Test
    fun aMissingIdGetsAGeneratedOne() {
        val chat = FakeChat(ChatResult("", listOf(ToolCall("", "get_current_datetime", "")), false), text("ok"))
        run(chat)
        val messages = chat.calls[1].messages
        assertEquals("call_0", messages.getJSONObject(messages.length() - 1).getString("tool_call_id"))
        val args = messages.getJSONObject(messages.length() - 2).getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function").getString("arguments")
        assertEquals("{}", args)
        assertEquals(0, JSONObject(args).length())
    }
}
