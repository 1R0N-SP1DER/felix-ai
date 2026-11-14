package com.felix.faze

import java.io.File
import org.json.JSONArray

enum class Task { CASUAL, CODING, DEEP }

/** Auto mode: which kind of request this is, which models suit it, and which providers are out of allowance right now. */
object AutoMode {
    private val coding = Regex(
        "```|\\b(code|coding|function|bug|debug|error|exception|stack ?trace|compile|compiler|python|kotlin|java|javascript|typescript|rust|c\\+\\+|sql|regex|script|api|json|refactor|implement|algorithm|class|git|android|gradle|html|css|syntax|unit test|variable)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val deep = Regex(
        "\\b(analy[sz]e|analysis|compare|explain in detail|in depth|step by step|essay|research|strategy|prove|derive|pros and cons|think carefully|break down|design a|plan for)\\b",
        RegexOption.IGNORE_CASE,
    )

    fun kindOf(text: String, effort: String = "medium"): Task = when {
        coding.containsMatchIn(text) -> Task.CODING
        text.length > 500 || deep.containsMatchIn(text) || effort == "high" || effort == "extra" || effort == "max" -> Task.DEEP
        else -> Task.CASUAL
    }

    /** The newest thing the person said, from the messages about to be sent. */
    fun lastUserText(messages: JSONArray): String {
        for (i in messages.length() - 1 downTo 0) {
            val m = messages.optJSONObject(i) ?: continue
            if (m.optString("role") != "user") continue
            val c = m.opt("content")
            if (c is String) return c
            if (c is JSONArray) {
                val sb = StringBuilder()
                for (k in 0 until c.length()) c.optJSONObject(k)?.optString("text")?.let { sb.append(it).append(' ') }
                return sb.toString()
            }
        }
        return ""
    }

    /** Offline model files by job: a chosen file wins, then one named for the job (LFM for chat, Qwen for the rest), then the single offline model. */
    fun offlineFor(task: Task, casualPick: String, deepPick: String, scanned: List<String>, fallback: String): String {
        val pick = if (task == Task.CASUAL) casualPick else deepPick
        if (pick.isNotEmpty()) return pick
        fun named(vararg words: String) = scanned.firstOrNull { p -> words.any { File(p).name.contains(it, ignoreCase = true) } }
        val found = if (task == Task.CASUAL) named("lfm") else (if (task == Task.CODING) named("coder", "qwen") else named("qwen"))
        return found ?: fallback
    }

    private val casualOrder = listOf("groq", "openrouter", "gemini", "nvidia", "custom")
    private val heavyOrder = listOf("gemini", "groq", "openrouter", "nvidia", "custom")

    /**
     * What to try, in order. Online: chat prefers Groq and OpenRouter, code and deep work prefers Gemini, and a provider that is out of
     * allowance goes to the back, so a limit never stops a task. The offline model is the last resort. Offline: LFM for chat, Qwen for the rest.
     */
    fun plan(
        task: Task,
        online: Boolean,
        keys: Set<String>,
        resting: (String) -> Boolean,
        model: (String) -> String,
        casualModel: String,
        deepModel: String,
    ): List<Attempt> {
        val offline = ArrayList<Attempt>()
        val first = if (task == Task.CASUAL) casualModel else deepModel
        val second = if (task == Task.CASUAL) deepModel else casualModel
        for (p in listOf(first, second)) if (p.isNotEmpty() && offline.none { it.model == p }) offline.add(Attempt("offline", p))
        val order = if (task == Task.CASUAL) casualOrder else heavyOrder
        val usable = order.filter { it in keys }
        val ready = usable.filter { !resting(it) }.map { Attempt(it, model(it)) }
        val tired = usable.filter { resting(it) }.map { Attempt(it, model(it)) }
        return if (online) ready + offline + tired else offline + ready + tired
    }
}

/** Remembers which providers hit their limit, and until when. */
class ProviderLimits(private val now: () -> Long = { System.currentTimeMillis() }) {
    private val until = HashMap<String, Long>()

    @Synchronized fun rest(provider: String, ms: Long) { until[provider] = maxOf(until[provider] ?: 0L, now() + ms) }

    @Synchronized fun isResting(provider: String): Boolean = (until[provider] ?: 0L) > now()

    /** A rate limit rests for the time the server gave (at least a minute); a daily quota for an hour; a refused key for ten minutes. */
    fun note(provider: String, e: RemoteError) {
        val text = (e.message ?: "").lowercase()
        when (e.kind) {
            "rate_limit" -> {
                val given = (e.retryAfter * 1000).toLong()
                rest(provider, if (text.contains("quota") || text.contains("daily") || given > 600_000L) maxOf(given, 3_600_000L) else maxOf(given, 60_000L))
            }
            "auth" -> rest(provider, 600_000L)
        }
    }
}
