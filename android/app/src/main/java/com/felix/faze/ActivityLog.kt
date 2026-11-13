package com.felix.faze

import java.io.File
import org.json.JSONObject

/** One line of the activity log: when, what kind (tool, wake, scheduled), and what happened. */
data class ActivityEntry(val time: Long, val kind: String, val text: String)

/**
 * A short history of what Faze did: tools it ran (and whether they worked or were declined), how it was woken, scheduled tasks that fired.
 * Kept on the phone only, newest 300 entries, one JSON line each. What was typed into other apps is never written down.
 */
class ActivityLog(private val file: File?, private val clock: () -> Long = { System.currentTimeMillis() }, private val max: Int = 300) {
    private val items = ArrayList<ActivityEntry>()
    private var loaded = false

    @Synchronized private fun load() {
        if (loaded) return
        loaded = true
        try {
            file?.takeIf { it.exists() }?.forEachLine { line ->
                try { val o = JSONObject(line); items.add(ActivityEntry(o.getLong("t"), o.getString("k"), o.getString("x"))) } catch (e: Exception) { /* a damaged line is skipped */ }
            }
        } catch (e: Exception) {
            // an unreadable log starts again empty
        }
        while (items.size > max) items.removeAt(0)
    }

    @Synchronized fun add(kind: String, text: String) {
        load()
        val e = ActivityEntry(clock(), kind, text.replace('\n', ' ').take(300))
        items.add(e)
        while (items.size > max) items.removeAt(0)
        try {
            file?.parentFile?.mkdirs()
            if (items.size == max) save() else file?.appendText(JSONObject().put("t", e.time).put("k", e.kind).put("x", e.text).toString() + "\n")
        } catch (ex: Exception) {
            // the log is best effort
        }
    }

    /** Newest first. */
    @Synchronized fun entries(): List<ActivityEntry> { load(); return items.reversed() }

    @Synchronized fun clear() {
        load()
        items.clear()
        try { file?.delete() } catch (e: Exception) { /* nothing to remove */ }
    }

    private fun save() {
        file?.writeText(items.joinToString("") { JSONObject().put("t", it.time).put("k", it.kind).put("x", it.text).toString() + "\n" })
    }

    companion object {
        /** "tool(args) -> outcome", with anything typed into another app, or secret, left out. */
        fun summary(name: String, args: JSONObject, result: ToolResult): String {
            val shown = when (name) {
                "type_text" -> "${args.optString("text", "").length} characters"
                "remember" -> "a note"
                else -> args.toString().take(80).let { if (it == "{}") "" else it }
            }
            val outcome = when {
                result.ok -> "done"
                result.text.startsWith(PermissionPolicy.DECLINED) -> "declined"
                else -> "failed: " + result.text.take(80)
            }
            return "$name" + (if (shown.isEmpty()) "" else "($shown)") + " -> $outcome"
        }

        fun label(kind: String): String = when (kind) {
            "tool" -> "Tool"
            "wake" -> "Woken"
            "scheduled" -> "Scheduled"
            else -> kind.replaceFirstChar { it.uppercase() }
        }
    }
}
