package com.felix.faze

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneNotesTest {
    private fun note(title: String = "Ada", text: String = "hi there", pkg: String = "com.chat", ongoing: Boolean = false, summary: Boolean = false) =
        PhoneNotes.Note("k1", pkg, "Chat", title, text, ongoing, summary, 5L)

    private fun hub(on: Boolean = true, hide: Boolean = false, out: MutableList<JSONObject>) = PhoneNotes({ on }, { hide }, { out.add(it) }, "com.felix.faze")

    @Test fun aNormalNotificationIsSentWithItsText() {
        val out = mutableListOf<JSONObject>()
        hub(out = out).posted(note())
        assertEquals(1, out.size)
        assertEquals("note", out[0].getString("t"))
        assertEquals("Ada", out[0].getString("title"))
        assertEquals("hi there", out[0].getString("text"))
        assertEquals("Chat", out[0].getString("app"))
    }

    @Test fun ongoingSummaryOwnAndEmptyOnesAreSkipped() {
        val out = mutableListOf<JSONObject>()
        val h = hub(out = out)
        h.posted(note(ongoing = true))
        h.posted(note(summary = true))
        h.posted(note(pkg = "com.felix.faze"))
        h.posted(note(title = " ", text = ""))
        assertTrue(out.isEmpty())
    }

    @Test fun offMeansNothingLeavesThePhone() {
        val out = mutableListOf<JSONObject>()
        val h = hub(on = false, out = out)
        h.posted(note())
        h.removed("k1")
        assertTrue(out.isEmpty())
    }

    @Test fun hidingTheTextSendsOnlyTheAppName() {
        val out = mutableListOf<JSONObject>()
        hub(hide = true, out = out).posted(note(title = "secret", text = "my code is 1234"))
        assertEquals("", out[0].getString("title"))
        assertFalse(out[0].toString().contains("1234"))
        assertFalse(out[0].toString().contains("secret"))
    }

    @Test fun longTextIsCut() {
        val out = mutableListOf<JSONObject>()
        hub(out = out).posted(note(text = "x".repeat(1000)))
        assertEquals(PhoneNotes.MAX, out[0].getString("text").length)
    }

    @Test fun clearingItSendsARemoval() {
        val out = mutableListOf<JSONObject>()
        hub(out = out).removed("k1")
        assertEquals("note_gone", out[0].getString("t"))
        assertEquals("k1", out[0].getString("id"))
    }
}
