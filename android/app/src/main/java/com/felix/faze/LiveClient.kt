package com.felix.faze

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/** A websocket as the client sees it. The Android side wraps OkHttp; tests hand in a fake. */
interface LiveSocket {
    /** False when the connection is already gone. */
    fun send(text: String): Boolean
    fun close()
}

interface LiveListener {
    fun onMessage(text: String)

    /** The connection ended or failed. [why] is the server's reason when it gave one. */
    fun onClosed(why: String)
}

fun interface LiveConnector {
    fun connect(url: String, listener: LiveListener): LiveSocket
}

/**
 * One Gemini Live conversation: opens the socket, gets the setup accepted (trying the message shapes of [LiveProtocol.SHAPES] in turn,
 * as core/live_client.py does), then hands every server event to [onEvent]. [open] blocks, so call it off the main thread.
 */
class LiveClient(
    private val key: String,
    private val model: String,
    private val connector: LiveConnector,
    private val onEvent: (LiveEvent) -> Unit,
    private val setupTimeoutMs: Long = 12_000,
) {
    private sealed class Early {
        class Msg(val text: String) : Early()
        class Gone(val why: String) : Early()
    }

    private class Handshake : LiveListener {
        val queue = LinkedBlockingQueue<Early>()
        @Volatile var forward: LiveListener? = null
        override fun onMessage(text: String) { forward?.onMessage(text) ?: queue.put(Early.Msg(text)) }
        override fun onClosed(why: String) { forward?.onClosed(why) ?: queue.put(Early.Gone(why)) }
    }

    @Volatile private var socket: LiveSocket? = null
    private val closed = AtomicBoolean(false)
    val rejections = ArrayList<String>()

    /** Null when the conversation is open, otherwise a readable reason. The key never appears in it. */
    fun open(instruction: String, declarations: JSONArray, search: Boolean, voice: String): String? {
        val shapes = if (search) LiveProtocol.SHAPES else LiveProtocol.SHAPES.filter { !it.second }
        for ((shape, withSearch) in shapes) {
            val handshake = Handshake()
            val sock = try {
                connector.connect(LiveProtocol.URL + key, handshake)
            } catch (e: Exception) {
                return "could not connect: " + LiveProtocol.scrub(e.message ?: e.javaClass.simpleName).take(160)
            }
            val label = shape + (if (withSearch) "" else " no-search")
            val first: Early? = try {
                if (!sock.send(LiveProtocol.setup(model, instruction, declarations, withSearch, voice, shape).toString())) Early.Gone("the connection closed")
                else handshake.queue.poll(setupTimeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                Early.Gone(e.message ?: e.javaClass.simpleName)
            }
            when (first) {
                null -> { sock.close(); return "the server did not answer the setup in time" }
                is Early.Gone -> {
                    sock.close()
                    val why = LiveProtocol.scrub(first.why).take(300)
                    rejections.add("$label: $why")
                    if (LiveProtocol.isRetryable(why)) continue
                    return why
                }
                is Early.Msg -> {
                    val data = try { JSONObject(first.text) } catch (e: Exception) { sock.close(); return "the server sent something unreadable" }
                    if (!data.has("setupComplete")) { sock.close(); return "the server did not confirm the setup: " + LiveProtocol.scrub(first.text).take(160) }
                    socket = sock
                    handshake.forward = dispatcher()
                    while (true) { // anything that slipped in before the switch
                        when (val early = handshake.queue.poll() ?: break) {
                            is Early.Msg -> handshake.forward!!.onMessage(early.text)
                            is Early.Gone -> handshake.forward!!.onClosed(early.why)
                        }
                    }
                    return null
                }
            }
        }
        return "setup was refused: " + rejections.joinToString(" | ").take(400)
    }

    private fun dispatcher() = object : LiveListener {
        override fun onMessage(text: String) {
            val events = try { LiveProtocol.parse(JSONObject(text)) } catch (e: Exception) { listOf(LiveEvent.Closed("the server sent something unreadable")) }
            for (e in events) {
                if (e is LiveEvent.Closed) markClosed(e.why) else onEvent(e)
            }
        }

        override fun onClosed(why: String) { markClosed(LiveProtocol.scrub(why)) }
    }

    private fun markClosed(why: String) {
        if (closed.compareAndSet(false, true)) onEvent(LiveEvent.Closed(why.ifEmpty { "closed" }))
    }

    private fun send(text: String) {
        val s = socket ?: return
        if (closed.get()) return
        if (!s.send(text)) markClosed("the connection was lost")
    }

    fun sendAudio(pcm: ByteArray) = send(LiveProtocol.audio(pcm))

    fun sendVideo(jpeg: ByteArray) = send(LiveProtocol.video(jpeg))

    fun sendText(text: String) = send(LiveProtocol.text(text))

    fun endAudio() = send(LiveProtocol.audioEnd())

    fun sendToolResponses(replies: List<LiveReply>) = send(LiveProtocol.toolResponses(replies))

    fun isOpen(): Boolean = socket != null && !closed.get()

    /** Hang up. Safe to call twice. */
    fun close() {
        closed.set(true)
        socket?.close()
        socket = null
    }
}
