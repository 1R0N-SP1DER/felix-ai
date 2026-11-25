package com.felix.faze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareTextTest {
    @Test fun aSingleWebAddressIsALink() {
        assertTrue(ShareText.isLink("https://example.com/a?b=1"))
        assertTrue(ShareText.isLink("  http://x.org \n"))
        assertFalse(ShareText.isLink("look at https://example.com"))
        assertFalse(ShareText.isLink("ftp://example.com"))
        assertFalse(ShareText.isLink("hello"))
    }

    @Test fun linksBecomeShortcutsAndTextBecomesATextFile() {
        val (n, b) = ShareText.file("https://example.com", "20261125-0530")
        assertEquals("link-20261125-0530.url", n)
        assertEquals("[InternetShortcut]\nURL=https://example.com\n", String(b))
        val (n2, b2) = ShareText.file("  hi there ", "s")
        assertEquals("text-s.txt", n2)
        assertEquals("hi there\n", String(b2))
    }
}
