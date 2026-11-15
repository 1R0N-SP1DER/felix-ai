package com.felix.faze

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenShareTest {
    class RecHands : Hands {
        val calls = ArrayList<String>()
        override fun tap(x: Int, y: Int): String { calls.add("tap $x,$y"); return "Tapped." }
        override fun longPress(x: Int, y: Int): String { calls.add("long $x,$y"); return "Pressed." }
        override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int): String { calls.add("swipe $x1,$y1,$x2,$y2"); return "Swiped." }
        override fun typeText(text: String): String { calls.add("type $text"); return "Typed." }
        override fun pressKey(key: String): String { calls.add("key $key"); if (key == "power") throw ToolFailure("not allowed"); return "Pressed." }
    }

    private fun share(on: Boolean = true, locked: Boolean = false, hands: Hands = RecHands(), jpeg: ByteArray = byteArrayOf(1, 2, 3), log: (String) -> Unit = {}) =
        ScreenShare({ jpeg }, hands, { 1080 to 2400 }, { on }, { locked }, log)

    private fun msg(t: String, vararg kv: Pair<String, Any>) = JSONObject().put("t", t).also { j -> kv.forEach { j.put(it.first, it.second) } }

    @Test fun ignoresMessagesThatAreNotAboutTheScreen() = assertNull(share().handle(msg("sync_list")))

    @Test fun offMeansOffForEverythingAndNothingIsTouched() {
        val h = RecHands()
        for (t in ScreenShare.TYPES) assertTrue(share(on = false, hands = h).handle(msg(t))!!.getString("message").contains("off"))
        assertTrue(h.calls.isEmpty())
    }

    @Test fun nothingWorksWhileThePhoneIsLocked() {
        val h = RecHands()
        assertEquals("The phone is locked.", share(locked = true, hands = h).handle(msg("screen_tap", "x" to 0.5, "y" to 0.5))!!.getString("message"))
        assertTrue(h.calls.isEmpty())
    }

    @Test fun sendsAPictureAsBase64() {
        val r = share().handle(msg("screen_get"))!!
        assertEquals("screen_ok", r.getString("t"))
        assertEquals("AQID", r.getString("jpeg"))
        assertEquals("error", share(jpeg = ByteArray(0)).handle(msg("screen_get"))!!.getString("t"))
    }

    @Test fun fractionsBecomePixels() {
        val h = RecHands()
        val s = share(hands = h)
        s.handle(msg("screen_tap", "x" to 0.5, "y" to 0.25))
        s.handle(msg("screen_long", "x" to 0.0, "y" to 1.0))
        s.handle(msg("screen_swipe", "x1" to 0.5, "y1" to 0.8, "x2" to 0.5, "y2" to 0.2))
        assertEquals(listOf("tap 539,599", "long 0,2399", "swipe 539,1919,539,479"), h.calls)
    }

    @Test fun pointsOffTheScreenAreRefused() {
        val h = RecHands()
        assertEquals("error", share(hands = h).handle(msg("screen_tap", "x" to 1.5, "y" to 0.5))!!.getString("t"))
        assertEquals("error", share(hands = h).handle(msg("screen_tap"))!!.getString("t"))
        assertTrue(h.calls.isEmpty())
    }

    @Test fun keysAndTextGoThroughHandsAndItsRefusalsComeBack() {
        val h = RecHands()
        val s = share(hands = h)
        assertEquals("screen_done", s.handle(msg("screen_key", "key" to "back"))!!.getString("t"))
        assertEquals("screen_done", s.handle(msg("screen_text", "text" to "hi"))!!.getString("t"))
        assertEquals("not allowed", s.handle(msg("screen_key", "key" to "power"))!!.getString("message"))
    }

    @Test fun logsOnceAMinuteNotPerFrame() {
        val lines = ArrayList<String>()
        val s = share(log = { lines.add(it) })
        repeat(20) { s.handle(msg("screen_get")) }
        assertEquals(1, lines.size)
    }
}
