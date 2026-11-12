package com.felix.faze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelSheetTest {
    @Test fun sameModelsInTheSameOrderAsTheWebSheet() {
        assertEquals(listOf("auto", "offline", "gemini", "groq", "openrouter", "custom"), ModelSheet.names.keys.toList())
        assertEquals("OpenRouter", ModelSheet.label("openrouter"))
        assertEquals("custom", ModelSheet.label("custom"))
    }

    @Test fun groqGeminiAndOpenRouterRunInTheApp() {
        val models = mapOf("groq" to "openai/gpt-oss-120b", "gemini" to "gemini-3.1-flash-lite", "openrouter" to "openai/gpt-oss-120b:free")
        val rows = ModelSheet.rows(setOf("groq", "gemini"), models)
        assertEquals(5, rows.size)
        assertEquals(listOf("custom", "gemini", "groq", "offline", "openrouter"), rows.filter { it.available }.map { it.id }.sorted())
        assertEquals("gpt-oss-120b", rows.first { it.id == "groq" }.sub)
        assertEquals("gemini-3.1-flash-lite", rows.first { it.id == "gemini" }.sub)
        assertEquals("ok", rows.first { it.id == "gemini" }.dot)
        assertEquals("bad", rows.first { it.id == "openrouter" }.dot) // no key yet
        assertTrue(rows.first { it.id == "openrouter" }.sub.startsWith("no key"))
        assertTrue(rows.filter { !it.available }.all { it.sub == ModelSheet.NOT_YET && it.dot == "" })
    }

    @Test fun offlineNeedsNoKeyAndShowsTheModelPicked() {
        val none = ModelSheet.rows(emptySet(), mapOf("offline" to "")).first { it.id == "offline" }
        assertEquals("warn", none.dot)
        assertTrue(none.sub.startsWith("pick a model"))
        val picked = ModelSheet.rows(emptySet(), mapOf("offline" to "/data/data/com.termux/files/home/models/LFM2.5-230M-Q4_K_M.gguf")).first { it.id == "offline" }
        assertEquals("ok", picked.dot)
        assertEquals("LFM2.5-230M-Q4_K_M", picked.sub)
        assertFalse(ModelSheet.thinkEnabled("offline"))
        assertTrue(ModelSheet.thinkHint("offline").contains("tools"))
    }

    @Test fun thinkingRulesMatchTheWebPage() {
        assertFalse(ModelSheet.thinkEnabled("groq"))
        assertFalse(ModelSheet.thinkEnabled("openrouter"))
        assertTrue(ModelSheet.thinkEnabled("gemini"))
        assertFalse(ModelSheet.thinkEnabled("lfm"))
        assertTrue(ModelSheet.thinkEnabled("qwen"))
        assertTrue(ModelSheet.thinkEnabled("auto"))
        assertEquals(listOf("auto", "off", "low", "medium", "high", "max"), ModelSheet.thinkLevels.map { it.first })
        assertTrue(ModelSheet.thinkHint("groq").startsWith("This online model has no thinking setting yet."))
    }

    @Test fun onlyWarningAndBadChipsAreMade() {
        assertTrue(ModelSheet.chips("groq", true, false, false).isEmpty())
        assertTrue(ModelSheet.chips("groq", true, true, true).isEmpty())
        assertEquals(listOf(StatusChip("gemini no key", "bad")), ModelSheet.chips("gemini", false, false, false))
        assertEquals(listOf(StatusChip("wake model needed", "warn")), ModelSheet.chips("groq", true, true, false))
    }
}
