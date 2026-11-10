package com.felix.faze

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A request to run later: [prompt] is handed to Faze as if the person had typed it. [everyMinutes] is 0 for a one-off. */
data class ScheduledTask(val id: String, val prompt: String, val nextAt: Long, val everyMinutes: Int, val createdAt: Long)

/** The tools' view of the scheduler. The real one also sets the phone alarm. */
interface SchedulerApi {
    fun add(prompt: String, atMillis: Long, everyMinutes: Int): ScheduledTask
    fun list(): List<ScheduledTask>
    fun cancel(id: String): Boolean
    fun now(): Long
    fun zone(): ZoneId
}

object ScheduleTime {
    const val MIN_REPEAT = 15
    const val MAX_AHEAD_MINUTES = 366 * 24 * 60

    /** "in N minutes" or a clock time "HH:mm" (24 hour; the next time it is that time). Exactly one must be given. */
    fun resolve(inMinutes: Int?, at: String?, now: Long, zone: ZoneId): Long {
        val hasAt = !at.isNullOrBlank()
        if ((inMinutes != null) == hasAt) throw ToolFailure("give either in_minutes or at (HH:mm), not both and not neither")
        if (inMinutes != null) {
            if (inMinutes < 1 || inMinutes > MAX_AHEAD_MINUTES) throw ToolFailure("in_minutes must be between 1 and $MAX_AHEAD_MINUTES")
            return now + inMinutes * 60_000L
        }
        val time = try { LocalTime.parse(at!!.trim().padStart(5, '0')) } catch (e: Exception) { throw ToolFailure("at must look like 07:30 (24 hour clock)") }
        val today = LocalDateTime.of(Instant.ofEpochMilli(now).atZone(zone).toLocalDate(), time)
        var t = today.atZone(zone).toInstant().toEpochMilli()
        if (t <= now) t = today.plusDays(1).atZone(zone).toInstant().toEpochMilli()
        return t
    }

    fun repeat(minutes: Int?): Int {
        val m = minutes ?: 0
        if (m == 0) return 0
        if (m < MIN_REPEAT) throw ToolFailure("repeat_minutes must be 0 (once) or at least $MIN_REPEAT")
        if (m > 7 * 24 * 60) throw ToolFailure("repeat_minutes can be at most one week (10080)")
        return m
    }

    /** The next run after [now] for a repeating task whose last slot was [from]: skips any slots that were missed. */
    fun advance(from: Long, everyMinutes: Int, now: Long): Long {
        val step = everyMinutes * 60_000L
        if (now < from) return from
        return from + ((now - from) / step + 1) * step
    }

    fun describe(t: Long, now: Long, zone: ZoneId): String {
        val at = Instant.ofEpochMilli(t).atZone(zone)
        val mins = ((t - now) / 60_000L).coerceAtLeast(0)
        val fmt = if (at.toLocalDate() == Instant.ofEpochMilli(now).atZone(zone).toLocalDate()) DateTimeFormatter.ofPattern("HH:mm") else DateTimeFormatter.ofPattern("EEE d MMM HH:mm")
        val left = if (mins >= 1440) "${mins / 1440}d ${mins % 1440 / 60}h" else if (mins >= 60) "${mins / 60}h ${mins % 60}m" else "${mins}m"
        return "${at.format(fmt)} (in $left)"
    }

    fun everyText(m: Int): String = when {
        m == 0 -> "once"
        m == 60 -> "every hour"
        m == 1440 -> "every day"
        m % 1440 == 0 -> "every ${m / 1440} days"
        m % 60 == 0 -> "every ${m / 60} hours"
        else -> "every $m minutes"
    }
}

/** The saved schedule: a small JSON file. Plain logic (the phone alarm lives elsewhere). */
class ScheduleStore(private val file: File, private val clock: () -> Long = { System.currentTimeMillis() }) {
    companion object {
        const val MAX_TASKS = 20
        const val MAX_PROMPT = 300
    }

    private var items: MutableList<ScheduledTask> = load()

    private fun load(): MutableList<ScheduledTask> = try {
        val arr = JSONArray(file.readText())
        MutableList(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            ScheduledTask(o.getString("id"), o.getString("prompt"), o.getLong("nextAt"), o.optInt("every", 0), o.optLong("createdAt", 0))
        }
    } catch (e: Exception) { mutableListOf() }

    private fun save() {
        val arr = JSONArray()
        for (t in items) arr.put(JSONObject().put("id", t.id).put("prompt", t.prompt).put("nextAt", t.nextAt).put("every", t.everyMinutes).put("createdAt", t.createdAt))
        file.writeText(arr.toString())
    }

    @Synchronized fun list(): List<ScheduledTask> = items.sortedBy { it.nextAt }

    @Synchronized fun add(prompt: String, atMillis: Long, every: Int): ScheduledTask {
        val p = prompt.trim()
        if (p.isEmpty()) throw ToolFailure("say what to do when it is time")
        if (p.length > MAX_PROMPT) throw ToolFailure("keep the task under $MAX_PROMPT characters")
        if (items.size >= MAX_TASKS) throw ToolFailure("there are already $MAX_TASKS scheduled tasks; cancel one first")
        var n = 1
        while (items.any { it.id == "t$n" }) n++
        val t = ScheduledTask("t$n", p, atMillis, every, clock())
        items.add(t)
        save()
        return t
    }

    @Synchronized fun cancel(id: String): Boolean {
        val removed = items.removeAll { it.id == id.trim().lowercase() }
        if (removed) save()
        return removed
    }

    @Synchronized fun clear() { items.clear(); save() }

    @Synchronized fun next(): ScheduledTask? = items.minByOrNull { it.nextAt }

    /** Tasks whose time has come. Each is moved on (repeating) or removed (one-off) at once, so a task never runs twice. */
    @Synchronized fun takeDue(now: Long): List<ScheduledTask> {
        val due = items.filter { it.nextAt <= now }
        if (due.isEmpty()) return emptyList()
        for (t in due) {
            items.remove(t)
            if (t.everyMinutes > 0) items.add(t.copy(nextAt = ScheduleTime.advance(t.nextAt, t.everyMinutes, now)))
        }
        save()
        return due.sortedBy { it.nextAt }
    }
}
