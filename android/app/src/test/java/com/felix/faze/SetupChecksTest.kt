package com.felix.faze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupChecksTest {
    @Test fun countsWhatIsReady() {
        val none = SetupChecks.items(false, false, false, true, false, false)
        assertEquals("0 of 5 ready", SetupChecks.summary(none))
        val all = SetupChecks.items(true, true, true, true, true, true)
        assertEquals("Everything is set up", SetupChecks.summary(all))
    }

    @Test fun termuxNeedsInstalledAndAllowed() {
        assertFalse(SetupChecks.items(true, true, true, false, true, true).first { it.id == "termux" }.ok)
        assertFalse(SetupChecks.items(true, true, true, true, false, true).first { it.id == "termux" }.ok)
        assertTrue(SetupChecks.items(true, true, true, true, true, true).first { it.id == "termux" }.ok)
        assertTrue(SetupChecks.items(true, true, true, false, false, true).first { it.id == "termux" }.unlocks.contains("not installed"))
    }
}
