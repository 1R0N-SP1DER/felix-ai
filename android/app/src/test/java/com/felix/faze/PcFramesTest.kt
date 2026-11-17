package com.felix.faze

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PcFramesTest {
    private fun frame(vararg b: Byte) = JSONObject().put("t", "pc_frame").put("jpeg", java.util.Base64.getEncoder().encodeToString(b))

    @Test fun keepsOnlyTheNewestPicture() {
        val f = PcFrames()
        assertNull(f.latest())
        f.handle(frame(1)); f.handle(frame(2)); f.handle(frame(3))
        assertEquals(3L, f.latest()!!.first)
        assertEquals(listOf<Byte>(3), f.latest()!!.second.toList())
    }

    @Test fun neverAnswersAndIgnoresOtherMessages() {
        val f = PcFrames()
        assertNull(f.handle(frame(1)))
        assertNull(f.handle(JSONObject().put("t", "sync_list")))
        assertEquals(1L, f.seq)
    }

    @Test fun damagedOrEmptyPicturesAreSkipped() {
        val f = PcFrames()
        f.handle(JSONObject().put("t", "pc_frame").put("jpeg", "!!!"))
        f.handle(JSONObject().put("t", "pc_frame").put("jpeg", ""))
        assertNull(f.latest())
    }

    @Test fun anErrorIsKeptUntilTheNextPicture() {
        val f = PcFrames()
        f.handle(JSONObject().put("t", "pc_stream_error").put("message", "Cannot open display"))
        assertEquals("Cannot open display", f.error)
        f.handle(frame(1))
        assertEquals("", f.error)
    }
}
