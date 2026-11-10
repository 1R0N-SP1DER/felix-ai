package com.felix.faze

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ResilientTest {
    private val msgs = JSONArray()

    /** A provider that fails with the given errors in turn, then answers. */
    private class Flaky(val name: String, vararg val failures: RemoteError, val tokensFirst: Boolean = false) : ChatApi {
        var calls = 0
        val models = ArrayList<String>()
        override fun chat(model: String, messages: JSONArray, tools: JSONArray?, onToken: (String) -> Unit, cancelled: () -> Boolean): ChatResult {
            models.add(model)
            val i = calls++
            if (i < failures.size) {
                if (tokensFirst) onToken("partial")
                throw failures[i]
            }
            return ChatResult("$name ok", emptyList(), false)
        }
    }

    private fun run(chats: Map<String, ChatApi>, plan: List<Attempt>, notes: MutableList<String> = ArrayList(), slept: MutableList<Long> = ArrayList(), cancelled: () -> Boolean = { false }) =
        ResilientChat(chats, { plan }, { notes.add(it) }, { slept.add(it) }, 1, 1000).chat("x", msgs, null, {}, cancelled)

    private val busy = RemoteError("server", "the provider had an error (503): high demand")

    @Test fun aBusyProviderIsRetriedOnceAfterAPause() {
        val g = Flaky("gemini", busy)
        val notes = ArrayList<String>(); val slept = ArrayList<Long>()
        val r = run(mapOf("gemini" to g), listOf(Attempt("gemini", "m1")), notes, slept)
        assertEquals("gemini ok", r.content)
        assertEquals(2, g.calls)
        assertEquals(listOf("Gemini is busy, trying again…"), notes)
        assertEquals(1000L, slept.sum())
    }

    @Test fun afterTheRetryItFallsBackAndSaysSo() {
        val g = Flaky("gemini", busy, busy)
        val q = Flaky("groq")
        val notes = ArrayList<String>()
        val r = run(mapOf("gemini" to g, "groq" to q), listOf(Attempt("gemini", "g1"), Attempt("groq", "q1")), notes)
        assertEquals("groq ok", r.content)
        assertEquals(2, g.calls); assertEquals(1, q.calls)
        assertEquals("Gemini is busy; answering with Groq", notes.last())
    }

    @Test fun sameProviderAlternatesComeBeforeOtherProviders() {
        val g = Flaky("gemini", busy, busy, busy) // first model fails twice (try + retry), the alternate fails once then works
        val r = run(mapOf("gemini" to g, "groq" to Flaky("groq")), listOf(Attempt("gemini", "g1"), Attempt("gemini", "g-alt"), Attempt("groq", "q1")))
        assertEquals("gemini ok", r.content)
        assertEquals(listOf("g1", "g1", "g-alt", "g-alt"), g.models)
    }

    @Test fun aRefusedRequestIsNotRetriedOrHidden() {
        val g = Flaky("gemini", RemoteError("bad_request", "request refused (400)"))
        try { run(mapOf("gemini" to g, "groq" to Flaky("groq")), listOf(Attempt("gemini", "m"), Attempt("groq", "q"))); fail() } catch (e: RemoteError) { assertEquals("bad_request", e.kind) }
        assertEquals(1, g.calls)
    }

    @Test fun neverRetriesOnceTextHasStartedArriving() {
        val g = Flaky("gemini", busy, tokensFirst = true)
        val q = Flaky("groq")
        try { run(mapOf("gemini" to g, "groq" to q), listOf(Attempt("gemini", "m"), Attempt("groq", "q"))); fail() } catch (e: RemoteError) { assertEquals("server", e.kind) }
        assertEquals(1, g.calls); assertEquals(0, q.calls)
    }

    @Test fun aMissingKeyOrBadModelFallsBackWithoutRetrying() {
        val g = Flaky("gemini", RemoteError("auth", "Gemini has no key"))
        val notes = ArrayList<String>()
        val r = run(mapOf("gemini" to g, "groq" to Flaky("groq")), listOf(Attempt("gemini", "m"), Attempt("groq", "q")), notes)
        assertEquals("groq ok", r.content)
        assertEquals(1, g.calls)
        assertEquals("Gemini has no working key; answering with Groq", notes.last())
    }

    @Test fun rateLimitWaitsAsLongAsAskedButNotMoreThanEightSeconds() {
        val slept = ArrayList<Long>()
        run(mapOf("g" to Flaky("g", RemoteError("rate_limit", "slow down", 3.0))), listOf(Attempt("g", "m")), slept = slept)
        assertEquals(3000L, slept.sum())
        slept.clear()
        run(mapOf("g" to Flaky("g", RemoteError("rate_limit", "slow down", 60.0))), listOf(Attempt("g", "m")), slept = slept)
        assertEquals(8000L, slept.sum())
    }

    @Test fun everythingFailingGivesTheLastError() {
        try { run(mapOf("g" to Flaky("g", busy, busy)), listOf(Attempt("g", "m"))); fail() } catch (e: RemoteError) { assertEquals("server", e.kind) }
        try { run(emptyMap(), emptyList()); fail() } catch (e: RemoteError) { assertTrue(e.message!!.contains("no model")) }
    }

    @Test fun cancellingStopsTheWait() {
        var calls = 0
        try { run(mapOf("g" to Flaky("g", busy, busy)), listOf(Attempt("g", "m")), cancelled = { calls++ > 1 }); fail() } catch (e: Cancelled) { /* stopped */ }
    }

    @Test fun thePlanOrdersChosenThenAlternatesThenOtherKeyedProviders() {
        val m = { p: String -> mapOf("groq" to "q1", "gemini" to "g1", "openrouter" to "").getValue(p) }
        assertEquals(
            listOf(Attempt("gemini", "g1"), Attempt("gemini", "gemini-flash-lite-latest"), Attempt("gemini", "gemini-2.5-flash-lite"), Attempt("groq", "q1")),
            Fallbacks.plan("gemini", setOf("gemini", "groq"), m),
        )
        assertEquals(listOf(Attempt("gemini", "g1"), Attempt("groq", "q1")), Fallbacks.plan("gemini", setOf("groq"), m)) // chosen first even without a key
        assertEquals(listOf(Attempt("groq", "q1")), Fallbacks.plan("groq", setOf("groq"), m))
        assertEquals(listOf(Attempt("offline", "/m.gguf")), Fallbacks.plan("offline", setOf("groq", "gemini"), { "/m.gguf" })) // offline never goes online by itself
    }

    // ---- free OpenRouter models

    private val list = """{"data":[
      {"id":"a/paid","pricing":{"prompt":"0.001","completion":"0.002"},"supported_parameters":["tools"],"context_length":128000},
      {"id":"b/free-no-tools:free","pricing":{"prompt":"0","completion":"0"},"supported_parameters":["temperature"],"context_length":200000},
      {"id":"c/other:free","pricing":{"prompt":"0","completion":"0"},"supported_parameters":["tools"],"context_length":32000},
      {"id":"openai/gpt-oss-20b:free","pricing":{"prompt":"0","completion":"0"},"supported_parameters":["tools","reasoning"],"context_length":131072},
      {"id":"d/other2:free","pricing":{"prompt":"0","completion":"0"},"supported_parameters":["tools"],"context_length":64000},
      {"id":"qwen/qwen3-coder:free","pricing":{"prompt":"0","completion":"0"},"supported_parameters":["tools"],"context_length":262000}]}"""

    @Test fun onlyFreeToolCapableModelsAreListedBestFirst() {
        assertEquals(listOf("openai/gpt-oss-20b:free", "qwen/qwen3-coder:free", "d/other2:free", "c/other:free"), OpenRouterModels.free(list))
        assertTrue(OpenRouterModels.free("not json").isEmpty())
        assertTrue(OpenRouterModels.free("""{"data":[]}""").isEmpty())
    }

    private class Router(val refuse: Set<String>) : ChatApi {
        val tried = ArrayList<String>()
        override fun chat(model: String, messages: JSONArray, tools: JSONArray?, onToken: (String) -> Unit, cancelled: () -> Boolean): ChatResult {
            tried.add(model)
            if (model in refuse) throw RemoteError("model", "this model is not usable with this key (403)")
            return ChatResult("ok $model", emptyList(), false)
        }
    }

    @Test fun aBlankModelPicksTheFirstFreeOneThatWorksAndRemembersIt() {
        val inner = Router(setOf("openai/gpt-oss-20b:free"))
        var saved = ""
        val chat = OpenRouterChat(inner, { list }, { saved = it })
        assertEquals("ok qwen/qwen3-coder:free", chat.chat("", msgs, null, {}, { false }).content)
        assertEquals(listOf("openai/gpt-oss-20b:free", "qwen/qwen3-coder:free"), inner.tried)
        assertEquals("qwen/qwen3-coder:free", saved)
        chat.chat("", msgs, null, {}, { false }) // the refused one is not tried again
        assertEquals("qwen/qwen3-coder:free", inner.tried.last())
        assertFalse(inner.tried.drop(2).contains("openai/gpt-oss-20b:free"))
    }

    @Test fun aTypedModelIdIsUsedAsIs() {
        val inner = Router(emptySet())
        var saved = ""
        OpenRouterChat(inner, { fail("must not fetch"); "" }, { saved = it }).chat("my/model", msgs, null, {}, { false })
        assertEquals(listOf("my/model"), inner.tried)
        assertEquals("", saved)
    }

    @Test fun noFreeModelAtAllIsAClearError() {
        try { OpenRouterChat(Router(emptySet()), { """{"data":[]}""" }, {}).chat("", msgs, null, {}, { false }); fail() } catch (e: RemoteError) { assertTrue(e.message!!.contains("no free model") || e.message!!.contains("lists no free model")) }
    }
}
