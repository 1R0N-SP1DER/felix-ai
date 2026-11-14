package com.felix.faze

import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

data class ChatMessage(val id: Long, val role: String, val text: String, val steps: List<String> = emptyList(), val attachments: List<Attachment> = emptyList(), val sources: List<Source> = emptyList())

interface SpeechOut {
    fun speak(sentence: String)
    fun stop()
}

interface EngineConfig {
    fun model(): String
    fun userName(): String
    fun speak(): Boolean

    /** Saved notes about the person, added to the system prompt (empty when memory is off or empty). */
    fun memoryBlock(): String = ""

    /** Effort level (standard, high, max) and the response length stop (0 to 4, 2 = the AI chooses). */
    fun effort(): String = "low"
    fun lengthSetting(): Int = ResponseLength.DEFAULT
}

interface EngineListener {
    fun onBusy(busy: Boolean)
    fun onStatus(text: String)

    /** How long the model took for the turn that just finished (milliseconds). */
    fun onTiming(llmMs: Long) {}
}

/**
 * One conversation: sends a message through the agent on a background thread, streams the reply into the message list,
 * and speaks it sentence by sentence while it is still being written. Everything that touches [messages] or the
 * listener goes through [ui] (the main thread), so the screen never sees a half-updated list.
 */
class ChatEngine(
    private val agent: Agent,
    private val config: EngineConfig,
    private val speech: SpeechOut,
    private val ui: (() -> Unit) -> Unit,
    private val executor: Executor,
    private val messages: MutableList<ChatMessage>,
    private val listener: EngineListener,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private var nextId = 1L
    private var busy = false // main thread only
    private val cancel = AtomicBoolean(false)
    private var started = 0L // main thread only

    fun isBusy(): Boolean = busy

    /** Pictures the tools made since the last look; they join the reply they belong to. */
    var takeImages: () -> List<Attachment> = { emptyList() }

    /** Main thread. Ignored while a reply is still being written. */
    fun send(text: String, attachments: List<Attachment> = emptyList()) {
        val clean = text.trim()
        if ((clean.isEmpty() && attachments.isEmpty()) || busy) return
        busy = true
        started = clock()
        nextId = maxOf(nextId, (messages.maxOfOrNull { it.id } ?: 0L) + 1) // a chat loaded from disk keeps its own ids
        cancel.set(false)
        messages.add(ChatMessage(nextId++, "user", clean, attachments = attachments))
        val history = history()
        val replyId = nextId++
        messages.add(ChatMessage(replyId, "assistant", ""))
        listener.onBusy(true)
        listener.onStatus("")
        executor.execute { run(replyId, history) }
    }

    /** Main thread: stop the reply and the speech. What was written so far stays. */
    fun stop() {
        cancel.set(true)
        speech.stop()
    }

    private fun history(): List<Pair<String, String>> =
        messages.filter { it.text.isNotEmpty() || it.attachments.any { a -> a.context.isNotBlank() } }.map { Pair(it.role, Attachment.forModel(it.text, it.attachments)) }.takeLast(MAX_HISTORY)

    private fun update(id: Long, change: (ChatMessage) -> ChatMessage) {
        val i = messages.indexOfFirst { it.id == id }
        if (i >= 0) messages[i] = change(messages[i])
    }

    private fun finish(replyId: Long) {
        val i = messages.indexOfFirst { it.id == replyId }
        if (i >= 0 && messages[i].text.isEmpty() && messages[i].steps.isEmpty() && messages[i].attachments.isEmpty()) messages.removeAt(i)
        busy = false
        listener.onBusy(false)
    }

    private fun describe(e: RemoteError): String =
        if (e.kind == "rate_limit" && e.retryAfter > 0) "${e.message} Try again in ${e.retryAfter.toInt()} seconds."
        else e.message ?: "something went wrong"

    private fun run(replyId: Long, history: List<Pair<String, String>>) {
        val speaking = config.speak()
        val sentences = SentenceStream()
        val shown = StringBuilder()
        val steps = ArrayList<String>()
        val effort = config.effort()
        Effort.active = Effort.norm(effort)
        val system = Persona.system(config.userName()) + config.memoryBlock() + Persona.toolNote(agent.toolNames()) + Effort.directive(effort) + ResponseLength.directive(config.lengthSetting())
        ReplyLimit.tokens = ResponseLength.tokens(config.lengthSetting())
        val reviewing = Effort.reviews(effort)
        val seen = ArrayList<Source>()
        val events = AgentEvents(
            onToken = { piece ->
                shown.append(piece)
                val now = shown.toString()
                ui { update(replyId) { it.copy(text = now) } }
                if (speaking && !reviewing) for (s in sentences.feed(piece)) speech.speak(s)
            },
            onReset = { // a tool call followed text: what was shown is dropped, and so is what was not yet spoken
                shown.setLength(0)
                sentences.reset()
                speech.stop()
                ui { update(replyId) { it.copy(text = "") } }
            },
            onTool = { name, ok ->
                steps.add(if (ok) name else "$name (failed)")
                val copy = ArrayList(steps)
                val made = takeImages()
                ui { update(replyId) { it.copy(steps = copy, attachments = it.attachments + made) } }
            },
        )
        events.onSource = { src ->
            if (src !in seen) {
                seen.add(src)
                val copy = ArrayList(seen)
                ui { update(replyId) { it.copy(sources = copy) } }
            }
        }
        try {
            var reply = agent.run(config.model(), system, history, events) { cancel.get() }
            if (reviewing && reply.length >= REVIEW_MIN && !cancel.get()) {
                ui { listener.onStatus("Checking the answer…") }
                reply = agent.review(config.model(), system, history, reply, events) { cancel.get() }
                ui { listener.onStatus("") }
            }
            if (speaking && reviewing) {
                sentences.reset()
                sentences.feed(reply)
                for (s in sentences.finish()) speech.speak(s)
            } else if (speaking) {
                val written = shown.toString()
                if (written.isEmpty()) { // an answer that never streamed
                    sentences.reset()
                    sentences.feed(reply)
                } else if (reply.startsWith(written) && reply.length > written.length) {
                    sentences.feed(reply.substring(written.length))
                }
                for (s in sentences.finish()) speech.speak(s)
            }
            ui {
                update(replyId) { it.copy(text = reply) }
                listener.onTiming(clock() - started)
                finish(replyId)
            }
        } catch (e: Cancelled) {
            ui { finish(replyId) }
        } catch (e: RemoteError) {
            if (cancel.get()) { ui { finish(replyId) }; return }
            val message = describe(e)
            ui {
                listener.onStatus(message)
                finish(replyId)
            }
        } catch (e: Exception) {
            if (cancel.get()) { ui { finish(replyId) }; return }
            val message = "something went wrong: ${e.message ?: e.javaClass.simpleName}"
            ui {
                listener.onStatus(message)
                finish(replyId)
            }
        }
    }

    companion object {
        const val MAX_HISTORY = 20
        const val REVIEW_MIN = 80
    }
}
