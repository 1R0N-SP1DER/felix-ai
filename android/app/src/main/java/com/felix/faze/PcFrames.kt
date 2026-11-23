package com.felix.faze

import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The computer's live screen. The computer pushes a picture whenever it has one (pc_frame). For JPEG only the newest is kept, so a phone that decodes slower
 * than the computer captures simply skips pictures instead of falling behind. For H.264 every unit matters to the decoder, so they are queued in order;
 * if the phone falls far behind, the backlog is dropped and decoding resumes at the next keyframe. Never answers: these are notices.
 */
class PcFrames {
    /** One H.264 access unit (a single picture), and whether it is a keyframe. */
    class Unit(val data: ByteArray, val key: Boolean)

    @Volatile var seq = 0L
        private set
    @Volatile private var jpeg: ByteArray? = null
    @Volatile var error: String = ""
        private set
    /** True once H.264 units have arrived: the picture then goes to the decoder, not to [latest]. */
    @Volatile var h264 = false
        private set

    private val units = ConcurrentLinkedQueue<Unit>()
    @Volatile private var needKey = true
    @Volatile var dropped = 0
        private set

    fun handle(msg: JSONObject): JSONObject? {
        when (msg.optString("t")) {
            "pc_frame" -> {
                if (msg.optString("codec") == "h264") {
                    val data = try { java.util.Base64.getDecoder().decode(msg.optString("data")) } catch (e: IllegalArgumentException) { return null }
                    if (data.isEmpty()) return null
                    val key = msg.optBoolean("key")
                    if (needKey && !key) return null
                    if (key) needKey = false
                    units.add(Unit(data, key))
                    if (units.size > MAX_BACKLOG) { dropped += units.size; units.clear(); needKey = true }
                    h264 = true
                } else {
                    val data = try { java.util.Base64.getDecoder().decode(msg.optString("jpeg")) } catch (e: IllegalArgumentException) { return null }
                    if (data.isEmpty()) return null
                    jpeg = data
                }
                error = ""
                seq++
            }
            "pc_stream_error" -> error = msg.optString("message", "the PC screen stopped")
        }
        return null
    }

    /** The newest JPEG picture and its number, or null when nothing has arrived. */
    fun latest(): Pair<Long, ByteArray>? = jpeg?.let { seq to it }

    /** The next H.264 unit for the decoder, or null when none is waiting. */
    fun nextUnit(): Unit? = units.poll()

    fun reset() { jpeg = null; error = ""; seq = 0; units.clear(); needKey = true; h264 = false; dropped = 0 }

    companion object { const val MAX_BACKLOG = 12 }
}
