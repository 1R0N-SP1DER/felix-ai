package com.felix.faze

/** Wake phrase matching and the endpoint of a spoken request. No Android in here, so it runs in plain JVM tests. */
object Wake {
    fun normalize(text: String): String = text.lowercase().replace(Regex("[^a-z0-9 ]+"), " ").trim().replace(Regex("\\s+"), " ")

    /** The phrase to listen for: "hey faze" unless the person set their own. */
    fun phrase(configured: String, assistant: String = "Faze"): String = normalize(configured).ifEmpty { normalize("hey $assistant") }

    /** What the recognizer may hear: the full phrase, plus the last word alone ("faze") so a short call also works. */
    fun accepted(phrase: String): List<String> {
        val p = normalize(phrase)
        val last = p.substringAfterLast(' ')
        return if (last.length >= 3 && last != p) listOf(p, last) else listOf(p)
    }

    fun grammarJson(phrase: String): String =
        (accepted(phrase) + "[unk]").joinToString(prefix = "[", postfix = "]") { "\"" + it.replace("\"", "") + "\"" }

    private fun distance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }

    /** True when what was heard is (close to) one of the accepted forms. Close = at most one edit per five letters. */
    fun matches(heard: String, phrase: String): Boolean {
        val h = normalize(heard)
        if (h.isEmpty() || h == "[unk]") return false
        for (form in accepted(phrase)) {
            if (h == form) return true
            if (distance(h, form) <= form.length / 5) return true
        }
        return false
    }
}

/** Ignores wake calls for a while after one fired (and while the assistant is busy or speaking). */
class Cooldown(private val seconds: Int, private val now: () -> Long = { System.currentTimeMillis() }) {
    private var until = 0L
    fun fire() { until = now() + seconds * 1000L }
    fun ready(): Boolean = now() >= until
}

enum class Heard { WAITING, SPEAKING, SPEECH_DONE, NOTHING }

/**
 * Cuts one request out of a stream of 16 kHz mono frames by loudness. It learns the room's noise level from the first
 * frames, so a quiet room and a noisy one both work. [feed] returns SPEECH_DONE once the person stopped talking, or
 * NOTHING if they never started (or kept going longer than [maxMs]).
 */
class Endpointer(
    private val sampleRate: Int = 16000,
    private val endSilenceMs: Int = 700,
    private val minSpeechMs: Int = 250,
    private val noSpeechMs: Int = 5000,
    private val maxMs: Int = 15000,
) {
    private var elapsedMs = 0.0
    private var speechMs = 0.0
    private var silenceMs = 0.0
    private var floor = -1.0
    private var calibrationMs = 0.0
    private var spoke = false

    fun rms(frame: ShortArray, length: Int = frame.size): Double {
        if (length == 0) return 0.0
        var sum = 0.0
        for (i in 0 until length) sum += frame[i].toDouble() * frame[i]
        return Math.sqrt(sum / length)
    }

    fun feed(frame: ShortArray, length: Int = frame.size): Heard {
        val ms = length * 1000.0 / sampleRate
        elapsedMs += ms
        val level = rms(frame, length)
        if (calibrationMs < CALIBRATE_MS && !spoke) { // the first moments set the noise floor
            floor = if (floor < 0) level else floor * 0.7 + level * 0.3
            calibrationMs += ms
        }
        val threshold = maxOf(MIN_LEVEL, floor * 3.0)
        val loud = level >= threshold && calibrationMs >= CALIBRATE_MS || (level >= MIN_LEVEL * 4 && calibrationMs < CALIBRATE_MS)
        if (loud) {
            speechMs += ms
            silenceMs = 0.0
            if (speechMs >= minSpeechMs) spoke = true
        } else {
            silenceMs += ms
        }
        if (spoke && silenceMs >= endSilenceMs) return Heard.SPEECH_DONE
        if (elapsedMs >= maxMs) return if (spoke) Heard.SPEECH_DONE else Heard.NOTHING
        if (!spoke && elapsedMs >= noSpeechMs) return Heard.NOTHING
        return if (spoke) Heard.SPEAKING else Heard.WAITING
    }

    companion object {
        const val MIN_LEVEL = 350.0
        const val CALIBRATE_MS = 240.0
    }
}
