package com.felix.faze

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YoloTest {
    private class Ask(var answer: Boolean = true) : Approver {
        var asked = 0
        override fun approve(tool: String, summary: String): Boolean { asked++; return answer }
    }

    private fun gate(tools: FakeTools, ask: Ask, mode: String = "ask", phone: String = "ask") =
        GatedTools(tools, ask, approvalMode = { mode }, phoneControl = { phone })

    @Test fun askModeStillAsks() {
        val tools = FakeTools(); val ask = Ask()
        assertTrue(gate(tools, ask).execute("read_clipboard", JSONObject()).ok)
        assertEquals(1, ask.asked)
    }

    @Test fun yoloRunsEverythingThatWouldAskWithoutAsking() {
        val tools = FakeTools(); val ask = Ask(false)
        val g = gate(tools, ask, mode = "yolo")
        for (t in listOf("read_clipboard", "write_clipboard", "tap", "set_mobile_data", "schedule_action", "read_screen")) assertTrue(t, g.execute(t, JSONObject()).ok)
        assertEquals(0, ask.asked)
        assertEquals(6, tools.executed.size)
    }

    @Test fun yoloDoesNotUnblockBlockedTools() {
        val tools = FakeTools(); val ask = Ask()
        val r = gate(tools, ask, mode = "yolo").execute("rm_rf_everything", JSONObject())
        assertFalse(r.ok)
        assertTrue(tools.executed.isEmpty())
    }

    @Test fun phoneControlOffRefusesTheScreenToolsInEveryMode() {
        for (mode in listOf("ask", "yolo")) {
            val tools = FakeTools(); val ask = Ask()
            val g = gate(tools, ask, mode = mode, phone = "off")
            for (t in PermissionPolicy.phoneTools) assertFalse("$mode $t", g.execute(t, JSONObject()).ok)
            assertTrue(tools.executed.isEmpty())
            assertEquals(0, ask.asked)
            assertTrue("other tools are not affected", g.execute("write_clipboard", JSONObject()).ok || mode == "ask")
        }
    }

    @Test fun phoneControlAlwaysOnNeverAsksAndNeverExpires() {
        var now = 0L
        val tools = FakeTools(); val ask = Ask(false)
        val g = GatedTools(tools, ask, { now }, approvalMode = { "ask" }, phoneControl = { "on" })
        assertTrue(g.execute("tap", JSONObject()).ok)
        now += 10 * 60 * 60_000L
        assertTrue(g.execute("swipe", JSONObject()).ok)
        assertEquals(0, ask.asked)
        // alwaysOn is only about the screen: the clipboard still asks
        assertFalse(g.execute("read_clipboard", JSONObject()).ok)
        assertEquals(1, ask.asked)
    }

    @Test fun badValuesFallBackToAsk() {
        assertEquals("ask", PermissionPolicy.approvalMode("whatever"))
        assertEquals("ask", PermissionPolicy.approvalMode(""))
        assertEquals("yolo", PermissionPolicy.approvalMode("yolo"))
        assertEquals("ask", PermissionPolicy.phoneMode("loud"))
        assertEquals("on", PermissionPolicy.phoneMode("on"))
        assertEquals("off", PermissionPolicy.phoneMode("off"))
    }
}
