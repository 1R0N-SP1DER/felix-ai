package com.felix.faze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RootTest {
    private class Fake(var works: Boolean = true) : RootShell {
        val ran = ArrayList<String>()
        override fun run(command: String): String? {
            ran.add(command)
            return if (works) "ok" else null
        }
    }

    @Test fun onlyTheThreeFixedCommandsAreAllowed() {
        assertTrue(RootCommands.isAllowed(RootCommands.HIDE_MIC_DOT))
        assertTrue(RootCommands.isAllowed(RootCommands.SHOW_MIC_DOT))
        assertTrue(RootCommands.isAllowed(RootCommands.READ_MIC_DOT))
        assertFalse(RootCommands.isAllowed("id"))
        assertFalse(RootCommands.isAllowed(RootCommands.HIDE_MIC_DOT + "; reboot"))
        assertFalse(RootCommands.isAllowed(""))
    }

    @Test fun nothingHappensWhenTheOptionIsOff() {
        val sh = Fake()
        val g = MicDotGuard(sh, { false })
        g.waiting()
        g.recording()
        g.restore()
        assertTrue(sh.ran.isEmpty())
    }

    @Test fun hiddenWhileWaitingShownWhileRecordingHiddenAgainAfter() {
        val sh = Fake()
        val g = MicDotGuard(sh, { true })
        g.waiting()
        g.waiting() // already hidden: no second command
        assertTrue(g.isHidden())
        g.recording()
        assertFalse(g.isHidden())
        g.waiting()
        assertEquals(listOf(RootCommands.HIDE_MIC_DOT, RootCommands.SHOW_MIC_DOT, RootCommands.HIDE_MIC_DOT), sh.ran)
    }

    @Test fun restoreWhenListeningStops() {
        val sh = Fake()
        val g = MicDotGuard(sh, { true })
        g.waiting()
        g.restore()
        assertFalse(g.isHidden())
        assertEquals(RootCommands.SHOW_MIC_DOT, sh.ran.last())
        g.restore() // nothing to restore now
        assertEquals(2, sh.ran.size)
    }

    @Test fun withoutRootItReportsAndStaysShown() {
        val sh = Fake(works = false)
        val said = ArrayList<String>()
        val g = MicDotGuard(sh, { true }, { said.add(it) })
        g.waiting()
        assertFalse(g.isHidden())
        assertEquals(1, said.size)
    }

    @Test fun ifRestoringFailsItKeepsTryingLater() {
        val sh = Fake()
        val g = MicDotGuard(sh, { true })
        g.waiting()
        sh.works = false
        g.restore()
        assertTrue(g.isHidden()) // still believes it is hidden, so the next restore retries
        sh.works = true
        g.restore()
        assertFalse(g.isHidden())
    }
}
