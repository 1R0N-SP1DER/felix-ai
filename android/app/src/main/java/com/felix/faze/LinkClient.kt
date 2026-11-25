package com.felix.faze

import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Collections

/** Another phone this phone has paired with and can control, the way a computer does. The secret and last address are kept in private storage. */
class LinkRemotes(private val load: () -> String, private val save: (String) -> Unit) {
    data class Remote(val id: String, val name: String, val secret: String, val host: String, val port: Int)

    private fun read(): JSONObject = try { JSONObject(load().ifBlank { "{}" }) } catch (e: Exception) { JSONObject() }

    @Synchronized
    fun all(): List<Remote> = read().let { j -> j.keys().asSequence().map { id -> j.getJSONObject(id).let { Remote(id, it.optString("name", "Phone"), it.getString("secret"), it.optString("host"), it.optInt("port", LinkServer.PORT)) } }.toList() }

    @Synchronized
    fun get(id: String): Remote? = all().firstOrNull { it.id == id }

    @Synchronized
    fun add(r: Remote) {
        save(read().put(r.id, JSONObject().put("name", r.name).put("secret", r.secret).put("host", r.host).put("port", r.port)).toString())
    }

    @Synchronized
    fun remove(id: String) {
        save(read().also { it.remove(id) }.toString())
    }
}

/** The client side of Faze Link v1, the part a computer plays, written for a phone so one phone can pair with and control another.
 *  The other phone runs a normal LinkServer, so everything a computer can ask of a phone works the same here. Layout: pc/docs/link.md. */
