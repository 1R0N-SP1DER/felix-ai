package com.felix.faze

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** A tool call found without asking the language model. [source] is "built-in", "learned" or "needle". */
data class Route(val tool: String, val args: JSONObject, val source: String, val learnedId: Int = -1)

/** Turns a spoken request into plain comparable words: lower case, no punctuation, no "hey faze" or "please", no little words. */
object Phrase {
    private val starts = listOf("hey faze", "hi faze", "ok faze", "okay faze", "hey", "faze", "okay", "ok", "please", "kindly", "can you", "could you", "would you", "will you", "i want you to", "i need you to", "go ahead and")
    private val ends = listOf("right now", "for me", "thank you", "thanks", "please", "now")
    private val little = setOf("the", "a", "an", "my", "me", "for", "some", "just", "please")

    fun clean(text: String): String {
        var t = text.lowercase().replace("'", "").replace("’", "").replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
        var changed = true
        while (changed) {
            changed = false
            for (s in starts) if (t.startsWith("$s ")) { t = t.removePrefix("$s ").trim(); changed = true }
            for (e in ends) if (t.endsWith(" $e")) { t = t.removeSuffix(" $e").trim(); changed = true }
        }
        return t
    }

    fun tokens(text: String): List<String> = clean(text).split(' ').filter { it.isNotEmpty() && it !in little }
}

/** The requests that are always the same tool: the Kotlin side of core/direct_tool_router.py. */
object BuiltInRoutes {
    private val writeClipboard = Regex("""(?:copy|put|save|write)\s+["“](.+?)["”]\s+(?:to|in|into)\s+(?:my |the )?clipboard""", RegexOption.IGNORE_CASE)
    private val time = Regex("^(what time is it|whats time|what is time|time now|current time|whats current time|date and time|current date and time|todays date|whats todays date|what is todays date)$")
    private val readClipboard = Regex("^(whats|what is) (on|in) clipboard$|^read clipboard$|^whats clipboard$|^what is clipboard$")
    private val volume = Regex("^(set |turn )?volume (to )?(\\d{1,2})$")
    private val notToday = Regex(
        "\\b(tomorrow|yesterday|next|last|week|weekend|month|forecast for|monday|tuesday|wednesday|thursday|friday|saturday|sunday|why|climate|average|usually|history|app|widget|change|and|then|also|after)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val city = Regex(
        "\\b(?:in|for|at)\\s+(?!today\\b|tonight\\b|the\\b|my\\b|now\\b|me\\b|this\\b)([A-Za-z][A-Za-z .'-]{1,40}?)(?=\\s*(?:today|tonight|right now|now|currently|outside|this\\b|\\?|!|\\.|,|$))",
        RegexOption.IGNORE_CASE,
    )

    fun route(message: String): Route? {
        val text = message.trim()
        if (text.length <= 90 && Regex("\\bweather\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) && !notToday.containsMatchIn(text)) {
            return Route("get_weather", JSONObject().put("city", city.find(text)?.groupValues?.get(1)?.trim().orEmpty()), "built-in")
        }
        writeClipboard.find(text)?.let { return Route("write_clipboard", JSONObject().put("text", it.groupValues[1]), "built-in") }
        val tokens = Phrase.tokens(text)
        val joined = tokens.joinToString(" ")
        volume.find(joined)?.let { return Route("set_volume", JSONObject().put("level", it.groupValues[3].toInt()), "built-in") }
        return when {
            "vibrate" in tokens && tokens.size <= 5 -> Route("vibrate", JSONObject(), "built-in")
            "battery" in tokens && tokens.size <= 6 -> Route("get_battery_status", JSONObject(), "built-in")
            time.matches(joined) -> Route("get_current_datetime", JSONObject(), "built-in")
            readClipboard.matches(joined) -> Route("read_clipboard", JSONObject(), "built-in")
            else -> null
        }
    }
}

/**
 * Learning a phrase from one success: the words of what you said become a template, with the tool's arguments as slots
 * ("open spotify" -> "open {app}"). Later requests that fit the template run the tool straight away.
 */
object Templates {
    const val MAX_TOKENS = 8
    private val joiners = setOf("and", "then", "also", "after", "before", "while", "but", "or")

    data class Made(val template: List<String>, val empties: List<String>)

    private fun find(tokens: List<String>, part: List<String>, used: BooleanArray): Int? {
        for (i in 0..tokens.size - part.size) {
            if ((0 until part.size).all { tokens[i + it] == part[it] && !used[i + it] }) return i
        }
        return null
    }

