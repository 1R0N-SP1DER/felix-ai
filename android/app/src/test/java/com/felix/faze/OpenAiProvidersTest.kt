package com.felix.faze

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiProvidersTest {
    private val messages = JSONArray().put(JSONObject().put("role", "user").put("content", "hi"))

    @Test fun nvidiaHasItsOwnAddressAndDefault() {
        assertFalse(OpenAiProviders.has("deepseek"))
        assertEquals("https://integrate.api.nvidia.com/v1", OpenAiProviders.all.getValue("nvidia").base)
        assertTrue(OpenAiProviders.has("nvidia") && !OpenAiProviders.has("groq"))
    }

    @Test fun theyAreOnTheModelSheetAndNeedAKey() {
        assertEquals("NVIDIA", ModelSheet.label("nvidia"))
        assertTrue(ModelSheet.live.containsAll(listOf("nvidia")))
        val rows = ModelSheet.rows(setOf("nvidia"), mapOf("nvidia" to "meta/llama-3.3-70b-instruct"))
        assertEquals("ok", rows.first { it.id == "nvidia" }.dot)
    }

    @Test fun theModelListComesFromEachServicesOwnAddress() {
        for ((id, base) in listOf("nvidia" to "https://integrate.api.nvidia.com/v1/models")) {
            val t = FakeTransport()
            t.onGet = { url -> assertEquals(base, url); HttpResult(200, emptyMap(), "{\"data\":[{\"id\":\"model-b\"},{\"id\":\"model-a\"}]}") }
            assertEquals(listOf("model-a", "model-b"), ModelCatalog(t) { "key-$id" }.fetch(id).map { it.id })
            assertEquals("Bearer key-$id", t.calls.single().headers["Authorization"])
        }
    }

    @Test fun aMissingKeyIsExplained() {
        try { ModelCatalog(FakeTransport()) { "" }.fetch("nvidia"); org.junit.Assert.fail() } catch (e: RemoteError) { assertTrue(e.message!!.contains("NVIDIA key")) }
    }

    @Test fun chatGoesToTheRightAddress() {
        val t = FakeTransport(); t.streamLines = sse("{\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}")
        val info = OpenAiProviders.all.getValue("nvidia")
        RemoteClient(t, info.base, { "sk-test" }, info.label).chat(info.defaultModel, messages, null, { }, { false })
        assertEquals("https://integrate.api.nvidia.com/v1/chat/completions", t.calls.single().url)
        assertEquals("Bearer sk-test", t.calls.single().headers["Authorization"])
    }
}
