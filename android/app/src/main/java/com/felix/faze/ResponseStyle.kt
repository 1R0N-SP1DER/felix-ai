package com.felix.faze

/**
 * How much effort the AI puts into an answer, on five levels. Low is quick. Medium and up double-check, and when the AI searches the web it
 * also looks for pictures and reads more pages. Extra goes beyond the minimum when it makes something. Max also reads its own answer back.
 */
object Effort {
    val levels = listOf("low" to "Low", "medium" to "Medium", "high" to "High", "extra" to "Extra", "max" to "Max")

    /** The level of the turn being answered now, so the tools can match it. Set by the chat engine before each turn. */
    @Volatile var active: String = "low"

    fun norm(level: String): String = if (levels.any { it.first == level }) level else "low"

    fun index(level: String): Int = levels.indexOfFirst { it.first == norm(level) }

    fun at(index: Int): String = levels[index.coerceIn(0, levels.size - 1)].first

    fun label(level: String): String = levels[index(level)].second

    fun directive(level: String): String = when (norm(level)) {
        "medium" -> "\nEffort: take care. Think before you answer and double-check facts, names and numbers.\n"
        "high" -> "\nEffort: be thorough. Work through the problem before answering. Cross-check facts, numbers and names, and verify what tools returned. If you are not sure of something, say so instead of guessing.\n"
        "extra", "max" -> "\nEffort: be very thorough. Work through the problem before answering. Cross-check facts, numbers and names, and verify what tools returned. " +
            "If you are not sure of something, say so instead of guessing. When asked to make something (a piece of writing, a plan, code, a design), do more than the bare minimum: " +
            "make it complete and polished, anticipate what is needed next, and include the extras that would clearly help.\n"
        else -> ""
    }

    /** From Medium up, a web search also brings back pictures and more pages. */
    fun enriches(level: String): Boolean = index(level) >= 1

    /** How many pages a web search reads. */
    fun searchResults(level: String): Int = when (norm(level)) { "high" -> 6; "extra", "max" -> 8; else -> 5 }

    /** True when the answer gets a second, reviewing pass. */
    fun reviews(level: String): Boolean = norm(level) == "max"

    fun hint(level: String): String = when (norm(level)) {
        "medium" -> "Double-checks facts. A web search also brings back pictures and pages to back the answer up."
        "high" -> "Thorough: cross-checks facts and tool results and says when it is unsure. Searches read more pages and bring pictures."
        "extra" -> "Everything in High, and when it makes something it goes beyond the minimum. Slower."
        "max" -> "Everything in Extra, then the AI reads its own answer back for mistakes before it replies. Slowest, and uses the most of your free limits."
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
