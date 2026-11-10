package com.felix.faze

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ScreenEyesTest {
    private val jpeg = byteArrayOf(1, 2, 3, 4)

    @Test fun theQuestionAndTheImageGoToTheModelInTheChatFormat() {
        val chat = FakeChat(text("  A login form with two fields.  "))
        val eyes = ScreenEyes(chat, { "vision-x" }, { jpeg })
        assertEquals("A login form with two fields.", eyes.look("what is this?"))
        val call = chat.calls.single()
        assertEquals("vision-x", call.model)
        assertEquals(null, call.tools)
        val user = call.messages.getJSONObject(1).getJSONArray("content")
        assertEquals("what is this?", user.getJSONObject(0).getString("text"))
        val url = user.getJSONObject(1).getJSONObject("image_url").getString("url")
        assertEquals("data:image/jpeg;base64,AQIDBA==", url)
        assertEquals("system", call.messages.getJSONObject(0).getString("role"))
    }

    @Test fun anEmptyQuestionStillAsks() {
        val chat = FakeChat(text("ok"))
        ScreenEyes(chat, { "m" }, { jpeg }).look("   ")
        assertEquals("What is on this screen?", chat.calls.single().messages.getJSONObject(1).getJSONArray("content").getJSONObject(0).getString("text"))
    }

    @Test fun failuresComeBackInWords() {
        for ((chat, capture, part) in listOf(
            Triple(FakeChat(RemoteError("auth", "Groq has no key")), { jpeg }, "Groq has no key"),
            Triple(FakeChat(text("   ")), { jpeg }, "no answer"),
            Triple(FakeChat(), { ByteArray(0) }, "no screenshot"),
        )) {
            try { ScreenEyes(chat, { "m" }, capture).look("q"); fail("should have failed: $part") } catch (e: ToolFailure) { assertTrue(e.message, e.message!!.contains(part)) }
        }
    }

    @Test fun theToolPassesTheQuestionAndNeedsEyes() {
        val device = FakeDevice()
        val eyes = FakeEyes()
        val withEyes = Tools(device, FakeTransport(), FakeSettings(), eyes = eyes)
        assertTrue(withEyes.names().contains("look_at_screen"))
        val r = withEyes.execute("look_at_screen", JSONObject().put("question", "what animal?"))
        assertEquals(ToolResult(true, "A photo of a cat."), r)
        assertEquals(listOf("what animal?"), eyes.asked)
        val without = Tools(device, FakeTransport(), FakeSettings())
        assertFalse(without.names().contains("look_at_screen"))
        assertFalse(without.execute("look_at_screen", JSONObject()).ok)
    }

    @Test fun lookingAsksFirstBecauseTheScreenshotLeavesThePhone() {
        assertEquals(Tier.CONFIRM, PermissionPolicy.tier("look_at_screen"))
        assertTrue(PermissionPolicy.summary("look_at_screen", JSONObject().put("question", "who is this?")).contains("who is this?"))
    }
}
