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

class PhoneAudioTest {
    private class Sink : AudioSink {
        val opened = ArrayList<Pair<Int, Int>>()
        val wrote = ArrayList<Int>()
        override fun open(rate: Int) { opened.add(rate to 1) }
        override fun open(rate: Int, channels: Int) { opened.add(rate to channels) }
        override fun write(pcm: ByteArray) { wrote.add(pcm.size) }
        override fun close() {}
    }

    @Test fun playsStereoSoundAndMonoMic() {
        val sink = Sink()
        val snd = PhoneAudio("sound", { JSONObject().put("t", "phone_sound_ok").put("rate", 44100).put("channels", 2).put("data", java.util.Base64.getEncoder().encodeToString(ByteArray(8))) }, sink, {})
        assertTrue(snd.step())
        assertEquals(listOf(44100 to 2), sink.opened)
        val mic = Sink()
        val m = PhoneAudio("audio", { JSONObject().put("t", "phone_audio_ok").put("rate", 16000).put("data", java.util.Base64.getEncoder().encodeToString(ByteArray(4))) }, mic, {})
        assertTrue(m.step())
        assertEquals(listOf(16000 to 1), mic.opened)
        assertEquals(listOf(4), mic.wrote)
    }

    @Test fun stopsAndReportsWhenThePhoneRefuses() {
        var said = ""
        val a = PhoneAudio("audio", { JSONObject().put("t", "error").put("message", "Camera and microphone sharing is off on the phone") }, Sink(), { said = it })
        assertEquals(false, a.step())
        assertTrue(said.contains("off"))
    }

    @Test fun cameraPictureComesBack() {
        val r = PhoneRemote { m, _ -> assertEquals("back", m.getString("facing")); JSONObject().put("t", "phone_cam_ok").put("jpeg", java.util.Base64.getEncoder().encodeToString(byteArrayOf(9, 9))) }
        assertEquals(2, r.camera("back").size)
    }
}
