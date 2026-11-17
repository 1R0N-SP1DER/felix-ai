package com.felix.faze

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class PcRemoteTest {
    private val sent = mutableListOf<JSONObject>()
    private val errors = mutableListOf<String>()
    private var reply: (JSONObject) -> JSONObject = { JSONObject().put("t", "pc_input_ok") }
    private val pending = mutableListOf<Runnable>()
    private val remote = PcRemote({ m, _ -> sent.add(m); reply(m) }, { pending.add(it) }, { errors.add(it) })
    private fun drain() { while (pending.isNotEmpty()) pending.removeAt(0).run() }

    @Test fun screenDecodesTheJpeg() {
        reply = { JSONObject().put("t", "pc_screen_ok").put("jpeg", java.util.Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))) }
        assertEquals(listOf<Byte>(1, 2, 3), remote.screen(800).toList())
        assertEquals(800, sent[0].getInt("w"))
    }

    @Test fun aRefusedScreenCarriesTheComputersWords() {
        reply = { JSONObject().put("t", "error").put("message", "Turn on remote control") }
        try { remote.screen(); fail() } catch (e: IOException) { assertEquals("Turn on remote control", e.message) }
    }

    @Test fun fastMovesAreGatheredIntoOneMessage() {
        remote.move(3f, 1f); remote.move(4f, 2f); remote.move(-1f, 0f)
        assertEquals(1, pending.size)
        drain()
        assertEquals(1, sent.size)
        assertEquals("move", sent[0].getString("kind"))
        assertEquals(6.0, sent[0].getDouble("dx"), 0.001)
        assertEquals(3.0, sent[0].getDouble("dy"), 0.001)
        remote.move(1f, 1f)
        assertEquals(1, pending.size)
    }

    @Test fun movesAndScrollsAreFiredWithoutWaitingWhenThereIsAFireRoute() {
        val fired = mutableListOf<JSONObject>()
        val r = PcRemote({ m, _ -> sent.add(m); reply(m) }, { pending.add(it) }, { errors.add(it) }, { fired.add(it) })
        r.move(3f, 4f); r.scroll(2); r.key("enter")
        drain()
        assertEquals(listOf("move", "scroll"), fired.map { it.getString("kind") })
        assertEquals(listOf("key"), sent.map { it.getString("kind") })
    }

    @Test fun holdingAndReleasingTheButton() {
        remote.button(true); remote.button(false)
        drain()
        assertEquals(listOf("down", "up"), sent.map { it.getString("kind") })
    }

    @Test fun clickScrollKeyAndTextAreSent() {
        remote.click("right"); remote.click(double = true); remote.scroll(-3); remote.key("enter"); remote.text("hi")
        drain()
        assertEquals(listOf("click", "click", "scroll", "key", "text"), sent.map { it.getString("kind") })
        assertEquals("right", sent[0].getString("button"))
        assertTrue(sent[1].getBoolean("double"))
        assertEquals("hi", sent[4].getString("text"))
    }

    @Test fun errorsReachTheScreenInWords() {
        reply = { JSONObject().put("t", "error").put("message", "no input tool") }
        remote.click(); drain()
        assertEquals(listOf("no input tool"), errors)
        reply = { throw IOException("the computer is not connected") }
        remote.key("tab"); drain()
        assertEquals("the computer is not connected", errors[1])
    }
}
