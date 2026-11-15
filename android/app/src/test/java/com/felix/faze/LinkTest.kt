package com.felix.faze

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket

class LinkTest {
    private val nc = ByteArray(16) { it.toByte() }
    private val np = ByteArray(16) { (it + 16).toByte() }
    private val servers = ArrayList<LinkServer>()

    @After fun down() { servers.forEach { it.stop() } }

    // Values computed by the PC's Python code (pc/tests/test_link.py), so both sides agree byte for byte.
    @Test fun matchesThePcCode() {
        assertEquals("cb354a5bdfd3a916b67b89034b39edf74628a27d425968d2c9e0f137d958ca31", LinkCrypto.hex(LinkCrypto.codeKey("ABCDEFGHJK")))
        val k = LinkCrypto.codeKey("ABCDEFGHJK")
        assertEquals("7f16c6f5b2a48f92ba77f61da7ac82238a77f3b6480202c03f66fe0dcddaa809", LinkCrypto.hex(LinkCrypto.proof(k, "phone", nc, np)))
        val secret = ByteArray(32) { it.toByte() }
        val sk = LinkCrypto.sessionKey(secret, nc, np)
        assertEquals("ae101d852a97e906dba070b0077e542a2038470cc985050d2281ea6b82088818", LinkCrypto.hex(sk))
        val ct = LinkCrypto.seal(sk, "PCPC".toByteArray() + ByteArray(8), "{\"t\":\"ping\"}".toByteArray())
        assertEquals("ec24b3bb72f4cfa9a913844bf1925ce9734e08d2017900180bb8b8d7", LinkCrypto.hex(ct))
    }

    @Test fun codeIsCleanedAndOpenRefusesTampering() {
        assertEquals("ABCDEFGHJK", LinkCrypto.cleanCode("abcde-fghjk 0O1I"))
        assertEquals(10, LinkCrypto.newCode().length)
        val key = ByteArray(32) { 7 }
        val n = ByteArray(12)
        val sealed = LinkCrypto.seal(key, n, "hi".toByteArray())
        assertEquals("hi", String(LinkCrypto.open(key, n, sealed)!!))
        sealed[0] = (sealed[0].toInt() xor 1).toByte()
        assertNull(LinkCrypto.open(key, n, sealed))
    }

    @Test fun connectionKindsFollowTheInterface() {
        assertEquals("USB tethering", LinkNet.kind("rndis0"))
        assertEquals("Bluetooth tethering", LinkNet.kind("bt-pan"))
        assertEquals("Wi-Fi hotspot", LinkNet.kind("swlan0"))
        assertEquals("Wi-Fi", LinkNet.kind("wlan0"))
    }

    @Test fun peersAreKeptAndRemoved() {
        var store = ""
        val peers = LinkPeers({ store }, { store = it })
        peers.add(LinkPeers.Peer("a", "Desk", "00", "linux"))
        peers.add(LinkPeers.Peer("b", "Laptop", "11", "windows"))
        assertEquals(2, peers.all().size)
        assertEquals("windows", peers.get("b")!!.os)
        peers.remove("a")
        assertEquals(listOf("b"), peers.all().map { it.id })
    }

    private fun server(peers: LinkPeers, clock: () -> Long = System::currentTimeMillis): LinkServer {
        val s = LinkServer(peers, "phone1", { "Pixel" }, port = 0, discoveryPort = 0, now = clock)
        s.start()
        servers.add(s)
        return s
    }

    private fun json(s: Socket) = JSONObject(String(LinkCrypto.readFrame(s.getInputStream())))

    private fun put(s: Socket, o: JSONObject) = LinkCrypto.writeFrame(s.getOutputStream(), o.toString().toByteArray())

    /** The computer's half of pairing, as the PC code does it. Returns the secret, or null when the phone refused. */
    private fun pair(port: Int, code: String, id: String = "pc1", attacker: Boolean = false): ByteArray? {
        val k = LinkCrypto.codeKey(code)
        Socket("127.0.0.1", port).use { s ->
            val nc = LinkCrypto.randomBytes(16)
            put(s, JSONObject().put("t", "pair").put("v", 1).put("id", id).put("name", "Desk").put("os", "linux").put("nonce", LinkCrypto.hex(nc)))
            val r = json(s)
            if (r.optString("t") != "pair_ok") return null
            val np = LinkCrypto.unhex(r.getString("nonce"))
            if (!LinkCrypto.same(LinkCrypto.unhex(r.getString("proof")), LinkCrypto.proof(k, "phone", nc, np)) && !attacker) return null // the PC stops here, and the phone counts nothing
            put(s, JSONObject().put("t", "pair_confirm").put("proof", LinkCrypto.hex(LinkCrypto.proof(k, "pc", np, nc))))
            val last = json(s)
            if (last.optString("t") != "secret") return null
            return LinkCrypto.open(k, "SECRET".toByteArray() + ByteArray(6), LinkCrypto.unhex(last.getString("ct")))
        }
    }

