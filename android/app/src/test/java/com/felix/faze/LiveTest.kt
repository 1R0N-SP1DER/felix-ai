package com.felix.faze

import java.util.concurrent.Executor
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTest {
    private class FakeSocket(val onSend: (String) -> Unit) : LiveSocket {
        val sent = ArrayList<String>()
        var closed = false
        override fun send(text: String): Boolean { if (closed) return false; sent.add(text); onSend(text); return true }
        override fun close() { closed = true }
    }

    /** Answers the setup of attempt number i with replies[i] (the last one repeats). */
    private class Script(val replies: List<(LiveListener) -> Unit>) : LiveConnector {
        val sockets = ArrayList<FakeSocket>()
        val urls = ArrayList<String>()
        var listener: LiveListener? = null
        override fun connect(url: String, listener: LiveListener): LiveSocket {
            val i = sockets.size
            urls.add(url)
            this.listener = listener
            val sock = FakeSocket { text -> if (text.startsWith("{\"setup\"")) replies.getOrElse(i) { replies.last() }(listener) }
            sockets.add(sock)
            return sock
        }
    }

    private val accept: (LiveListener) -> Unit = { it.onMessage("{\"setupComplete\":{}}") }
    private fun reject(why: String): (LiveListener) -> Unit = { it.onClosed(why) }
    private val retryable = "1007: Invalid JSON payload received. Unknown name \"responseModalities\""

    private fun client(script: Script, events: MutableList<LiveEvent> = ArrayList()) = LiveClient("KEY123", "gemini-live", script, { events.add(it) }, 500)

    private fun open(c: LiveClient, search: Boolean = true) = c.open("be brief", JSONArray(), search, "Kore")

    private fun tools(): ToolRunner = object : ToolRunner {
        val calls = ArrayList<String>()
        override fun schema(): JSONArray = JSONArray().put(
            JSONObject().put("type", "function").put(
                "function",
                JSONObject().put("name", "battery").put("description", "Battery level").put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()).put("additionalProperties", false)),
            ),
        )
        override fun execute(name: String, args: JSONObject): ToolResult = if (name == "boom") throw IllegalStateException("exploded") else if (name == "nope") ToolResult(false, "not allowed") else ToolResult(true, "81 percent")
    }

    // ---- protocol

    @Test fun setupPutsTheOutputSettingsWhereTheShapeSays() {
        val a = LiveProtocol.setup("m", "hi", JSONArray(), true, "Puck", "generation").getJSONObject("setup")
        assertEquals("models/m", a.getString("model"))
        assertEquals("Puck", a.getJSONObject("generationConfig").getJSONObject("speechConfig").getJSONObject("voiceConfig").getJSONObject("prebuiltVoiceConfig").getString("voiceName"))
        assertFalse(a.has("responseModalities"))
        assertEquals("googleSearch", a.getJSONArray("tools").getJSONObject(0).keys().next())
        val b = LiveProtocol.setup("models/m", "hi", JSONArray().put(JSONObject().put("name", "x")), false, "Kore", "flat").getJSONObject("setup")
        assertEquals("models/m", b.getString("model"))
        assertEquals("AUDIO", b.getJSONArray("responseModalities").getString(0))
        assertFalse(b.has("generationConfig"))
        assertEquals(1, b.getJSONArray("tools").length())
        assertTrue(b.getJSONArray("tools").getJSONObject(0).has("functionDeclarations"))
        assertEquals("hi", b.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text"))
    }

    @Test fun audioAndToolMessages() {
        val a = JSONObject(LiveProtocol.audio(byteArrayOf(1, 2, 3))).getJSONObject("realtimeInput").getJSONObject("audio")
        assertEquals("audio/pcm;rate=16000", a.getString("mimeType"))
        assertEquals("AQID", a.getString("data"))
        val r = JSONObject(LiveProtocol.toolResponses(listOf(LiveReply("1", "battery", true, "81"), LiveReply("2", "x", false, "no")))).getJSONObject("toolResponse").getJSONArray("functionResponses")
        assertEquals("81", r.getJSONObject(0).getJSONObject("response").getString("result"))
        assertEquals("no", r.getJSONObject(1).getJSONObject("response").getString("error"))
        assertTrue(JSONObject(LiveProtocol.audioEnd()).getJSONObject("realtimeInput").getBoolean("audioStreamEnd"))
    }

    @Test fun serverMessagesBecomeEvents() {
        val msg = JSONObject(
            """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"data":"AQID","mimeType":"audio/pcm"}},{"text":"hi"}]},
            "inputTranscription":{"text":"hello"},"outputTranscription":{"text":"hey"},"interrupted":true,"turnComplete":true},
            "toolCall":{"functionCalls":[{"id":"c1","name":"battery","args":{"a":1}}]},"goAway":{"timeLeft":"30s"}}""",
        )
        val e = LiveProtocol.parse(msg)
        assertEquals(listOf<Byte>(1, 2, 3), (e[0] as LiveEvent.Audio).pcm.toList())
        assertEquals("hi", (e[1] as LiveEvent.Text).text)
        assertEquals("hello", (e[2] as LiveEvent.HeardText).text)
        assertEquals("hey", (e[3] as LiveEvent.SaidText).text)
        assertTrue(e[4] is LiveEvent.Interrupted)
        assertTrue(e[5] is LiveEvent.TurnComplete)
        val call = (e[6] as LiveEvent.ToolCalls).calls.single()
        assertEquals("c1", call.id)
        assertEquals(1, call.args.getInt("a"))
        assertEquals("30s", (e[7] as LiveEvent.GoAway).timeLeft)
        assertTrue(LiveProtocol.parse(JSONObject("{}")).isEmpty())
    }

    @Test fun keysNeverAppearAndRetryableReasonsAreRecognised() {
        assertEquals("wss://x?key=...&a=1", LiveProtocol.scrub("wss://x?key=SECRET&a=1"))
        assertTrue(LiveProtocol.isRetryable(retryable))
        assertTrue(LiveProtocol.isRetryable("Google Search tool is not supported"))
        assertFalse(LiveProtocol.isRetryable("1008: API key not valid"))
    }

    @Test fun geminiDeclarationsDropWhatGeminiRejects() {
        val d = GeminiWire.declarations(tools().schema())
        assertEquals("battery", d.getJSONObject(0).getString("name"))
        assertEquals("Battery level", d.getJSONObject(0).getString("description"))
        assertFalse(d.getJSONObject(0).getJSONObject("parameters").has("additionalProperties"))
    }

    // ---- client

    @Test fun opensWithTheFirstShapeWhenTheServerAccepts() {
        val script = Script(listOf(accept))
        val c = client(script)
        assertNull(open(c))
        assertEquals(1, script.sockets.size)
        assertTrue(script.urls[0].endsWith("key=KEY123"))
        assertTrue(JSONObject(script.sockets[0].sent[0]).getJSONObject("setup").has("generationConfig"))
        assertTrue(c.isOpen())
    }

    @Test fun triesTheFlatShapeWhenTheFirstIsRefusedByName() {
        val script = Script(listOf(reject(retryable), accept))
        val c = client(script)
        assertNull(open(c))
        assertEquals(2, script.sockets.size)
        assertTrue(script.sockets[0].closed)
        assertTrue(JSONObject(script.sockets[1].sent[0]).getJSONObject("setup").has("responseModalities"))
        assertEquals(1, c.rejections.size)
    }

    @Test fun triesWithoutSearchAsALastResort() {
        val script = Script(listOf(reject(retryable), reject(retryable), reject(retryable), accept))
        val c = client(script)
        assertNull(open(c))
        assertEquals(4, script.sockets.size)
        assertFalse(JSONObject(script.sockets[3].sent[0]).getJSONObject("setup").has("tools"))
    }

    @Test fun givesUpWithEveryReasonWhenAllShapesFail() {
        val script = Script(listOf(reject(retryable)))
        val c = client(script)
        val why = open(c)!!
        assertTrue(why.startsWith("setup was refused"))
        assertEquals(4, script.sockets.size)
    }

    @Test fun withoutSearchOnlyTwoShapesAreTried() {
        val script = Script(listOf(reject(retryable)))
        assertTrue(open(client(script), search = false)!!.startsWith("setup was refused"))
        assertEquals(2, script.sockets.size)
    }

    @Test fun anErrorThatAnotherShapeCannotFixStopsAtOnceAndHidesTheKey() {
        val script = Script(listOf(reject("1008: API key not valid key=SECRET123")))
        val why = open(client(script))!!
        assertEquals(1, script.sockets.size)
        assertTrue(why.contains("API key not valid"))
        assertFalse(why.contains("SECRET123"))
    }

    @Test fun noAnswerInTimeIsAReadableError() {
        val script = Script(listOf({ _ -> }))
        assertEquals("the server did not answer the setup in time", open(client(script)))
        assertTrue(script.sockets[0].closed)
    }

    @Test fun serverEventsReachTheListenerAndCloseIsReportedOnce() {
        val script = Script(listOf(accept))
        val events = ArrayList<LiveEvent>()
        val c = client(script, events)
        assertNull(open(c))
        script.listener!!.onMessage("""{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"data":"AQID"}}]},"turnComplete":true}}""")
        assertEquals(2, events.size)
        assertTrue(events[0] is LiveEvent.Audio)
        script.listener!!.onClosed("1011: boom")
        script.listener!!.onClosed("again")
        assertEquals("1011: boom", (events.last() as LiveEvent.Closed).why)
        assertEquals(3, events.size)
        val before = script.sockets[0].sent.size
        c.sendAudio(byteArrayOf(1))
        assertEquals(before, script.sockets[0].sent.size)
        assertFalse(c.isOpen())
    }

    @Test fun hangingUpYourselfIsNotAnErrorEvent() {
        val script = Script(listOf(accept))
        val events = ArrayList<LiveEvent>()
        val c = client(script, events)
        assertNull(open(c))
        c.close()
        script.listener!!.onClosed("1000: bye")
        assertTrue(events.isEmpty())
        assertTrue(script.sockets[0].closed)
    }

    @Test fun aSocketThatDiesWhileSendingIsAClose() {
        val script = Script(listOf(accept))
        val events = ArrayList<LiveEvent>()
        val c = client(script, events)
        assertNull(open(c))
        script.sockets[0].closed = true // the connection dropped without a callback
        c.sendAudio(byteArrayOf(1))
        assertEquals("the connection was lost", (events.single() as LiveEvent.Closed).why)
    }

    // ---- session

    private class FakeMic(val fail: String? = null) : LiveAudioIn {
        var onChunk: ((ByteArray) -> Unit)? = null
        var stopped = 0
        override fun start(onChunk: (ByteArray) -> Unit): String? { if (fail != null) return fail; this.onChunk = onChunk; return null }
        override fun stop() { stopped++ }
    }

    private class FakeSpeaker : LiveAudioOut {
        val played = ArrayList<Int>()
        var flushed = 0
        var stopped = 0
        override fun play(pcm: ByteArray) { played.add(pcm.size) }
        override fun flush() { flushed++ }
        override fun stop() { stopped++ }
    }

    private class Rig(val script: Script, val mic: FakeMic, val speaker: FakeSpeaker, val states: ArrayList<String>, val lines: ArrayList<String>, val ended: IntArray, val session: LiveSession)

    private fun rig(replies: List<(LiveListener) -> Unit> = listOf(accept), mic: FakeMic = FakeMic(), half: Boolean = false): Rig {
        val script = Script(replies)
        val speaker = FakeSpeaker()
        val states = ArrayList<String>()
        val lines = ArrayList<String>()
        val ended = IntArray(1)
        val direct = Executor { it.run() }
        val s = LiveSession(script, "KEY123", "m", "Kore", true, "be brief", tools(), mic, speaker, direct, { states.add(it) }, { who, text -> lines.add("$who: $text") }, { ended[0]++ }, { half })
        return Rig(script, mic, speaker, states, lines, ended, s)
    }

    private fun push(r: Rig, json: String) = r.script.listener!!.onMessage(json)
    private val audioMsg = """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"data":"AQID"}}]}}}"""

    @Test fun startOpensTheConversationAndStreamsTheMic() {
        val r = rig()
        assertNull(r.session.start())
        assertTrue(r.session.isRunning())
        assertEquals(LiveSession.LISTENING, r.states.last())
        r.mic.onChunk!!(byteArrayOf(9, 9))
        assertTrue(r.script.sockets[0].sent.last().contains("realtimeInput"))
        // the tool the model may call went into the setup
        assertTrue(r.script.sockets[0].sent[0].contains("battery"))
    }

    @Test fun theModelsVoicePlaysAndTalkingOverItCutsItOff() {
        val r = rig()
        assertNull(r.session.start())
        push(r, audioMsg)
        push(r, audioMsg)
        assertEquals(listOf(3, 3), r.speaker.played)
        assertEquals(LiveSession.SPEAKING, r.states.last())
        push(r, """{"serverContent":{"interrupted":true}}""")
        assertEquals(1, r.speaker.flushed)
        assertEquals(LiveSession.LISTENING, r.states.last())
        push(r, audioMsg)
        push(r, """{"serverContent":{"turnComplete":true}}""")
        assertEquals(LiveSession.LISTENING, r.states.last())
    }

    @Test fun withoutHeadphonesTheMicIsMutedWhileTheModelSpeaks() {
        val r = rig(half = true)
        assertNull(r.session.start())
        val before = r.script.sockets[0].sent.size
        r.mic.onChunk!!(byteArrayOf(1))
        assertEquals(before + 1, r.script.sockets[0].sent.size)
        push(r, audioMsg)
        r.mic.onChunk!!(byteArrayOf(1))
        assertEquals(before + 1, r.script.sockets[0].sent.size)
        push(r, """{"serverContent":{"turnComplete":true}}""")
        r.mic.onChunk!!(byteArrayOf(1))
        assertEquals(before + 2, r.script.sockets[0].sent.size)
    }

    @Test fun withHeadphonesTheMicStaysOpenWhileItSpeaks() {
        val r = rig(half = false)
        assertNull(r.session.start())
        push(r, audioMsg)
        val before = r.script.sockets[0].sent.size
        r.mic.onChunk!!(byteArrayOf(1))
        assertEquals(before + 1, r.script.sockets[0].sent.size)
    }

    @Test fun transcriptsAreReported() {
        val r = rig()
        assertNull(r.session.start())
        push(r, """{"serverContent":{"inputTranscription":{"text":"what time is it"},"outputTranscription":{"text":"noon"}}}""")
        assertEquals(listOf("you: what time is it", "faze: noon"), r.lines)
    }

    @Test fun toolCallsRunThroughTheGateAndAllAnswersGoBackTogether() {
        val r = rig()
        assertNull(r.session.start())
        push(r, """{"toolCall":{"functionCalls":[{"id":"a","name":"battery","args":{}},{"id":"b","name":"nope","args":{}},{"id":"c","name":"boom","args":{}}]}}""")
        val reply = JSONObject(r.script.sockets[0].sent.last()).getJSONObject("toolResponse").getJSONArray("functionResponses")
        assertEquals(3, reply.length())
        assertEquals("81 percent", reply.getJSONObject(0).getJSONObject("response").getString("result"))
        assertEquals("not allowed", reply.getJSONObject(1).getJSONObject("response").getString("error"))
        assertTrue(reply.getJSONObject(2).getJSONObject("response").getString("error").contains("exploded"))
        assertEquals("a", reply.getJSONObject(0).getString("id"))
    }

    @Test fun aDroppedConnectionEndsLiveModeWithTheReason() {
        val r = rig()
        assertNull(r.session.start())
        r.script.listener!!.onClosed("1011: server error")
        assertFalse(r.session.isRunning())
        assertEquals(1, r.ended[0])
        assertEquals("Live mode ended: 1011: server error", r.states.last())
        assertTrue(r.mic.stopped >= 1)
        assertTrue(r.speaker.stopped >= 1)
    }

    @Test fun stopHangsUpOnceAndCanBeCalledAgain() {
        val r = rig()
        assertNull(r.session.start())
        r.session.stop()
        r.session.stop()
        assertEquals(1, r.ended[0])
        assertEquals("Live mode is off", r.states.last())
        assertTrue(r.script.sockets[0].closed)
    }

    @Test fun aSetupFailureLeavesNothingRunning() {
        val r = rig(listOf(reject("1008: API key not valid")))
        assertEquals("1008: API key not valid", r.session.start())
        assertFalse(r.session.isRunning())
        assertEquals("Live mode could not start: 1008: API key not valid", r.states.last())
        assertNull(r.mic.onChunk)
    }

    @Test fun aMicFailureClosesTheConversation() {
        val r = rig(mic = FakeMic(fail = "the microphone permission is not granted"))
        assertEquals("the microphone permission is not granted", r.session.start())
        assertFalse(r.session.isRunning())
        assertTrue(r.script.sockets[0].closed)
        assertEquals("Live mode could not start: the microphone permission is not granted", r.states.last())
    }

    @Test fun theInstructionAddsHowSpeechDiffers() {
        val i = LiveSession.instruction("Felix", "", listOf("battery"))
        assertTrue(i.contains("talking out loud"))
        assertNotNull(Persona.system("Felix"))
    }
}
