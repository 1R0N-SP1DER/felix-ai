package com.felix.faze

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GeminiTest {
    private fun msgs(vararg m: JSONObject) = JSONArray().also { a -> m.forEach { a.put(it) } }
    private fun user(t: String) = JSONObject().put("role", "user").put("content", t)
    private val tools = JSONArray("""[{"type":"function","function":{"name":"get_weather","description":"Weather","parameters":{"type":"object","properties":{"city":{"type":"string"}},"required":["city"],"additionalProperties":false}}}]""")

    @Test fun systemGoesToTheInstructionAndChatToContents() {
        val b = GeminiWire.body(msgs(JSONObject().put("role", "system").put("content", "Be Faze."), user("hi"), JSONObject().put("role", "assistant").put("content", "hello")), null, "")
        assertEquals("Be Faze.", b.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text"))
        val c = b.getJSONArray("contents")
        assertEquals(2, c.length())
        assertEquals("user", c.getJSONObject(0).getString("role"))
        assertEquals("model", c.getJSONObject(1).getString("role"))
        assertFalse(b.has("tools")); assertFalse(b.has("generationConfig"))
    }

    @Test fun toolsBecomeFunctionDeclarationsWithoutKeysGeminiRejects() {
        val b = GeminiWire.body(msgs(user("weather?")), tools, "low")
        val d = b.getJSONArray("tools").getJSONObject(0).getJSONArray("functionDeclarations").getJSONObject(0)
        assertEquals("get_weather", d.getString("name"))
        assertFalse(d.getJSONObject("parameters").has("additionalProperties"))
        assertEquals("city", d.getJSONObject("parameters").getJSONArray("required").getString(0))
        assertEquals("low", b.getJSONObject("generationConfig").getJSONObject("thinkingConfig").getString("thinkingLevel"))
    }

    @Test fun aToolRoundTripKeepsCallsSignaturesAndGroupsAnswers() {
        val calls = JSONArray()
            .put(JSONObject().put("id", "call_0:SIG1").put("type", "function").put("function", JSONObject().put("name", "get_weather").put("arguments", "{\"city\":\"Lagos\"}")))
            .put(JSONObject().put("id", "call_1").put("type", "function").put("function", JSONObject().put("name", "get_battery_status").put("arguments", "{}")))
        val b = GeminiWire.body(
            msgs(
                user("go"),
                JSONObject().put("role", "assistant").put("content", JSONObject.NULL).put("tool_calls", calls),
                JSONObject().put("role", "tool").put("tool_call_id", "call_0:SIG1").put("content", "sunny"),
                JSONObject().put("role", "tool").put("tool_call_id", "call_1").put("content", "90%"),
            ),
            tools, "",
        ).getJSONArray("contents")
        assertEquals(3, b.length())
        val model = b.getJSONObject(1)
        assertEquals("model", model.getString("role"))
        assertEquals("SIG1", model.getJSONArray("parts").getJSONObject(0).getString("thoughtSignature"))
        assertEquals("Lagos", model.getJSONArray("parts").getJSONObject(0).getJSONObject("functionCall").getJSONObject("args").getString("city"))
        assertFalse(model.getJSONArray("parts").getJSONObject(1).has("thoughtSignature"))
        val answers = b.getJSONObject(2).getJSONArray("parts")
        assertEquals(2, answers.length()) // both answers in one turn, in call order
        assertEquals("get_weather", answers.getJSONObject(0).getJSONObject("functionResponse").getString("name"))
        assertEquals("90%", answers.getJSONObject(1).getJSONObject("functionResponse").getJSONObject("response").getString("result"))
    }

    @Test fun parsesTextAndSkipsThoughts() {
        val r = GeminiWire.parse("""{"candidates":[{"content":{"parts":[{"text":"hmm","thought":true},{"text":"Hello "},{"text":"there"}]}}]}""")
        assertEquals("Hello there", r.content)
        assertTrue(r.toolCalls.isEmpty())
        assertFalse(r.streamed)
    }

    @Test fun parsesCallsWithSignatures() {
        val r = GeminiWire.parse("""{"candidates":[{"content":{"parts":[{"functionCall":{"name":"get_weather","args":{"city":"Lagos"}},"thoughtSignature":"ABC"},{"functionCall":{"name":"vibrate","args":{}}}]}}]}""")
        assertEquals(2, r.toolCalls.size)
        assertEquals("call_0:ABC", r.toolCalls[0].id)
        assertEquals("call_1", r.toolCalls[1].id)
        assertEquals("Lagos", JSONObject(r.toolCalls[0].arguments).getString("city"))
    }

    @Test fun blockedEmptyAndBrokenAnswersAreErrors() {
        for ((json, kind) in listOf(
            """{"promptFeedback":{"blockReason":"SAFETY"}}""" to "bad_request",
            """{"candidates":[{"finishReason":"MAX_TOKENS","content":{"parts":[]}}]}""" to "empty",
            """{"candidates":[]}""" to "empty",
            "not json" to "server",
        )) {
            try { GeminiWire.parse(json); fail(json) } catch (e: RemoteError) { assertEquals(json, kind, e.kind) }
        }
    }

    @Test fun thinkingLevelsMapToGeminiNames() {
        assertEquals("minimal", GeminiWire.thinkingFor("off")); assertEquals("high", GeminiWire.thinkingFor("max"))
        assertEquals("medium", GeminiWire.thinkingFor("medium")); assertEquals("", GeminiWire.thinkingFor("auto"))
    }

    private class Net(val status: Int, val body: String) : Transport {
        var url = ""; var key: String? = null; var sent = ""
        override fun get(url: String, headers: Map<String, String>, timeoutMs: Int) = HttpResult(200, emptyMap(), "")
        override fun post(url: String, headers: Map<String, String>, body: ByteArray, contentType: String, timeoutMs: Int): HttpResult {
            this.url = url; key = headers["x-goog-api-key"]; sent = String(body); return HttpResult(status, emptyMap(), this.body)
        }
        override fun postStream(url: String, headers: Map<String, String>, json: String, timeoutMs: Int, onLine: (String) -> Boolean) = HttpResult(200, emptyMap(), "")
    }

    @Test fun theClientSendsTheKeyInTheHeaderNotTheUrl() {
        val net = Net(200, """{"candidates":[{"content":{"parts":[{"text":"ok"}]}}]}""")
        val r = GeminiClient(net, { " k123 " }).chat("gemini-3.1-flash-lite", msgs(user("hi")), null, {}, { false })
        assertEquals("ok", r.content)
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-lite:generateContent", net.url)
        assertEquals("k123", net.key)
        assertFalse(net.url.contains("k123"))
    }

    @Test fun noKeyAndHttpErrorsAreClassified() {
        try { GeminiClient(Net(200, ""), { "" }).chat("m", msgs(user("x")), null, {}, { false }); fail() } catch (e: RemoteError) { assertEquals("auth", e.kind) }
        try { GeminiClient(Net(429, """{"error":{"message":"quota"}}"""), { "k" }).chat("m", msgs(user("x")), null, {}, { false }); fail() } catch (e: RemoteError) { assertEquals("rate_limit", e.kind) }
        try { GeminiClient(Net(401, "{}"), { "k" }).chat("m", msgs(user("x")), null, {}, { false }); fail() } catch (e: RemoteError) { assertEquals("auth", e.kind) }
    }
}
