package com.felix.faze

import kotlin.random.Random

/**
 * Faze taking over when Google's assistant pops up (Hey Google, the power-button hold, the corner swipe): the service sees the window open,
 * sends Back to dismiss it, and opens Faze's overlay with a short morph from Google's look to Faze's. Only window names of Google's apps are
 * looked at; nothing on the screen is read. The matching rule and the lines live here so they are tested without a phone.
 */
object GoogleTakeover {
    val PACKAGES = setOf("com.google.android.googlequicksearchbox", "com.google.android.apps.bard", "com.google.android.apps.googleassistant")

    /** Pieces of a window's class name that mean "the assistant" (not the search app, Lens or Circle to Search). */
    val DEFAULT_WORDS = listOf("assistant", "gemini", "bard", "voiceinteraction", "voice_interaction", "hotword", ".opa.")

    /** Extra words the person added, comma or space separated. */
    fun words(extra: String): List<String> = DEFAULT_WORDS + extra.lowercase().split(',', ' ', '\n').map { it.trim() }.filter { it.length >= 3 }

    fun matches(pkg: String?, cls: String?, words: List<String>): Boolean {
        if (pkg == null || cls == null || pkg !in PACKAGES) return false
        val c = cls.lowercase()
        return words.any { c.contains(it) }
    }

    val LINES = listOf(
        "Nah, Google isn't around. Did you mean me?",
        "Oh no, were you calling Google? Too late, you've got me.",
        "Google stepped out. I'm here, what do you need?",
        "Hey, I saw that. You meant me, right?",
        "Google? Never heard of them. What's up?",
        "Sorry, wrong number. This is FAZE.",
        "Psst, Google can't help right now. I can.",
        "You said Google, but I'm the one who answers.",
        "Google's busy. I've got this.",
        "Ah, you were looking for me. Obviously.",
        "Plot twist: it's me.",
        "Google who? Oh, you meant FAZE.",
    )

    /** A line other than the last one used. */
    fun pick(last: Int, random: Random = Random.Default): Int {
        if (LINES.size < 2) return 0
        var i = random.nextInt(LINES.size)
        if (i == last) i = (i + 1) % LINES.size
        return i
    }
}

/** Lets one event through, then none for [gapMs]: Google's window sends several events as it opens. */
class Debounce(private val gapMs: Long) {
    private var last = Long.MIN_VALUE / 2

    fun allow(now: Long): Boolean {
        if (now - last < gapMs) return false
        last = now
        return true
    }
}
