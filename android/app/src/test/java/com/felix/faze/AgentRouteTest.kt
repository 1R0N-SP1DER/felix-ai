package com.felix.faze

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRouteTest {
    private fun router(store: LearnedRoutes = LearnedRoutes(File.createTempFile("learned", ".json").also { it.delete() })) =
        FastRoute(store, null, { true }, { true }, { true })

    private fun run(msg: String, chat: FakeChat, tools: FakeTools, router: FastRoute?, events: Events = Events()): String =
        Agent(chat, tools, 5, router).run("m", "SYSTEM", listOf("user" to msg), events.events) { false }

    @Test fun aRecognisedRequestRunsItsToolAndTheModelOnlySaysItIsDone() {
        val chat = FakeChat(text("Your battery is at 82 percent."))
        val tools = FakeTools(mapOf("get_battery_status" to ToolResult(true, "82%")))
        val ev = Events()
        assertEquals("Your battery is at 82 percent.", run("what's my battery", chat, tools, router(), ev))
        assertEquals(listOf("get_battery_status"), tools.executed.map { it.first })
        assertEquals(listOf("get_battery_status" to true), ev.tools)
        val call = chat.calls.single()
        assertNull(call.tools) // no tool list: the model has nothing to decide
        val last = call.messages.getJSONObject(call.messages.length() - 1)
        assertEquals("tool", last.getString("role"))
        assertEquals("82%", last.getString("content"))
        assertTrue(call.messages.toString().contains("tool_calls"))
    }

    @Test fun ifTheModelIsUnavailableTheToolResultIsStillSaid() {
        val chat = FakeChat(RemoteError("rate_limit", "busy"))
        val tools = FakeTools(mapOf("get_battery_status" to ToolResult(true, "82%")))
        assertEquals("82%", run("what's my battery", chat, tools, router()))
    }

    @Test fun aDeclinedToolIsNotAskedAgainByTheModel() {
        val declined = ToolResult(false, "${PermissionPolicy.DECLINED} read_clipboard. Do not try it again.")
        val chat = FakeChat(text("Okay, I won't."))
        val tools = FakeTools(mapOf("read_clipboard" to declined))
        assertEquals("Okay, I won't.", run("what's on my clipboard", chat, tools, router()))
        assertEquals(1, tools.executed.size)
        assertEquals(1, chat.calls.size)
        assertNull(chat.calls[0].tools)
    }

    @Test fun aFailedRouteFallsBackToTheModelWithItsTools() {
        val store = LearnedRoutes(File.createTempFile("learned", ".json").also { it.delete() })
        val r = router(store)
        val tools = FakeTools(mapOf("get_current_datetime" to ToolResult(false, "no clock")))
        val chat = FakeChat(text("I could not read the time."))
        assertEquals("I could not read the time.", run("what time is it", chat, tools, r))
        assertTrue(chat.calls.single().tools != null) // the normal loop, with tools
    }

    @Test fun aToolCallTheModelMadeIsLearnedAndNextTimeSkipsTheModelsChoice() {
        val store = LearnedRoutes(File.createTempFile("learned", ".json").also { it.delete() })
        val r = router(store)
        val tools = FakeTools(mapOf("get_battery_status" to ToolResult(true, "82%")))
        val first = FakeChat(calls(Pair("get_battery_status", "{}")), text("It is at 82 percent."))
        assertEquals("It is at 82 percent.", run("how much juice is left", first, tools, r))
        assertEquals(1, store.size())

        val second = FakeChat(text("Still 82 percent."))
        assertEquals("Still 82 percent.", run("hey faze how much juice is left?", second, tools, r))
        assertEquals(2, tools.executed.size)
        assertEquals(1, second.calls.size)
        assertNull(second.calls[0].tools)
    }

    @Test fun twoToolCallsInOneTurnAreNotLearned() {
        val store = LearnedRoutes(File.createTempFile("learned", ".json").also { it.delete() })
        val tools = FakeTools()
        val chat = FakeChat(calls(Pair("get_battery_status", "{}"), Pair("get_current_datetime", "{}")), text("Both done."))
        run("how much juice and what day", chat, tools, router(store))
        assertEquals(0, store.size())
    }

    @Test fun withoutARouterNothingChanges() {
        val chat = FakeChat(text("Hi."))
        assertEquals("Hi.", run("what's my battery", chat, FakeTools(), null))
        assertTrue(chat.calls.single().tools != null)
    }
}
