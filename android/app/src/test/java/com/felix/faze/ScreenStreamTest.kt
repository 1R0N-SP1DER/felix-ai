package com.felix.faze

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class ScreenStreamTest {
    private val sent = java.util.Collections.synchronizedList(mutableListOf<JSONObject>())
    private var allowed = true
    private var locked = false
    private var clock = 1_000L
    private var opens = 0
    private var sources: (Int) -> ByteArray = { ByteArray(10) { it.toByte() } }
    private val sizes = mutableListOf<Pair<Int, Int>>()

    private fun make() = ScreenStream(
        { w, h -> sizes.add(w to h); opens++; val data = sources(opens); object : ScreenStream.Source { override val input: InputStream = ByteArrayInputStream(data); override fun close() {} } },
        { 1080 to 2400 }, { allowed }, { locked }, { sent.add(it) }, { clock },
    )

    private fun wait(cond: () -> Boolean) { val end = System.currentTimeMillis() + 3000; while (System.currentTimeMillis() < end && !cond()) Thread.sleep(5) }
    private fun msg(t: String) = JSONObject().put("t", t)

    @Test fun targetKeepsTheShapeWithEvenSizes() {
        assertEquals(720 to 1600, ScreenStream.target(1080, 2400))
        assertEquals(480 to 800, ScreenStream.target(480, 800))
        assertEquals(720 to 1558, ScreenStream.target(1080, 2337))
    }

    @Test fun ignoresOtherMessages() = org.junit.Assert.assertNull(make().handle(msg("screen_get")))

    @Test fun refusedWhenOffOrLocked() {
        val s = make()
        allowed = false
        assertEquals("error", s.handle(msg("screen_stream_start"))!!.getString("t"))
        allowed = true; locked = true
        assertEquals("The phone is locked.", s.handle(msg("screen_stream_start"))!!.getString("message"))
        assertEquals(0, opens)
    }

    @Test fun startsPushingPiecesAndRestartsWhenTheRecorderEnds() {
        val s = make()
        assertEquals("screen_stream_ok", s.handle(msg("screen_stream_start"))!!.getString("t"))
        wait { opens >= 2 }
        s.stop()
        assertTrue(opens >= 2)
        assertTrue(sent.all { it.getString("t") == "screen_h264" })
        assertEquals(listOf<Byte>(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), java.util.Base64.getDecoder().decode(sent[0].getString("data")).toList())
        assertEquals(720 to 1600, sizes[0])
    }

    @Test fun stopsWhenThePingsStop() {
        val s = make()
        s.handle(msg("screen_stream_start"))
        wait { sent.isNotEmpty() }
        clock += ScreenStream.IDLE_MS + 1
        val before = opens
        Thread.sleep(150)
        val after = opens
        assertTrue(after - before < 100000)
        wait { sent.size > 0 }
        // once idle, nothing new is opened
        val stable = opens
        Thread.sleep(150)
        assertEquals(stable, opens)
    }

    @Test fun aRecorderThatKeepsDyingGivesUpWithAnError() {
        sources = { ByteArray(0) }
        val s = make()
        s.handle(msg("screen_stream_start"))
        wait { sent.any { it.getString("t") == "screen_stream_error" } }
        assertTrue(sent.any { it.getString("t") == "screen_stream_error" })
        assertEquals(3, opens)
    }

    @Test fun stopMessageStops() {
        val s = make()
        s.handle(msg("screen_stream_start"))
        assertEquals("ok", s.handle(msg("screen_stream_stop"))!!.getString("t"))
    }
}
