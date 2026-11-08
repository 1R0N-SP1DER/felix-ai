package com.felix.faze

import java.io.ByteArrayOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/** What the voice loop needs from the rest of the app. */
interface VoiceHost {
    /** Blocking, on a worker thread. Throws with a readable message when it fails. */
    fun transcribe(wav: ByteArray): String

    /** Hands what the person said to the chat (which answers and speaks as usual). */
    fun ask(text: String)

    /** Stop the reply and the speech now (the person talked over it). */
    fun interrupt()
    fun engineBusy(): Boolean
    fun speaking(): Boolean

    /** The model has already written something for this turn, so a filler phrase would only get in the way. */
    fun replyStarted(): Boolean

    /** Speak a short phrase that covers a pause. */
    fun say(phrase: String)
    fun status(text: String)

    /** True when the person can talk over the reply without the mic hearing the phone itself (headphones). */
    fun bargeIn(): Boolean
}

/** Short, natural phrases that cover the wait for a slow answer. */
fun interface FillerSource {
    fun first(userText: String): String?
}

object Fillers {
    private val searchy = Regex("\\b(search|look up|look it up|find|google|news|weather|price|prices|latest|who is|what is|what's|how much|where is|when is|check)\\b", RegexOption.IGNORE_CASE)
    val lookups = listOf("Let me look that up for you.", "One sec, let me check.", "Let me find that out.")
    val general = listOf("Let me get started on that.", "On it.", "Give me a second.", "Sure, one moment.")
    val late = listOf("It's taking a bit, still on it.", "Still working on it, sorry for the wait.", "Almost there, bear with me.")

    fun canned(userText: String, n: Int): String {
        val list = if (searchy.containsMatchIn(userText)) lookups else general
        return list[Math.floorMod(n, list.size)]
    }

    fun late(n: Int): String = late[Math.floorMod(n, late.size)]

    /** A model's answer as one speakable phrase, or null when it is not one (a question, too long, empty). */
    fun clean(raw: String): String? {
        val line = raw.trim().lines().firstOrNull().orEmpty().trim().trim('"', '\'', '“', '”').trim()
        if (line.isEmpty() || line.length > 80 || line.contains('?') || line.split(' ').size > 12) return null
        return line
    }
}

/**
 * A small fast model says the filler, so it fits what was asked ("let me look that up for you" for a search, "on it" for a task).
 * If the model is slow, fails or has no key, a canned phrase is used instead: a filler must never be the thing that waits.
 */
class ModelFiller(
    private val chat: ChatApi,
    private val model: String,
    private val hasKey: () -> Boolean,
    private val executor: Executor,
    private val timeoutMs: Long = 1500,
) : FillerSource {
    private var counter = 0

    override fun first(userText: String): String? {
        val fallback = Fillers.canned(userText, counter++)
        if (!hasKey()) return fallback
        val stop = AtomicBoolean(false)
        return try {
            val messages = JSONArray()
                .put(JSONObject().put("role", "system").put("content", SYSTEM))
                .put(JSONObject().put("role", "user").put("content", userText.take(300)))
            val answer = CompletableFuture.supplyAsync({ chat.chat(model, messages, null, { _ -> }, { stop.get() }).content }, executor)
                .get(timeoutMs, TimeUnit.MILLISECONDS)
            Fillers.clean(answer) ?: fallback
        } catch (e: Exception) {
            stop.set(true)
            fallback
        }
    }

    companion object {
        const val DEFAULT_MODEL = "llama-3.1-8b-instant"
        const val SYSTEM = "You are the voice of a phone assistant. The user just made a request and the real answer will take a few seconds. " +
            "Say ONE short, natural, friendly spoken phrase (3 to 8 words) that covers the pause: looking something up, getting started on a task, or thinking. " +
            "Match the request. Do not answer it, do not ask a question, no emojis. Output only the phrase."
    }
}

/**
 * Voice chat: you talk, the app listens, turns it into text, the chat answers and speaks, and it listens again. Driven by the mic's
 * 100 ms frames alone (time is counted in frames), so it is tested without a phone or a clock. While the answer is slow, a filler
 * phrase fills the silence. With headphones you can talk over the answer; without them the mic is ignored until it has finished.
 */
