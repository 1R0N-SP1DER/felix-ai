package com.felix.faze

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class PcFramesTest {
    private fun unit(n: Int, key: Boolean) = JSONObject().put("t", "pc_frame").put("codec", "h264").put("key", key).put("n", n).put("data", Base64.getEncoder().encodeToString(byteArrayOf(n.toByte(), 1, 2)))

    @Test fun jpegKeepsOnlyTheNewest() {
        val f = PcFrames()
        f.handle(JSONObject().put("t", "pc_frame").put("jpeg", Base64.getEncoder().encodeToString(byteArrayOf(1))))
        f.handle(JSONObject().put("t", "pc_frame").put("jpeg", Base64.getEncoder().encodeToString(byteArrayOf(2))))
        assertEquals(2, f.latest()!!.second[0].toInt())
        assertFalse(f.h264)
    }

    @Test fun h264WaitsForAKeyframeThenQueuesInOrder() {
        val f = PcFrames()
        f.handle(unit(1, false))
        assertNull(f.nextUnit())
        f.handle(unit(2, true)); f.handle(unit(3, false)); f.handle(unit(4, false))
        assertTrue(f.h264)
        assertEquals(listOf(2, 3, 4), generateSequence { f.nextUnit() }.map { it.data[0].toInt() }.toList())
    }

    @Test fun aBigBacklogIsDroppedUntilTheNextKeyframe() {
        val f = PcFrames()
        f.handle(unit(0, true))
        for (i in 1..PcFrames.MAX_BACKLOG + 2) f.handle(unit(i, false))
        assertNull(f.nextUnit())
        f.handle(unit(99, false))
        assertNull(f.nextUnit())
        f.handle(unit(100, true))
        assertEquals(100, f.nextUnit()!!.data[0].toInt())
        assertTrue(f.dropped > 0)
    }

    @Test fun resetStartsOverAndWantsAKeyframe() {
        val f = PcFrames()
        f.handle(unit(1, true))
        f.reset()
        assertNull(f.nextUnit())
        f.handle(unit(2, false))
        assertNull(f.nextUnit())
        f.handle(unit(3, true))
        assertNotNull(f.nextUnit())
    }
}
