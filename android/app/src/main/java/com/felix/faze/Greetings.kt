package com.felix.faze

/** The greeting lines of the web UI home screen, copied from core/web/app.js (a Python test keeps them in sync). */
object Greetings {
    val any = listOf(
        "Coffee and {ai} time?",
        "What's on your mind, {user}?",
        "Back at it, {user}?",
        "{user}! What are we building?",
        "Ready when you are.",
        "Hey {user}. What's the plan?",
        "Let's make something, {user}.",
        "Talk to me, {user}.",
        "Got a minute, {user}?",
        "Fire away.",
    )

    val morning = listOf(
        "Good morning, {user}.",
        "Morning, {user}. Coffee first?",
        "Fresh day, {user}. What comes first?",
    )

    val afternoon = listOf(
        "Good afternoon, {user}.",
        "Afternoon, {user}. What are we tackling?",
        "Mid-day check-in, {user}?",
    )

    val evening = listOf(
        "Good evening, {user}.",
        "Winding down or ramping up, {user}?",
        "Evening, {user}. What are we up to?",
    )

    val night = listOf(
        "Still up, {user}?",
        "Burning the midnight oil, {user}?",
        "Late-night ideas, {user}?",
    )

    fun pool(hour: Int): List<String> {
        val slot = when {
            hour in 5..11 -> morning
            hour in 12..16 -> afternoon
            hour in 17..21 -> evening
            else -> night
        }
        return any + slot
    }

    /** Same rule as greetingFor() in app.js. [rand] returns a number in [0, 1). */
    fun greetingFor(hour: Int, user: String, ai: String, rand: () -> Double = { Math.random() }): String {
        val pool = pool(hour)
        val pick = pool[minOf(pool.size - 1, Math.floor(rand() * pool.size).toInt())]
        return pick.replace("{user}", user.ifEmpty { "there" }).replace("{ai}", ai.ifEmpty { "Faze" })
    }
}