class VoiceLoop(
    private val host: VoiceHost,
    private val worker: Executor,
    private val filler: FillerSource,
    private val fillerWorker: Executor,
    private val newEndpointer: () -> Endpointer = { Endpointer(endSilenceMs = 800) },
    private val fillersOn: () -> Boolean = { true },
) {
    private enum class Stage { LISTENING, TRANSCRIBING, THINKING }

    @Volatile private var running = false
    private var mic: LiveAudioIn? = null
    private val lock = Any()
    private var stage = Stage.LISTENING
    private var endpointer = newEndpointer()
    private var capturing = false
    private val clip = ByteArrayOutputStream()
    private val preRoll = ArrayDeque<ByteArray>()
    private var idle = 0
    private var nowMs = 0L
    private var askedAt = 0L
    private var sawActive = false
    private var firstAt = 0L
    private var lateAt = 0L
    private var said = 0
    private var lateCount = 0
    @Volatile private var phrase: String? = null
    private var turn = 0

    fun isRunning(): Boolean = running

    /** Null when listening has begun, otherwise why not. */
    fun start(mic: LiveAudioIn): String? {
        if (running) return "voice chat is already on"
        this.mic = mic
        running = true
        val failed = mic.start { onFrame(it) }
        if (failed != null) running = false
        else host.status(LISTENING)
        return failed
    }

    fun stop() {
        if (!running) return
        running = false
        synchronized(lock) { turn++ }
        mic?.stop()
        mic = null
        host.status("Voice chat is off")
    }

    private fun onFrame(pcm: ByteArray) {
        if (!running) return
        synchronized(lock) {
            nowMs += pcm.size / 2 * 1000L / Recorder.RATE
            when (stage) {
                Stage.TRANSCRIBING -> Unit
                Stage.LISTENING -> capture(pcm, false)
                Stage.THINKING -> think(pcm)
            }
        }
    }

    private fun toShorts(pcm: ByteArray) = ShortArray(pcm.size / 2) { i -> ((pcm[2 * i + 1].toInt() shl 8) or (pcm[2 * i].toInt() and 0xff)).toShort() }

    private fun capture(pcm: ByteArray, fromThinking: Boolean) {
        preRoll.addLast(pcm)
        while (preRoll.size > PRE_ROLL) preRoll.removeFirst()
        when (endpointer.feed(toShorts(pcm))) {
            Heard.WAITING -> Unit
            Heard.SPEAKING -> {
                keep(pcm)
                if (fromThinking) { // talking over the answer
                    host.interrupt()
                    stage = Stage.LISTENING
                    idle = 0
                    host.status(LISTENING)
                }
            }
            Heard.SPEECH_DONE -> { keep(pcm); finishTurn() }
            Heard.NOTHING -> resetCapture()
        }
    }

    /** The first loud frame brings the moments just before it, so the start of a word is not cut off. */
    private fun keep(pcm: ByteArray) {
        if (!capturing) { capturing = true; for (f in preRoll) clip.write(f) } else clip.write(pcm)
    }

    private fun resetCapture() {
        clip.reset()
        capturing = false
        preRoll.clear()
        endpointer = newEndpointer()
    }

    private fun finishTurn() {
        val pcm = clip.toByteArray()
        resetCapture()
        stage = Stage.TRANSCRIBING
        host.status("Transcribing…")
        val mine = ++turn
        worker.execute {
            val text = try {
                host.transcribe(Wav.fromPcm(pcm, Recorder.RATE)).trim()
            } catch (e: Exception) {
                synchronized(lock) { if (running && turn == mine) { stage = Stage.LISTENING; resetCapture() } }
                if (running) host.status("Could not transcribe: ${e.message ?: e.javaClass.simpleName}")
                return@execute
            }
            onTranscript(text, mine)
        }
    }

    private fun onTranscript(text: String, mine: Int) {
        synchronized(lock) {
            if (!running || turn != mine) return
            if (isJunk(text)) {
                stage = Stage.LISTENING
                resetCapture()
                host.status(LISTENING)
                return
            }
            stage = Stage.THINKING
            idle = 0
            sawActive = false
            askedAt = nowMs
            said = 0
            phrase = null
            firstAt = nowMs + FIRST_FILLER_MS
            resetCapture()
        }
        host.status("Thinking…")
        host.ask(text)
        fillerWorker.execute {
            val p = try { filler.first(text) } catch (e: Exception) { null } ?: Fillers.canned(text, 0)
            synchronized(lock) { if (turn == mine) phrase = p }
        }
    }

    private fun think(pcm: ByteArray) {
        if (host.engineBusy() || host.speaking()) {
            sawActive = true
            idle = 0
            fillers()
            if (host.bargeIn()) capture(pcm, true)
        } else if (sawActive || nowMs - askedAt > ASK_GRACE_MS) {
            if (++idle >= IDLE_FRAMES) {
                stage = Stage.LISTENING
                resetCapture()
                host.status(LISTENING)
            }
        }
    }

    private fun fillers() {
        if (!fillersOn() || host.replyStarted() || said >= MAX_FILLERS) return
        val p = phrase
        if (said == 0) {
            if (p != null && nowMs >= firstAt) { host.say(p); said = 1; lateAt = nowMs + LATE_FILLER_MS }
        } else if (nowMs >= lateAt) {
            host.say(Fillers.late(lateCount++))
            said++
            lateAt = nowMs + LATE_FILLER_MS
        }
    }

    private fun isJunk(text: String): Boolean {
        val t = text.lowercase().replace(Regex("[^a-z0-9 ]"), "").trim()
        return t.length < 2 || t in NOISE
    }

    companion object {
        const val LISTENING = "Voice chat: listening"
        private const val PRE_ROLL = 5
        private const val IDLE_FRAMES = 4
        private const val ASK_GRACE_MS = 3000L
        private const val FIRST_FILLER_MS = 800L
        private const val LATE_FILLER_MS = 7000L
        private const val MAX_FILLERS = 4

        /** What speech recognition tends to invent from room noise. */
        private val NOISE = setOf("you", "thanks for watching", "thank you for watching", "bye", "bye bye")
    }
}
