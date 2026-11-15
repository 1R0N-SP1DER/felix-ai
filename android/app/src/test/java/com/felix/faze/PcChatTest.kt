package com.felix.faze

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcChatTest {
    private fun msgs(text: String) = JSONArray().put(JSONObject().put("role", "user").put("content", text))

    @Test fun aConnectedComputerComesBeforeThePhonesOwnModelButAfterReadyOnlineProviders() {
        val keys = setOf("groq")
        val online = AutoMode.plan(Task.CASUAL, true, keys, { false }, { "m" }, "/a.gguf", "/b.gguf", pc = true).map { it.provider }
        assertEquals(listOf("groq", "pc", "offline", "offline"), online)
        val offline = AutoMode.plan(Task.CASUAL, false, keys, { false }, { "m" }, "/a.gguf", "/b.gguf", pc = true).map { it.provider }
        assertEquals(listOf("pc", "offline", "offline", "groq"), offline)
        assertTrue(AutoMode.plan(Task.CASUAL, false, keys, { false }, { "m" }, "/a.gguf", "/b.gguf").none { it.provider == "pc" })
    }

    @Test fun returnsTheComputersAnswerAndSendsTheConversation() {
        var sent: JSONObject? = null
        val api = PcChat({ m, _, _ -> sent = m; JSONObject().put("t", "pc_chat_ok").put("content", " hello from the PC ") })
        val got = api.chat("ollama", msgs("hi"), null, { }, { false })
        assertEquals("hello from the PC", got.content)
        assertEquals("pc_chat", sent!!.getString("t"))
        assertEquals(1, sent!!.getJSONArray("messages").length())
        assertEquals(false, sent!!.getBoolean("deep"))
    }

    @Test fun codeQuestionsAskForTheBiggerModel() {
        var deep = false
        PcChat({ m, _, _ -> deep = m.getBoolean("deep"); JSONObject().put("content", "x") }).chat("ollama", msgs("write a python function to sort a list"), null, { }, { false })
        assertTrue(deep)
    }

    @Test fun problemsBecomeErrorsThatLetAutoTryTheNextProvider() {
        fun kind(f: () -> JSONObject): String = try { PcChat({ _, _, _ -> f() }).chat("ollama", msgs("hi"), null, { }, { false }); "" } catch (e: RemoteError) { e.kind }
        assertEquals("network", kind { throw java.io.IOException("gone") })
        assertEquals("server", kind { JSONObject().put("t", "error").put("message", "ollama is not running") })
        assertEquals("empty", kind { JSONObject().put("content", "  ") })
    }

    @Test fun aStopIsNotHiddenAsAnError() {
        var stopped = false
        try { PcChat({ _, _, _ -> throw Cancelled() }).chat("ollama", msgs("hi"), null, { }, { false }) } catch (e: Cancelled) { stopped = true }
        assertTrue(stopped)
    }
}
