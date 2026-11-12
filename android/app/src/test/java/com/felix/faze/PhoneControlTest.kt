package com.felix.faze

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneControlTest {
    private fun run(chat: FakeChat, tools: ToolRunner, rounds: Int = 5): String =
        Agent(chat, tools, rounds).run("m", "sys", listOf("user" to "open telegram and message Vic hi"), Events().events) { false }

    @Test fun readingTheScreenAgainReallyReadsItAgain() {
        // the screen changes after a tap: an identical read_screen call must not be answered from the first one
        val chat = FakeChat(calls("read_screen" to "{}"), calls("tap" to "{\"x\":5,\"y\":5}"), calls("read_screen" to "{}"), text("Done."))
        val tools = FakeTools()
        run(chat, tools)
        assertEquals(listOf("read_screen", "tap", "read_screen"), tools.executed.map { it.first })
    }

    @Test fun tappingTheSameSpotTwiceRunsTwice() {
        val chat = FakeChat(calls("tap" to "{\"x\":9,\"y\":9}"), calls("tap" to "{\"x\":9,\"y\":9}"), text("Done."))
        val tools = FakeTools()
        run(chat, tools)
        assertEquals(2, tools.executed.size)
    }

    @Test fun otherIdenticalRepeatsAreStillNotRunTwice() {
        val chat = FakeChat(calls("get_battery_status" to "{}"), calls("get_battery_status" to "{}"), text("Done."))
        val tools = FakeTools()
        run(chat, tools)
        assertEquals(1, tools.executed.size)
    }

    @Test fun aPhoneTaskHasNoLimitOnSteps() {
        val steps = (1..40).map { calls("tap" to "{\"x\":$it,\"y\":1}") } + text("Sent.")
        val chat = FakeChat(*steps.toTypedArray())
        val tools = FakeTools()
        assertEquals("Sent.", run(chat, tools))
        assertEquals(40, tools.executed.size)
    }

    @Test fun otherTasksKeepTheirShortLimit() {
        val steps = (1..10).map { calls("get_weather" to "{\"city\":\"c$it\"}") } + text("x")
        val chat = FakeChat(*steps.toTypedArray())
        try { run(chat, FakeTools()); org.junit.Assert.fail("should stop") } catch (e: RemoteError) { assertTrue(e.message!!.contains("kept calling tools")) }
    }

    private class RecordingMarker : TouchMarker {
        val log = ArrayList<String>()
        override fun tap(x: Int, y: Int) { log.add("tap $x $y") }
        override fun longPress(x: Int, y: Int) { log.add("long $x $y") }
        override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int) { log.add("swipe $x1 $y1 $x2 $y2") }
    }

    @Test fun theTouchIsShownThenDone() {
        val hands = FakeHands(); val marker = RecordingMarker()
        val h = MarkedHands(hands, marker) { true }
        h.tap(10, 20); h.longPress(1, 2); h.swipe(1, 2, 3, 4)
        assertEquals(listOf("tap 10 20", "long 1 2", "swipe 1 2 3 4"), marker.log)
        assertEquals(3, hands.log.size)
    }

    @Test fun withTheSettingOffNothingIsShownButTheTouchStillHappens() {
        val hands = FakeHands(); val marker = RecordingMarker()
        MarkedHands(hands, marker) { false }.tap(1, 1)
        assertTrue(marker.log.isEmpty())
        assertEquals(1, hands.log.size)
    }

    @Test fun aBrokenMarkerNeverStopsTheTouch() {
        val hands = FakeHands()
        val broken = object : TouchMarker {
            override fun tap(x: Int, y: Int) = throw IllegalStateException("no overlay")
            override fun longPress(x: Int, y: Int) = throw IllegalStateException("no overlay")
            override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int) = throw IllegalStateException("no overlay")
        }
        val h = MarkedHands(hands, broken) { true }
        h.tap(1, 1); h.swipe(1, 1, 2, 2)
        assertEquals(2, hands.log.size)
    }

    @Test fun theMarkMovesAndFades() {
        val (g0, a0) = MarkLook.ring(0, MarkLook.TAP_MS)
        val (g1, a1) = MarkLook.ring(MarkLook.TAP_MS, MarkLook.TAP_MS)
        assertEquals(0f, g0, 0.001f); assertEquals(1f, a0, 0.001f)
        assertEquals(1f, g1, 0.001f); assertEquals(0f, a1, 0.001f)
        val (m0, _) = MarkLook.travel(0, MarkLook.SWIPE_MS)
        val (mEnd, aEnd) = MarkLook.travel(MarkLook.SWIPE_MS, MarkLook.SWIPE_MS)
        val (mMid, aMid) = MarkLook.travel((MarkLook.SWIPE_MS * 0.5).toLong(), MarkLook.SWIPE_MS)
        assertEquals(0f, m0, 0.001f)
        assertEquals(1f, mMid, 0.001f); assertEquals(1f, aMid, 0.001f) // arrived, still fully visible
        assertEquals(1f, mEnd, 0.001f); assertEquals(0f, aEnd, 0.001f)
    }

    @Test fun theKeepAliveStartsOnlyForToolsThatLeaveTheApp() {
        var started = 0
        val k = KeepAliveTools(FakeTools()) { started++ }
        k.execute("get_battery_status", JSONObject()); assertEquals(0, started)
        k.execute("open_app", JSONObject()); assertEquals(1, started)
        k.execute("read_screen", JSONObject()); k.execute("tap", JSONObject()); assertEquals(3, started)
    }

    @Test fun theOfflineModelCanReadTheScreen() {
        assertTrue("read_screen" in LocalLlama.OFFLINE_TOOLS)
        assertFalse("look_at_screen" in LocalLlama.OFFLINE_TOOLS) // the vision model is online
        assertTrue(LocalLlama.compactTools(org.json.JSONArray().put(JSONObject().put("type", "function").put("function", JSONObject().put("name", "read_screen").put("description", "Reads it. More."))))!!.length() == 1)
    }
}
