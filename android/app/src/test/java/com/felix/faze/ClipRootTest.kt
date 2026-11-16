package com.felix.faze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipRootTest {
    private val pkg = "com.felix.faze"
    private val denial = "W/ClipboardService( 1234): Denying clipboard access to com.felix.faze, application is not in focus nor is it a system service for user 0"
    private var t = 10_000L
    private var fired = 0
    private val root = ClipRoot(pkg, { fired++ }, { t })

    @Test fun matchesTheSystemsDenialForThisApp() {
        assertTrue(ClipRoot.matches(denial, pkg))
        assertTrue(ClipRoot.matches("E/ClipboardService: Denying clipboard access to com.felix.faze, application is not in focus", pkg))
    }

    @Test fun ignoresOtherAppsAndOtherLines() {
        assertFalse(ClipRoot.matches(denial.replace("com.felix.faze", "com.other.app"), pkg))
        assertFalse(ClipRoot.matches("I/ActivityManager: Start proc com.felix.faze", pkg))
        assertFalse(ClipRoot.matches("", pkg))
    }

    @Test fun aCopyStartsOneGrabAndABurstIsOneCopy() {
        assertTrue(root.onLine(denial)); assertFalse(root.onLine(denial)); t += 100; assertFalse(root.onLine(denial))
        assertEquals(1, fired)
        t += 1000
        assertTrue(root.onLine(denial))
        assertEquals(2, fired)
    }

    @Test fun unrelatedLinesNeverFire() {
        root.onLine("D/whatever: hello")
        assertEquals(0, fired)
    }
}
