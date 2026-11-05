package com.felix.faze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ModelCatalogTest {
    private val groq = """{"data":[{"id":"openai/gpt-oss-120b","active":true},{"id":"whisper-large-v3","active":true},{"id":"llama-3.3-70b-versatile"},
        {"id":"meta-llama/llama-guard-4-12b"},{"id":"playai-tts"},{"id":"old-model","active":false},{"id":"openai/gpt-oss-20b"}]}"""
    private val gemini = """{"models":[
        {"name":"models/gemini-3.1-flash-lite","displayName":"Gemini 3.1 Flash Lite","supportedGenerationMethods":["generateContent","countTokens"]},
        {"name":"models/gemini-embedding-001","supportedGenerationMethods":["embedContent"]},
        {"name":"models/gemini-2.5-flash-image","supportedGenerationMethods":["generateContent"]},
        {"name":"models/gemma-3-27b-it","supportedGenerationMethods":["generateContent"]},
        {"name":"models/gemini-2.5-pro","displayName":"Gemini 2.5 Pro","supportedGenerationMethods":["generateContent"]}]}"""
    private val router = """{"data":[
        {"id":"z/paid","pricing":{"prompt":"0.1","completion":"0.2"},"supported_parameters":["tools"]},
        {"id":"a/free:free","pricing":{"prompt":"0","completion":"0"},"supported_parameters":["tools","temperature"]},
        {"id":"b/notools:free","pricing":{"prompt":"0","completion":"0"},"supported_parameters":["temperature"]},
        {"id":"c/paid2","pricing":{"prompt":"1","completion":"1"},"supported_parameters":["tools"]}]}"""

    @Test fun groqListKeepsOnlyActiveChatModels() {
        assertEquals(listOf("llama-3.3-70b-versatile", "openai/gpt-oss-120b", "openai/gpt-oss-20b"), ModelLists.groq(groq).map { it.id })
        assertEquals("gpt-oss-120b", ModelLists.groq(groq).first { it.id == "openai/gpt-oss-120b" }.label)
    }

    @Test fun geminiListKeepsGeminiChatModelsWithoutEmbeddingOrImageOnes() {
        val ids = ModelLists.gemini(gemini).map { it.id }
        assertEquals(listOf("gemini-2.5-pro", "gemini-3.1-flash-lite"), ids)
        assertEquals("Gemini 3.1 Flash Lite", ModelLists.gemini(gemini).first { it.id == "gemini-3.1-flash-lite" }.note)
    }

    @Test fun openrouterListHasToolModelsFreeFirst() {
        val l = ModelLists.openrouter(router)
        assertEquals(listOf("a/free:free", "c/paid2", "z/paid"), l.map { it.id })
        assertEquals("free", l[0].note); assertEquals("", l[1].note)
    }

    @Test fun filterMatchesIdsAndNotesAndCapsTheCount() {
        val l = ModelLists.openrouter(router)
        assertEquals(listOf("a/free:free"), ModelLists.filter(l, "FREE").map { it.id })
        assertEquals(2, ModelLists.filter(l, "", limit = 2).size)
        assertTrue(ModelLists.filter(l, "zzz").isEmpty())
    }

    @Test fun emptyOrOddJsonGivesAnEmptyList() {
        assertTrue(ModelLists.groq("""{"x":1}""").isEmpty())
        assertTrue(ModelLists.gemini("""{"models":[]}""").isEmpty())
    }

    private class Net(val status: Int, val body: String) : Transport {
        var url = ""; var headers: Map<String, String> = emptyMap()
        override fun get(url: String, headers: Map<String, String>, timeoutMs: Int): HttpResult { this.url = url; this.headers = headers; return HttpResult(status, emptyMap(), body) }
        override fun post(url: String, headers: Map<String, String>, body: ByteArray, contentType: String, timeoutMs: Int) = HttpResult(200, emptyMap(), "")
        override fun postStream(url: String, headers: Map<String, String>, json: String, timeoutMs: Int, onLine: (String) -> Boolean) = HttpResult(200, emptyMap(), "")
    }

    @Test fun keysGoInHeadersAndTheRightAddressIsUsed() {
        val g = Net(200, gemini)
        assertEquals(2, ModelCatalog(g) { "KEY" }.fetch("gemini").size)
        assertTrue(g.url.startsWith("https://generativelanguage.googleapis.com/v1beta/models"))
        assertEquals("KEY", g.headers["x-goog-api-key"]); assertFalse(g.url.contains("KEY"))
        val q = Net(200, groq)
        ModelCatalog(q) { "K2" }.fetch("groq")
        assertEquals("Bearer K2", q.headers["Authorization"]); assertTrue(q.url.endsWith("/models"))
    }

    @Test fun openrouterWorksWithoutAKeyButOthersNeedOne() {
        assertEquals(3, ModelCatalog(Net(200, router)) { "" }.fetch("openrouter").size)
        for (p in listOf("groq", "gemini")) try { ModelCatalog(Net(200, "{}")) { "" }.fetch(p); fail(p) } catch (e: RemoteError) { assertEquals("auth", e.kind) }
    }

    @Test fun httpAndParseProblemsAreClearErrors() {
        try { ModelCatalog(Net(401, "{}")) { "k" }.fetch("groq"); fail() } catch (e: RemoteError) { assertEquals("auth", e.kind) }
        try { ModelCatalog(Net(200, "not json")) { "k" }.fetch("groq"); fail() } catch (e: RemoteError) { assertEquals("server", e.kind) }
        try { ModelCatalog(Net(200, """{"data":[]}""")) { "k" }.fetch("groq"); fail() } catch (e: RemoteError) { assertEquals("empty", e.kind) }
    }
}
