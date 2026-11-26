package com.felix.faze

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneVideoTest {
    private fun nal(type: Int, vararg body: Int): ByteArray = byteArrayOf(0, 0, 0, 1, type.toByte()) + body.map { it.toByte() }.toByteArray()
    private val sps = nal(0x67, 1, 2)
    private val pps = nal(0x68, 3)
    private val idr = nal(0x65, 0x88, 9, 9)
    private val p1 = nal(0x41, 0x9A, 7, 7)
    private val p2 = nal(0x41, 0x9A, 8, 8)

    @Test fun cutsPicturesAndFindsKeyframes() {
        val s = H264Splitter()
        val out = s.feed(sps + pps + idr + p1 + p2)
        // the newest pictures are held until the next one begins or the stream goes quiet
        assertEquals(1, out.size)
        assertTrue(out[0].key)
        assertEquals((sps + pps + idr).toList(), out[0].data.toList())
        val rest = s.flush()
        assertEquals(2, rest.size)
        assertFalse(rest[0].key)
        assertEquals(p1.toList(), rest[0].data.toList())
        assertEquals(p2.toList(), rest[1].data.toList())
    }

    @Test fun piecesCanArriveInAnySize() {
        val s = H264Splitter()
        val all = sps + pps + idr + p1
        val units = ArrayList<H264Splitter.AccessUnit>()
        for (b in all) units += s.feed(byteArrayOf(b))
        units += s.flush()
        assertEquals(2, units.size)
        assertTrue(units[0].key)
        assertEquals(p1.toList(), units[1].data.toList())
    }

    @Test fun queueReadsWhatWasPutAndEndsWhenFinished() {
        val q = QueueInputStream()
        q.put(byteArrayOf(1, 2, 3))
        q.put(byteArrayOf(4))
        val b = ByteArray(2)
        assertEquals(2, q.read(b, 0, 2))
        assertEquals(1, q.read(b, 0, 2))
        assertEquals(3, b[0].toInt())
        assertEquals(1, q.read(b, 0, 2))
        q.finish()
        assertEquals(-1, q.read(b, 0, 2))
    }

    @Test fun feedFillsTheFrameQueueAndKeepsErrors() {
        val frames = PcFrames()
        val feed = PhoneVideoFeed(frames, quietMs = 5)
        val enc = { b: ByteArray -> JSONObject().put("t", "screen_h264").put("data", java.util.Base64.getEncoder().encodeToString(b)) }
        feed.handle(enc(sps + pps + idr))
        feed.handle(enc(p1))
        Thread.sleep(150) // the quiet timer flushes the last picture
        val u = frames.nextUnit()
        assertTrue(u != null && u.key)
        assertTrue(frames.h264)
        feed.handle(JSONObject().put("t", "screen_stream_error").put("message", "stopped"))
        assertEquals("stopped", frames.error)
    }
}
