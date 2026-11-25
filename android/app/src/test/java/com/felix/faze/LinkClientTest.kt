package com.felix.faze

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Two phones in one process: one runs the LinkServer, the other the LinkClient, over the real sockets. */
class LinkClientTest {
    private var store = ""
    private var remoteStore = ""
    private val peers = LinkPeers({ store }, { store = it })
    private val remotes = LinkRemotes({ remoteStore }, { remoteStore = it })
    private val server = LinkServer(peers, "serverid", { "Pixel" }, port = 0, discoveryPort = 0, handler = { _, m -> if (m.optString("t") == "echo") JSONObject().put("t", "echoed").put("v", m.optString("v")) else null })
    private val client = LinkClient("clientid", { "Galaxy" }, remotes)

    @After fun down() { server.stop() }

    @Test fun pairsReconnectsAndAsks() {
        server.start()
        val port = server.boundPort
        val code = server.openPairing()
        val r = client.pair("127.0.0.1", port, code)
        assertEquals("serverid", r.id)
        assertEquals("Pixel", r.name)
        assertEquals("android", peers.get("clientid")!!.os)
        assertEquals("Galaxy", peers.get("clientid")!!.name)
        client.connect("serverid")
        var tries = 0
        while ("clientid" !in server.connected() && tries++ < 50) Thread.sleep(50)
        assertTrue(client.connected().contains("serverid"))
        assertTrue(server.connected().contains("clientid"))
        val back = client.ask("serverid", JSONObject().put("t", "echo").put("v", "hi"), 3000)
        assertEquals("hi", back.getString("v"))
        val ping = client.ask("serverid", JSONObject().put("t", "ping"), 3000)
        assertEquals("pong", ping.getString("t"))
        // the server can ask the client too
        val srvAsk = server.ask("clientid", JSONObject().put("t", "ping"), 3000)
        assertEquals("pong", srvAsk.getString("t"))
    }

    @Test fun wrongCodeIsRefused() {
        server.start()
        server.openPairing()
        try {
            client.pair("127.0.0.1", server.boundPort, "AAAAAAAAAA")
            fail("should have been refused")
        } catch (e: java.io.IOException) {
            assertTrue(remotes.all().isEmpty())
        }
    }

    @Test fun closedPairingIsRefused() {
        server.start()
        try {
            client.pair("127.0.0.1", server.boundPort, "ABCDEFGHJK")
            fail("should have been refused")
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!.isNotEmpty())
        }
    }

    @Test fun remotesAreKeptAndForgotten() {
        remotes.add(LinkRemotes.Remote("a", "One", "00", "10.0.0.2", 8765))
        remotes.add(LinkRemotes.Remote("b", "Two", "11", "10.0.0.3", 9000))
        assertEquals(9000, remotes.get("b")!!.port)
        client.forget("a")
        assertEquals(listOf("b"), remotes.all().map { it.id })
    }
}
