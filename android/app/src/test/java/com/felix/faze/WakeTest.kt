package com.felix.faze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeTest {
    @Test fun thePhraseDefaultsToHeyAndTheName() {
        assertEquals("hey faze", Wake.phrase(""))
        assertEquals("hey jarvis", Wake.phrase("  Hey, Jarvis! "))
        assertEquals("hey max", Wake.phrase("", "Max"))
    }

    @Test fun theLastWordAloneAlsoWakesIt() {
        assertEquals(listOf("hey faze", "faze"), Wake.accepted("hey faze"))
        assertEquals(listOf("faze"), Wake.accepted("faze"))
        assertEquals(listOf("ok computer", "computer"), Wake.accepted("OK computer"))
        assertEquals(listOf("hi yo"), Wake.accepted("hi yo")) // a short last word is too easy to mishear
    }

    @Test fun theGrammarHasDecoysSoLookAlikesAreNotForcedOntoThePhrase() {
        val g = Wake.strictGrammar("hey faze")
        assertEquals("hey faze", g.first())
        assertEquals("[unk]", g.last())
        assertTrue(g.contains("hey phase"))
        assertTrue(g.contains("hay faze"))
        assertTrue(g.contains("phase"))
        assertFalse(g.contains("faze")) // the bare word is no longer a wake call
        assertEquals(g.size, g.toSet().size)
        org.json.JSONArray(Wake.strictGrammarJson("hey faze")) // valid JSON
        assertEquals(listOf("ok computer", "[unk]"), Wake.strictGrammar("OK computer"))
    }

    @Test fun matchingIsExactOrAboutOneEditPerFiveLetters() {
        assertTrue(Wake.matches("hey faze", "hey faze"))
        assertTrue(Wake.matches("Hey, Faze.", "hey faze"))
        assertTrue(Wake.matches("faze", "hey faze"))
        assertTrue(Wake.matches("hey fase", "hey faze")) // one letter off in eight
        assertFalse(Wake.matches("hey faise", "hey faze")) // two off is too far
        assertFalse(Wake.matches("hey fate and more", "hey faze"))
        assertFalse(Wake.matches("fate", "hey faze")) // a four-letter word must be exact
        assertFalse(Wake.matches("hello there", "hey faze"))
        assertFalse(Wake.matches("[unk]", "hey faze"))
        assertFalse(Wake.matches("", "hey faze"))
        assertFalse(Wake.matches("what is the weather", "hey faze"))
    }

    @Test fun cooldownBlocksUntilItEnds() {
        var t = 1000L
        val c = Cooldown(6) { t }
        assertTrue(c.ready())
        c.fire()
        assertFalse(c.ready())
        t += 5999
        assertFalse(c.ready())
        t += 2
        assertTrue(c.ready())
    }

    // ---- the endpointer, with synthetic audio (30 ms frames at 16 kHz)

    private fun frame(amp: Int): ShortArray = ShortArray(480) { i -> if (amp == 0) 0 else (amp * Math.sin(i * 0.3)).toInt().toShort() }

    private fun run(e: Endpointer, vararg parts: Pair<Int, Int>): Heard { // (amplitude, frames)
        var last = Heard.WAITING
        for ((amp, n) in parts) for (i in 0 until n) {
            last = e.feed(frame(amp))
            if (last == Heard.SPEECH_DONE || last == Heard.NOTHING) return last
        }
        return last
    }

    @Test fun speechThenSilenceEndsTheRequest() {
        assertEquals(Heard.SPEECH_DONE, run(Endpointer(), Pair(100, 10), Pair(4000, 30), Pair(100, 40)))
    }

    @Test fun itKeepsGoingWhileThePersonKeepsTalking() {
        assertEquals(Heard.SPEAKING, run(Endpointer(), Pair(100, 10), Pair(4000, 30), Pair(100, 10)))
    }

    @Test fun aShortPauseInsideASentenceDoesNotEndIt() {
        val e = Endpointer(endSilenceMs = 700)
        assertEquals(Heard.SPEAKING, run(e, Pair(100, 10), Pair(4000, 20), Pair(100, 15), Pair(4000, 10)))
    }

    @Test fun silenceFromTheStartGivesUpAfterTheTimeout() {
        assertEquals(Heard.NOTHING, run(Endpointer(noSpeechMs = 3000), Pair(0, 200)))
    }

    @Test fun aClickIsNotSpeech() {
        assertEquals(Heard.NOTHING, run(Endpointer(noSpeechMs = 3000), Pair(100, 10), Pair(4000, 2), Pair(100, 200)))
    }

    @Test fun aNoisyRoomStillEndsOnceTheTalkingStops() {
        // steady noise at 800, speech at 6000, then noise again
        assertEquals(Heard.SPEECH_DONE, run(Endpointer(), Pair(800, 12), Pair(6000, 30), Pair(800, 40)))
    }

    @Test fun neverRunsPastTheLimit() {
        assertEquals(Heard.SPEECH_DONE, run(Endpointer(maxMs = 3000), Pair(100, 10), Pair(4000, 500)))
    }
}
