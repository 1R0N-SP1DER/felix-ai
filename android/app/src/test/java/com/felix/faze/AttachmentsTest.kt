package com.felix.faze

import java.io.File
import java.nio.file.Files
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AttachmentsTest {
    private fun dir(): File = Files.createTempDirectory("attach").toFile()

    private val png = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4, 5))

    private fun maker(t: FakeTransport, shelf: ImageShelf, key: String = "rk", d: File = dir()) =
        GeminiImages(t, { key }, { "" }, d, shelf, { 42L })

    @Test fun aPictureIsRequestedAndSavedAndShelved() {
        val t = FakeTransport()
        t.onPost = { _, _ -> HttpResult(200, emptyMap(), """{"candidates":[{"content":{"parts":[{"text":"ok"},{"inlineData":{"mimeType":"image/png","data":"$png"}}]}}]}""") }
        val shelf = ImageShelf()
        val d = dir()
        val told = maker(t, shelf, d = d).make("a red fox", "landscape")
        assertTrue(told.contains("shown in the chat"))
        val call = t.calls.single()
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-image:generateContent", call.url)
        assertEquals("rk", call.headers["x-goog-api-key"])
        val body = org.json.JSONObject(call.body)
        assertEquals("a red fox", body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text"))
        assertEquals("3:2", body.getJSONObject("generationConfig").getJSONObject("imageConfig").getString("aspectRatio"))
        val got = shelf.take().single()
        assertTrue(got.isImage)
        assertEquals(5, File(got.path).length())
        assertTrue(shelf.take().isEmpty())
    }

    @Test fun noKeyOrAnErrorBecomesAReadableFailure() {
        val shelf = ImageShelf()
        try { maker(FakeTransport(), shelf, key = "").make("x", "square"); fail() } catch (e: ToolFailure) { assertTrue(e.message!!.contains("Settings")) }
        val t = FakeTransport()
        t.onPost = { _, _ -> HttpResult(429, emptyMap(), """{"error":{"message":"out of credits"}}""") }
        try { maker(t, shelf).make("x", "square"); fail() } catch (e: ToolFailure) { assertTrue(e.message!!.contains("out of credits")) }
        t.onPost = { _, _ -> HttpResult(401, emptyMap(), "{}") }
        try { maker(t, shelf).make("x", "square"); fail() } catch (e: ToolFailure) { assertTrue(e.message!!.contains("401")) }
        assertTrue(shelf.take().isEmpty())
    }

    @Test fun attachmentsSurviveSavingAndTheAiSeesTheirContext() {
        val list = listOf(Attachment("photo.jpg", "/x/photo.jpg", Attachment.IMAGE, "a cat on a sofa"), Attachment("notes.txt", "/x/notes.txt", Attachment.FILE, "buy milk"))
        assertEquals(list, Attachment.fromJson(Attachment.toJson(list)))
        val seen = Attachment.forModel("what is this?", list)
        assertTrue(seen.startsWith("what is this?"))
        assertTrue(seen.contains("a cat on a sofa") && seen.contains("buy milk") && seen.contains("notes.txt"))
        assertEquals("hi", Attachment.forModel("hi", emptyList()))
    }

    @Test fun aSavedChatKeepsItsAttachments() {
        val store = ChatStore(dir())
        val a = Attachment("g.jpg", "/x/g.jpg", Attachment.IMAGE)
        store.save("c1", listOf(ChatMessage(1, "user", "draw a fox"), ChatMessage(2, "assistant", "", attachments = listOf(a))))
        val back = store.load("c1")!!.messages
        assertEquals(listOf(a), back[1].attachments)
    }
}
