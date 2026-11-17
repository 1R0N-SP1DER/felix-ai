package com.felix.faze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class QuotasTest {
    @Before fun clear() = Quotas.map.clear()

    @Test fun readsGroqsHeaders() {
        val q = parseQuota(mapOf("x-ratelimit-remaining-requests" to "9842", "x-ratelimit-limit-requests" to "14400", "x-ratelimit-remaining-tokens" to "5990"), 5L)!!
        assertEquals(9842, q.requestsLeft); assertEquals(14400, q.requestsLimit); assertEquals(5990, q.tokensLeft); assertEquals(5L, q.at)
    }

    @Test fun noHeadersMeansNothingToShow() {
        assertNull(parseQuota(emptyMap()))
        assertNull(parseQuota(mapOf("x-ratelimit-limit-requests" to "14400")))
        assertNull(Quotas.text("Groq"))
    }

    @Test fun wordsForTheList() {
        Quotas.record("Groq", Quota(9842, 14400, null, 0))
        assertEquals("9,842 of 14,400 requests left", Quotas.text("Groq"))
        Quotas.record("A", Quota(12, null, null, 0))
        assertEquals("12 requests left", Quotas.text("A"))
        Quotas.record("B", Quota(null, null, 4500, 0))
        assertEquals("4,500 tokens left this minute", Quotas.text("B"))
    }

    @Test fun garbageIsIgnored() = assertNull(parseQuota(mapOf("x-ratelimit-remaining-requests" to "soon")))
}
