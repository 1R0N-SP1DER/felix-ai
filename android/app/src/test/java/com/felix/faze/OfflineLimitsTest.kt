package com.felix.faze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineLimitsTest {
    @Test fun missingValuesUseTheDefaults() {
        assertEquals(4, OfflineLimits.threads(null))
        assertEquals(2048, OfflineLimits.ctx(null))
    }

    @Test fun silliValuesAreClamped() {
        assertEquals(1, OfflineLimits.threads(0))
        assertEquals(8, OfflineLimits.threads(64))
        assertEquals(512, OfflineLimits.ctx(10))
        assertEquals(8192, OfflineLimits.ctx(1_000_000))
    }

    @Test fun everyPresetIsInsideTheLimits() {
        for (t in OfflineLimits.THREADS) assertEquals(t, OfflineLimits.threads(t))
        for (c in OfflineLimits.CTX) assertEquals(c, OfflineLimits.ctx(c))
        assertTrue(OfflineLimits.THREADS.contains(OfflineLimits.DEFAULT_THREADS) && OfflineLimits.CTX.contains(OfflineLimits.DEFAULT_CTX))
    }
}
