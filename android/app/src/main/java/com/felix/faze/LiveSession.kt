package com.felix.faze

import java.util.concurrent.Executor

/** The microphone side of live mode: 16 kHz mono 16-bit PCM in small chunks. */
interface LiveAudioIn {
    /** Starts capturing and calls [onChunk] from a capture thread. Returns null, or a readable reason it could not start. */
    fun start(onChunk: (ByteArray) -> Unit): String?
    fun stop()
}

/** The speaker side: 24 kHz mono 16-bit PCM, played as it arrives. */
interface LiveAudioOut {
    fun play(pcm: ByteArray)

    /** Silence now and drop what is queued (the person talked over it). */
    fun flush()
    fun stop()
}

/**
 * Live mode: a spoken conversation with Gemini Live. Mic audio goes up, the voice comes back and plays, talking over it cuts it off, and
 * the model's tool calls run through the same approval gate as chat. Everything the phone does is behind [LiveAudioIn], [LiveAudioOut] and
 * [LiveConnector], so the flow is tested without a phone.
 */
class LiveSession(
    private val connector: LiveConnector,
    private val key: String,
    private val model: String,
    private val voice: String,
    private val search: Boolean,
    private val instruction: String,
    private val tools: ToolRunner,
    private val mic: LiveAudioIn,
    private val speaker: LiveAudioOut,
    private val worker: Executor,
    private val status: (String) -> Unit,
    private val line: (who: String, text: String) -> Unit,
    private val ended: () -> Unit = {},
    /** True when the phone would hear itself (no headphones): the mic is then muted while the model speaks, so it cannot interrupt itself. */
    private val halfDuplex: () -> Boolean = { false },
) {
    @Volatile private var client: LiveClient? = null
    @Volatile private var running = false
    @Volatile private var speaking = false

    fun isRunning(): Boolean = running

    /** Blocks while connecting. Null when live mode is on, otherwise why it is not. */
    fun start(): String? {
        if (running) return "live mode is already on"
        val c = LiveClient(key, model, connector, ::onEvent)
        client = c
        status("Connecting…")
        val declarations = GeminiWire.declarations(tools.schema())
        val failed = c.open(instruction, declarations, search, voice)
        if (failed != null) {
            c.close()
            client = null
            status("Live mode could not start: $failed")
            return failed
        }
        running = true
        val micFailed = mic.start { chunk -> if (running && !(speaking && halfDuplex())) c.sendAudio(chunk) }
        if (micFailed != null) {
            stop()
            status("Live mode could not start: $micFailed")
            return micFailed
        }
        status(LISTENING)
        return null
    }

    @Synchronized
    fun stop() {
        val wasRunning = running
        running = false
        mic.stop()
        speaker.stop()
        client?.close()
        client = null
        speaking = false
        if (wasRunning) { status("Live mode is off"); ended() }
    }

    private fun onEvent(e: LiveEvent) {
        when (e) {
            is LiveEvent.Audio -> {
                if (!speaking) { speaking = true; status(SPEAKING) }
                speaker.play(e.pcm)
            }
            is LiveEvent.Interrupted -> { speaker.flush(); speaking = false; status(LISTENING) }
            is LiveEvent.TurnComplete -> { speaking = false; status(LISTENING) }
            is LiveEvent.HeardText -> line("you", e.text)
            is LiveEvent.SaidText -> line("faze", e.text)
            is LiveEvent.Text -> Unit
            is LiveEvent.ToolCalls -> worker.execute { runCalls(e.calls) }
            is LiveEvent.GoAway -> status("Live mode will end soon" + (if (e.timeLeft.isEmpty()) "" else " (${e.timeLeft})"))
            is LiveEvent.Closed -> {
                val wasRunning = running
                stop()
                if (wasRunning) status("Live mode ended: ${e.why}")
            }
        }
    }

    /** Runs the model's tool calls one by one (a confirm tool waits for the person) and sends every result back in one message. */
    private fun runCalls(calls: List<LiveCall>) {
        val replies = calls.map { c ->
            val r = try {
                tools.execute(c.name, c.args)
            } catch (e: Exception) {
                ToolResult(false, "the tool failed: ${e.message ?: e.javaClass.simpleName}")
            }
            LiveReply(c.id, c.name, r.ok, r.text)
        }
        if (running) client?.sendToolResponses(replies)
    }

    companion object {
        const val LISTENING = "Live: listening"
        const val SPEAKING = "Live: speaking"

        /** What the model is told: the usual persona and notes, plus how a spoken conversation differs from typing. */
        fun instruction(userName: String, memoryBlock: String, toolNames: List<String>): String =
            Persona.system(userName) + memoryBlock + Persona.toolNote(toolNames) +
                "\n\nYou are talking out loud, live. Keep answers short and natural, as spoken sentences: no lists, no markdown, no symbols read aloud."
    }
}
