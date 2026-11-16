package com.felix.faze

import org.json.JSONObject
import java.io.IOException
import java.io.InputStream

/** Sends a file from this phone to a connected computer in pieces. The computer puts it in its own inbox folder and never replaces a file. */
class PcSend(private val ask: (JSONObject) -> JSONObject) {
    companion object { const val PIECE = 384 * 1024 }

    /** Returns where the computer saved it. [progress] gets the bytes sent so far. Throws IOException with the computer's own words on a refusal. */
    fun send(name: String, input: InputStream, progress: (Long) -> Unit = {}): String {
        var offset = 0L
        var saved = ""
        val buf = ByteArray(PIECE)
        while (true) {
            var n = 0
            while (n < PIECE) { val r = input.read(buf, n, PIECE - n); if (r < 0) break; n += r }
            val eof = n < PIECE
            val r = ask(JSONObject().put("t", "pc_put").put("name", name).put("offset", offset).put("eof", eof)
                .put("data", java.util.Base64.getEncoder().encodeToString(buf.copyOf(n))))
            if (r.optString("t") != "pc_put_ok") throw IOException(r.optString("message", "the computer did not take the file"))
            offset += n
            progress(offset)
            if (eof) { saved = r.optString("saved"); break }
        }
        return saved
    }
}
