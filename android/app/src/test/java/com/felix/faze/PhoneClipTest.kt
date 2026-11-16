package com.felix.faze

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneClipTest {
    private val written = mutableListOf<String>()
    private val sent = mutableListOf<String>()
    private var on = true
    private val clip = PhoneClip({ on }, { written.add(it) }, { sent.add(it) })
    private fun note(text: String) = JSONObject().put("t", "clip").put("text", text)

    @Test fun ignoresOtherMessages() = assertNull(clip.handle(JSONObject().put("t", "sync_list")))

    @Test fun aCopyFromTheComputerIsWrittenAndNeverAnswered() {
        assertNull(clip.handle(note("hello")))
        assertEquals(listOf("hello"), written)
    }

    @Test fun whatTheComputerSentIsNotEchoedBack() {
        clip.handle(note("hello"))
        clip.changed("hello")
        assertEquals(emptyList<String>(), sent)
    }

    @Test fun aCopyOnThePhoneIsSentOnce() {
        clip.changed("mine")
        clip.changed("mine")
        assertEquals(listOf("mine"), sent)
    }

    @Test fun offMeansNothingMoves() {
        on = false
        clip.handle(note("x"))
        clip.changed("y")
        assertEquals(emptyList<String>(), written + sent)
    }

    @Test fun emptyUnreadableAndHugeAreIgnored() {
        clip.changed(null)
        clip.changed("")
        clip.changed("a".repeat(PhoneClip.MAX + 1))
        clip.handle(note(""))
        clip.handle(note("a".repeat(PhoneClip.MAX + 1)))
        assertEquals(emptyList<String>(), written + sent)
    }

    @Test fun aNewerCopyFromTheComputerReplacesTheOlder() {
        clip.handle(note("one"))
        clip.handle(note("two"))
        clip.changed("two")
        assertEquals(listOf("one", "two"), written)
        assertEquals(emptyList<String>(), sent)
    }
}
