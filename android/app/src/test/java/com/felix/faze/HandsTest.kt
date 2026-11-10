package com.felix.faze

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HandsTest {
    @Test fun eachActionIsOneInputCommand() {
        assertEquals("input tap 540 1200", InputCommands.tap(540, 1200))
        assertEquals("input swipe 500 900 500 900 800", InputCommands.longPress(500, 900))
        assertEquals("input swipe 500 1800 500 600 300", InputCommands.swipe(500, 1800, 500, 600))
        assertEquals("input keyevent KEYCODE_BACK", InputCommands.key("back"))
        assertEquals("input keyevent KEYCODE_APP_SWITCH", InputCommands.key(" Recents "))
    }

    @Test fun pointsAndKeysAreChecked() {
        for (bad in listOf(-1, 5001)) try { InputCommands.tap(bad, 10); fail("$bad") } catch (e: ToolFailure) { assertTrue(e.message!!.contains("between 0 and")) }
        for (bad in listOf("power", "reboot", "", "KEYCODE_POWER")) try { InputCommands.key(bad); fail(bad) } catch (e: ToolFailure) { assertTrue(e.message!!.contains("unknown key")) }
        assertFalse(InputCommands.KEYS.keys.any { it.contains("power") })
    }

    @Test fun plainTextIsTypedAndSpacesBecomePercentS() {
        assertTrue(InputCommands.isPlain("Hello world, it's 5pm".replace("'", "")))
        assertEquals("input text 'Hello%sworld'", InputCommands.typeCommand("Hello world"))
        assertEquals("input text 'me@x.com'", InputCommands.typeCommand("me@x.com"))
    }

    @Test fun anythingTrickyGoesThroughTheClipboardInstead() {
        for (t in listOf("it's", "50%", "café", "😀", "a;b", "\$(reboot)", "a\"b", "line\nbreak", "a`b", "a|b", "a&b"))
            assertFalse(t, InputCommands.isPlain(t))
        assertEquals("input keyevent 279", InputCommands.PASTE)
    }

    @Test fun emptyAndLongTextAreRefused() {
        for (t in listOf("", "x".repeat(301))) try { InputCommands.checkText(t); fail("bad text") } catch (e: ToolFailure) { assertTrue(e.message!!.isNotEmpty()) }
        InputCommands.checkText("x".repeat(300))
    }

    @Test fun theToolsReachTheHandsWithTheirArguments() {
        val hands = FakeHands()
        val tools = Tools(FakeDevice(), FakeTransport(), FakeSettings(), hands = hands)
        assertTrue(tools.names().containsAll(listOf("tap", "long_press", "swipe", "type_text", "press_key")))
        assertEquals("Tapped at 10,20.", tools.execute("tap", JSONObject("{\"x\":10,\"y\":20}")).text)
        tools.execute("long_press", JSONObject("{\"x\":1,\"y\":2}"))
        tools.execute("swipe", JSONObject("{\"x1\":1,\"y1\":2,\"x2\":3,\"y2\":4}"))
        tools.execute("type_text", JSONObject("{\"text\":\"hi\"}"))
        tools.execute("press_key", JSONObject("{\"key\":\"home\"}"))
        assertEquals(listOf("tap 10,20", "hold 1,2", "swipe 1,2,3,4", "type hi", "key home"), hands.log)
    }

    @Test fun missingOrWrongArgumentsFailInWords() {
        val tools = Tools(FakeDevice(), FakeTransport(), FakeSettings(), hands = FakeHands())
        val r = tools.execute("tap", JSONObject("{\"x\":10}"))
        assertFalse(r.ok)
        assertTrue(r.text, r.text.contains("y is required"))
        assertFalse(tools.execute("tap", JSONObject("{\"x\":\"left\",\"y\":1}")).ok)
    }

    @Test fun withoutHandsThereAreNoControlTools() {
        val tools = Tools(FakeDevice(), FakeTransport(), FakeSettings())
        assertFalse(tools.names().contains("tap"))
        assertFalse(tools.execute("tap", JSONObject("{\"x\":1,\"y\":1}")).ok)
    }
}
