package com.felix.faze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalTextTest {
    @Test fun clipboardReadAndWriteSayWhatTheyDo() {
        val r = ApprovalText.build("read_clipboard", "Read the clipboard")
        assertEquals("Let Faze read your clipboard?", r.title)
        assertEquals("normal", r.level)
        assertTrue(r.how.contains("Nothing is changed"))
        val w = ApprovalText.build("write_clipboard", "Put this on your clipboard: hi")
        assertEquals("Put this on your clipboard: hi", w.what)
        assertTrue(w.how.startsWith("Replaces"))
    }

    @Test fun anythingElseIsMarkedHigherLevel() {
        val x = ApprovalText.build("open_shell", "x")
        assertEquals("higher-level", x.level)
        assertTrue(x.how.contains("open_shell"))
    }
}
