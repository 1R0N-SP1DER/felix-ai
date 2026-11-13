package com.felix.faze

/** How much effort the AI puts into an answer. High asks it to double-check; Max also reads its own answer through once more before sending. */
object Effort {
    val levels = listOf("standard" to "Standard", "high" to "High", "max" to "Max")

    fun norm(level: String): String = if (levels.any { it.first == level }) level else "standard"

    fun directive(level: String): String = when (norm(level)) {
        "high", "max" -> "\nEffort: be thorough. Work through the problem before answering. Cross-check facts, numbers and names, and verify what tools returned. " +
            "If you are not sure of something, say so instead of guessing. When asked to make something (a piece of writing, a plan, code, a design), do more than the bare minimum: " +
            "make it complete and polished, anticipate what is needed next, and include the extras that would clearly help.\n"
        else -> ""
    }

    /** True when the answer gets a second, reviewing pass. */
    fun reviews(level: String): Boolean = norm(level) == "max"

    fun hint(level: String): String = when (norm(level)) {
        "high" -> "The AI works it through, checks facts and tool results, and goes beyond the minimum when it makes something. A little slower."
        "max" -> "Everything in High, then the AI reads its own answer back for mistakes before it replies. Slowest, and uses the most of your free limits."
        else -> "Quick and to the point."
    }

    const val REVIEW_PROMPT = "Check your answer above carefully. Look for mistakes, wrong or unverified facts, missing steps and anything the user asked for that you left out. " +
        "Then reply with the final answer only, corrected and improved where needed. Do not mention this check."
}

/** The response length slider: five stops, from a sentence or two to a long, in-depth answer. The middle one lets the AI choose. */
object ResponseLength {
    const val DEFAULT = 2
    const val STOPS = 5
    private val labels = listOf("Very short", "Short", "Default", "Long", "Very long")

    fun norm(step: Int): Int = step.coerceIn(0, STOPS - 1)

    fun label(step: Int): String = labels[norm(step)]

    fun directive(step: Int): String = when (norm(step)) {
        0 -> "\nLength: answer in one or two sentences at most.\n"
        1 -> "\nLength: keep answers short and to the point.\n"
        3 -> "\nLength: give thorough, detailed, well-organised answers.\n"
        4 -> "\nLength: give very long, in-depth answers that cover every angle, with examples.\n"
        else -> ""
    }

    /** The most the model may write. 0 means leave the client's own limit. */
    fun tokens(step: Int): Int = when (norm(step)) {
        0 -> 200
        1 -> 500
        3 -> 4000
        4 -> 6000
        else -> 0
    }

    fun hint(step: Int): String = when (norm(step)) {
        2 -> "The AI decides how long to answer."
        else -> "Applies to every answer until you move the slider back to Default."
    }
}

/** The reply-length limit for the answer being written now. 0 leaves each client's own limit. Set by the chat engine before each turn. */
object ReplyLimit {
    @Volatile var tokens: Int = 0
}
