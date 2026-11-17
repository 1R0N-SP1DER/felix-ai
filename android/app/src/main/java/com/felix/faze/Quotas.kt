package com.felix.faze

import androidx.compose.runtime.mutableStateMapOf

/** What a provider said it has left, read from the rate-limit headers of its last good answer (Groq sends them; providers that do not just show nothing). */
data class Quota(val requestsLeft: Int?, val requestsLimit: Int?, val tokensLeft: Int?, val at: Long)

fun parseQuota(headers: Map<String, String>, now: Long = System.currentTimeMillis()): Quota? {
    fun num(name: String) = headers[name]?.trim()?.toDoubleOrNull()?.toInt()
    val left = num("x-ratelimit-remaining-requests")
    val limit = num("x-ratelimit-limit-requests")
    val tokens = num("x-ratelimit-remaining-tokens")
    if (left == null && tokens == null) return null
    return Quota(left, limit, tokens, now)
}

object Quotas {
    val map = mutableStateMapOf<String, Quota>()

    fun record(label: String, q: Quota) { map[label] = q }

    /** "9,842 of 14,400 requests left" for the model list, or null when the provider has not said. */
    fun text(label: String): String? {
        val q = map[label] ?: return null
        val n = java.text.NumberFormat.getIntegerInstance(java.util.Locale.US)
        return when {
            q.requestsLeft != null && q.requestsLimit != null -> "${n.format(q.requestsLeft)} of ${n.format(q.requestsLimit)} requests left"
            q.requestsLeft != null -> "${n.format(q.requestsLeft)} requests left"
            q.tokensLeft != null -> "${n.format(q.tokensLeft)} tokens left this minute"
            else -> null
        }
    }
}
