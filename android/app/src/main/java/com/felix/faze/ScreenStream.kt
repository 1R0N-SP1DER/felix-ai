package com.felix.faze

import org.json.JSONObject
import java.io.InputStream

/**
 * Live video of this phone's screen for a connected computer. With root, `screenrecord` produces H.264 straight from the display encoder; the video is
 * cut into pieces and pushed to the computer as it is made (screen_h264), where it is decoded and drawn. That is real video at the phone's own frame rate,
 * not screenshots. screenrecord ends by itself every three minutes, so it is simply started again. Everything the person switched on for screen sharing
 * still applies: off means refused, and a locked phone is refused.
 */
class ScreenStream(
    private val open: (Int, Int) -> Source,
    private val size: () -> Pair<Int, Int>,
    private val allowed: () -> Boolean,
    private val locked: () -> Boolean,
    private val send: (JSONObject) -> Unit,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val log: (String) -> Unit = {},
) {
    interface Source { val input: InputStream; fun close(); fun error(): String = "" }

    companion object {
        const val IDLE_MS = 10_000L
        const val PIECE = 24 * 1024
        const val MAX_WIDTH = 720
        val TYPES = setOf("screen_stream_start", "screen_stream_ping", "screen_stream_stop")

        /** screenrecord wants even sizes; keep the shape, cap the width. */
        fun target(w: Int, h: Int, maxWidth: Int = MAX_WIDTH): Pair<Int, Int> {
            val scale = if (w > maxWidth) maxWidth.toDouble() / w else 1.0
            return ((w * scale).toInt() / 2 * 2).coerceAtLeast(2) to ((h * scale).toInt() / 2 * 2).coerceAtLeast(2)
        }
    }

    @Volatile private var running = false
    @Volatile private var lastError = ""
    @Volatile private var lastPing = 0L
    private var thread: Thread? = null

    fun handle(msg: JSONObject): JSONObject? {
        val t = msg.optString("t")
        if (t !in TYPES) return null
        return when (t) {
            "screen_stream_stop" -> { stop(); JSONObject().put("t", "ok") }
            "screen_stream_ping" -> { lastPing = now(); JSONObject().put("t", "ok") }
            else -> {
                if (!allowed()) return error("Screen sharing is off on the phone. Turn on Allow screen view and control in Settings > Advanced > Devices.")
                if (locked()) return error("The phone is locked.")
                start()
                JSONObject().put("t", "screen_stream_ok")
            }
        }
    }

    @Synchronized private fun start() {
        lastPing = now()
        if (running) return
        running = true
        log("A computer is watching the screen live")
        thread = Thread { pump() }.also { it.isDaemon = true; it.start() }
    }

    fun stop() { running = false }

    private fun pump() {
        var quick = 0
        val buf = ByteArray(PIECE)
        try {
            while (running) {
                if (now() - lastPing > IDLE_MS) break
                val began = now()
                val (w, h) = size().let { target(it.first, it.second) }
                var src: Source? = null
                try {
                    src = open(w, h)
                    while (running) {
                        val n = src.input.read(buf)
                        if (n < 0) break
                        if (now() - lastPing > IDLE_MS) { running = false; break }
                        send(JSONObject().put("t", "screen_h264").put("data", java.util.Base64.getEncoder().encodeToString(buf.copyOf(n))))
                    }
                } catch (e: Exception) {
                    if (!running) break
                } finally {
                    try { src?.close() } catch (_: Exception) { }
                    try { src?.let { lastError = it.error().ifEmpty { lastError } } } catch (_: Exception) { }
                }
                if (!running) break
                quick = if (now() - began < 3000) quick + 1 else 0 // a run of three minutes is normal; instant endings are not
                if (quick >= 3) {
                    send(JSONObject().put("t", "screen_stream_error").put("message", "The phone's screen recorder keeps stopping. It needs root (screenrecord)." + (if (lastError.isNotEmpty()) " It said: $lastError" else "")))
                    break
                }
            }
        } finally {
            running = false
        }
    }

    private fun error(m: String) = JSONObject().put("t", "error").put("message", m)
}

/** screenrecord as root, H.264 on standard output. */
class RootScreenSource(w: Int, h: Int) : ScreenStream.Source {
    private val proc: Process = ProcessBuilder("su", "-c", "screenrecord --output-format=h264 --size ${w}x$h --bit-rate 6000000 -").redirectErrorStream(false).start()
    override val input: InputStream get() = proc.inputStream
    override fun close() { try { proc.destroy() } catch (_: Exception) { } }
    override fun error(): String = try {
        if (proc.errorStream.available() > 0) String(proc.errorStream.readBytes()).trim().lines().lastOrNull()?.take(160) ?: "" else ""
    } catch (_: Exception) { "" }
}
