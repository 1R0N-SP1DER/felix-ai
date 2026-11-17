package com.felix.faze

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleTakeoverTest {
    private val words = GoogleTakeover.words("")

    @Test fun theAssistantWindowIsTakenOverButSearchAndOtherAppsAreNot() {
        val g = "com.google.android.googlequicksearchbox"
        assertTrue(GoogleTakeover.matches(g, "com.google.android.apps.search.assistant.ui.AssistantActivity", words))
        assertTrue(GoogleTakeover.matches("com.google.android.apps.bard", "com.google.android.apps.bard.GeminiOverlay", words))
        assertTrue(GoogleTakeover.matches(g, "com.google.android.apps.gsa.staticplugins.opa.OpaActivity", words))
        assertFalse(GoogleTakeover.matches(g, "com.google.android.apps.gsa.searchnow.SearchActivity", words))
        assertFalse(GoogleTakeover.matches("com.whatsapp", "com.whatsapp.AssistantSettings", words))
        assertFalse(GoogleTakeover.matches(null, "x", words))
        assertFalse(GoogleTakeover.matches(g, null, words))
    }

    @Test fun extraWordsLetAPhoneWithAnotherWindowNameWork() {
        val g = "com.google.android.googlequicksearchbox"
        assertFalse(GoogleTakeover.matches(g, "com.google.Zorp", words))
        assertTrue(GoogleTakeover.matches(g, "com.google.Zorp", GoogleTakeover.words("zorp, ab")))
        assertEquals(GoogleTakeover.DEFAULT_WORDS.size + 1, GoogleTakeover.words("zorp, ab").size) // two-letter scraps are ignored
    }

    @Test fun theLineIsNeverTheSameTwiceInARow() {
        var last = -1
        val r = Random(7)
        repeat(200) {
            val next = GoogleTakeover.pick(last, r)
            assertNotEquals(last, next)
            assertTrue(next in GoogleTakeover.LINES.indices)
            last = next
        }
        assertTrue(GoogleTakeover.LINES.any { it.contains("Did you mean me") })
    }

    @Test fun oneEventPerOpeningGetsThrough() {
        val d = Debounce(2500)
        assertTrue(d.allow(10_000))
        assertFalse(d.allow(10_300))
        assertFalse(d.allow(12_400))
        assertTrue(d.allow(12_600))
    }

    @Test fun aLeaveAloneWordKeepsAWindowOpen() {
        val pkg = "com.google.android.apps.bard"
        assertTrue(GoogleTakeover.matches(pkg, "com.x.GeminiActivity", GoogleTakeover.DEFAULT_WORDS))
        assertFalse(GoogleTakeover.matches(pkg, "com.x.GeminiActivity", GoogleTakeover.DEFAULT_WORDS, GoogleTakeover.skipWords("geminiactivity")))
        assertEquals(GoogleTakeover.DEFAULT_SKIP + "abc", GoogleTakeover.skipWords("ab, abc"))
    }

    @Test fun recentWindowsAreKeptNewestFirstWithoutRepeats() {
        var h = ""
        for (n in listOf("a / 1", "b / 2", "a / 1", "c / 3")) h = GoogleTakeover.remember(h, n, 3)
        assertEquals("c / 3\na / 1\nb / 2", h)
        assertEquals(3, GoogleTakeover.remember(h, "d / 4", 3).lines().size)
    }

    @Test fun theFullGeminiAppAndOtherGoogleWindowsAreLeftAlone() {
        val pkg = "com.google.android.googlequicksearchbox"
        val skip = GoogleTakeover.skipWords("")
        val w = GoogleTakeover.DEFAULT_WORDS
        assertFalse(GoogleTakeover.matches(pkg, "com.google.android.apps.search.assistant.surfaces.voice.robin.main.MainActivity", w, skip))
        assertFalse(GoogleTakeover.matches(pkg, "com.google.android.apps.search.googleapp.launcher.minusone.activity.MinusOneActivity", w, skip))
        assertFalse(GoogleTakeover.matches(pkg, "com.google.android.apps.search.googleapp.activity.GoogleAppActivity", w, skip))
        assertTrue(GoogleTakeover.matches(pkg, "com.google.android.apps.search.assistant.surfaces.voice.robin.ui.floaty.activity.FloatyActivity", w, skip))
    }
}
