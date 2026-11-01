package com.felix.faze

data class ModelChoice(val id: String, val label: String, val note: String)

/** What the model chip offers. Notes come from the 16-prompt bench on real tools. */
object ModelChoices {
    val all = listOf(
        ModelChoice("openai/gpt-oss-120b", "gpt-oss-120b", "best tool use, about 1 s"),
        ModelChoice("openai/gpt-oss-20b", "gpt-oss-20b", "faster, but never searches"),
    )

    fun label(id: String): String = all.firstOrNull { it.id == id }?.label ?: id.substringAfterLast('/')
}

/** "stt 0.6s · llm 1.0s", shown under the live bar. Missing parts are left out. */
object TimingText {
    private fun sec(ms: Long) = String.format(java.util.Locale.US, "%.1fs", ms / 1000.0)

    fun format(sttMs: Long, llmMs: Long): String =
        listOfNotNull(if (sttMs > 0) "stt ${sec(sttMs)}" else null, if (llmMs > 0) "llm ${sec(llmMs)}" else null).joinToString(" · ")
}
