package com.felix.faze

import java.util.concurrent.Executor
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceLoopTest {
    private class FakeHost : VoiceHost {
        var transcript = "what is the weather"
        var transcribeFails: String? = null
        val wavs = ArrayList<ByteArray>()
        val asked = ArrayList<String>()
        val said = ArrayList<String>()
        val statuses = ArrayList<String>()
        var interrupts = 0
        var busy = false
        var speaking = false
        var started = false
        var barge = false
        override fun transcribe(wav: ByteArray): String { wavs.add(wav); transcribeFails?.let { throw RuntimeException(it) }; return transcript }
        override fun ask(text: String) { asked.add(text) }
        override fun interrupt() { interrupts++ }
        override fun engineBusy() = busy
        override fun speaking() = speaking
        override fun replyStarted() = started
        override fun say(phrase: String) { said.add(phrase) }
        override fun status(text: String) { statuses.add(text) }
        override fun bargeIn() = barge
    }

    private class FakeMic(val fail: String? = null) : LiveAudioIn {
        var cb: ((ByteArray) -> Unit)? = null
        var stopped = 0
        override fun start(onChunk: (ByteArray) -> Unit): String? { if (fail != null) return fail; cb = onChunk; return null }
        override fun stop() { stopped++ }
    }

    private val direct = Executor { it.run() }

    private fun frame(loud: Boolean): ByteArray {
        val b = ByteArray(3200) // 100 ms of 16 kHz mono
        if (loud) for (i in 0 until 1600) { val v = if (i % 2 == 0) 6000 else -6000; b[2 * i] = (v and 0xff).toByte(); b[2 * i + 1] = ((v shr 8) and 0xff).toByte() }
        return b
    }

    private fun feed(m: FakeMic, loud: Boolean, n: Int) { repeat(n) { m.cb!!(frame(loud)) } }

    /** A moment of quiet (the noise level is learned from it), a few words, then the pause that ends the turn. */
    private fun talk(m: FakeMic) { feed(m, false, 3); feed(m, true, 5); feed(m, false, 8) }

    private class Rig(val host: FakeHost, val mic: FakeMic, val loop: VoiceLoop)

    private fun rig(source: FillerSource = FillerSource { "Let me check." }, worker: Executor = Executor { it.run() }, on: () -> Boolean = { true }): Rig {
        val h = FakeHost()
        val m = FakeMic()
        val l = VoiceLoop(h, worker, source, Executor { it.run() }, fillersOn = on)
        assertNull(l.start(m))
        return Rig(h, m, l)
    }

    // ---- the loop

    @Test fun aTurnIsHeardTranscribedAndAsked() {
        val r = rig()
        talk(r.mic)
        assertEquals(listOf("what is the weather"), r.host.asked)
        assertEquals("RIFF", String(r.host.wavs.single(), 0, 4))
        assertTrue(r.host.wavs.single().size > 3200 * 5) // the words and the moments before them
        assertEquals("Thinking…", r.host.statuses.last())
    }

    @Test fun roomNoiseAloneIsNeverTranscribed() {
        val r = rig()
        feed(r.mic, false, 120)
        assertTrue(r.host.wavs.isEmpty())
        assertTrue(r.host.asked.isEmpty())
    }

    @Test fun inventedWordsFromNoiseAreDropped() {
        val r = rig()
        r.host.transcript = "Thank you for watching!"
        talk(r.mic)
        assertTrue(r.host.asked.isEmpty())
        assertEquals(VoiceLoop.LISTENING, r.host.statuses.last())
        r.host.transcript = "turn the torch on"
        talk(r.mic)
        assertEquals(listOf("turn the torch on"), r.host.asked)
    }

    @Test fun aFailedTranscriptionShowsWhyAndKeepsListening() {
        val r = rig()
        r.host.transcribeFails = "no connection"
        talk(r.mic)
        assertEquals("Could not transcribe: no connection", r.host.statuses.last())
        assertTrue(r.host.asked.isEmpty())
        r.host.transcribeFails = null
        talk(r.mic)
        assertEquals(1, r.host.asked.size)
    }

    @Test fun afterTheAnswerItListensAgain() {
        val r = rig()
        talk(r.mic)
        r.host.busy = true
        feed(r.mic, false, 5)
        r.host.busy = false
        feed(r.mic, false, 4)
        assertEquals(VoiceLoop.LISTENING, r.host.statuses.last())
        r.host.transcript = "and tomorrow?"
        talk(r.mic)
        assertEquals(listOf("what is the weather", "and tomorrow?"), r.host.asked)
    }

    @Test fun ifTheChatNeverStartsItGivesUpWaitingAndListens() {
        val r = rig()
        talk(r.mic)
        feed(r.mic, false, 40)
        assertEquals(VoiceLoop.LISTENING, r.host.statuses.last())
    }

    // ---- fillers

    @Test fun aSlowAnswerGetsAFillerThenAnOccasionalNudge() {
        val r = rig()
        talk(r.mic)
        r.host.busy = true
        feed(r.mic, false, 7)
        assertTrue(r.host.said.isEmpty())
        feed(r.mic, false, 1)
        assertEquals(listOf("Let me check."), r.host.said)
        feed(r.mic, false, 69)
        assertEquals(1, r.host.said.size)
        feed(r.mic, false, 1)
        assertEquals(listOf("Let me check.", Fillers.late(0)), r.host.said)
        feed(r.mic, false, 1000)
        assertEquals(4, r.host.said.size) // never an endless stream of them
    }

    @Test fun noFillerWhenTheAnswerAlreadyStartedOrFillersAreOff() {
        val a = rig()
        talk(a.mic)
        a.host.busy = true
        a.host.started = true
        feed(a.mic, false, 200)
        assertTrue(a.host.said.isEmpty())
        val b = rig(on = { false })
        talk(b.mic)
        b.host.busy = true
        feed(b.mic, false, 200)
        assertTrue(b.host.said.isEmpty())
    }

    @Test fun aBrokenFillerSourceFallsBackToACannedPhrase() {
        val r = rig(source = FillerSource { throw IllegalStateException("model down") })
        r.host.transcript = "look up the news"
        talk(r.mic)
        r.host.busy = true
        feed(r.mic, false, 10)
        assertTrue(r.host.said.single() in Fillers.lookups)
    }

    // ---- talking over the answer

    @Test fun withHeadphonesTalkingOverTheAnswerInterruptsAndStartsANewTurn() {
        val r = rig()
        talk(r.mic)
        r.host.busy = true
        r.host.speaking = true
        r.host.barge = true
        r.host.started = true
        feed(r.mic, false, 3)
        feed(r.mic, true, 4)
        assertEquals(1, r.host.interrupts)
        assertEquals(VoiceLoop.LISTENING, r.host.statuses.last())
        r.host.busy = false
        r.host.speaking = false
        r.host.transcript = "actually never mind"
        feed(r.mic, false, 8)
        assertEquals(listOf("what is the weather", "actually never mind"), r.host.asked)
    }

    @Test fun withoutHeadphonesTheMicIsIgnoredWhileItAnswers() {
        val r = rig()
        talk(r.mic)
        r.host.busy = true
        r.host.speaking = true
        r.host.barge = false
        r.host.started = true
        feed(r.mic, true, 20)
        feed(r.mic, false, 20)
        assertEquals(0, r.host.interrupts)
        assertEquals(1, r.host.asked.size)
    }

    // ---- mute

    @Test fun aMutedMicCapturesNothingButTheLoopCarriesOn() {
        val r = rig()
        r.loop.muted = true
        talk(r.mic)
        assertTrue(r.host.wavs.isEmpty())
        r.loop.muted = false
        talk(r.mic)
        assertEquals(1, r.host.asked.size)
    }

    @Test fun fillerPhrasesStillComeWhileMuted() {
        val r = rig()
        talk(r.mic)
        r.host.busy = true
        r.loop.muted = true
        feed(r.mic, false, 8)
        assertEquals(1, r.host.said.size)
    }

    // ---- starting and stopping

    @Test fun stoppingWhileTranscribingDropsTheResult() {
        val queue = ArrayList<Runnable>()
        val r = rig(worker = Executor { queue.add(it) })
        talk(r.mic)
        assertEquals(1, queue.size)
        r.loop.stop()
        queue.forEach { it.run() }
        assertTrue(r.host.asked.isEmpty())
        assertEquals(1, r.mic.stopped)
        assertEquals("Voice chat is off", r.host.statuses.last())
        assertFalse(r.loop.isRunning())
        r.mic.cb!!(frame(true)) // frames after stop are ignored
    }

    @Test fun aMicThatCannotStartLeavesNothingRunning() {
        val h = FakeHost()
        val l = VoiceLoop(h, direct, FillerSource { null }, direct)
        assertEquals("the microphone permission is not granted", l.start(FakeMic(fail = "the microphone permission is not granted")))
        assertFalse(l.isRunning())
    }

    @Test fun startingTwiceIsRefused() {
        val r = rig()
        assertEquals("voice chat is already on", r.loop.start(FakeMic()))
    }

    // ---- filler phrases

    @Test fun fillerCleanKeepsOnlyOneSpeakablePhrase() {
        assertEquals("Let me check.", Fillers.clean("  \"Let me check.\" "))
        assertEquals("Line one.", Fillers.clean("Line one.\nLine two"))
        assertNull(Fillers.clean(""))
        assertNull(Fillers.clean("Which one do you mean?"))
        assertNull(Fillers.clean("a b c d e f g h i j k l m"))
        assertNull(Fillers.clean("x".repeat(81)))
    }

    @Test fun cannedPhrasesMatchWhatWasAsked() {
        assertTrue(Fillers.canned("what's the weather in Lagos", 0) in Fillers.lookups)
        assertTrue(Fillers.canned("turn on the torch", 0) in Fillers.general)
        assertTrue(Fillers.canned("turn on the torch", -3).isNotEmpty())
        assertTrue(Fillers.late(-1).isNotEmpty())
    }

    private class FakeChat(val reply: () -> String, val delayMs: Long = 0) : ChatApi {
        var calls = 0
        var model = ""
        var userText = ""
        var system = ""
        override fun chat(model: String, messages: JSONArray, tools: JSONArray?, onToken: (String) -> Unit, cancelled: () -> Boolean): ChatResult {
            calls++
            this.model = model
            system = messages.getJSONObject(0).getString("content")
            userText = messages.getJSONObject(1).getString("content")
            if (delayMs > 0) Thread.sleep(delayMs)
            return ChatResult(reply(), emptyList(), false)
        }
    }

    @Test fun theSmallModelWritesTheFiller() {
        val chat = FakeChat({ "  \"One sec, checking the news.\"  " })
        val f = ModelFiller(chat, "small-model", { true }, direct)
        assertEquals("One sec, checking the news.", f.first("what's in the news"))
        assertEquals("small-model", chat.model)
        assertEquals("what's in the news", chat.userText)
        assertEquals(ModelFiller.SYSTEM, chat.system)
    }

    @Test fun anyProblemWithTheModelFallsBackToACannedPhrase() {
        val canned = Fillers.lookups + Fillers.general
        assertTrue(ModelFiller(FakeChat({ throw RuntimeException("rate limited") }), "m", { true }, direct).first("find me a recipe") in canned)
        assertTrue(ModelFiller(FakeChat({ "Which recipe?" }), "m", { true }, direct).first("find me a recipe") in canned)
        val noKey = FakeChat({ "never used" })
        assertTrue(ModelFiller(noKey, "m", { false }, direct).first("hello") in canned)
        assertEquals(0, noKey.calls)
        val slow = ModelFiller(FakeChat({ "Too late." }, delayMs = 400), "m", { true }, Executor { Thread(it).start() }, timeoutMs = 50)
        assertTrue(slow.first("hello") in canned)
    }
}
