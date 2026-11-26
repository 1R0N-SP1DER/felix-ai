package com.felix.faze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NoRootHandsTest {
    private class Fake(var ok: Boolean = true) : A11yPort {
        val calls = ArrayList<String>()
        override fun gesture(points: List<Pair<Int, Int>>, durationMs: Long): Boolean { calls.add("gesture ${points.size} $durationMs"); return ok }
        override fun global(action: String): Boolean { calls.add("global $action"); return ok }
        override fun typeInto(text: String): Boolean { calls.add("type $text"); return ok }
        override fun media(key: String): Boolean { calls.add("media $key"); return ok }
        override fun edit(key: String): Boolean { calls.add("edit $key"); return ok }
    }

    @Test fun mapsTouchAndKeys() {
        val f = Fake()
        val h = NoRootHands(f)
        h.tap(10, 20); h.longPress(1, 2); h.swipe(1, 2, 3, 4)
        h.pressKey("back"); h.pressKey("escape"); h.pressKey("enter"); h.pressKey("delete"); h.pressKey("volume_up"); h.pressKey("recents")
        assertEquals(listOf("gesture 1 50", "gesture 1 650", "gesture 2 300", "global back", "global back", "edit enter", "edit backspace", "media volume_up", "global recents"), f.calls)
    }

    @Test fun keysThatNeedRootSayHow() {
        try { NoRootHands(Fake()).pressKey("tab"); fail() } catch (e: ToolFailure) { assertTrue(e.message!!.contains("needs root")) }
    }

    @Test fun aFailedActionExplainsTheAccessibilityService() {
        try { NoRootHands(Fake(false)).tap(1, 1); fail() } catch (e: ToolFailure) { assertTrue(e.message!!.contains("accessibility")) }
    }

    @Test fun textEditing() {
        assertEquals("ab" to 2, TextEdit.apply("backspace", "abc", 3, 3))
        assertEquals("ac" to 1, TextEdit.apply("backspace", "abc", 2, 2))
        assertEquals("a" to 1, TextEdit.apply("backspace", "abc", 1, 3))
        assertEquals("" to 0, TextEdit.apply("backspace", "", 0, 0))
        assertEquals("bc" to 0, TextEdit.apply("delete", "abc", 0, 0))
        assertEquals("abc" to 1, TextEdit.apply("left", "abc", 2, 2))
        assertEquals("abc" to 3, TextEdit.apply("right", "abc", 3, 3))
        assertEquals("abc" to 0, TextEdit.apply("left", "abc", 0, 0))
        assertNull(TextEdit.apply("tab", "abc", 0, 0))
    }

    @Test fun choosingFollowsTheModeAndRootCheck() {
        val root = object : Hands {
            override fun tap(x: Int, y: Int) = "root"; override fun longPress(x: Int, y: Int) = "root"; override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int) = "root"
            override fun typeText(text: String) = "root"; override fun pressKey(key: String) = "root"
        }
        val no = NoRootHands(Fake())
        var mode = "auto"
        var rootOk = true
        val c = ChoosingHands(root, no, { mode }) { rootOk }
        assertEquals("root", c.tap(1, 1))
        rootOk = false
        assertTrue(c.tap(1, 1).startsWith("Tapped"))
        mode = "root"
        assertEquals("root", c.tap(1, 1))
        assertEquals(ControlMode.AUTO, ControlMode.normalize("junk"))
    }
}
