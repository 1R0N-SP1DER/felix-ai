package com.felix.faze

import org.json.JSONObject

/**
 * Clipboard sync with the paired computer, text only. A copy on the computer is put on this phone's clipboard (Android lets any app write it).
 * A copy made on the phone is sent while Faze can read it, which Android allows only while the app is in front, so [changed] is called
 * when the clipboard changes and again when the app comes to the front. A copy that just came from the other side is never sent back.
 */
class PhoneClip(private val enabled: () -> Boolean, private val write: (String) -> Unit, private val send: (String) -> Unit) {
    companion object { const val MAX = 64 * 1024 }

    private var last: String? = null

    /** A notice from the computer. Never answered. */
    fun handle(msg: JSONObject): JSONObject? {
        if (msg.optString("t") != "clip") return null
        val text = msg.optString("text")
        if (!enabled() || text.isEmpty() || text == last || text.toByteArray().size > MAX) return null
        last = text // before writing: the clipboard listener will see it and must not send it back
        write(text)
        return null
    }

    /** The phone's own clipboard now holds [text] (null when it could not be read). */
    fun changed(text: String?) {
        if (!enabled() || text.isNullOrEmpty() || text == last || text.toByteArray().size > MAX) return
        last = text
        send(text)
    }
}
