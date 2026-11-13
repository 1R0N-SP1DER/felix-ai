package com.felix.faze

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseStyleTest {
    private val HI = listOf("data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}", "data: [DONE]")

    @Test fun effortHasFiveLevelsAndEachDoesMoreThanTheLast() {
        assertEquals(listOf("low", "medium", "high", "extra", "max"), Effort.levels.map { it.first })
        assertEquals("", Effort.directive("low"))
        assertEquals("low", Effort.norm("standard")) // an old saved level
        assertTrue(Effort.directive("medium").contains("double-check"))
        assertTrue(Effort.directive("high").contains("Cross-check"))
        assertFalse(Effort.directive("high").contains("bare minimum"))
        for (l in listOf("extra", "max")) assertTrue(Effort.directive(l).contains("more than the bare minimum"))
        assertFalse(Effort.reviews("extra"))
        assertTrue(Effort.reviews("max"))
        assertEquals(3, Effort.index("extra"))
        assertEquals("max", Effort.at(99))
    }

    @Test fun searchesBringPicturesAndMorePagesFromMediumUp() {
        assertFalse(Effort.enriches("low"))
        for (l in listOf("medium", "high", "extra", "max")) assertTrue(Effort.enriches(l))
        assertTrue(Effort.searchResults("low") < Effort.searchResults("high"))
        assertTrue(Effort.searchResults("high") < Effort.searchResults("max"))
    }

    @Test fun theLengthSliderHasFiveStopsAndTheMiddleLetsTheAiChoose() {
        assertEquals("Default", ResponseLength.label(2))
        assertEquals("", ResponseLength.directive(2))
        assertEquals(0, ResponseLength.tokens(2))
        assertTrue(ResponseLength.tokens(0) < ResponseLength.tokens(1))
        assertTrue(ResponseLength.tokens(3) < ResponseLength.tokens(4))
        assertTrue(ResponseLength.directive(0).contains("one or two sentences"))
        assertTrue(ResponseLength.directive(4).contains("very long"))
        assertEquals("Very long", ResponseLength.label(99)) // out of range is clamped
    }

    @Test fun theLengthSettingChangesTheRequestAndTheDefaultIsUntouched() {
        val t = FakeTransport()
        t.streamLines = HI
        val client = RemoteClient(t, "https://x", { "k" }, "X")
        ReplyLimit.tokens = ResponseLength.tokens(4)
        try { client.chat("m", JSONArray(), null, { _ -> }, { false }) } finally { ReplyLimit.tokens = 0 }
        assertEquals(6000, org.json.JSONObject(t.calls.single().body).getInt("max_tokens"))
        val t2 = FakeTransport(); t2.streamLines = HI
        RemoteClient(t2, "https://x", { "k" }, "X").chat("m", JSONArray(), null, { _ -> }, { false })
        assertEquals(RemoteClient.DEFAULT_MAX, org.json.JSONObject(t2.calls.single().body).getInt("max_tokens"))
        val t3 = FakeTransport(); t3.streamLines = HI
        ReplyLimit.tokens = 6000
        try { RemoteClient(t3, "https://x", { "k" }, "X", 400).chat("m", JSONArray(), null, { _ -> }, { false }) } finally { ReplyLimit.tokens = 0 }
        assertEquals(400, org.json.JSONObject(t3.calls.single().body).getInt("max_tokens")) // a client with its own small limit keeps it
    }

    @Test fun maxEffortReadsTheDraftBackAndFallsBackToTheDraftOnFailure() {
        val chat = FakeChat(ChatResult("the checked answer", emptyList(), true))
        val agent = Agent(chat, FakeTools())
        val ev = AgentEvents({ _ -> }, { }, { _, _ -> })
        assertEquals("the checked answer", agent.review("m", "sys", listOf("user" to "q"), "the draft answer", ev) { false })
        val last = chat.calls.last().messages
        assertEquals("assistant", last.getJSONObject(last.length() - 2).getString("role"))
        assertEquals(Effort.REVIEW_PROMPT, last.getJSONObject(last.length() - 1).getString("content"))
        val failing = Agent(FakeChat(RemoteError("server", "down")), FakeTools())
        assertEquals("the draft answer", failing.review("m", "sys", listOf("user" to "q"), "the draft answer", ev) { false })
    }
}