    fun make(message: String, args: JSONObject): Made? {
        val tokens = Phrase.tokens(message)
        if (tokens.isEmpty() || tokens.size > MAX_TOKENS) return null
        val used = BooleanArray(tokens.size)
        val empties = ArrayList<String>()
        val slots = ArrayList<Triple<Int, Int, String>>()
        for (k in args.keys()) {
            val v = args.get(k)
            val text = when (v) {
                is String -> v
                is Int, is Long -> v.toString()
                is Double -> if (v == Math.floor(v)) v.toLong().toString() else return null
                else -> return null
            }
            val vt = Phrase.tokens(text)
            if (vt.isEmpty()) { if (v is String) { empties.add(k); continue } else return null }
            if (vt.size > 4 || vt.any { it in joiners }) return null
            val at = find(tokens, vt, used) ?: return null
            for (i in at until at + vt.size) used[i] = true
            slots.add(Triple(at, vt.size, if (v is String) "{$k}" else "{#$k}"))
        }
        if (slots.size > 2) return null
        slots.sortBy { it.first }
        val template = ArrayList<String>()
        var i = 0
        while (i < tokens.size) {
            val s = slots.firstOrNull { it.first == i }
            if (s != null) { template.add(s.third); i += s.second } else { template.add(tokens[i]); i++ }
        }
        for (j in 1 until template.size) if (template[j].startsWith("{") && template[j - 1].startsWith("{")) return null // two slots side by side cannot be told apart
        if (template.none { !it.startsWith("{") && it.length >= 3 }) return null
        return Made(template, empties.sorted())
    }

    /** The slot values if [tokens] fit [template] exactly, otherwise null. */
    fun match(template: List<String>, tokens: List<String>): Map<String, String>? {
        val caps = LinkedHashMap<String, String>()
        fun go(ti: Int, mi: Int): Boolean {
            if (ti == template.size) return mi == tokens.size
            val t = template[ti]
            if (!t.startsWith("{")) return mi < tokens.size && tokens[mi] == t && go(ti + 1, mi + 1)
            val name = t.removePrefix("{").removePrefix("#").removeSuffix("}")
            if (t.startsWith("{#")) {
                if (mi < tokens.size && tokens[mi].length <= 3 && tokens[mi].all { it.isDigit() }) {
                    caps[name] = tokens[mi]
                    if (go(ti + 1, mi + 1)) return true
                    caps.remove(name)
                }
                return false
            }
            for (len in 1..4) {
                if (mi + len > tokens.size) break
                val part = tokens.subList(mi, mi + len)
                if (part.any { it in joiners }) break
                caps[name] = part.joinToString(" ")
                if (go(ti + 1, mi + len)) return true
            }
            caps.remove(name)
            return false
        }
        return if (go(0, 0)) caps else null
    }

    fun argsFor(template: List<String>, empties: List<String>, caps: Map<String, String>): JSONObject {
        val out = JSONObject()
        for (t in template) {
            if (!t.startsWith("{")) continue
            val name = t.removePrefix("{").removePrefix("#").removeSuffix("}")
            if (t.startsWith("{#")) out.put(name, caps.getValue(name).toInt()) else out.put(name, caps.getValue(name))
        }
        for (e in empties) out.put(e, "")
        return out
    }
}

/** The shortcuts learned so far: one small file in the app's private folder. Only words of the phrases are kept, never replies. */
class LearnedRoutes(private val file: File, private val now: () -> Long = { System.currentTimeMillis() }) {
    data class Item(val id: Int, val tool: String, val template: List<String>, val empties: List<String>, val hits: Int, val fails: Int, val created: Long)

    companion object { const val MAX = 200; const val MAX_FAILS = 2 }

