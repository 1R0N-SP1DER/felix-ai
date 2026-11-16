package com.felix.faze

import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections

/** The computers this phone has paired with. The secret is stored here, in the app's private storage. */
class LinkPeers(private val load: () -> String, private val save: (String) -> Unit) {
    data class Peer(val id: String, val name: String, val secret: String, val os: String = "computer")

    private fun read(): JSONObject = try { JSONObject(load().ifBlank { "{}" }) } catch (e: Exception) { JSONObject() }

    @Synchronized
    fun all(): List<Peer> = read().let { j -> j.keys().asSequence().map { id -> j.getJSONObject(id).let { Peer(id, it.optString("name", "Computer"), it.getString("secret"), it.optString("os", "computer")) } }.toList() }

    @Synchronized
    fun get(id: String): Peer? = all().firstOrNull { it.id == id }

    @Synchronized
    fun add(peer: Peer) {
        save(read().put(peer.id, JSONObject().put("name", peer.name).put("secret", peer.secret).put("os", peer.os)).toString())
    }

    @Synchronized
    fun remove(id: String) {
        save(read().also { it.remove(id) }.toString())
    }
}

/** The phone is the hub: it listens, computers connect. Pairing happens only while a code is open (3 minutes, 5 wrong tries). After that a
 *  computer proves it holds the long-term secret and every message is encrypted. Layout: pc/docs/link.md. */
