package com.felix.faze

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveUiTest {
    private fun pcm(v: Int, n: Int = 1600): ByteArray {
        val b = ByteArray(n * 2)
        for (i in 0 until n) { val s = if (i % 2 == 0) v else -v; b[2 * i] = (s and 0xff).toByte(); b[2 * i + 1] = ((s shr 8) and 0xff).toByte() }
        return b
    }

    @Test fun silenceIsZeroAndLoudSpeechIsFull() {
        assertEquals(0f, MicLevel.of(pcm(0)), 0f)
        assertEquals(0f, MicLevel.of(ByteArray(0)), 0f)
        assertEquals(1f, MicLevel.of(pcm(6000)), 0.001f)
        assertEquals(1f, MicLevel.of(pcm(32000)), 0.001f)
    }

    @Test fun quietSpeechStillMovesTheBar() {
        val l = MicLevel.of(pcm(500))
        assertTrue(l > 0.25f && l < 0.5f)
        assertTrue(MicLevel.of(pcm(2000)) > l)
    }

    @Test fun barsStayInRangeAndTheMiddleIsTallest() {
        val quiet = LiveBars.heights(0f, 0f, 23)
        assertEquals(23, quiet.size)
        assertTrue(quiet.all { it == 0.08f })
        val loud = LiveBars.heights(1f, 0f, 23)
        assertTrue(loud.all { it in 0.08f..1f })
        assertTrue(loud[11] > loud[0])
        assertTrue(loud[11] > loud[22])
    }

    @Test fun louderIsTallerAtTheSamePoint() {
        assertTrue(LiveBars.heights(0.8f, 0.3f, 23).sum() > LiveBars.heights(0.2f, 0.3f, 23).sum())
        assertEquals(1, LiveBars.heights(0.5f, 0f, 1).size)
        assertTrue(LiveBars.heights(5f, 0f, 5).all { it <= 1f })
    }

    // ---- tools that take over the screen

    private class Inner : ToolRunner {
        val ran = ArrayList<String>()
        override fun schema(): JSONArray = JSONArray().put("schema")
        override fun execute(name: String, args: JSONObject): ToolResult { ran.add(name); return ToolResult(true, "ok") }
        override fun precheck(name: String, args: JSONObject): String? = if (name == "bad") "no" else null
    }

    @Test fun anAppOpensOnlyAfterTheSpeechHasFinishedWhenThereIsNoBubble() {
        val inner = Inner()
        var polls = 0
        val sleeps = ArrayList<Long>()
        val tools = SpeechAwareTools(inner, { true }, { polls++ < 3 }, { false }, { sleeps.add(it) })
        assertTrue(tools.execute("open_app", JSONObject()).ok)
        assertEquals(listOf("open_app"), inner.ran)
        assertEquals(listOf(100L, 100L, 100L, 300L), sleeps)
        sleeps.clear()
        tools.execute("open_url", JSONObject())
        assertEquals(listOf("open_app", "open_url"), inner.ran)
    }

    @Test fun withTheBubbleUpTheToolRunsAtOnceAndTheSpeechCarriesOn() {
        val inner = Inner()
        val sleeps = ArrayList<Long>()
        SpeechAwareTools(inner, { true }, { true }, { true }, { sleeps.add(it) }).execute("open_app", JSONObject())
        assertEquals(listOf("open_app"), inner.ran)
        assertTrue(sleeps.isEmpty())
    }

    @Test fun otherToolsAndQuietMomentsNeverWait() {
        val inner = Inner()
        val sleeps = ArrayList<Long>()
        SpeechAwareTools(inner, { true }, { true }, { false }, { sleeps.add(it) }).execute("get_weather", JSONObject())
        SpeechAwareTools(inner, { false }, { true }, { false }, { sleeps.add(it) }).execute("open_app", JSONObject())
        SpeechAwareTools(inner, { true }, { false }, { false }, { sleeps.add(it) }).execute("open_app", JSONObject())
        assertEquals(listOf("get_weather", "open_app", "open_app"), inner.ran)
        assertTrue(sleeps.isEmpty())
    }

    @Test fun itGivesUpWaitingAfterTheMaximum() {
        val inner = Inner()
        val sleeps = ArrayList<Long>()
        SpeechAwareTools(inner, { true }, { true }, { false }, { sleeps.add(it) }, maxWaitMs = 1000).execute("open_app", JSONObject())
        assertEquals(listOf("open_app"), inner.ran)
        assertEquals(11, sleeps.size)
    }

    @Test fun theWrapperPassesTheSchemaAndPrecheckThrough() {
        val tools = SpeechAwareTools(Inner(), { true }, { false }, { false })
        assertEquals(1, tools.schema().length())
        assertEquals("no", tools.precheck("bad", JSONObject()))
        assertFalse(SpeechAwareTools.FOREGROUND_TOOLS.contains("get_weather"))
    }
}