class LinkClient(
    private val deviceId: String,
    private val deviceName: () -> String,
    private val remotes: LinkRemotes,
    private val onChange: () -> Unit = {},
    /** Called for things the other phone asks of this one (messages with "rid" or one-way notices). Return the answer or null. */
    private val handler: (String, JSONObject) -> JSONObject? = { _, _ -> null },
) {
    private class Conn(val socket: Socket, val session: LinkSession, val kind: String)

    private val conns = Collections.synchronizedMap(HashMap<String, Conn>())
    private val waiting = Collections.synchronizedMap(HashMap<String, java.util.concurrent.ArrayBlockingQueue<JSONObject>>())
    private val rids = java.util.concurrent.atomic.AtomicLong(1)

    fun connected(): Set<String> = synchronized(conns) { conns.keys.toSet() }

    fun connectionOf(id: String): String = conns[id]?.kind ?: ""

    /** Pairs with a phone that is showing a code. Returns the saved remote. Throws with a readable message when the code is wrong or the phone is not reachable. */
    fun pair(host: String, port: Int, code: String): LinkRemotes.Remote {
        val c = open(host, port)
        try {
            val key = LinkCrypto.codeKey(code)
            val nc = LinkCrypto.randomBytes(16)
            send(c, JSONObject().put("t", "pair").put("v", 1).put("id", deviceId).put("name", deviceName()).put("os", "android").put("nonce", LinkCrypto.hex(nc)))
            val ok = recv(c)
            val np = LinkCrypto.unhex(ok.getString("nonce"))
            if (!LinkCrypto.same(LinkCrypto.unhex(ok.optString("proof")), LinkCrypto.proof(key, "phone", nc, np))) throw java.io.IOException("this is not the phone showing that code")
            send(c, JSONObject().put("t", "pair_confirm").put("proof", LinkCrypto.hex(LinkCrypto.proof(key, "pc", np, nc))))
            val reply = recv(c)
            val secret = LinkCrypto.open(key, "SECRET".toByteArray() + ByteArray(6), LinkCrypto.unhex(reply.getString("ct"))) ?: throw java.io.IOException("wrong code")
            val r = LinkRemotes.Remote(reply.getString("id"), reply.optString("name", "Phone"), LinkCrypto.hex(secret), host, port)
            remotes.add(r)
            onChange()
            return r
        } finally {
            try { c.close() } catch (_: Exception) { }
        }
    }

    /** Connects to a paired phone and keeps listening for what it sends. Blocks only for the handshake. */
    fun connect(id: String, host: String? = null) {
        val r = remotes.get(id) ?: throw java.io.IOException("this phone is not paired")
        if (id in connected()) return
        val h = host ?: r.host
        val c = open(h, r.port)
        try {
            val secret = LinkCrypto.unhex(r.secret)
            val nc = LinkCrypto.randomBytes(16)
            send(c, JSONObject().put("t", "hello").put("v", 1).put("id", deviceId).put("nonce", LinkCrypto.hex(nc)))
            val ok = recv(c)
            val np = LinkCrypto.unhex(ok.getString("nonce"))
            if (!LinkCrypto.same(LinkCrypto.unhex(ok.optString("proof")), LinkCrypto.proof(secret, "phone", nc, np))) throw java.io.IOException("this phone did not prove who it is")
            send(c, JSONObject().put("t", "auth").put("proof", LinkCrypto.hex(LinkCrypto.proof(secret, "pc", np, nc))))
            val s = LinkSession(c.getInputStream(), c.getOutputStream(), LinkCrypto.sessionKey(secret, nc, np), phone = false)
            val ready = s.recv()
            if (ready.optString("t") != "ready") throw java.io.IOException(ready.optString("message", "the phone refused"))
            c.soTimeout = 0
            conns[id]?.socket?.let { try { it.close() } catch (_: Exception) { } }
            conns[id] = Conn(c, s, LinkNet.kindOf(c.localAddress))
            if (h != r.host) remotes.add(r.copy(host = h))
            Thread({ listen(id, c, s) }, "faze-link-client").also { it.isDaemon = true }.start()
            onChange()
        } catch (e: Exception) {
            try { c.close() } catch (_: Exception) { }
            throw e
        }
    }

    private fun listen(id: String, c: Socket, s: LinkSession) {
        try {
            while (true) {
                val msg = s.recv()
                if (msg.has("re")) { waiting["$id#${msg.optLong("re")}"]?.offer(msg); continue }
                val reply = if (msg.optString("t") == "ping") JSONObject().put("t", "pong") else handler(id, msg)
                if (reply != null) s.send(if (msg.has("rid")) reply.put("re", msg.optLong("rid")) else reply)
            }
        } catch (_: Exception) {
            // the other phone left or the connection broke
        } finally {
            synchronized(conns) { if (conns[id]?.socket === c) conns.remove(id) }
            try { c.close() } catch (_: Exception) { }
            onChange()
        }
    }

    /** Asks the other phone to do something and waits for its answer, like LinkServer.ask. */
    fun ask(id: String, msg: JSONObject, timeoutMs: Long, cancelled: () -> Boolean = { false }): JSONObject {
        val conn = conns[id] ?: throw java.io.IOException("that phone is not connected")
        val rid = rids.getAndIncrement()
        val key = "$id#$rid"
        val q = java.util.concurrent.ArrayBlockingQueue<JSONObject>(1)
        waiting[key] = q
        try {
            conn.session.send(JSONObject(msg.toString()).put("rid", rid))
            var left = timeoutMs
            while (left > 0) {
                if (cancelled()) throw Cancelled()
                q.poll(minOf(left, 100L), java.util.concurrent.TimeUnit.MILLISECONDS)?.let { return it }
                left -= 100
            }
            throw java.io.IOException("the phone did not answer in time")
        } finally {
            waiting.remove(key)
        }
    }

    fun notify(id: String, msg: JSONObject) { conns[id]?.session?.send(msg) }

    fun drop(id: String) {
        conns.remove(id)?.socket?.let { try { it.close() } catch (_: Exception) { } }
        onChange()
    }

    fun forget(id: String) { remotes.remove(id); drop(id) }

    private fun open(host: String, port: Int): Socket {
        val c = Socket()
        try {
            c.connect(InetSocketAddress(host, port), 5000)
            c.soTimeout = 15_000
            return c
        } catch (e: Exception) {
            try { c.close() } catch (_: Exception) { }
            throw java.io.IOException("could not reach $host. Is Faze open there with Allow devices on, on the same network?")
        }
    }

    private fun send(c: Socket, obj: JSONObject) = LinkCrypto.writeFrame(c.getOutputStream(), obj.toString().toByteArray())

    private fun recv(c: Socket): JSONObject {
        val m = JSONObject(String(LinkCrypto.readFrame(c.getInputStream())))
        if (m.optString("t") == "error") throw java.io.IOException(m.optString("message", "refused"))
        return m
    }

    companion object {
        data class Found(val id: String, val name: String, val host: String, val port: Int)

        /** Shouts "FAZE?" on the local network and collects what answers for a moment. Other Faze phones (and computers) reply with their name. */
        fun discover(ownId: String, waitMs: Long = 1200, port: Int = LinkServer.DISCOVERY_PORT, target: InetAddress? = null): List<Found> {
            val found = LinkedHashMap<String, Found>()
            try {
                DatagramSocket().use { d ->
                    d.broadcast = true
                    d.soTimeout = 200
                    val ask = "FAZE?".toByteArray()
                    d.send(DatagramPacket(ask, ask.size, target ?: InetAddress.getByName("255.255.255.255"), port))
                    val end = System.currentTimeMillis() + waitMs
                    val buf = ByteArray(512)
                    while (System.currentTimeMillis() < end) {
                        try {
                            val p = DatagramPacket(buf, buf.size)
                            d.receive(p)
                            val j = JSONObject(String(p.data, 0, p.length))
                            if (j.optString("t") == "faze" && j.optString("id") != ownId) found[j.getString("id")] = Found(j.getString("id"), j.optString("name", "Faze"), p.address.hostAddress ?: "", j.optInt("port", LinkServer.PORT))
                        } catch (_: java.net.SocketTimeoutException) { }
                    }
                }
            } catch (_: Exception) { }
            return found.values.toList()
        }
    }
}
