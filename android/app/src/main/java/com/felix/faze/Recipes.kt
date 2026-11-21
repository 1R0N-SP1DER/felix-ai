package com.felix.faze

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** One tool call of a reply, as it ran: the name, its arguments (as JSON text) and whether it worked. */
data class Step(val name: String, val args: String, val ok: Boolean) {
    fun argsJson(): JSONObject = try { JSONObject(args) } catch (e: Exception) { JSONObject() }

    companion object {
        fun toJson(steps: List<Step>): JSONArray = JSONArray().also { a -> for (s in steps) a.put(JSONObject().put("name", s.name).put("args", s.args).put("ok", s.ok)) }
        fun fromJson(a: JSONArray?): List<Step> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { Step(it.optString("name"), it.optString("args", "{}"), it.optBoolean("ok", true)) } }.filter { it.name.isNotEmpty() }
    }
}

/**
 * What a thumbs up teaches: this exact request (its words, in any order, without the little ones) leads to these tool steps. Used only offline, so the
 * small offline model never has to be loaded to think about it: the steps run again and the answer is the saved one (or the tools' own text).
 * Only steps that are safe to run again from words are kept; anything the person must approve is still asked about when it runs.
 */
class RecipeBook(private val file: File, private val now: () -> Long = { System.currentTimeMillis() }) {
    data class Recipe(val key: String, val phrase: String, val steps: List<Step>, val say: String, val silent: Boolean, val hits: Int, val fails: Int, val created: Long)

    companion object {
        const val MAX = 100
        const val MAX_STEPS = 5
        const val MAX_FAILS = 2

        /** Tools whose steps may be learned: they do the same thing every time they are asked the same way. */
        val REPEATABLE = FastRoute.LEARNABLE + setOf("read_clipboard", "write_clipboard", "open_url", "set_wifi", "set_bluetooth", "set_mobile_data", "end_live")

        /** Tools that only do something and have nothing to report, so the saved reply is as good as a new one. */
        val SILENT = setOf("open_app", "set_volume", "vibrate", "open_url", "write_clipboard", "set_wifi", "set_bluetooth", "set_mobile_data", "end_live")

        /** The same request in a different word order or with filler words gives the same key. */
        fun keyOf(request: String): String = Phrase.tokens(request).sorted().joinToString(" ")

        fun canLearn(steps: List<Step>) = steps.isNotEmpty() && steps.size <= MAX_STEPS && steps.all { it.ok && it.name in REPEATABLE }
    }

    @Synchronized
    fun list(): List<Recipe> {
        if (!file.isFile) return emptyList()
        return try {
            val a = JSONArray(file.readText())
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                Recipe(o.getString("key"), o.optString("phrase"), Step.fromJson(o.optJSONArray("steps")), o.optString("say"), o.optBoolean("silent", false), o.optInt("hits", 0), o.optInt("fails", 0), o.optLong("created", 0))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun write(items: List<Recipe>) {
        file.parentFile?.mkdirs()
        val a = JSONArray()
        for (r in items) a.put(JSONObject().put("key", r.key).put("phrase", r.phrase).put("steps", Step.toJson(r.steps)).put("say", r.say).put("silent", r.silent).put("hits", r.hits).put("fails", r.fails).put("created", r.created))
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(a.toString())
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    /** The recipe for this request, if one was taught and every tool in it still exists. */
    fun find(request: String, have: Set<String>): Recipe? {
        val key = keyOf(request)
        if (key.isEmpty()) return null
        return list().firstOrNull { it.key == key && it.steps.all { s -> s.name in have } }
    }

    /** True if saved. A newer success for the same request replaces the older one. */
    @Synchronized
    fun save(request: String, steps: List<Step>, say: String): Boolean {
        val key = keyOf(request)
        if (key.isEmpty() || !canLearn(steps)) return false
        var items = list().filter { it.key != key }
        if (items.size >= MAX) items = items.sortedWith(compareBy<Recipe>({ it.hits }, { it.created })).drop(1)
        val silent = steps.all { it.name in SILENT }
        write(items + Recipe(key, request.trim().take(120), steps, say.take(600), silent, 0, 0, now()))
        return true
    }

    @Synchronized fun hit(key: String) { write(list().map { if (it.key == key) it.copy(hits = it.hits + 1, fails = 0) else it }) }

    /** A recipe that keeps failing is forgotten. */
    @Synchronized fun fail(key: String) { write(list().map { if (it.key == key) it.copy(fails = it.fails + 1) else it }.filter { it.fails < MAX_FAILS }) }

    @Synchronized fun remove(request: String) { val key = keyOf(request); write(list().filter { it.key != key }) }

    @Synchronized fun clear() { file.delete() }

    fun size() = list().size
}

/** What a thumbs up or down does, kept apart from the screen so it can be tested. */
object Feedback {
    interface Learner {
        fun learnTemplate(request: String, tool: String, args: JSONObject)
        fun saveRecipe(request: String, steps: List<Step>, say: String): Boolean
        fun forgetRecipe(request: String)
        fun confirmRecipe(request: String)
        fun forgetShortcut(id: Int)
        fun confirmShortcut(id: Int)
    }

    /** The new rating for the message (same tap again takes it back) and a short line to show. */
    data class Outcome(val feedback: Int, val note: String)

    fun rate(m: ChatMessage, request: String, tap: Int, learner: Learner): Outcome {
        if (m.feedback == tap) return Outcome(0, "")
        if (tap > 0) {
            val chain = m.chain
            val note = when {
                m.via == "recipe" -> { learner.confirmRecipe(request); "Got it. I'll keep doing it this way." }
                m.via == "learned" -> { learner.confirmShortcut(m.viaId); "Got it. I'll keep doing it this way." }
                m.via == "built-in" -> "That one is already built in."
                chain.isEmpty() -> "Thanks. There was nothing to learn from a plain answer."
                chain.size == 1 && chain[0].ok && chain[0].name in FastRoute.LEARNABLE && m.via != "needle" -> {
                    learner.learnTemplate(request, chain[0].name, chain[0].argsJson())
                    "Learned. Offline, I'll do this without thinking."
                }
                learner.saveRecipe(request, chain, m.text) -> "Learned. Offline, I'll do these steps without thinking."
                else -> "Thanks. Those steps can't be repeated safely from words, so I didn't save them."
            }
            return Outcome(1, note)
        }
        if (m.via == "recipe") learner.forgetRecipe(request)
        if (m.via == "learned") learner.forgetShortcut(m.viaId)
        return Outcome(-1, "Noted. Tap retry and I'll try another way.")
    }
}
