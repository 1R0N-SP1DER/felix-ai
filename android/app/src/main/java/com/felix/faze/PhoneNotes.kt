package com.felix.faze

import org.json.JSONObject

/**
 * Sends this phone's notifications to a connected computer, so they show up there too. Nothing is sent unless the person switched it on and a computer
 * is connected right now. Ongoing ones (music, navigation, Faze's own) and group summaries are skipped. With "hide the text" on, only the app name goes.
 */
class PhoneNotes(
    private val enabled: () -> Boolean,
    private val hideText: () -> Boolean,
    private val send: (JSONObject) -> Unit,
    private val ownPackage: String,
) {
    class Note(val key: String, val pkg: String, val app: String, val title: String, val text: String, val ongoing: Boolean, val summary: Boolean, val time: Long)

    companion object {
        const val MAX = 400

        fun worthSending(n: Note, own: String): Boolean =
            !n.ongoing && !n.summary && n.pkg != own && (n.title.isNotBlank() || n.text.isNotBlank())

        fun clip(s: String) = if (s.length <= MAX) s else s.take(MAX - 1) + "…"
    }

    fun posted(n: Note) {
        if (!enabled() || !worthSending(n, ownPackage)) return
        val hide = hideText()
        send(JSONObject().put("t", "note").put("id", n.key).put("pkg", n.pkg).put("app", n.app)
            .put("title", if (hide) "" else clip(n.title)).put("text", if (hide) "New notification" else clip(n.text)).put("time", n.time))
    }

    fun removed(key: String) {
        if (!enabled()) return
        send(JSONObject().put("t", "note_gone").put("id", key))
    }
}
