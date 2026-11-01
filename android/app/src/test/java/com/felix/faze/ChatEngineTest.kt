package com.felix.faze

import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatEngineTest {
    private class Speech : SpeechOut {
        val spoken = ArrayList<String>()
        val sinceStop = ArrayList<String>()
        var stops = 0

        override fun speak(sentence: String) {
            spoken.add(sentence)
            sinceStop.add(sentence)
        }

        override fun stop() {
            stops++
            sinceStop.clear()
        }
    }

    private class Listener : EngineListener {
        val busy = ArrayList<Boolean>()
        var status = ""

        override fun onBusy(busy: Boolean) {
            this.busy.add(busy)
        }

        override fun onStatus(text: String) {
            status = text
        }
    }

    private class Config(var speak: Boolean = true) : EngineConfig {
        override fun model() = "test-model"
        override fun userName() = "Felix"
        override fun speak() = speak
    }

    private class Queue : Executor {
        val tasks = ArrayList<Runnable>()

        override fun execute(command: Runnable) {
            tasks.add(command)
        }

        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeAt(0).run()
        }
    }

    private val messages = ArrayList<ChatMessage>()
    private val speech = Speech()
    private val listener = Listener()
    private val config = Config()
    private val tools = FakeTools()

    private fun engine(chat: FakeChat, executor: Executor = Executor { it.run() }) =
        ChatEngine(Agent(chat, tools), config, speech, { it() }, executor, messages, listener)

    @Test
    fun aReplyStreamsIntoTheMessageList() {
        val chat = FakeChat(FakeChat.Streamed(listOf("Half past ", "four.")))
        engine(chat).send("what is the time")
        assertEquals(listOf("user", "assistant"), messages.map { it.role })
        assertEquals("what is the time", messages[0].text)
        assertEquals("Half past four.", messages[1].text)
        assertEquals(listOf(true, false), listener.busy)
        assertEquals("", listener.status)
    }

    @Test
    fun theSystemPromptNamesTheUserAndTheTools() {
        val chat = FakeChat(text("ok"))
        engine(chat).send("hi")
        val system = chat.calls[0].messages.getJSONObject(0).getString("content")
        assertTrue(system.contains("Felix"))
        assertTrue(system.contains("You can call these tools: get_current_datetime, get_battery_status, get_weather"))
        assertEquals("test-model", chat.calls[0].model)
    }

    @Test
    fun toolStepsAreShownOnTheReply() {
        val chat = FakeChat(calls(Pair("get_current_datetime", "{}"), Pair("get_battery_status", "{}")), text("Done."))
        engine(chat).send("time and charge, please")
        assertEquals(listOf("get_current_datetime", "get_battery_status"), messages[1].steps)
        assertEquals("Done.", messages[1].text)
    }

    @Test
    fun speechStartsBeforeTheReplyHasFinished() {
        val atSecondPiece = ArrayList<List<String>>()
        val chat = object : ChatApi {
            override fun chat(model: String, messages: org.json.JSONArray, tools: org.json.JSONArray?, onToken: (String) -> Unit, cancelled: () -> Boolean): ChatResult {
                onToken("The answer is forty two, as expected. ")
                atSecondPiece.add(ArrayList(speech.spoken)) // what had been spoken before the rest arrived
                onToken("Ask me anything else.")
                return ChatResult("The answer is forty two, as expected. Ask me anything else.", emptyList(), true)
            }
        }
        ChatEngine(Agent(chat, tools), config, speech, { it() }, { it.run() }, messages, listener).send("question")
        assertEquals(listOf(listOf("The answer is forty two, as expected.")), atSecondPiece)
        assertEquals(listOf("The answer is forty two, as expected.", "Ask me anything else."), speech.spoken)
    }

    @Test
    fun aReplyThatNeverStreamedIsStillSpoken() {
        engine(FakeChat(text("Done. The timer is set for five minutes."))).send("set a timer")
        assertEquals(listOf("Done. The timer is set for five minutes."), speech.spoken)
        assertEquals("Done. The timer is set for five minutes.", messages[1].text)
    }

    @Test
    fun textDroppedByAToolCallIsNotSpokenAfterTheReset() {
        val chat = FakeChat(calls(Pair("get_current_datetime", "{}"), content = "Let me check that for you now. "), FakeChat.Streamed(listOf("It is sunny and warm outside.")))
        engine(chat).send("what is it like out")
        assertTrue(speech.stops >= 1)
        assertTrue(speech.sinceStop.any { it.contains("sunny") })
        assertFalse(speech.sinceStop.any { it.contains("check that") })
        assertEquals("It is sunny and warm outside.", messages[1].text)
    }

    @Test
    fun speakingCanBeSwitchedOff() {
        config.speak = false
        engine(FakeChat(FakeChat.Streamed(listOf("A quiet reply that is plain. ")))).send("hi")
        assertTrue(speech.spoken.isEmpty())
        assertEquals("A quiet reply that is plain. ", messages[1].text.let { if (it.endsWith(" ")) it else "$it " })
    }

    @Test
    fun anErrorShowsInTheStatusAndLeavesNoEmptyBubble() {
        engine(FakeChat(RemoteError("rate_limit", "rate limited: slow down", 30.0))).send("hello")
        assertEquals(listOf("user"), messages.map { it.role })
        assertTrue(listener.status, listener.status.contains("rate limited: slow down"))
        assertTrue(listener.status.contains("Try again in 30 seconds."))
        assertEquals(listOf(true, false), listener.busy)
    }

    @Test
    fun anUnexpectedErrorIsAlsoCaught() {
        val chat = object : ChatApi {
            override fun chat(model: String, messages: org.json.JSONArray, tools: org.json.JSONArray?, onToken: (String) -> Unit, cancelled: () -> Boolean): ChatResult =
                throw IllegalStateException("boom")
        }
        ChatEngine(Agent(chat, tools), config, speech, { it() }, { it.run() }, messages, listener).send("hello")
        assertTrue(listener.status, listener.status.contains("boom"))
        assertEquals(listOf(true, false), listener.busy)
    }

    @Test
    fun stoppingKeepsWhatWasWrittenAndSilencesTheSpeech() {
        val queue = Queue()
        val chat = FakeChat(Cancelled())
        val engine = engine(chat, queue)
        engine.send("hello")
        engine.stop()
        queue.runAll()
        assertEquals(listOf(true, false), listener.busy)
        assertTrue(speech.stops >= 1)
        assertEquals("", listener.status)
    }

    @Test
    fun aSecondMessageWhileBusyIsIgnored() {
        val queue = Queue()
        val engine = engine(FakeChat(text("first"), text("second")), queue)
        engine.send("one")
        assertTrue(engine.isBusy())
        engine.send("two")
        assertEquals(listOf("user", "assistant"), messages.map { it.role })
        queue.runAll()
        assertFalse(engine.isBusy())
        engine.send("three")
        queue.runAll()
        assertEquals(listOf("user", "assistant", "user", "assistant"), messages.map { it.role })
        assertEquals(listOf("one", "first", "three", "second"), messages.map { it.text })
    }

    @Test
    fun blankMessagesAreIgnored() {
        val chat = FakeChat()
        engine(chat).send("   ")
        assertTrue(messages.isEmpty())
        assertTrue(chat.calls.isEmpty())
    }

    @Test
    fun theHistoryDropsEmptyMessagesAndKeepsTheLastTwenty() {
        for (i in 0 until 30) {
            messages.add(ChatMessage(1000L + i, if (i % 2 == 0) "user" else "assistant", "message $i"))
        }
        messages.add(ChatMessage(2000L, "assistant", ""))
        val chat = FakeChat(text("ok"))
        engine(chat).send("latest")
        val sent = chat.calls[0].messages
        assertEquals(1 + ChatEngine.MAX_HISTORY, sent.length())
        assertEquals("latest", sent.getJSONObject(sent.length() - 1).getString("content"))
        assertEquals("message 11", sent.getJSONObject(1).getString("content"))
        assertFalse((0 until sent.length()).any { sent.getJSONObject(it).getString("content").isEmpty() })
    }
}
