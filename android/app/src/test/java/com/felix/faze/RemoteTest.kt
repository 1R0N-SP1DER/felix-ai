package com.felix.faze

import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RemoteTest {
    private val transport = FakeTransport()
    private val messages = JSONArray().put(JSONObject().put("role", "user").put("content", "hi"))

    private fun client(key: String = "gsk_test") = RemoteClient(transport, Groq.BASE, { key }, "Groq")

    private fun chat(tools: JSONArray? = null, tokens: MutableList<String> = ArrayList(), cancelled: () -> Boolean = { false }) =
        client().chat("m1", messages, tools, { tokens.add(it) }, cancelled)

    private fun expectError(block: () -> Unit): RemoteError {
        try {
            block()
        } catch (e: RemoteError) {
            return e
        }
        fail("expected a RemoteError")
        throw IllegalStateException()
    }

    @Test
    fun streamsTextAndSendsWhatItShould() {
        transport.streamLines = sse(delta("Hel"), delta("lo"), delta(" there"))
        val tokens = ArrayList<String>()
        val out = chat(tokens = tokens)
        assertEquals("Hello there", out.content)
        assertTrue(out.toolCalls.isEmpty())
        assertTrue(out.streamed)
        assertEquals(listOf("Hel", "lo", " there"), tokens)
        val call = transport.calls[0]
        assertEquals("https://api.groq.com/openai/v1/chat/completions", call.url)
        assertEquals("Bearer gsk_test", call.headers["Authorization"])
        val sent = JSONObject(call.body)
        assertEquals("m1", sent.getString("model"))
        assertTrue(sent.getBoolean("stream"))
        assertEquals(1000, sent.getInt("max_tokens"))
        assertFalse(sent.has("tools"))
    }

    @Test
    fun toolCallsAreAssembledAcrossChunksInOrder() {
        transport.streamLines = sse(
            delta(toolCalls = listOf(tc(1, "get_weather", "{\"ci", "b"))),
            delta(toolCalls = listOf(tc(0, "get_current_datetime", "{}", "a"))),
            delta(toolCalls = listOf(tc(1, "", "ty\": \"Abuja\"}"))),
        )
        val tools = JSONArray().put(JSONObject().put("type", "function"))
        val out = chat(tools = tools)
        assertEquals(listOf(ToolCall("a", "get_current_datetime", "{}"), ToolCall("b", "get_weather", "{\"city\": \"Abuja\"}")), out.toolCalls)
        assertFalse(out.streamed)
        val sent = JSONObject(transport.calls[0].body)
        assertEquals("auto", sent.getString("tool_choice"))
        assertEquals(1, sent.getJSONArray("tools").length())
    }

    @Test
    fun noKeyAsksForNothing() {
        val e = expectError { client(key = "  ").chat("m", messages, null, {}, { false }) }
        assertEquals("auth", e.kind)
        assertTrue(transport.calls.isEmpty())
    }

    @Test
    fun aNetworkFailureIsANetworkError() {
        transport.streamError = IOException("no route")
        assertEquals("network", expectError { chat() }.kind)
    }

    @Test
    fun errorStatuses() {
        val cases = listOf(
            Triple(401, "{\"error\": {\"message\": \"Invalid API Key\"}}", "auth"),
            Triple(429, "{\"error\": {\"message\": \"Rate limit reached for model\"}}", "rate_limit"),
            Triple(400, "{\"error\": {\"message\": \"Failed to call a function.\", \"code\": \"tool_use_failed\"}}", "tool_failed"),
            Triple(400, "{\"error\": {\"message\": \"The model `nope` does not exist\"}}", "model"),
            Triple(404, "{\"error\": {\"message\": \"No endpoints found\"}}", "model"),
            Triple(403, "{\"error\": {\"message\": \"m1 is only available on agentic harnesses. Try another.\"}}", "model"),
            Triple(403, "{\"error\": {\"message\": \"Forbidden\"}}", "auth"),
            Triple(503, "upstream down", "server"),
            Triple(400, "{\"error\": {\"message\": \"messages must be non-empty\"}}", "bad_request"),
        )
        for ((status, body, kind) in cases) {
            transport.streamStatus = status
            transport.streamBody = body
            assertEquals("$status $body", kind, expectError { chat() }.kind)
        }
    }

    @Test
    fun retryAfterFromHeaders() {
        assertEquals(30.0, parseRetryAfter(mapOf("retry-after" to "30")), 0.0)
        assertEquals(90.0, parseRetryAfter(mapOf("x-ratelimit-reset-requests" to "1m30s")), 0.0)
        assertEquals(2.5, parseRetryAfter(mapOf("x-ratelimit-reset-tokens" to "2.5s")), 0.0)
        assertEquals(3600.0 + 120.0 + 5.0, parseRetryAfter(mapOf("x-ratelimit-reset-requests" to "1h2m5s")), 0.0)
        assertEquals(0.0, parseRetryAfter(mapOf("x-ratelimit-reset-requests" to "soon")), 0.0)
        assertEquals(0.0, parseRetryAfter(emptyMap()), 0.0)
        transport.streamStatus = 429
        transport.streamBody = "{}"
        transport.streamHeaders = mapOf("retry-after" to "30")
        assertEquals(30.0, expectError { chat() }.retryAfter, 0.0)
    }

    @Test
    fun aMidStreamErrorObject() {
        transport.streamLines = sse(delta("par"), "{\"error\": {\"message\": \"upstream exploded\"}}")
        val e = expectError { chat() }
        assertEquals("server", e.kind)
        assertTrue(e.message!!.contains("upstream exploded"))
    }

    @Test
    fun cancellingStopsTheStream() {
        transport.streamLines = sse(delta("a"), delta("b"))
        val tokens = ArrayList<String>()
        try {
            chat(tokens = tokens, cancelled = { true })
            fail("expected Cancelled")
        } catch (e: Cancelled) {
            assertTrue(tokens.isEmpty())
        }
    }

    @Test
    fun junkLinesAreSkipped() {
        transport.streamLines = listOf("", ": keep-alive", "data: not json", "data: " + delta("fine"), "data: [DONE]", "data: " + delta("ignored"))
        assertEquals("fine", chat().content)
    }
}