    @Test fun pairsThenConnectsAndAnswersPing() {
        var store = ""
        val peers = LinkPeers({ store }, { store = it })
        val srv = server(peers)
        val code = srv.openPairing()
        val secret = pair(srv.boundPort, code)!!
        assertEquals(32, secret.size)
        assertEquals("linux", peers.get("pc1")!!.os)
        assertFalse(srv.pairingOpen())
        Socket("127.0.0.1", srv.boundPort).use { s ->
            val nc = LinkCrypto.randomBytes(16)
            put(s, JSONObject().put("t", "hello").put("v", 1).put("id", "pc1").put("nonce", LinkCrypto.hex(nc)))
            val r = json(s)
            val np = LinkCrypto.unhex(r.getString("nonce"))
            assertTrue(LinkCrypto.same(LinkCrypto.unhex(r.getString("proof")), LinkCrypto.proof(secret, "phone", nc, np)))
            put(s, JSONObject().put("t", "auth").put("proof", LinkCrypto.hex(LinkCrypto.proof(secret, "pc", np, nc))))
            val sess = LinkSession(s.getInputStream(), s.getOutputStream(), LinkCrypto.sessionKey(secret, nc, np), phone = false)
            assertEquals("ready", sess.recv().getString("t"))
            sess.send(JSONObject().put("t", "ping"))
            assertEquals("pong", sess.recv().getString("t"))
            assertTrue("pc1" in srv.connected())
        }
    }

    @Test fun wrongCodeIsRefusedAndClosesAfterFiveTries() {
        var store = ""
        val peers = LinkPeers({ store }, { store = it })
        val srv = server(peers)
        srv.openPairing()
        repeat(5) { assertNull(pair(srv.boundPort, "AAAAAAAAAA", attacker = true)) }
        assertFalse(srv.pairingOpen())
        assertTrue(peers.all().isEmpty())
    }

    @Test fun pairingWindowExpires() {
        var t = 0L
        var store = ""
        val srv = server(LinkPeers({ store }, { store = it }), { t })
        val code = srv.openPairing()
        t += LinkServer.PAIR_WINDOW_MS + 1
        assertNull(pair(srv.boundPort, code))
    }

    @Test fun unknownComputerCannotConnect() {
        var store = ""
        val srv = server(LinkPeers({ store }, { store = it }))
        Socket("127.0.0.1", srv.boundPort).use { s ->
            put(s, JSONObject().put("t", "hello").put("v", 1).put("id", "nobody").put("nonce", LinkCrypto.hex(ByteArray(16))))
            assertEquals("error", json(s).getString("t"))
        }
    }

    @Test fun replayedMessageIsRefused() {
        val key = ByteArray(32) { 3 }
        val out = java.io.ByteArrayOutputStream()
        val a = LinkSession(java.io.ByteArrayInputStream(ByteArray(0)), out, key, phone = false)
        a.send(JSONObject().put("t", "x"))
        val frame = out.toByteArray()
        val b = LinkSession(java.io.ByteArrayInputStream(frame + frame), java.io.ByteArrayOutputStream(), key, phone = true)
        assertNotNull(b.recv())
        var refused = false
        try { b.recv() } catch (e: java.io.IOException) { refused = true }
        assertTrue(refused)
    }

    @Test fun thePhoneCanAskAConnectedComputerAndGetTheAnswer() {
        var store = ""
        val peers = LinkPeers({ store }, { store = it })
        val srv = server(peers)
        val secret = pair(srv.boundPort, srv.openPairing())!!
        Socket("127.0.0.1", srv.boundPort).use { s ->
            val nc = LinkCrypto.randomBytes(16)
            put(s, JSONObject().put("t", "hello").put("v", 1).put("id", "pc1").put("nonce", LinkCrypto.hex(nc)))
            val r = json(s)
            val np = LinkCrypto.unhex(r.getString("nonce"))
            put(s, JSONObject().put("t", "auth").put("proof", LinkCrypto.hex(LinkCrypto.proof(secret, "pc", np, nc))))
            val sess = LinkSession(s.getInputStream(), s.getOutputStream(), LinkCrypto.sessionKey(secret, nc, np), phone = false)
            assertEquals("ready", sess.recv().getString("t"))
            val answer = java.util.concurrent.atomic.AtomicReference<JSONObject>()
            val t = Thread { answer.set(srv.ask("pc1", JSONObject().put("t", "pc_chat"), 5000)) }
            t.start()
            val q = sess.recv() // the computer receives the phone's question...
            assertEquals("pc_chat", q.getString("t"))
            sess.send(JSONObject().put("t", "pc_chat_ok").put("content", "hi").put("re", q.getLong("rid"))) // ...and answers with the same number
            t.join(5000)
            assertEquals("hi", answer.get().getString("content"))
        }
    }

    @Test fun askingAComputerThatIsNotConnectedFails() {
        var store = ""
        val srv = server(LinkPeers({ store }, { store = it }))
        var failed = false
        try { srv.ask("nobody", JSONObject().put("t", "x"), 100) } catch (e: java.io.IOException) { failed = true }
        assertTrue(failed)
    }
}
