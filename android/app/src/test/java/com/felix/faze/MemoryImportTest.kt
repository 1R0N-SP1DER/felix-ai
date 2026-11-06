package com.felix.faze

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryImportTest {
    private fun store() = MemoryStore(File.createTempFile("mem", "dir").let { it.delete(); File(it, "memory.json") })
    private fun file(vararg n: Pair<String, String>) = """{"format":"faze-memory-v1","notes":[""" + n.joinToString(",") { """{"text":${org.json.JSONObject.quote(it.first)},"category":${org.json.JSONObject.quote(it.second)}}""" } + "]}"

    @Test fun importsNotesWithTheirCategories() {
        val s = store()
        val r = s.importJson(file("Prefers short answers" to "me", "Builds the Faze app" to "Work/Faze", "Sister's birthday is in May" to "people/sister"))
        assertEquals(3, r.added)
        assertEquals(listOf("me" to 1, "people/sister" to 1, "work/faze" to 1), s.categories())
        assertEquals("Imported 3 notes", r.summary())
    }

    @Test fun duplicatesSecretsAndBadCategoriesAreHandled() {
        val s = store()
        s.add("Likes tea")
        val key = "my key is " + "gsk_" + "a".repeat(21)
        val r = s.importJson(file("likes TEA" to "me", key to "me", "A note in a weird place" to "bad;cat", "ok" to "me"))
        assertEquals(1, r.added); assertEquals(1, r.duplicates); assertEquals(1, r.secrets); assertEquals(1, r.invalid)
        assertTrue(s.categories().any { it.first == "imported" })
        assertEquals("Imported 1 note (1 already there, 1 left out because they looked like secrets, 1 unreadable)".replace("1 left out because they looked", "1 left out because they looked"), r.summary())
    }

    @Test fun aTooLongNoteIsShortenedNotLost() {
        val s = store()
        val long = "This is a long fact about the project. " + "word ".repeat(80)
        val r = s.importJson(file(long to "me"))
        assertEquals(1, r.added); assertEquals(1, r.shortened)
        val t = s.list()[0].text
        assertTrue(t.length <= MemoryStore.MAX_LENGTH && t.endsWith("…") && t.startsWith("This is a long fact"))
        assertTrue(r.summary().contains("shortened"))
    }

    @Test fun importingTwiceAddsNothingTheSecondTime() {
        val s = store()
        val f = file("One fact" to "me", "Another fact" to "work")
        assertEquals(2, s.importJson(f).added)
        val again = s.importJson(f)
        assertEquals(0, again.added); assertEquals(2, again.duplicates)
        assertEquals(2, s.list().size)
    }

    @Test fun theLimitStopsAnImportAndSaysSo() {
        val s = store()
        val many = (1..MemoryStore.MAX_ENTRIES + 5).map { "fact number $it of many" to "me" }.toTypedArray()
        val r = s.importJson(file(*many))
        assertEquals(MemoryStore.MAX_ENTRIES, r.added); assertEquals(5, r.full)
    }

    @Test fun brokenFilesChangeNothing() {
        val s = store()
        s.add("Keep me")
        assertEquals(1, s.importJson("not json").invalid)
        assertEquals(1, s.importJson("""{"nothing":1}""").invalid)
        assertEquals(listOf("Keep me"), s.list().map { it.text })
    }

    @Test fun readLimitedStopsAtTheLimit() {
        assertEquals(10, readLimited(ByteArray(10).inputStream(), 100)!!.size)
        assertNull(readLimited(ByteArray(200).inputStream(), 100))
    }
}
