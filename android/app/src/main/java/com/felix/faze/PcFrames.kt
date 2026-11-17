package com.felix.faze

import org.json.JSONObject

/**
 * The computer's live screen. The computer pushes a picture whenever it has one (pc_frame); only the newest is kept, so a phone that decodes slower than
 * the computer captures simply skips pictures instead of falling behind. Never answers: these are notices.
 */
class PcFrames {
    @Volatile var seq = 0L
        private set
    @Volatile private var jpeg: ByteArray? = null
    @Volatile var error: String = ""
        private set

    fun handle(msg: JSONObject): JSONObject? {
        when (msg.optString("t")) {
            "pc_frame" -> {
                val data = try { java.util.Base64.getDecoder().decode(msg.optString("jpeg")) } catch (e: IllegalArgumentException) { return null }
                if (data.isEmpty()) return null
                jpeg = data
                error = ""
                seq++
            }
            "pc_stream_error" -> error = msg.optString("message", "the PC screen stopped")
        }
        return null
    }

    /** The newest picture and its number, or null when nothing has arrived. */
    fun latest(): Pair<Long, ByteArray>? = jpeg?.let { seq to it }

    fun reset() { jpeg = null; error = ""; seq = 0 }
}
