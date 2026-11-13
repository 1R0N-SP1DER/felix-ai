package com.felix.faze

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WebImagesTest {
    private class ByteTransport(val pictures: Map<String, ByteArray>) : Transport {
        val search = FakeTransport()
        val fetched = ArrayList<String>()
        override fun get(url: String, headers: Map<String, String>, timeoutMs: Int) = search.get(url, headers, timeoutMs)
        override fun post(url: String, headers: Map<String, String>, body: ByteArray, contentType: String, timeoutMs: Int) = search.post(url, headers, body, contentType, timeoutMs)
        override fun postStream(url: String, headers: Map<String, String>, json: String, timeoutMs: Int, onLine: (String) -> Boolean) = search.postStream(url, headers, json, timeoutMs, onLine)
        override fun getBytes(url: String, headers: Map<String, String>, timeoutMs: Int, maxBytes: Int): ByteArray? { fetched.add(url); return pictures[url] }
    }

    private fun dir(): File = Files.createTempDirectory("web").toFile()

    @Test fun picturesAreFoundDownloadedAndShelved() {
        val t = ByteTransport(mapOf("https://a.example/1.jpg" to byteArrayOf(1, 2), "https://b.example/2.jpg" to byteArrayOf(3)))
        t.search.onPost = { _, _ -> HttpResult(200, emptyMap(), """{"images":[{"url":"https://a.example/1.jpg","description":"a red fox"},{"url":"https://b.example/2.jpg"}]}""") }
        val shelf = ImageShelf()
        val told = WebImages(t, { "tk" }, dir(), shelf, { it }).find("red fox", 3)
        assertTrue(told.contains("Showed 2 pictures"))
        val got = shelf.take()
        assertEquals(2, got.size)
        assertEquals("a red fox", got[0].name)
        assertTrue(got.all { it.isImage && File(it.path).exists() })
        assertTrue(org.json.JSONObject(t.search.calls.single().body).getBoolean("include_images"))
    }

    @Test fun unsafeAddressesNonPicturesAndMissingKeyAreSkippedOrExplained() {
        assertFalse(ImageUrl.allowed("http://a.example/x.jpg"))
        assertFalse(ImageUrl.allowed("https://localhost/x.jpg"))
        assertFalse(ImageUrl.allowed("https://192.168.1.5/x.jpg"))
        assertFalse(ImageUrl.allowed("https://user:pw@a.example/x.jpg"))
        assertTrue(ImageUrl.allowed("https://upload.wikimedia.org/x.jpg"))
        val t = ByteTransport(mapOf("https://ok.example/1.jpg" to byteArrayOf(7)))
        t.search.onPost = { _, _ -> HttpResult(200, emptyMap(), """{"images":["http://plain.example/p.jpg","https://192.168.0.2/a.jpg","https://bad.example/b.jpg","https://ok.example/1.jpg"]}""") }
        val shelf = ImageShelf()
        WebImages(t, { "tk" }, dir(), shelf, { if (it.size == 1 && it[0] == 7.toByte()) it else null }).find("x", 4)
        assertEquals(1, shelf.take().size)
        assertTrue(t.fetched.none { it.startsWith("http://") || it.contains("192.168") })
        try { WebImages(t, { "" }, dir(), shelf, { it }).find("x", 1); fail() } catch (e: ToolFailure) { assertTrue(e.message!!.contains("Tavily")) }
        t.search.onPost = { _, _ -> HttpResult(200, emptyMap(), """{"images":[]}""") }
        try { WebImages(t, { "tk" }, dir(), shelf, { it }).find("x", 1); fail() } catch (e: ToolFailure) { assertTrue(e.message!!.contains("no pictures")) }
    }
}
