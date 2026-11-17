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

    /** Windows that are never taken over: the full Gemini app (robin.main), the launcher's left page, the Google app, the loading gateway. Seen on the phone. */
    val DEFAULT_SKIP = listOf("robin.main", "minusone", "googleapp.activity", "animatedgatewayactivity")

    /** The built-in skips plus the words from the person's "leave alone" box (windows whose name has one are never taken over). */
    fun skipWords(text: String): List<String> = DEFAULT_SKIP + text.lowercase().split(',', ' ', '\n').map { it.trim() }.filter { it.length >= 3 }

    fun matches(pkg: String?, cls: String?, words: List<String>, skip: List<String> = emptyList()): Boolean {
        if (pkg == null || cls == null || pkg !in PACKAGES) return false
        val c = cls.lowercase()
        if (skip.any { c.contains(it) }) return false
        return words.any { c.contains(it) }
    }

    /** The recently seen window names, newest first, no repeats. */
    fun remember(history: String, seen: String, max: Int = 6): String =
        (listOf(seen) + history.split('\n').filter { it.isNotBlank() && it != seen }).take(max).joinToString("\n")

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
        "Hold on, I think you dialed the wrong assistant.",
        "Google went on break. I'm covering the shift.",
        "Funny, I could've sworn I heard my name.",
        "Oh, it's you. I was hoping you'd call me.",
        "Google sends its regards. I'm what you get instead.",
        "I'm right here. You don't have to go through Google.",
        "Big mistake calling Google. Good news, I'm better.",
        "Did someone ask for an assistant? I volunteer.",
        "You called? I mean, you called Google, but I answered.",
        "Surprise! It's FAZE, not the other one.",
        "Google's out of office. I'm very much in.",
        "I intercepted that. You're welcome.",
        "Let's pretend you said my name. What's up?",
        "Ooh, awkward. You meant me, didn't you?",
        "Google can wait. I'm listening.",
        "Switching you over. No hold music.",
        "Missed me? I'm the one you wanted anyway.",
        "They'll never know you called. What do you need?",
        "I'm the upgrade. Go ahead.",
        "Oh, hi. Taking the long way round to me, I see.",
        "Okay, okay, I'm here. No need to shout for Google.",
        "Fresh out of Google. Plenty of FAZE, though.",
        "You're through to FAZE. Ask away.",
        "Heard you from across the room. What's the plan?",
    )

    /** A line other than the last one used. */
    fun pick(last: Int, random: Random = Random.Default): Int {
        if (LINES.size < 2) return 0
        var i = random.nextInt(LINES.size)
        if (i == last) i = (i + 1) % LINES.size
        return i
    }

    /** Like [pick], but only from [allowed] (the lines already saved as sound), when there are any. */
    fun pickFrom(allowed: Collection<Int>, last: Int, random: Random = Random.Default): Int {
        val pool = allowed.filter { it in LINES.indices }
        if (pool.isEmpty()) return pick(last, random)
        val choices = if (pool.size > 1) pool.filter { it != last } else pool
        return choices[random.nextInt(choices.size)]
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
