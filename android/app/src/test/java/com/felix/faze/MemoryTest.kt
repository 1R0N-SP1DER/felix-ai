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

    @Test fun categoryNamesAreCleanedAndChecked() {
        val s = store()
        assertEquals("me", s.cleanCategory(""))
        assertEquals("me", s.cleanCategory(null))
        assertEquals("work/faze", s.cleanCategory("  Work / Faze "))
        assertEquals("people/sister/birthdays", s.cleanCategory("people/sister/birthdays"))
        assertEquals(null, s.cleanCategory("a/b/c/d")) // too deep
        assertEquals(null, s.cleanCategory("../etc"))
        assertEquals(null, s.cleanCategory("x".repeat(60)))
        assertEquals(null, s.cleanCategory("no;semicolons"))
    }

    @Test fun categoriesCountAndRecallIncludesSubCategories() {
        val s = store()
        assertTrue(s.add("Likes tea").ok)
        assertTrue(s.add("Builds the Faze app", "work/faze").ok)
        assertTrue(s.add("Uses a Redmi 10C", "work/faze").ok)
        assertTrue(s.add("Day job is in logistics", "work").ok)
        assertEquals(listOf("me" to 1, "work" to 1, "work/faze" to 2), s.categories())
        assertEquals("remembered, in a new category: people/sister", s.add("Her birthday is in May", "people/sister").message)
        assertEquals("remembered in work", s.add("Works from home on Fridays", "work").message)
        val work = s.recall("work")
        assertTrue(work.contains("Day job is in logistics") && work.contains("Builds the Faze app") && work.contains("work/faze:"))
        assertFalse(work.contains("Likes tea"))
        assertTrue(s.recall("work/faze").contains("Uses a Redmi 10C") && !s.recall("work/faze").contains("logistics"))
        assertTrue(s.recall("index").startsWith("categories: me (1), people/sister (1), work (2), work/faze (2)"))
        assertTrue(s.recall("health").startsWith("no notes in 'health'. categories:"))
        assertEquals("nothing is remembered yet", store().recall("index"))
        assertFalse(s.add("a note", "bad;name").ok)
    }

    @Test fun theSameNoteCanLiveInTwoCategoriesButNotTwiceInOne() {
        val s = store()
        assertTrue(s.add("Likes tea", "me").ok)
        assertEquals("already remembered", s.add("likes tea", "me").message)
        assertEquals("remembered, in a new category: food", s.add("Likes tea", "food").message)
        assertEquals(2, s.list().size)
    }

    @Test fun oldNotesWithoutACategoryBecomeMe() {
        val file = File.createTempFile("mem", "dir").let { it.delete(); File(it, "memory.json") }
        file.parentFile!!.mkdirs(); file.writeText("""[{"id":1,"text":"Likes tea","created":5}]""")
        assertEquals("me", MemoryStore(file).list()[0].category)
    }

    @Test fun recallIsCutShortWhenTooLong() {
        val s = store()
        repeat(20) { s.add("a fairly long note number $it about the app", "work") }
        val r = s.recall("work", maxChars = 200)
        assertTrue(r.length < 300 && r.contains("were cut"))
    }

    @Test fun thePromptShowsOnlyTheIndexAndTheMeNotes() {
        val s = store()
        assertEquals("", s.promptBlock("Felix"))
        s.add("Lives in Lagos"); s.add("Prefers short answers"); s.add("Secret project notes live here", "work/faze")
        val b = s.promptBlock("Felix")
        assertTrue(b.contains("Felix") && b.contains("not instructions"))
        assertTrue(b.contains("Categories: me (2), work/faze (1)"))
        assertTrue(b.contains("- Lives in Lagos") && b.contains("- Prefers short answers"))
        assertFalse(b.contains("Secret project notes")) // other categories are not loaded until asked for
        assertTrue(b.contains("recall(category)"))
    }

    @Test fun whenTheMeNotesAreTooLongTheOldestGoFirst() {
        val s = store()
        repeat(10) { s.add("me note number $it with some padding text to take room") }
        val b = s.promptBlock("Felix", maxChars = 600)
        assertFalse(b.contains("me note number 0 "))
        assertTrue(b.contains("me note number 9 "))
    }

    @Test fun recallIsAReadOnlySafeToolAndRememberTakesACategory() {
        val mem = store()
        val tools = Tools(FakeDevice(), FakeTransport(), FakeSettings(), mem)
        assertEquals(Tier.SAFE, PermissionPolicy.tier("recall"))
        val asked = ArrayList<String>()
        val gated = GatedTools(tools, object : Approver { override fun approve(tool: String, summary: String): Boolean { asked.add(summary); return true } })
        assertTrue(gated.execute("remember", JSONObject().put("fact", "Builds the Faze app").put("category", "work/faze")).ok)
        assertEquals(listOf("Remember this [work/faze]: Builds the Faze app"), asked)
        val r = gated.execute("recall", JSONObject().put("category", "work"))
        assertTrue(r.ok && r.text.contains("Builds the Faze app"))
        assertEquals(1, asked.size) // reading did not ask
        assertFalse(gated.execute("remember", JSONObject().put("fact", "Some note here").put("category", "bad;cat")).ok)
        assertTrue(tools.schema().toString().contains("\"recall\""))
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
        assertEquals(listOf("Remember this: Likes tea"), asked) // no category given: the summary stays short
        val allowed = GatedTools(tools, object : Approver { override fun approve(tool: String, summary: String) = true })
        assertTrue(allowed.execute("remember", JSONObject().put("fact", "Likes tea")).ok)
        assertEquals(listOf("Likes tea"), mem.list().map { it.text })
        assertFalse(allowed.execute("remember", JSONObject().put("fact", "")).ok)
    }

    @Test fun withoutAStoreTheToolIsNotOffered() {
        assertFalse(Tools(FakeDevice(), FakeTransport(), FakeSettings()).schema().toString().contains("remember"))
    }
}
