package com.felix.faze

import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.Executor

/**
 * Drives a connected computer like a remote receiver: shows its screen and moves its mouse and keyboard. The computer decides whether it allows this
 * (a switch in its own settings), and tells us in words when it does not. Mouse movement is gathered while the previous message is still in flight,
 * so a fast drag sends fewer, larger moves instead of a queue.
 */
class PcRemote(private val ask: (JSONObject, Long) -> JSONObject, private val worker: Executor, private val onError: (String) -> Unit, private val fire: ((JSONObject) -> Unit)? = null) {
    private var pdx = 0f
    private var pdy = 0f
    private var scheduled = false

    /** One picture of the computer's screen as JPEG bytes. Throws IOException with the computer's own words when it refuses. */
    fun screen(width: Int = 960): ByteArray {
        val r = ask(JSONObject().put("t", "pc_screen_get").put("w", width), 20_000)
        if (r.optString("t") != "pc_screen_ok") throw IOException(r.optString("message", "the computer did not send its screen"))
        return java.util.Base64.getDecoder().decode(r.getString("jpeg"))
    }

    /** Starts the computer's live screen stream (pc_frame pictures arrive on their own). Returns the capture method's name. Throws IOException with what to install when it cannot. */
    fun startStream(width: Int = 960, fps: Int = 30, quality: Int = 60): String {
        val r = ask(JSONObject().put("t", "pc_stream_start").put("w", width).put("fps", fps).put("q", quality), 150_000) // the first time the desktop asks for approval
        if (r.optString("t") != "pc_stream_ok") throw IOException(r.optString("message", "the computer could not start its screen stream"))
        return r.optString("engine")
    }

    /** Tells the computer we are still watching; it stops capturing a few seconds after the last ping. */
    fun pingStream() { try { ask(JSONObject().put("t", "pc_stream_ping"), 5_000) } catch (_: Exception) { } }

    fun stopStream() { try { ask(JSONObject().put("t", "pc_stream_stop"), 5_000) } catch (_: Exception) { } }

    fun move(dx: Float, dy: Float) {
        synchronized(this) {
            pdx += dx; pdy += dy
            if (scheduled) return
            scheduled = true
        }
        worker.execute { flush() }
    }

    private fun flush() {
        val dx: Float
        val dy: Float
        synchronized(this) { dx = pdx; dy = pdy; pdx = 0f; pdy = 0f; scheduled = false }
        if (dx != 0f || dy != 0f) input("move", "dx" to dx.toDouble(), "dy" to dy.toDouble(), quick = true)
    }

    /** Holds or releases a mouse button (for dragging things on the computer). */
    fun button(down: Boolean, button: String = "left") = worker.execute { input(if (down) "down" else "up", "button" to button) }

    fun click(button: String = "left", double: Boolean = false) = worker.execute { input("click", "button" to button, "double" to double) }
    fun scroll(lines: Int) = worker.execute { input("scroll", "dy" to lines, quick = true) }
    fun key(name: String) = worker.execute { input("key", "key" to name) }
    fun text(text: String) = worker.execute { input("text", "text" to text) }

    /** Presses the keys together, like Ctrl+Shift+T: in order, released in reverse. */
    fun combo(keys: List<String>) = worker.execute { input("combo", "keys" to org.json.JSONArray(keys)) }

    /** [quick] inputs (movement, scrolling) are sent without waiting for an answer, so a fast drag is not slowed by round trips. Clicks and keys wait, so a refusal is reported. */
    private fun input(kind: String, vararg kv: Pair<String, Any>, quick: Boolean = false) {
        try {
            val m = JSONObject().put("t", "pc_input").put("kind", kind)
            kv.forEach { m.put(it.first, it.second) }
            if (quick && fire != null) { fire.invoke(m); return }
            val r = ask(m, 10_000)
            if (r.optString("t") == "error") onError(r.optString("message", "the computer refused"))
        } catch (e: Exception) {
            onError(e.message ?: "the computer could not be reached")
        }
    }
}
