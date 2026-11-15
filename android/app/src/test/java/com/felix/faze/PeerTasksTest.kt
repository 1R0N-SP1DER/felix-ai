package com.felix.faze

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerTasksTest {
    @Test fun aTaskStartsAChat() {
        val started = ArrayList<String>()
        val r = PeerTasks({ false }, { started.add(it) }).handle(JSONObject().put("t", "phone_task").put("task", "  check battery "))!!
        assertEquals("phone_task_ok", r.getString("t"))
        assertEquals(listOf("check battery"), started)
    }

    @Test fun aBusyPhoneRefusesAndNothingStarts() {
        val started = ArrayList<String>()
        val r = PeerTasks({ true }, { started.add(it) }).handle(JSONObject().put("t", "phone_task").put("task", "x"))!!
        assertEquals("error", r.getString("t"))
        assertTrue(started.isEmpty())
    }

    @Test fun emptyTasksAndOtherMessagesAreNotTasks() {
        val p = PeerTasks({ false }, { })
        assertEquals("error", p.handle(JSONObject().put("t", "phone_task").put("task", " "))!!.getString("t"))
        assertNull(p.handle(JSONObject().put("t", "sync_list")))
    }

    private fun tools(on: Boolean, answer: Pair<Boolean, String> = true to "ok") = Tools(FakeDevice(), FakeTransport(), FakeSettings(), computers = object : Computers {
        override fun available() = on
        override fun ask(task: String) = answer
    })

    @Test fun theComputerToolIsOfferedOnlyWhileOneIsLinkedAndNeedsApproval() {
        fun names(on: Boolean): List<String> {
            val s = tools(on).schema()
            return (0 until s.length()).map { s.getJSONObject(it).getJSONObject("function").getString("name") }
        }
        assertTrue("ask_computer" in names(true))
        assertTrue("ask_computer" !in names(false))
        assertEquals(Tier.CONFIRM, PermissionPolicy.tier("ask_computer"))
        assertTrue(PermissionPolicy.summary("ask_computer", JSONObject().put("task", "open firefox")).contains("open firefox"))
    }

    @Test fun theToolReturnsTheComputersAnswerOrItsFailure() {
        assertEquals(ToolResult(true, "opened"), tools(true, true to "opened").execute("ask_computer", JSONObject().put("task", "x")))
        assertEquals(ToolResult(false, "busy"), tools(true, false to "busy").execute("ask_computer", JSONObject().put("task", "x")))
        assertEquals(false, tools(true).execute("ask_computer", JSONObject().put("task", " ")).ok)
    }
}
