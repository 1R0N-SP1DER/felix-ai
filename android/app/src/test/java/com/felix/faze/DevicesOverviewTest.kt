package com.felix.faze

import org.junit.Assert.assertEquals
import org.junit.Test

class DevicesOverviewTest {
    @Test fun theSummaryIsPlain() {
        assertEquals("No devices linked yet", connectedSummary(0, 0))
        assertEquals("2 linked, none connected right now", connectedSummary(0, 2))
        assertEquals("1 device connected", connectedSummary(1, 1))
        assertEquals("All 3 devices connected", connectedSummary(3, 3))
        assertEquals("1 of 3 devices connected", connectedSummary(1, 3))
    }
}
