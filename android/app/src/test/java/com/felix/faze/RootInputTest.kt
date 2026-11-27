package com.felix.faze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.PipedInputStream
import java.io.PipedOutputStream

class RootInputTest {
    @Test fun parsesEachCommand() {
        assertEquals(InputLine.Tap(10, 20), InputLine.parse("tap 10 20"))
        assertEquals(InputLine.Swipe(1, 2, 3, 4, 300), InputLine.parse("swipe 1 2 3 4 300\n"))
        assertEquals(InputLine.Key("KEYCODE_BACK"), InputLine.parse("key KEYCODE_BACK"))
        assertEquals(InputLine.Text("hello world"), InputLine.parse("text hello world"))
        assertEquals(InputLine.Ping, InputLine.parse("ping"))
    }

    @Test fun refusesAnythingElse() {
        assertNull(InputLine.parse("tap 1"))
        assertNull(InputLine.parse("tap a b"))
        assertNull(InputLine.parse("key rm -rf"))
        assertNull(InputLine.parse("text "))
        assertNull(InputLine.parse("reboot"))
    }

    /** A stand-in helper: answers every line with [answer]. */
    private fun helper(answer: (String) -> String?): () -> Pair<java.io.OutputStream, java.io.InputStream> = {
        val toHelper = PipedOutputStream(); val helperIn = PipedInputStream(toHelper)
        val helperOut = PipedOutputStream(); val fromHelper = PipedInputStream(helperOut)
        Thread {
            helperIn.bufferedReader().forEachLine { l -> answer(l)?.let { helperOut.write((it + "\n").toByteArray()); helperOut.flush() } }
            helperOut.close()
        }.also { it.isDaemon = true; it.start() }
        toHelper to fromHelper
    }

    @Test fun answersFromTheHelper() {
        val c = RootInputClient(helper { "ok" })
        assertEquals("ok", c.call("tap 1 2"))
        assertEquals("ok", c.call("key KEYCODE_BACK"))
    }

    @Test fun anErrorFromTheHelperMeansUseTheSlowCommand() {
        val c = RootInputClient(helper { if (it == "ping") "ok" else "err key" })
        assertNull(c.call("key KEYCODE_BACK"))
    }

    @Test fun noHelperMeansNullAndNoRetryForAMinute() {
        var launches = 0; var t = 1000L
        val c = RootInputClient({ launches++; null }, now = { t })
        assertNull(c.call("tap 1 2")); assertNull(c.call("tap 1 2"))
        assertEquals(1, launches)
        t += 61_000
        assertNull(c.call("tap 1 2"))
        assertEquals(2, launches)
    }

    @Test fun aHelperThatNeverAnswersThePingIsGivenUp() {
        val c = RootInputClient(helper { null }, now = { 5L })
        val began = System.currentTimeMillis()
        assertNull(c.call("tap 1 2"))
        assertTrue(System.currentTimeMillis() - began < 8000)
    }
}
