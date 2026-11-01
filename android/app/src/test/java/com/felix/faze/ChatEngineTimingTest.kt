package com.felix.faze

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatEngineTimingTest {
    @Test fun reportsHowLongTheModelTook() {
        var now = 1000L
        val timings = ArrayList<Long>()
        val listener = object : EngineListener {
            override fun onBusy(busy: Boolean) {}
            override fun onStatus(text: String) {}
            override fun onTiming(llmMs: Long) { timings.add(llmMs) }
        }
        val config = object : EngineConfig {
            override fun model() = "m"
            override fun userName() = "Felix"
            override fun speak() = false
        }
        val speech = object : SpeechOut { override fun speak(sentence: String) {}; override fun stop() {} }
        val chat = object : ChatApi {
            override fun chat(model: String, messages: org.json.JSONArray, tools: org.json.JSONArray?, onToken: (String) -> Unit, cancelled: () -> Boolean): ChatResult {
                now += 1200 // the model "takes" 1.2 s
                return text("Done.")
            }
        }
        ChatEngine(Agent(chat, FakeTools()), config, speech, { it() }, { it.run() }, ArrayList(), listener, { now }).send("hi")
        assertEquals(listOf(1200L), timings)
    }

    @Test fun newMessagesDoNotReuseTheIdsOfALoadedChat() {
        val messages = ArrayList<ChatMessage>()
        messages.add(ChatMessage(7, "user", "old question"))
        messages.add(ChatMessage(8, "assistant", "old answer"))
        val listener = object : EngineListener {
            override fun onBusy(busy: Boolean) {}
            override fun onStatus(text: String) {}
        }
        val config = object : EngineConfig {
            override fun model() = "m"
            override fun userName() = "Felix"
            override fun speak() = false
        }
        val speech = object : SpeechOut { override fun speak(sentence: String) {}; override fun stop() {} }
        val chat = object : ChatApi {
            override fun chat(model: String, messages: org.json.JSONArray, tools: org.json.JSONArray?, onToken: (String) -> Unit, cancelled: () -> Boolean): ChatResult = text("New answer.")
        }
        ChatEngine(Agent(chat, FakeTools()), config, speech, { it() }, { it.run() }, messages, listener).send("new question")
        assertEquals(4, messages.size)
        assertEquals(4, messages.map { it.id }.toSet().size) // all different
        assertEquals("old answer", messages[1].text) // the old reply was not overwritten
        assertEquals("New answer.", messages[3].text)
    }

    @Test fun theDownloadCounterCountsWhatIsRead() {
        val s = CountingInputStream(ByteArray(1000).inputStream())
        s.read()
        s.read(ByteArray(300), 0, 300)
        s.readBytes()
        assertEquals(1000L, s.count)
    }
}
