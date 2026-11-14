package com.felix.faze

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoModeTest {
    private val all = setOf("groq", "gemini", "openrouter", "nvidia")
    private fun plan(task: Task, online: Boolean, resting: Set<String> = emptySet(), keys: Set<String> = all, casual: String = "/m/lfm.gguf", deep: String = "/m/qwen.gguf") =
        AutoMode.plan(task, online, keys, { it in resting }, { "m-$it" }, casual, deep).map { it.provider + ":" + it.model }

    @Test fun requestsAreSortedIntoChatCodeAndDeepWork() {
        assertEquals(Task.CASUAL, AutoMode.kindOf("hey how's your day going"))
        assertEquals(Task.CODING, AutoMode.kindOf("why does this Kotlin function throw an error"))
        assertEquals(Task.CODING, AutoMode.kindOf("fix this\n```x = 1```"))
        assertEquals(Task.DEEP, AutoMode.kindOf("compare the two economies for me"))
        assertEquals(Task.DEEP, AutoMode.kindOf("hello", "max"))
        assertEquals(Task.CASUAL, AutoMode.kindOf("hello", "medium"))
        assertEquals(Task.DEEP, AutoMode.kindOf("a".repeat(600)))
    }

    @Test fun onlineChatPrefersGroqAndOpenRouterButCodePrefersGemini() {
        val chat = plan(Task.CASUAL, true).map { it.substringBefore(":") }
        assertEquals(listOf("groq", "openrouter", "gemini", "nvidia", "offline", "offline"), chat)
        assertEquals("gemini", plan(Task.CODING, true).first().substringBefore(":"))
    }

    @Test fun aProviderOutOfAllowanceGoesToTheBackButOfflineComesBeforeIt() {
        val p = plan(Task.CASUAL, true, resting = setOf("groq")).map { it.substringBefore(":") }
        assertEquals("openrouter", p.first())
        assertTrue(p.indexOf("offline") < p.indexOf("groq"))
        assertEquals("groq", p.last())
    }

    @Test fun offlineUsesTheSmallModelForChatAndTheBigOneForCode() {
        assertEquals("offline:/m/lfm.gguf", plan(Task.CASUAL, false).first())
        assertEquals("offline:/m/qwen.gguf", plan(Task.CODING, false).first())
        assertEquals("offline:/m/qwen.gguf", plan(Task.DEEP, false).first())
        assertEquals("groq", plan(Task.CASUAL, false).first { !it.startsWith("offline") }.substringBefore(":"))
    }

    @Test fun noKeysAndNoModelsLeavesNothingToTry() {
        assertTrue(AutoMode.plan(Task.CASUAL, true, emptySet(), { false }, { "" }, "", "").isEmpty())
        assertEquals(listOf("offline"), AutoMode.plan(Task.CASUAL, true, emptySet(), { false }, { "" }, "/a.gguf", "/a.gguf").map { it.provider })
    }

    @Test fun offlineModelsAreChosenByPickThenNameThenFallback() {
        val files = listOf("/h/Qwen3-4B.gguf", "/h/LFM2-1.2B.gguf", "/h/other.gguf")
        assertEquals("/x/mine.gguf", AutoMode.offlineFor(Task.CASUAL, "/x/mine.gguf", "", files, "/f.gguf"))
        assertEquals("/h/LFM2-1.2B.gguf", AutoMode.offlineFor(Task.CASUAL, "", "", files, "/f.gguf"))
        assertEquals("/h/Qwen3-4B.gguf", AutoMode.offlineFor(Task.DEEP, "", "", files, "/f.gguf"))
        assertEquals("/f.gguf", AutoMode.offlineFor(Task.DEEP, "", "", emptyList(), "/f.gguf"))
    }

    @Test fun limitsRestForTheRightTime() {
        var t = 0L
        val l = ProviderLimits { t }
        l.note("groq", RemoteError("rate_limit", "slow down", 5.0))
        assertTrue(l.isResting("groq"))
        t = 59_000; assertTrue(l.isResting("groq"))
        t = 61_000; assertFalse(l.isResting("groq"))
        l.note("gemini", RemoteError("rate_limit", "You exceeded your current quota"))
        t = 61_000 + 3_000_000; assertTrue(l.isResting("gemini"))
        l.note("nvidia", RemoteError("server", "busy"))
        assertFalse(l.isResting("nvidia"))
    }

    @Test fun theNewestUserMessageIsFound() {
        val m = JSONArray().put(JSONObject().put("role", "user").put("content", "old"))
            .put(JSONObject().put("role", "assistant").put("content", "x"))
            .put(JSONObject().put("role", "user").put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "new one"))))
            .put(JSONObject().put("role", "tool").put("content", "r"))
        assertEquals("new one ", AutoMode.lastUserText(m))
    }

    @Test fun resilientChatUsesTheAutoPlanAndReportsLimits() {
        val groq = object : ChatApi {
            override fun chat(model: String, messages: JSONArray, tools: JSONArray?, onToken: (String) -> Unit, cancelled: () -> Boolean): ChatResult =
                throw RemoteError("rate_limit", "limit", 0.0)
        }
        val gem = object : ChatApi {
            override fun chat(model: String, messages: JSONArray, tools: JSONArray?, onToken: (String) -> Unit, cancelled: () -> Boolean) = ChatResult("gemini ok", emptyList(), false)
        }
        val limited = ArrayList<String>()
        val r = ResilientChat(mapOf("groq" to groq, "gemini" to gem), { emptyList() }, { }, { }, 0, 1,
            { listOf(Attempt("groq", "g"), Attempt("gemini", "m")) }, { p, _ -> limited.add(p) }).chat("x", JSONArray(), null, { }, { false })
        assertEquals("gemini ok", r.content)
        assertEquals(listOf("groq"), limited)
    }
}
