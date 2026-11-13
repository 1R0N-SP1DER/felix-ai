package com.felix.faze

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcesTest {
    private val search = "Web search results for 'x':\nSummary: s\n1. Fox facts: they are red\n   https://a.example/fox\n2. Another page\n   https://b.example/p"

    @Test fun aWebSearchGivesItsPagesAsSources() {
        val s = SourceTracker.from("search_web", JSONObject(), ToolResult(true, search))
        assertEquals(listOf(Source("web", "Fox facts", "https://a.example/fox"), Source("web", "Another page", "https://b.example/p")), s)
    }

    @Test fun deviceReadsAndMemoryAreSourcesButActionsAndFailuresAreNot() {
        assertEquals(listOf(Source("device", "Battery status")), SourceTracker.from("get_battery_status", JSONObject(), ToolResult(true, "80%")))
        assertEquals(listOf(Source("memory", "Saved notes: food")), SourceTracker.from("recall", JSONObject().put("category", "food"), ToolResult(true, "likes rice")))
        assertTrue(SourceTracker.from("set_volume", JSONObject(), ToolResult(true, "done")).isEmpty())
        assertTrue(SourceTracker.from("search_web", JSONObject(), ToolResult(false, search)).isEmpty())
    }

    @Test fun sourcesSurviveSaving() {
        val l = listOf(Source("web", "A", "https://a"), Source("device", "Phone clock"))
        assertEquals(l, Source.fromJson(Source.toJson(l)))
    }

    @Test fun aSearchAtMediumEffortAlsoBringsPictures() {
        val t = FakeTransport()
        t.onPost = { _, _ -> HttpResult(200, emptyMap(), """{"results":[{"title":"T","url":"https://a.example","content":"c"}],"answer":"ans"}""") }
        val shelf = ImageShelf()
        val finder = object : ImageFinder { override fun find(query: String, count: Int): String { shelf.add(Attachment("p", "", Attachment.IMAGE, "", "https://i.example/1.jpg")); return "Showed 1 picture" } }
        val tools = Tools(FakeDevice(), t, FakeSettings(), finder = finder)
        try {
            Effort.active = "low"
            assertTrue(!tools.execute("search_web", JSONObject().put("query", "fox")).text.contains("Showed"))
            assertTrue(shelf.take().isEmpty())
            Effort.active = "medium"
            assertTrue(tools.execute("search_web", JSONObject().put("query", "fox")).text.contains("Showed 1 picture"))
            assertEquals(1, shelf.take().size)
        } finally { Effort.active = "low" }
    }
}
