package com.felix.faze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakableTest {
    private fun feedAll(vararg pieces: String): Pair<SentenceStream, List<List<String>>> {
        val s = SentenceStream()
        return Pair(s, pieces.map { s.feed(it) })
    }

    @Test
    fun sentencesComeOutAsTheyComplete() {
        val (s, out) = feedAll("The weather today is", " mild and clear. ", "Tomorrow it will rain", " in the afternoon. ", "Bring")
        assertEquals(listOf(emptyList(), listOf("The weather today is mild and clear."), emptyList(), listOf("Tomorrow it will rain in the afternoon."), emptyList()), out)
        assertEquals(listOf("Bring"), s.finish())
    }

    @Test
    fun aSentenceEndNeedsASpaceAfterIt() {
        val (s, out) = feedAll("Version 3.5 is out", " and works fine.")
        assertEquals(listOf(emptyList<String>(), emptyList()), out)
        assertEquals(listOf("Version 3.5 is out and works fine."), s.finish())
    }

    @Test
    fun shortFragmentsWaitForTheNextSentence() {
        val (_, out) = feedAll("Yes. ", "That is correct and complete. ")
        assertEquals(emptyList<String>(), out[0])
        assertEquals(listOf("Yes. That is correct and complete."), out[1])
    }

    @Test
    fun newlinesEndALineEvenWithoutPunctuation() {
        val (_, out) = feedAll("- first item\n", "- second item\n")
        assertEquals(listOf(listOf("- first item"), listOf("- second item")), out)
    }

    @Test
    fun codeBlocksAreSaidOnceAndHeldWhileOpen() {
        val (_, out) = feedAll("Here is the fix for you. ", "```py\nprint(1)", "\n``` ", "And that is all of it. ")
        assertEquals(listOf("Here is the fix for you."), out[0])
        assertEquals(emptyList<String>(), out[1])
        assertEquals(emptyList<String>(), out[2])
        assertTrue(out[3].any { it.contains("Code omitted.") })
        assertEquals(listOf("open"), Speakable.split("open ```code never closed", final = true).first)
    }

    @Test
    fun resetForgetsEverything() {
        val (s, _) = feedAll("Something that will be dropped. ")
        s.reset()
        assertEquals(listOf("A fresh start with a full sentence."), s.feed("A fresh start with a full sentence. "))
    }

    @Test
    fun finishNeverRepeats() {
        val (s, _) = feedAll("One whole sentence right here. ", "tail")
        assertEquals(listOf("tail"), s.finish())
        assertEquals(emptyList<String>(), s.finish())
    }

    @Test
    fun cleanStripsMarkdownAndCapsTheLength() {
        assertEquals("Bold code a link Head See link now", Speakable.clean("**Bold** `code` [a link](http://x.y) ```\nprint(1)\n``` # Head\nSee https://example.com now"))
        assertEquals(Speakable.MAX_CHARS, Speakable.clean("word ".repeat(1000)).length)
    }
}
