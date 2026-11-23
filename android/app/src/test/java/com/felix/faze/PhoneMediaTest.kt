package com.felix.faze

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneMediaTest {
    private class Mic(var fail: String? = null) : MicSource {
        var started = 0; var stopped = 0
        override fun start() { fail?.let { throw IllegalStateException(it) }; started++ }
        override fun take() = byteArrayOf(1, 2, 3, 4)
        override fun stop() { stopped++ }
    }

    private class Cam : CamSource {
        var facing = ""
        override fun frame(facing: String): ByteArray { this.facing = facing; return byteArrayOf(9, 8, 7) }
        override fun stop() {}
    }

    private fun msg(t: String) = JSONObject().put("t", t)

    @Test fun offUnlessTheSwitchIsOn() {
        val m = PhoneMedia({ false }, Mic(), Cam())
        assertEquals("error", m.handle(msg("phone_audio_get"))!!.getString("t"))
        assertEquals("error", m.handle(msg("phone_cam_get"))!!.getString("t"))
    }

    @Test fun otherMessagesAreNotItsBusiness() {
        assertEquals(null, PhoneMedia({ true }, Mic(), Cam()).handle(msg("fs_roots")))
    }

    @Test fun soundAndPicturesComeBackEncoded() {
        val mic = Mic(); val cam = Cam()
        val m = PhoneMedia({ true }, mic, cam)
        val a = m.handle(msg("phone_audio_get"))!!
        assertEquals("phone_audio_ok", a.getString("t"))
        assertEquals(16000, a.getInt("rate"))
        assertEquals("AQIDBA==", a.getString("data"))
        val c = m.handle(msg("phone_cam_get").put("facing", "back"))!!
        assertEquals("phone_cam_ok", c.getString("t"))
        assertEquals("back", cam.facing)
        m.handle(msg("phone_audio_stop"))
        assertEquals(1, mic.stopped)
    }

    @Test fun aRefusalFromTheDeviceBecomesAPlainError() {
        val r = PhoneMedia({ true }, Mic("busy"), Cam()).handle(msg("phone_audio_get"))!!
        assertEquals("error", r.getString("t"))
        assertTrue(r.getString("message").contains("busy"))
    }
}