    @Synchronized
    fun list(): List<Item> {
        if (!file.isFile) return emptyList()
        return try {
            val a = JSONArray(file.readText())
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                fun strings(k: String) = o.optJSONArray(k)?.let { arr -> (0 until arr.length()).map { i -> arr.getString(i) } } ?: emptyList()
                Item(o.getInt("id"), o.getString("tool"), strings("template"), strings("empties"), o.optInt("hits", 0), o.optInt("fails", 0), o.optLong("created", 0))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun write(items: List<Item>) {
        file.parentFile?.mkdirs()
        val a = JSONArray()
        for (t in items) a.put(JSONObject().put("id", t.id).put("tool", t.tool).put("template", JSONArray(t.template)).put("empties", JSONArray(t.empties)).put("hits", t.hits).put("fails", t.fails).put("created", t.created))
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(a.toString())
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    /** True if a new shortcut was saved. The same phrase for another tool is ambiguous, so both are dropped. */
    @Synchronized
    fun add(tool: String, template: List<String>, empties: List<String>): Boolean {
        var items = list()
        val same = items.filter { it.template == template }
        if (same.any { it.tool == tool }) return false
        if (same.isNotEmpty()) { write(items.filter { it.template != template }); return false }
        if (items.size >= MAX) items = items.sortedWith(compareBy<Item>({ it.hits }, { it.created })).drop(1)
        write(items + Item((items.maxOfOrNull { it.id } ?: 0) + 1, tool, template, empties, 0, 0, now()))
        return true
    }

    @Synchronized fun hit(id: Int) { write(list().map { if (it.id == id) it.copy(hits = it.hits + 1, fails = 0) else it }) }

    /** A shortcut whose tool keeps failing is forgotten. */
    @Synchronized fun fail(id: Int) { write(list().map { if (it.id == id) it.copy(fails = it.fails + 1) else it }.filter { it.fails < MAX_FAILS }) }

    @Synchronized fun remove(id: Int) { write(list().filter { it.id != id }) }

    @Synchronized fun clear() { file.delete() }

    fun size() = list().size
}

/** Asks the Needle tool-calling model (a small server run in Termux) which tool a request means. Quietly gives nothing when it is not running. */
class NeedleClient(
    private val transport: Transport,
    private val url: String = "http://127.0.0.1:8765/complete",
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    @Volatile private var downUntil = 0L

    fun ask(message: String, schema: JSONArray): Route? {
        if (message.length > 160 || clock() < downUntil) return null
        val res = try {
            transport.post(url, emptyMap(), JSONObject().put("input", message).toString().toByteArray(), "application/json", 2500)
        } catch (e: Exception) {
            downUntil = clock() + 60_000 // not running: do not wait for it on every message
            return null
        }
        if (res.status != 200) { downUntil = clock() + 60_000; return null }
        return parse(res.body, schema)
    }

    companion object {
        const val MIN_CONFIDENCE = 0.5

        /** The same trust rules as core/needle_router.py (one call, confident) plus: the tool and its arguments must exist in [schema]. */
        fun parse(body: String, schema: JSONArray): Route? {
            val d = try { JSONObject(body) } catch (e: Exception) { return null }
            if (d.optString("type") != "call" || !d.optBoolean("success", false) || d.optDouble("confidence", 0.0) < MIN_CONFIDENCE) return null
            val calls = d.optJSONArray("function_calls") ?: return null
            if (calls.length() != 1) return null
            val c = calls.optJSONObject(0) ?: return null
            val name = c.optString("name")
            val args = c.optJSONObject("arguments") ?: JSONObject()
            return if (fits(name, args, schema)) Route(name, args, "needle") else null
        }

        fun fits(name: String, args: JSONObject, schema: JSONArray): Boolean {
            for (i in 0 until schema.length()) {
                val f = schema.optJSONObject(i)?.optJSONObject("function") ?: continue
                if (f.optString("name") != name) continue
                val p = f.optJSONObject("parameters")
                val props = p?.optJSONObject("properties")
                val required = p?.optJSONArray("required")
                for (k in args.keys()) if (props == null || !props.has(k)) return false
                for (j in 0 until (required?.length() ?: 0)) if (!args.has(required!!.getString(j))) return false
                return true
            }
            return false
        }
    }
}

/**
 * Finds the tool for a request without the language model, in this order: built-in rules, shortcuts learned from earlier successes,
 * then Needle. The model is then only needed to say what was done. Learns from tool calls the model made itself.
 */
class FastRoute(
    private val learned: LearnedRoutes,
    private val needle: NeedleClient?,
    private val enabled: () -> Boolean,
    private val needleOn: () -> Boolean = { true },
    private val canLearn: (String) -> Boolean = { false },
) {
    companion object {
        /** Only tools whose arguments are plain words or numbers, and that are safe to run again from a phrase. */
        val LEARNABLE = setOf("vibrate", "get_battery_status", "get_current_datetime", "get_wifi_status", "set_volume", "get_weather", "open_app", "search_web", "list_apps")
        private val urlish = Regex("(https?:|www\\.|\\.[a-z]{2,}\\b)", RegexOption.IGNORE_CASE)
    }

    private fun names(schema: JSONArray): Set<String> =
        (0 until schema.length()).mapNotNull { schema.optJSONObject(it)?.optJSONObject("function")?.optString("name") }.toSet()

    fun route(message: String, schema: JSONArray): Route? {
        if (!enabled() || message.isBlank()) return null
        val have = names(schema)
        BuiltInRoutes.route(message)?.let { if (it.tool in have) return it }
        learnedRoute(message, have)?.let { return it }
        if (needleOn()) needle?.ask(message, schema)?.let { return it }
        return null
    }

    private fun learnedRoute(message: String, have: Set<String>): Route? {
        if (urlish.containsMatchIn(message)) return null // a web address is not an app name or a search
        val tokens = Phrase.tokens(message)
        if (tokens.isEmpty()) return null
        for (item in learned.list().sortedByDescending { it.template.count { t -> !t.startsWith("{") } }) {
            if (item.tool !in have) continue
            val caps = Templates.match(item.template, tokens) ?: continue
            return Route(item.tool, Templates.argsFor(item.template, item.empties, caps), "learned", item.id)
        }
        return null
    }

    /** Called with how a routed call went, so a shortcut that works is counted and one that keeps failing is dropped. */
    fun outcome(route: Route, ok: Boolean) {
        if (route.source != "learned") return
        if (ok) learned.hit(route.learnedId) else learned.fail(route.learnedId)
    }

    /** A tool call that worked after you asked for it in words: remember the words. */
    fun learn(message: String, tool: String, args: JSONObject) {
        if (!enabled() || tool !in LEARNABLE || !canLearn(tool) || urlish.containsMatchIn(message)) return
        if (BuiltInRoutes.route(message) != null) return
        val made = Templates.make(message, args) ?: return
        learned.add(tool, made.template, made.empties)
    }
}
