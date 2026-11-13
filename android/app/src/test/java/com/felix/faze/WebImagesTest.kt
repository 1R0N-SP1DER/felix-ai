package com.felix.faze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WebImagesTest {
    @Test fun picturesAreFoundAndShownFromTheirAddressWithoutDownloading() {
        val t = FakeTransport()
        t.onPost = { _, _ -> HttpResult(200, emptyMap(), """{"images":[{"url":"https://a.example/1.jpg","description":"a red fox"},{"url":"https://b.example/2.jpg"}]}""") }
        val shelf = ImageShelf()
        val told = WebImages(t, { "tk" }, shelf).find("red fox", 3)
        assertTrue(told.contains("Showed 2 pictures"))
        val got = shelf.take()
        assertEquals(2, got.size)
        assertEquals("a red fox", got[0].name)
        assertEquals("https://a.example/1.jpg", got[0].url)
        assertTrue(got.all { it.isImage && it.isRemote && it.path.isEmpty() })
        assertTrue(org.json.JSONObject(t.calls.single().body).getBoolean("include_images"))
        assertEquals(1, t.calls.size) // only the search itself went out
    }

    @Test fun unsafeAddressesAndDuplicatesAreSkippedAndProblemsExplained() {
        assertFalse(ImageUrl.allowed("http://a.example/x.jpg"))
        assertFalse(ImageUrl.allowed("https://localhost/x.jpg"))
        assertFalse(ImageUrl.allowed("https://192.168.1.5/x.jpg"))
        assertFalse(ImageUrl.allowed("https://user:pw@a.example/x.jpg"))
        assertTrue(ImageUrl.allowed("https://upload.wikimedia.org/x.jpg"))
        val t = FakeTransport()
        t.onPost = { _, _ -> HttpResult(200, emptyMap(), """{"images":["http://plain.example/p.jpg","https://192.168.0.2/a.jpg","https://ok.example/1.jpg","https://ok.example/1.jpg"]}""") }
        val shelf = ImageShelf()
        WebImages(t, { "tk" }, shelf).find("x", 4)
        assertEquals(listOf("https://ok.example/1.jpg"), shelf.take().map { it.url })
        try { WebImages(t, { "" }, shelf).find("x", 1); fail() } catch (e: ToolFailure) { assertTrue(e.message!!.contains("Tavily")) }
        t.onPost = { _, _ -> HttpResult(200, emptyMap(), """{"images":[]}""") }
        try { WebImages(t, { "tk" }, shelf).find("x", 1); fail() } catch (e: ToolFailure) { assertTrue(e.message!!.contains("no pictures")) }
    }
}
