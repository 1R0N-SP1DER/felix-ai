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

    /** Sounds the model tends to confuse with a word of the phrase. Listing them as separate choices keeps them from being forced onto the real phrase. */
    private val confusable = mapOf(
        "faze" to listOf("phase", "face", "fate", "fade", "phrase", "days", "fades", "fuzz"),
        "hey" to listOf("hay", "hate", "they", "hi"),
    )

    /** What the recognizer may hear: the phrase, look-alike decoys (so they are heard as something else), and [unk] for everything else. */
    fun strictGrammar(phrase: String): List<String> {
        val p = normalize(phrase)
        val words = p.split(' ')
        val out = LinkedHashSet<String>()
        out.add(p)
        for ((i, w) in words.withIndex()) {
            for (c in confusable[w].orEmpty()) out.add(words.toMutableList().also { it[i] = c }.joinToString(" "))
        }
        for (c in confusable[words.last()].orEmpty()) out.add(c)
        out.add("[unk]")
        return out.toList()
    }

    fun strictGrammarJson(phrase: String): String =
        strictGrammar(phrase).joinToString(prefix = "[", postfix = "]") { "\"" + it.replace("\"", "") + "\"" }

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

/**
 * Decides whether one finished recognition (Vosk's result with word timing and confidence) really is the wake phrase and not
 * a cough, a sneeze or a similar word. Every word must be right, confident, and the whole thing must last about as long as the
 * phrase takes to say.
 */
object WakeVerifier {
    fun accept(resultJson: String, phrase: String, minConf: Double = 0.7, minMs: Int = 350, maxMs: Int = 2200): Boolean {
        val words = try {
            org.json.JSONObject(resultJson).optJSONArray("result") ?: return false
        } catch (e: Exception) {
            return false
        }
        if (words.length() == 0) return false
        val spoken = ArrayList<String>()
        var start = Double.MAX_VALUE
        var end = 0.0
        for (i in 0 until words.length()) {
            val w = words.optJSONObject(i) ?: return false
            if (w.optDouble("conf", 0.0) < minConf) return false
            spoken.add(w.optString("word", ""))
            start = minOf(start, w.optDouble("start", 0.0))
            end = maxOf(end, w.optDouble("end", 0.0))
        }
        if (spoken.joinToString(" ") != Wake.normalize(phrase)) return false
        val ms = (end - start) * 1000.0
        return ms in minMs.toDouble()..maxMs.toDouble()
    }
}

/** Cuts a stream of reads of any size into frames of exactly [size] samples (the wake engines want fixed frames). */
class FrameBuffer(private val size: Int) {
    private val buf = ShortArray(size)
    private var fill = 0

    /** [onFrame] gets a shared array: use it right away, don't keep it. */
    fun push(data: ShortArray, n: Int, onFrame: (ShortArray) -> Unit) {
        var i = 0
        while (i < n) {
            val take = minOf(size - fill, n - i)
            System.arraycopy(data, i, buf, fill, take)
            fill += take
            i += take
            if (fill == size) {
                onFrame(buf)
                fill = 0
            }
        }
    }

    fun clear() {
        fill = 0
    }
}

object Pcm {
    /** Android hands a background app a microphone that records only zeros. Little-endian 16-bit samples. */
    fun isSilent(pcm: ByteArray, threshold: Int = 6): Boolean {
        var i = 0
        while (i + 1 < pcm.size) {
            val sample = (pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xff)
            if (Math.abs(sample) >= threshold) return false
            i += 2
        }
        return true
    }
}

object Keys {
    const val VOLUME_UP = 24
    const val VOLUME_DOWN = 25
}

enum class ChordAction { NONE, OPEN, CLOSE }

data class ChordResult(val consume: Boolean, val action: ChordAction)

/**
 * Both volume keys held together opens the overlay; with the overlay open, volume up held alone closes it. A quick press of
 * either key is left alone (not consumed), so normal volume changes still work. Key times come in, so this needs no Android.
 */
class ChordDetector(private val openHoldMs: Long = 600, private val closeHoldMs: Long = 700) {
    var overlayOpen = false
    private var upAt = -1L
    private var downAt = -1L
    private var chordAt = -1L
    private var chordLatched = false
    private var fired = false
    private var closeFired = false
    private var closeBlocked = false

    fun anyHeld(): Boolean = upAt >= 0 || downAt >= 0

    fun onKey(keyCode: Int, down: Boolean, repeat: Int, now: Long): ChordResult {
        if (keyCode != Keys.VOLUME_UP && keyCode != Keys.VOLUME_DOWN) return ChordResult(false, ChordAction.NONE)
        val wasChord = chordLatched
        if (down) {
            if (keyCode == Keys.VOLUME_UP) { if (repeat == 0 || upAt < 0) upAt = now }
            else { if (repeat == 0 || downAt < 0) downAt = now }
            if (upAt >= 0 && downAt >= 0 && !chordLatched) {
                chordLatched = true
                chordAt = maxOf(upAt, downAt)
                fired = false
            }
        } else {
            if (keyCode == Keys.VOLUME_UP) { upAt = -1; closeFired = false } else downAt = -1
            if (chordLatched) fired = true // letting go of either key cancels a chord that has not opened anything yet
        }
        val action = check(now)
        val consume = chordLatched || wasChord || (overlayOpen && keyCode == Keys.VOLUME_UP && down && repeat > 0)
        if (!anyHeld()) {
            chordLatched = false
            fired = false
            closeFired = false
            closeBlocked = false
        }
        return ChordResult(consume, action)
    }

    /** Call while a key is held (on each key event and on a short timer): the hold time passing is what fires the action. */
    fun check(now: Long): ChordAction {
        if (chordLatched && !fired && !overlayOpen && upAt >= 0 && downAt >= 0 && now - chordAt >= openHoldMs) { // both still down
            fired = true
            closeBlocked = true // the keys that just opened it must not close it again
            return ChordAction.OPEN
        }
        if (overlayOpen && !chordLatched && !closeBlocked && upAt >= 0 && downAt < 0 && !closeFired && now - upAt >= closeHoldMs) {
            closeFired = true
            return ChordAction.CLOSE
        }
        return ChordAction.NONE
    }
}
