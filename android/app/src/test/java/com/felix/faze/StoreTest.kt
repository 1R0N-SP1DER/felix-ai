package com.felix.faze

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StoreTest {
    private fun tmp(): File = File.createTempFile("faze", "dir").let { it.delete(); it.mkdirs(); it }
    private fun msgs(vararg p: Pair<String, String>) = p.mapIndexed { i, m -> ChatMessage(i + 1L, m.first, m.second) }

    @Test fun savesAndLoadsAChatWithItsSteps() {
        val s = ChatStore(tmp())
        val m = listOf(ChatMessage(1, "user", "battery?"), ChatMessage(2, "assistant", "92 percent", listOf("get_battery_status")))
        s.save("c1", m)
        assertEquals(m, s.load("c1")!!.messages)
        assertEquals("battery?", s.load("c1")!!.title)
    }

    @Test fun aChatWithNoUserMessageIsNotSaved() {
        val s = ChatStore(tmp())
        s.save("c1", emptyList())
        s.save("c2", listOf(ChatMessage(1, "assistant", "")))
        assertTrue(s.list().isEmpty())
    }

    @Test fun listsNewestFirstAndSearchesTitlesAndText() {
        var t = 100L
        val s = ChatStore(tmp()) { t }
        s.save("a", msgs("user" to "weather in Lagos", "assistant" to "sunny"))
        t = 200
        s.save("b", msgs("user" to "plan a project", "assistant" to "start with the goal"))
        assertEquals(listOf("b", "a"), s.list().map { it.id })
        assertEquals(listOf("a"), s.list("lagos").map { it.id })
        assertEquals(listOf("b"), s.list("GOAL").map { it.id }) // matches message text, any case
        assertTrue(s.list("nothing like this").isEmpty())
    }

    @Test fun savingAgainReplacesNotDuplicates() {
        val s = ChatStore(tmp())
        s.save("a", msgs("user" to "one"))
        s.save("a", msgs("user" to "one", "assistant" to "two"))
        assertEquals(1, s.list().size)
        assertEquals(2, s.load("a")!!.messages.size)
    }

    @Test fun deleteAndDamagedFiles() {
        val dir = tmp()
        val s = ChatStore(dir)
        s.save("a", msgs("user" to "x"))
        File(dir, "bad.json").writeText("{not json")
        assertEquals(listOf("a"), s.list().map { it.id }) // the damaged one is skipped
        assertNull(s.load("bad"))
        s.delete("a")
        assertNull(s.load("a"))
    }

    @Test fun anIdCannotEscapeTheFolder() {
        val dir = tmp()
        val s = ChatStore(dir)
        s.save("../../evil", msgs("user" to "x"))
        assertTrue(dir.listFiles()!!.all { it.parentFile.canonicalPath == dir.canonicalPath })
        assertFalse(File(dir.parentFile.parentFile, "evil.json").exists())
    }

    @Test fun titlesAreShort() {
        val s = ChatStore(tmp())
        assertEquals("New chat", s.titleFor(emptyList()))
        assertTrue(s.titleFor(msgs("user" to "word ".repeat(40))).length <= 41)
    }

    // ---- unzip

    private fun zip(vararg e: Pair<String, String>): ByteArray {
        val b = ByteArrayOutputStream()
        ZipOutputStream(b).use { z -> for ((n, c) in e) { z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry() } }
        return b.toByteArray()
    }

    @Test fun unzipsIntoTheTarget() {
        val dir = tmp()
        ZipSafe.extract(zip("model/am/final.mdl" to "a", "model/conf/model.conf" to "b").inputStream(), dir)
        assertEquals("a", File(dir, "model/am/final.mdl").readText())
        assertEquals(File(dir, "model").canonicalPath, ZipSafe.modelRoot(dir)!!.canonicalPath)
    }

    @Test fun refusesAnEntryThatEscapesTheFolder() {
        val dir = tmp()
        try {
            ZipSafe.extract(zip("../escaped.txt" to "x").inputStream(), dir)
            fail("should refuse")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("unsafe"))
        }
        assertFalse(File(dir.parentFile, "escaped.txt").exists())
    }

    @Test fun modelRootIsNullWithoutTheModelParts() {
        val dir = tmp()
        File(dir, "x").mkdirs()
        assertNull(ZipSafe.modelRoot(dir))
    }

    @Test fun modelChoicesAndTimingText() {
        assertEquals("gpt-oss-120b", ModelChoices.label("openai/gpt-oss-120b"))
        assertEquals("other", ModelChoices.label("x/other"))
        assertEquals("stt 0.6s · llm 1.0s", TimingText.format(600, 1000))
        assertEquals("llm 1.2s", TimingText.format(0, 1234))
        assertEquals("", TimingText.format(0, 0))
        assertNotNull(ModelChoices.all.first())
    }
}
