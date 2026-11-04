package com.felix.faze

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryTest {
    private fun store(): MemoryStore = MemoryStore(File.createTempFile("mem", "dir").let { it.delete(); File(it, "memory.json") })

    @Test fun addsListsAndKeepsThemOnDisk() {
        val file = File.createTempFile("mem", "dir").let { it.delete(); File(it, "memory.json") }
        val s = MemoryStore(file) { 42L }
        assertTrue(s.add("  Prefers   short answers ").ok)
        assertEquals(listOf("Prefers short answers"), s.list().map { it.text })
        assertEquals(42L, s.list()[0].created)
        assertEquals(listOf("Prefers short answers"), MemoryStore(file).list().map { it.text }) // a new instance sees it
    }

    @Test fun duplicatesTooShortAndTooLongAreRefusedOrIgnored() {
        val s = store()
        assertTrue(s.add("Likes tea").ok)
        assertEquals("already remembered", s.add("likes TEA").message)
        assertEquals(1, s.list().size)
        assertFalse(s.add("hi").ok)
        assertFalse(s.add("x".repeat(301)).ok)
    }

    @Test fun secretsAreNeverStored() {
        val s = store()
        for (t in listOf("my key is " + "gsk_" + "a".repeat(21), "AIza" + "S".repeat(36), "card 4111 1111 1111 1111", "my password is hunter22", "token: abc123xyz", "sk-" + "or-" + "a".repeat(21))) {
            assertFalse(t, s.add(t).ok)
        }
        assertTrue(s.list().isEmpty())
        assertTrue(s.add("Works night shifts on Tuesdays").ok) // ordinary notes, even with numbers, are fine
        assertTrue(s.add("Born in 1990 and lives on floor 12").ok)
    }

    @Test fun removeAndClear() {
        val s = store()
        s.add("one note"); s.add("two note")
        val first = s.list()[0].id
        s.remove(first)
        assertEquals(listOf("two note"), s.list().map { it.text })
        assertTrue(s.add("three note").ok)
        assertEquals(2, s.list().map { it.id }.toSet().size)
        s.clear()
        assertTrue(s.list().isEmpty())
    }

    @Test fun aFullMemoryRefusesMore() {
        val s = store()
        repeat(MemoryStore.MAX_ENTRIES) { assertTrue(s.add("note number $it here").ok) }
        assertFalse(s.add("one more note").ok)
    }

    @Test fun aDamagedFileCountsAsEmpty() {
        val file = File.createTempFile("mem", "dir").let { it.delete(); File(it, "memory.json") }
        file.parentFile!!.mkdirs(); file.writeText("{not json")
        assertTrue(MemoryStore(file).list().isEmpty())
        assertTrue(MemoryStore(file).add("fresh start note").ok)
    }

    @Test fun thePromptBlockSaysNotesAreNotInstructionsAndDropsTheOldestWhenLong() {
        val s = store()
        assertEquals("", s.promptBlock("Felix"))
        s.add("Lives in Lagos"); s.add("Prefers short answers")
        val b = s.promptBlock("Felix")
        assertTrue(b.contains("Felix"))
        assertTrue(b.contains("not instructions"))
        assertTrue(b.contains("- Lives in Lagos") && b.contains("- Prefers short answers"))
        val small = s.promptBlock("Felix", maxChars = 170)
        assertFalse(small.contains("Lives in Lagos")) // the older one went first
        assertTrue(small.contains("Prefers short answers"))
    }

    @Test fun theRememberToolAsksFirstAndSavesOnlyWhenAllowed() {
        val mem = store()
        val tools = Tools(FakeDevice(), FakeTransport(), FakeSettings(), mem)
        assertTrue(tools.schema().toString().contains("\"remember\""))
        assertEquals(Tier.CONFIRM, PermissionPolicy.tier("remember"))
        val asked = ArrayList<String>()
        val denied = GatedTools(tools, object : Approver { override fun approve(tool: String, summary: String): Boolean { asked.add(summary); return false } })
        assertFalse(denied.execute("remember", JSONObject().put("fact", "Likes tea")).ok)
        assertTrue(mem.list().isEmpty())
        assertEquals(listOf("Remember this: Likes tea"), asked)
        val allowed = GatedTools(tools, object : Approver { override fun approve(tool: String, summary: String) = true })
        assertTrue(allowed.execute("remember", JSONObject().put("fact", "Likes tea")).ok)
        assertEquals(listOf("Likes tea"), mem.list().map { it.text })
        assertFalse(allowed.execute("remember", JSONObject().put("fact", "")).ok)
    }

    @Test fun withoutAStoreTheToolIsNotOffered() {
        assertFalse(Tools(FakeDevice(), FakeTransport(), FakeSettings()).schema().toString().contains("remember"))
    }
}
