package com.felix.faze

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class PcSendTest {
    private val got = mutableListOf<JSONObject>()

    @Test fun sendsInPiecesAndEndsWithEof() {
        val data = ByteArray(PcSend.PIECE * 2 + 10) { it.toByte() }
        val seen = mutableListOf<Long>()
        val saved = PcSend { m -> got.add(m); JSONObject().put("t", "pc_put_ok").put("saved", if (m.getBoolean("eof")) "/home/x/Faze/a.bin" else "") }
            .send("a.bin", ByteArrayInputStream(data)) { seen.add(it) }
        assertEquals("/home/x/Faze/a.bin", saved)
        assertEquals(3, got.size)
        assertEquals(listOf(false, false, true), got.map { it.getBoolean("eof") })
        assertEquals(listOf(0L, PcSend.PIECE.toLong(), PcSend.PIECE * 2L), got.map { it.getLong("offset") })
        assertEquals(data.size.toLong(), seen.last())
        val joined = got.flatMap { java.util.Base64.getDecoder().decode(it.getString("data")).toList() }
        assertEquals(data.toList(), joined)
    }

    @Test fun anEmptyFileStillArrives() {
        PcSend { m -> got.add(m); JSONObject().put("t", "pc_put_ok").put("saved", "/x") }.send("e", ByteArrayInputStream(ByteArray(0)))
        assertEquals(1, got.size)
        assertTrue(got[0].getBoolean("eof"))
    }

    @Test fun aRefusalStopsAndCarriesTheWords() {
        try {
            PcSend { JSONObject().put("t", "error").put("message", "Turn on remote control") }.send("a", ByteArrayInputStream(ByteArray(5)))
            fail()
        } catch (e: IOException) { assertEquals("Turn on remote control", e.message) }
    }
}
