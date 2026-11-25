package com.felix.faze

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PhoneRemoteTest {
    @Test fun sendsTheScreenMessagesAPhoneUnderstands() {
        val sent = ArrayList<JSONObject>()
        val r = PhoneRemote { m, _ -> sent.add(m); JSONObject().put("t", "screen_done").put("note", "ok") }
        r.tap(0.5f, 0.25f)
        r.swipe(0.1f, 0.9f, 0.1f, 0.2f)
        r.key("home")
        r.text("hello")
        assertEquals(listOf("screen_tap", "screen_swipe", "screen_key", "screen_text"), sent.map { it.getString("t") })
        assertEquals(0.25, sent[0].getDouble("y"), 0.001)
        assertEquals("home", sent[2].getString("key"))
    }

    @Test fun readsAPictureAndReportsRefusals() {
        val jpeg = byteArrayOf(1, 2, 3)
        val ok = PhoneRemote { _, _ -> JSONObject().put("t", "screen_ok").put("jpeg", java.util.Base64.getEncoder().encodeToString(jpeg)) }
        assertEquals(3, ok.screen().size)
        val no = PhoneRemote { _, _ -> JSONObject().put("t", "error").put("message", "Screen sharing is off on the phone.") }
        try { no.screen(); fail() } catch (e: java.io.IOException) { assertTrue(e.message!!.contains("off")) }
        try { no.tap(0.1f, 0.1f); fail() } catch (e: java.io.IOException) { assertTrue(e.message!!.contains("off")) }
    }

    @Test fun touchesOnTheBarsAreIgnored() {
        // a 100x200 picture in a 400x400 box: drawn 200 wide, bars 100 each side
        assertNull(FitMap.fraction(50f, 200f, 400f, 400f, 100, 200))
        val mid = FitMap.fraction(200f, 200f, 400f, 400f, 100, 200)!!
        assertEquals(0.5f, mid.first, 0.001f)
        assertEquals(0.5f, mid.second, 0.001f)
        val corner = FitMap.fraction(100f, 0f, 400f, 400f, 100, 200)!!
        assertEquals(0f, corner.first, 0.001f)
        assertNotNull(FitMap.fraction(300f, 400f, 400f, 400f, 100, 200))
        assertNull(FitMap.fraction(1f, 1f, 0f, 0f, 100, 200))
    }
}
