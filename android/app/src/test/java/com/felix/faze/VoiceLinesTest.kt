package com.felix.faze

import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceLinesTest {
    private val lines = listOf("one", "two", "three")
    private fun dir(): File = Files.createTempDirectory("lines").toFile()

    @Test fun thereAreEnoughLinesAndNoRepeats() {
        assertTrue(GoogleTakeover.LINES.size in 30..40)
        assertEquals(GoogleTakeover.LINES.size, GoogleTakeover.LINES.toSet().size)
    }

    @Test fun downloadSavesEveryLineAndSkipsWhatIsAlreadySaved() {
        val asked = ArrayList<String>()
        val v = VoiceLines(dir(), { asked.add(it); byteArrayOf(1, 2, 3) }, lines, { "Puck-m" }, { })
        val (n, why) = v.download()
        assertEquals(3, n); assertNull(why)
        assertEquals(listOf(0, 1, 2), v.saved())
        assertEquals(listOf<Byte>(1, 2, 3), v.pcm(1)!!.toList())
        v.download()
        assertEquals(3, asked.size) // nothing was asked again
    }

    @Test fun aFailureStopsAfterOneRetryAndKeepsWhatWasSaved() {
        var calls = 0
        val v = VoiceLines(dir(), { calls++; if (it == "two") throw ToolFailure("Gemini voice answered 429") else byteArrayOf(9) }, lines, { "Puck-m" }, { })
        val (n, why) = v.download()
        assertEquals(1, n)
        assertTrue(why!!.contains("429"))
        assertEquals(3, calls) // one, then two twice
        assertEquals(listOf(0), v.saved())
    }

    @Test fun anotherVoiceMeansTheOldSoundIsNotUsedAndIsCleared() {
        val d = dir()
        var tag = "Puck-m"
        val v = VoiceLines(d, { byteArrayOf(7) }, lines, { tag }, { })
        v.download()
        tag = "Kore-m"
        assertTrue(v.saved().isEmpty())
        assertNull(v.pcm(0))
        v.download()
        assertEquals(3, d.listFiles()!!.size) // the Puck files are gone
    }

    @Test fun noKeyIsExplained() {
        val (n, why) = VoiceLines(dir(), null, lines, { "x" }, { }).download()
        assertEquals(0, n); assertTrue(why!!.contains("Gemini key"))
    }

    @Test fun pickingPrefersSavedLinesAndAvoidsTheLastOne() {
        val r = Random(1)
        repeat(30) { assertTrue(GoogleTakeover.pickFrom(listOf(4, 9), 4, r) == 9) }
        assertNotNull(GoogleTakeover.pickFrom(emptyList(), -1, r))
        assertFalse(GoogleTakeover.pickFrom(emptyList(), 3, Random(2)) == 3)
        assertEquals(5, GoogleTakeover.pickFrom(listOf(5), 5, r))
    }
}
