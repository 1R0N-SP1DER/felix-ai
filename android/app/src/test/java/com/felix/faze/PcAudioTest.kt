package com.felix.faze

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PcAudioTest {
    private val asked = mutableListOf<JSONObject>()
    private val errors = mutableListOf<String>()
    private val events = mutableListOf<String>()
    private val sink = object : AudioSink {
        override fun open(rate: Int) { events.add("open $rate") }
        override fun write(pcm: ByteArray) { events.add("write ${pcm.size}") }
        override fun close() { events.add("close") }
    }
    private fun chunk(n: Int, rate: Int = 32000) = JSONObject().put("t", "pc_audio_ok").put("rate", rate).put("data", java.util.Base64.getEncoder().encodeToString(ByteArray(n)))

    @Test fun playsChunksAndOpensTheSpeakerOnce() {
        val a = PcAudio({ asked.add(it); chunk(100) }, sink, { errors.add(it) })
        assertTrue(a.step()); assertTrue(a.step())
        assertEquals(listOf("open 32000", "write 100", "write 100"), events)
        assertEquals("monitor", asked[0].getString("source"))
    }

    @Test fun anEmptyChunkWaitsABeat() {
        val slept = mutableListOf<Long>()
        val a = PcAudio({ chunk(0) }, sink, { }, { slept.add(it) })
        assertTrue(a.step())
        assertEquals(listOf(40L), slept)
        assertFalse(events.any { it.startsWith("write") })
    }

    @Test fun aRefusalStopsWithTheComputersWords() {
        val a = PcAudio({ JSONObject().put("t", "error").put("message", "Turn on remote control") }, sink, { errors.add(it) })
        assertFalse(a.step())
        assertEquals(listOf("Turn on remote control"), errors)
    }

    @Test fun aLostConnectionStops() {
        val a = PcAudio({ throw java.io.IOException("not connected") }, sink, { errors.add(it) })
        assertFalse(a.step())
        assertEquals(listOf("not connected"), errors)
    }

    @Test fun startAndStopTellTheComputerToStopRecording() {
        val gate = java.util.concurrent.CountDownLatch(1)
        val a = PcAudio({ m -> asked.add(m); if (m.getString("t") == "pc_audio_get") { gate.countDown(); Thread.sleep(5) }; if (m.getString("t") == "pc_audio_get") chunk(10) else JSONObject().put("t", "ok") }, sink, { })
        a.start("mic")
        assertTrue(gate.await(2, java.util.concurrent.TimeUnit.SECONDS))
        a.stop()
        val end = System.currentTimeMillis() + 2000
        while (System.currentTimeMillis() < end && asked.none { it.getString("t") == "pc_audio_stop" }) Thread.sleep(10)
        assertTrue(asked.any { it.getString("t") == "pc_audio_stop" && it.getString("source") == "mic" })
        assertTrue(events.contains("close"))
    }
}