class LinkServer(
    private val peers: LinkPeers,
    private val deviceId: String,
    private val deviceName: () -> String,
    private val port: Int = PORT,
    private val discoveryPort: Int = DISCOVERY_PORT,
    private val onChange: () -> Unit = {},
    private val handler: (String, JSONObject) -> JSONObject? = { _, _ -> null },
    private val now: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val PORT = 8765
        const val DISCOVERY_PORT = 8766
        const val PAIR_WINDOW_MS = 3 * 60 * 1000L
        const val MAX_TRIES = 5
    }

    @Volatile private var server: ServerSocket? = null
    @Volatile private var discovery: DatagramSocket? = null
    @Volatile private var code: String? = null
    @Volatile private var codeUntil = 0L
    @Volatile private var tries = 0
    private val live = Collections.synchronizedMap(HashMap<String, Socket>())
    private val kinds = Collections.synchronizedMap(HashMap<String, String>())
    private val sessions = Collections.synchronizedMap(HashMap<String, LinkSession>())
    private val waiting = Collections.synchronizedMap(HashMap<String, java.util.concurrent.ArrayBlockingQueue<JSONObject>>())
    private val rids = java.util.concurrent.atomic.AtomicLong(1)

    val running: Boolean get() = server != null
    val boundPort: Int get() = server?.localPort ?: -1

    /** How a connected computer reaches the phone: Wi-Fi, USB, Bluetooth, hotspot. */
    fun connectionOf(id: String): String = kinds[id] ?: ""

    /** Asks a connected computer to do something and waits for its answer. Either side can ask: a message with "rid" is answered with the same number in "re". */
    fun ask(id: String, msg: JSONObject, timeoutMs: Long, cancelled: () -> Boolean = { false }): JSONObject {
        val s = sessions[id] ?: throw java.io.IOException("that computer is not connected")
        val rid = rids.getAndIncrement()
        val key = "$id#$rid"
        val q = java.util.concurrent.ArrayBlockingQueue<JSONObject>(1)
        waiting[key] = q
        try {
            s.send(JSONObject(msg.toString()).put("rid", rid))
            var left = timeoutMs
            while (left > 0) {
                if (cancelled()) throw Cancelled()
                q.poll(minOf(left, 100L), java.util.concurrent.TimeUnit.MILLISECONDS)?.let { return it }
                left -= 100
            }
            throw java.io.IOException("the computer did not answer in time")
        } finally {
            waiting.remove(key)
        }
    }

    /** Sends a notice nobody waits for to every connected computer. */
    fun notify(msg: JSONObject) {
        val all = synchronized(live) { sessions.values.toList() }
        for (s in all) try { s.send(msg) } catch (_: Exception) { }
    }

    fun connected(): Set<String> = synchronized(live) { live.keys.toSet() }

    /** Opens pairing and returns the code to show. */
    fun openPairing(): String {
        val c = LinkCrypto.newCode()
        code = c
        codeUntil = now() + PAIR_WINDOW_MS
        tries = 0
        return c
    }

    fun closePairing() { code = null }

    fun pairingOpen(): Boolean = code != null && now() < codeUntil

    fun start() {
        if (server != null) return
        val s = ServerSocket(port)
        server = s
        Thread({ acceptLoop(s) }, "faze-link").also { it.isDaemon = true }.start()
        try {
            val d = DatagramSocket(discoveryPort)
            discovery = d
            Thread({ answerLoop(d) }, "faze-link-find").also { it.isDaemon = true }.start()
        } catch (e: Exception) {
            discovery = null // finding by broadcast is optional; typing the address still works
        }
    }

    fun stop() {
        try { server?.close() } catch (e: Exception) {}
        try { discovery?.close() } catch (e: Exception) {}
        server = null
        discovery = null
        closePairing()
        synchronized(live) { live.values.forEach { try { it.close() } catch (e: Exception) {} }; live.clear() }
        sessions.clear()
        onChange()
    }

    /** Drops a computer's connection (used when it is removed). */
    fun drop(id: String) { sessions.remove(id); synchronized(live) { live.remove(id) }?.let { try { it.close() } catch (e: Exception) {} }; onChange() }

    private fun answerLoop(d: DatagramSocket) {
        val buf = ByteArray(256)
        while (!d.isClosed) {
            try {
                val p = DatagramPacket(buf, buf.size)
                d.receive(p)
                if (String(p.data, 0, p.length).trim() == "FAZE?") {
                    val reply = JSONObject().put("t", "faze").put("id", deviceId).put("name", deviceName()).put("port", boundPort).toString().toByteArray()
                    d.send(DatagramPacket(reply, reply.size, p.address, p.port))
                }
            } catch (e: Exception) {
                if (d.isClosed) return
            }
        }
    }

    private fun acceptLoop(s: ServerSocket) {
        while (!s.isClosed) {
            try {
                val c = s.accept()
                Thread({ serve(c) }, "faze-link-conn").also { it.isDaemon = true }.start()
            } catch (e: Exception) {
                if (s.isClosed) return
            }
        }
    }

    private fun send(c: Socket, obj: JSONObject) = LinkCrypto.writeFrame(c.getOutputStream(), obj.toString().toByteArray())

    private fun fail(c: Socket, message: String) {
        try { send(c, JSONObject().put("t", "error").put("message", message)) } catch (e: Exception) {}
    }

    private fun serve(c: Socket) {
        try {
            c.soTimeout = 15_000
            val first = JSONObject(String(LinkCrypto.readFrame(c.getInputStream())))
            when (first.optString("t")) {
                "pair" -> pairWith(c, first)
                "hello" -> session(c, first)
                else -> fail(c, "unknown request")
            }
        } catch (e: Exception) {
            // a dropped or confused connection ends here
        } finally {
            try { c.close() } catch (e: Exception) {}
        }
    }

    private fun pairWith(c: Socket, first: JSONObject) {
        val open = code
        if (open == null || now() >= codeUntil) return fail(c, "the phone is not waiting to pair. Open Settings > Devices > Pair a computer.")
        val key = LinkCrypto.codeKey(open)
        val pcId = first.getString("id")
        val pcName = first.optString("name", "Computer")
        val nc = LinkCrypto.unhex(first.getString("nonce"))
        val np = LinkCrypto.randomBytes(16)
        send(c, JSONObject().put("t", "pair_ok").put("nonce", LinkCrypto.hex(np)).put("proof", LinkCrypto.hex(LinkCrypto.proof(key, "phone", nc, np))))
        val confirm = JSONObject(String(LinkCrypto.readFrame(c.getInputStream())))
        val good = confirm.optString("t") == "pair_confirm" &&
            LinkCrypto.same(LinkCrypto.unhex(confirm.optString("proof")), LinkCrypto.proof(key, "pc", np, nc))
        if (!good) {
            if (++tries >= MAX_TRIES) closePairing()
            return fail(c, "wrong code")
        }
        val secret = LinkCrypto.randomBytes(32)
        val ct = LinkCrypto.seal(key, "SECRET".toByteArray() + ByteArray(6), secret)
        peers.add(LinkPeers.Peer(pcId, pcName, LinkCrypto.hex(secret), first.optString("os", "computer").lowercase().take(20)))
        closePairing()
        send(c, JSONObject().put("t", "secret").put("id", deviceId).put("name", deviceName()).put("ct", LinkCrypto.hex(ct)))
        onChange()
    }

    private fun session(c: Socket, hello: JSONObject) {
        val id = hello.getString("id")
        val peer = peers.get(id) ?: return fail(c, "this phone does not know this computer. Pair again.")
        val secret = LinkCrypto.unhex(peer.secret)
        val nc = LinkCrypto.unhex(hello.getString("nonce"))
        val np = LinkCrypto.randomBytes(16)
        send(c, JSONObject().put("t", "hello_ok").put("nonce", LinkCrypto.hex(np)).put("proof", LinkCrypto.hex(LinkCrypto.proof(secret, "phone", nc, np))))
        val auth = JSONObject(String(LinkCrypto.readFrame(c.getInputStream())))
        if (auth.optString("t") != "auth" || !LinkCrypto.same(LinkCrypto.unhex(auth.optString("proof")), LinkCrypto.proof(secret, "pc", np, nc))) return fail(c, "not authorised")
        val s = LinkSession(c.getInputStream(), c.getOutputStream(), LinkCrypto.sessionKey(secret, nc, np), phone = true)
        s.send(JSONObject().put("t", "ready").put("id", deviceId).put("name", deviceName()))
        kinds[id] = LinkNet.kindOf(c.localAddress)
        synchronized(live) { live.put(id, c)?.let { try { it.close() } catch (e: Exception) {} } }
        sessions[id] = s
        onChange()
        c.soTimeout = 0
        try {
            while (true) {
                val msg = s.recv()
                if (msg.has("re")) { // an answer to something this phone asked
                    waiting["$id#${msg.optLong("re")}"]?.offer(msg)
                    continue
                }
                val reply = if (msg.optString("t") == "ping") JSONObject().put("t", "pong") else handler(id, msg)
                if (reply != null) s.send(if (msg.has("rid")) reply.put("re", msg.optLong("rid")) else reply)
            }
        } finally {
            synchronized(live) { if (live[id] === c) { live.remove(id); sessions.remove(id) } }
            onChange()
        }
    }
}

object LinkNet {
    /** Names the way in from the network interface that the connection arrived on. */
    fun kind(iface: String): String {
        val n = iface.lowercase()
        return when {
            n.startsWith("rndis") || n.startsWith("usb") || n.startsWith("ncm") -> "USB tethering"
            n.startsWith("bt-pan") || n.startsWith("bnep") -> "Bluetooth tethering"
            n.startsWith("ap") || n.startsWith("swlan") -> "Wi-Fi hotspot"
            n.startsWith("wlan") || n.startsWith("wifi") -> "Wi-Fi"
            n.startsWith("eth") -> "Ethernet"
            else -> "Network"
        }
    }

    fun kindOf(local: java.net.InetAddress): String = try { kind(java.net.NetworkInterface.getByInetAddress(local)?.name ?: "") } catch (e: Exception) { "Network" }

    /** This phone's own addresses, for typing into the computer when finding it automatically does not work. */
    fun addresses(): List<String> = try {
        java.net.NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { i -> i.inetAddresses.toList().filterIsInstance<java.net.Inet4Address>().map { it.hostAddress + "  (" + kind(i.name) + ")" } }
    } catch (e: Exception) { emptyList() }
}
