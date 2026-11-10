package com.felix.faze

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class ShareTest {
    @Test fun aPictureIsSentAsRealtimeVideoJpeg() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val v = JSONObject(LiveProtocol.video(bytes)).getJSONObject("realtimeInput").getJSONObject("video")
        assertEquals("image/jpeg", v.getString("mimeType"))
        assertEquals(bytes.toList(), Base64.getDecoder().decode(v.getString("data")).toList())
    }

    @Test fun framesAreLimitedToOneAtATime() {
        var t = 10_000L
        val g = FrameGate(1000) { t }
        assertTrue(g.tryBegin())
        t += 5000
        assertFalse("the last one is still being sent", g.tryBegin())
        g.done()
        assertTrue(g.tryBegin())
    }

    @Test fun framesAreLimitedToOnePerSecond() {
        var t = 10_000L
        val g = FrameGate(1000) { t }
        assertTrue(g.tryBegin()); g.done()
        t += 400
        assertFalse(g.tryBegin())
        t += 700
        assertTrue(g.tryBegin())
    }

    @Test fun theModelIsTold() {
        assertTrue(LiveSession.instruction("Felix", "", listOf("battery")).contains("camera or screen"))
    }
}
