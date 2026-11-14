package com.felix.faze

import java.util.concurrent.Executor
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartSpeechTest {
    private class Dev : DeviceSpeech {
        val said = ArrayList<String>()
        var stopped = 0
        override fun say(text: String) { said.add(text) }
        override fun stop() { stopped++ }
        override fun isSpeaking() = false
    }

    private class Out : LiveAudioOut {
        val played = ArrayList<ByteArray>()
        var flushed = 0
        override fun play(pcm: ByteArray) { played.add(pcm) }
        override fun flush() { flushed++ }
        override fun stop() {}
    }

    private val direct = Executor { it.run() }

    private fun speaker(dev: Dev, out: Out, synth: Synth?, mode: String = "auto", online: Boolean = true, key: Boolean = true, now: () -> Long = { 0L }) =
        SmartSpeaker(dev, out, synth, { mode }, { online }, { key }, now, direct)

    @Test fun onlineAutoUsesTheNaturalVoiceAndOfflineUsesThePhoneVoice() {
        val dev = Dev(); val out = Out()
        val s = speaker(dev, out, { byteArrayOf(1, 2) })
        s.speak("Hello there.")
        assertEquals(1, out.played.size)
        assertTrue(dev.said.isEmpty())
        val off = Dev(); val out2 = Out()
        speaker(off, out2, { byteArrayOf(1) }, online = false).speak("Hello there.")
        assertEquals(listOf("Hello there."), off.said)
        assertTrue(out2.played.isEmpty())
    }

    @Test fun phoneModeAndMissingKeyNeverUseTheNaturalVoice() {
        val dev = Dev(); val out = Out()
        speaker(dev, out, { byteArrayOf(1) }, mode = "device").speak("One.")
        speaker(dev, out, { byteArrayOf(1) }, key = false).speak("Two.")
        speaker(dev, out, null).speak("Three.")
        assertEquals(listOf("One.", "Two.", "Three."), dev.said)
        assertTrue(out.played.isEmpty())
    }

    @Test fun aFailureFallsBackToThePhoneVoiceThenTheNaturalOneRestsForAMinute() {
        val dev = Dev(); val out = Out()
        var t = 1000L
        var fail = true
        val s = speaker(dev, out, { if (fail) throw ToolFailure("Gemini voice answered 429") else byteArrayOf(9) }, now = { t })
        s.speak("First.")
        assertEquals(listOf("First."), dev.said)
        fail = false
        t += 30_000
        s.speak("Second.") // still resting
        assertEquals(listOf("First.", "Second."), dev.said)
        t += 31_000
        s.speak("Third.")
        assertEquals(1, out.played.size)
    }

    @Test fun sentencesThatArriveWhileOneIsBeingMadeAreJoinedIntoOneRequest() {
        val dev = Dev(); val out = Out()
        val queued = ArrayList<Runnable>()
        val asked = ArrayList<String>()
        val s = SmartSpeaker(dev, out, { asked.add(it); byteArrayOf(1) }, { "auto" }, { true }, { true }, { 0L }, Executor { queued.add(it) })
        s.speak("One.")
        s.speak("Two.")
        s.speak("Three.")
        assertEquals(1, queued.size) // one worker was started
        queued[0].run()
        assertEquals(listOf("One. Two. Three."), asked)
        assertFalse(s.isSpeaking())
    }

    @Test fun stopSilencesBothAndDropsWhatWasWaiting() {
        val dev = Dev(); val out = Out()
        val queued = ArrayList<Runnable>()
        val asked = ArrayList<String>()
        val s = SmartSpeaker(dev, out, { asked.add(it); byteArrayOf(1) }, { "auto" }, { true }, { true }, { 0L }, Executor { queued.add(it) })
        s.speak("Never heard.")
        s.stop()
        queued[0].run()
        assertTrue(out.played.isEmpty()) // made after the stop, so not played
        assertEquals(1, out.flushed)
        assertEquals(1, dev.stopped)
    }

    @Test fun theGeminiRequestAndAnswerAreRead() {
        val wav = ByteArray(44) + byteArrayOf(5, 6, 7, 8)
        wav[0] = 'R'.code.toByte(); wav[1] = 'I'.code.toByte(); wav[2] = 'F'.code.toByte(); wav[3] = 'F'.code.toByte()
        // a data chunk header at 36: "data" + size 4
        val dataAt = 36
        "data".forEachIndexed { i, c -> wav[dataAt + i] = c.code.toByte() }
        wav[dataAt + 4] = 4
        val reply = """{"steps":[{"type":"model_output","content":[{"type":"audio","data":"${java.util.Base64.getEncoder().encodeToString(wav)}"}]}]}"""
        val t = FakeTransport()
        t.onPost = { _, _ -> HttpResult(200, emptyMap(), reply) }
        val pcm = GeminiSpeech(t, { "gk" }, { "" }, { "Puck" }).synth("Hello")
        assertEquals(listOf<Byte>(5, 6, 7, 8), pcm.toList())
        val call = t.calls.single()
        assertEquals("gk", call.headers["x-goog-api-key"])
        val body = JSONObject(call.body)
        assertEquals(GeminiSpeech.DEFAULT_MODEL, body.getString("model"))
        assertEquals("Puck", body.getJSONObject("generation_config").getJSONArray("speech_config").getJSONObject(0).getString("voice"))
        assertEquals("audio", body.getJSONObject("response_format").getString("type"))
    }

    @Test fun rawSoundPassesThroughAndErrorsBecomeFailures() {
        assertEquals(listOf<Byte>(1, 2, 3), WavReader.pcm(byteArrayOf(1, 2, 3)).toList())
        val t = FakeTransport()
        t.onPost = { _, _ -> HttpResult(429, emptyMap(), "{}") }
        try { GeminiSpeech(t, { "gk" }, { "" }, { "Kore" }).synth("x"); org.junit.Assert.fail() } catch (e: ToolFailure) { assertTrue(e.message!!.contains("429")) }
    }
}
