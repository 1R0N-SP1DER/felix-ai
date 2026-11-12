package com.felix.faze

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CustomProviderTest {
    private val messages = JSONArray().put(JSONObject().put("role", "user").put("content", "hi"))
    private fun chunk(text: String) = "{\"choices\":[{\"delta\":{\"content\":\"$text\"}}]}"

    @Test fun addressesAreCleanedAndChecked() {
        assertEquals("https://api.deepseek.com/v1", CustomEndpoint.base("  https://api.deepseek.com/v1/  "))
        assertEquals("https://integrate.api.nvidia.com/v1", CustomEndpoint.base("https://integrate.api.nvidia.com/v1/chat/completions"))
        assertEquals("http://127.0.0.1:8080/v1", CustomEndpoint.base("http://127.0.0.1:8080/v1"))
        assertEquals("http://localhost:11434/v1", CustomEndpoint.base("http://localhost:11434/v1"))
    }

    @Test fun anInsecureOrEmptyAddressIsRefusedWithAReason() {
        assertNull(CustomEndpoint.base(""))
        assertTrue(CustomEndpoint.problem("")!!.contains("add the address"))
        assertTrue(CustomEndpoint.problem("http://192.168.1.5:8000/v1")!!.contains("https"))
        assertTrue(CustomEndpoint.problem("ftp://x.com")!!.contains("https"))
        assertTrue(CustomEndpoint.problem("not a url")!!.isNotEmpty())
        assertNull(CustomEndpoint.problem("https://integrate.api.nvidia.com/v1"))
    }

    @Test fun itChatsToTheAddressWithTheKeyAsABearerToken() {
        val t = FakeTransport(); t.streamLines = sse(chunk("Hey"), chunk(" you"))
        val out = CustomChat(t, { "https://integrate.api.nvidia.com/v1/" }, { "nvapi-test" }, { "NVIDIA" }).chat("meta/llama-3.3-70b-instruct", messages, null, { }, { false })
        assertEquals("Hey you", out.content)
        val call = t.calls.single()
        assertEquals("https://integrate.api.nvidia.com/v1/chat/completions", call.url)
        assertEquals("Bearer nvapi-test", call.headers["Authorization"])
        assertEquals("meta/llama-3.3-70b-instruct", JSONObject(call.body).getString("model"))
    }

    @Test fun noKeyMeansNoAuthorizationHeader() {
        val t = FakeTransport(); t.streamLines = sse(chunk("ok"))
        CustomChat(t, { "http://127.0.0.1:8080/v1" }, { "" }, { "" }).chat("local", messages, null, { }, { false })
        assertFalse(t.calls.single().headers.containsKey("Authorization"))
    }

    @Test fun missingAddressOrModelIsExplainedNotCrashed() {
        val t = FakeTransport()
        try { CustomChat(t, { "" }, { "" }, { "NVIDIA" }).chat("m", messages, null, { }, { false }); fail() } catch (e: RemoteError) { assertTrue(e.message!!, e.message!!.contains("NVIDIA") && e.message!!.contains("add the address")) }
        try { CustomChat(t, { "https://x.com/v1" }, { "k" }, { "NVIDIA" }).chat(" ", messages, null, { }, { false }); fail() } catch (e: RemoteError) { assertTrue(e.message!!.contains("no model set")) }
        assertTrue(t.calls.isEmpty())
    }

    @Test fun theModelListIsReadFromTheAddress() {
        val t = FakeTransport()
        t.onGet = { url -> assertEquals("https://integrate.api.nvidia.com/v1/models", url); HttpResult(200, emptyMap(), "{\"data\":[{\"id\":\"meta/llama-3.3-70b-instruct\"},{\"id\":\"nvidia/nv-embed-v1\"},{\"id\":\"deepseek-ai/deepseek-r1\"}]}") }
        val list = ModelCatalog(t, { "nvapi-test" }, { "https://integrate.api.nvidia.com/v1" }).fetch("custom")
        assertEquals(listOf("deepseek-ai/deepseek-r1", "meta/llama-3.3-70b-instruct"), list.map { it.id })
        assertEquals("Bearer nvapi-test", t.calls.single().headers["Authorization"])
    }

    @Test fun theModelListWorksWithoutAKeyForALocalServer() {
        val t = FakeTransport()
        t.onGet = { HttpResult(200, emptyMap(), "{\"data\":[{\"id\":\"qwen3\"}]}") }
        assertEquals(listOf("qwen3"), ModelCatalog(t, { "" }, { "http://127.0.0.1:1234/v1" }).fetch("custom").map { it.id })
        assertFalse(t.calls.single().headers.containsKey("Authorization"))
    }

    @Test fun customIsOnTheModelSheetAndNeedsOnlyAnAddress() {
        assertEquals("Custom", ModelSheet.label("custom"))
        assertTrue("custom" in ModelSheet.live)
        assertFalse(ModelSheet.thinkEnabled("custom"))
        val rows = ModelSheet.rows(emptySet(), mapOf("custom" to ""))
        assertTrue(rows.first { it.id == "custom" }.sub.contains("address"))
    }
}
