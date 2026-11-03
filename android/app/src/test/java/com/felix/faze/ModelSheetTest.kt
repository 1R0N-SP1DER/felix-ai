package com.felix.faze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelSheetTest {
    @Test fun sameModelsInTheSameOrderAsTheWebSheet() {
        assertEquals(listOf("auto", "qwen", "lfm", "gemini", "groq", "openrouter"), ModelSheet.names.keys.toList())
        assertEquals("OpenRouter", ModelSheet.label("openrouter"))
        assertEquals("custom", ModelSheet.label("custom"))
    }

    @Test fun onlyGroqIsAvailableInTheApp() {
        val rows = ModelSheet.rows(true, "openai/gpt-oss-120b")
        assertEquals(6, rows.size)
        assertEquals(listOf("groq"), rows.filter { it.available }.map { it.id })
        val groq = rows.first { it.id == "groq" }
        assertEquals("ok", groq.dot)
        assertEquals("gpt-oss-120b", groq.sub)
        assertTrue(rows.filter { !it.available }.all { it.sub == ModelSheet.NOT_YET && it.dot == "" })
    }

    @Test fun withoutAKeyGroqIsMarkedBad() {
        val groq = ModelSheet.rows(false, "openai/gpt-oss-120b").first { it.id == "groq" }
        assertEquals("bad", groq.dot)
        assertTrue(groq.sub.startsWith("no key"))
    }

    @Test fun thinkingRulesMatchTheWebPage() {
        assertFalse(ModelSheet.thinkEnabled("groq"))
        assertFalse(ModelSheet.thinkEnabled("openrouter"))
        assertFalse(ModelSheet.thinkEnabled("lfm"))
        assertTrue(ModelSheet.thinkEnabled("qwen"))
        assertTrue(ModelSheet.thinkEnabled("auto"))
        assertEquals(listOf("auto", "off", "low", "medium", "high", "max"), ModelSheet.thinkLevels.map { it.first })
        assertTrue(ModelSheet.thinkHint("groq").startsWith("This online model has no thinking setting yet."))
    }

    @Test fun onlyWarningAndBadChipsAreMade() {
        assertTrue(ModelSheet.chips(true, false, false).isEmpty())
        assertTrue(ModelSheet.chips(true, true, true).isEmpty())
        assertEquals(listOf(StatusChip("groq no key", "bad")), ModelSheet.chips(false, false, false))
        assertEquals(listOf(StatusChip("wake model needed", "warn")), ModelSheet.chips(true, true, false))
    }
}
