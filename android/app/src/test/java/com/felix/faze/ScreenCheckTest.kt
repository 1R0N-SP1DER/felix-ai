package com.felix.faze

import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenCheckTest {
    private fun text(p: ScreenProbe) = ScreenCheck.report(p).joinToString("\n")

    @Test fun withoutRootItPointsAtTheControlMethod() {
        assertTrue(text(ScreenProbe(false, null, "", 0, "", false)).contains("Control method"))
    }

    @Test fun everythingWorking() {
        val t = text(ScreenProbe(true, 120_000 to 0.4, "", 80_000, "", true))
        assertTrue(t.contains("Screenshot: works")); assertTrue(t.contains("Live video: works")); assertTrue(t.contains("Fast touch and keys: working"))
    }

    @Test fun aBlackScreenshotIsCalledOut() {
        assertTrue(text(ScreenProbe(true, 5_000 to 0.0, "", 80_000, "", true)).contains("came back black"))
    }

    @Test fun noVideoShowsWhatScreenrecordSaid() {
        val t = text(ScreenProbe(true, 120_000 to 0.4, "", 0, "Unable to get output buffers", false))
        assertTrue(t.contains("Unable to get output buffers")); assertTrue(t.contains("will lag"))
    }

    @Test fun aFailedScreenshotSaysWhy() {
        assertTrue(text(ScreenProbe(true, null, "the screenshot timed out", 0, "", true)).contains("the screenshot timed out"))
    }
}
