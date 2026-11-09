package com.felix.faze

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionsTest {
    private class Ask(var answer: Boolean = true, var boom: Boolean = false) : Approver {
        val asked = ArrayList<Pair<String, String>>()
        override fun approve(tool: String, summary: String): Boolean {
            asked.add(Pair(tool, summary))
            if (boom) throw IllegalStateException("ui died")
            return answer
        }
    }

    private val tools = FakeTools()
    private fun gate(ask: Ask) = GatedTools(tools, ask)
    private fun args(text: String = "") = JSONObject().put("text", text)

    @Test fun safeToolsRunWithoutAsking() {
        val ask = Ask()
        for (t in listOf("get_current_datetime", "get_battery_status", "get_weather", "search_web", "open_app", "open_url", "list_apps", "vibrate", "set_volume", "get_wifi_status")) {
            assertTrue(t, gate(ask).execute(t, JSONObject()).ok)
        }
        assertTrue(ask.asked.isEmpty())
        assertEquals(10, tools.executed.size)
    }

    private class Clock(var now: Long = 0L) : () -> Long { override fun invoke() = now }

    @Test fun oneYesCoversControllingTheScreenForTwoMinutes() {
        val ask = Ask(true)
        val clock = Clock()
        val g = GatedTools(tools, ask, clock)
        assertTrue(g.execute("tap", JSONObject()).ok)
        clock.now = 119_000
        assertTrue(g.execute("swipe", JSONObject()).ok)
        assertTrue(g.execute("type_text", JSONObject()).ok)
        assertEquals(listOf("tap"), ask.asked.map { it.first })
        clock.now = 121_000
        assertTrue(g.execute("press_key", JSONObject()).ok)
        assertEquals(listOf("tap", "press_key"), ask.asked.map { it.first })
    }

    @Test fun aNoGivesNoLeaseAndStopTakesTheLeaseBack() {
        val ask = Ask(false)
        val g = GatedTools(tools, ask, Clock())
        assertFalse(g.execute("tap", JSONObject()).ok)
        assertFalse(g.execute("tap", JSONObject()).ok)
        assertEquals(2, ask.asked.size)
        ask.answer = true
        assertTrue(g.execute("tap", JSONObject()).ok)
        g.revokeControl()
        assertTrue(g.execute("tap", JSONObject()).ok)
        assertEquals(4, ask.asked.size)
    }

    @Test fun theLeaseCoversOnlyControlToolsNotTheRest() {
        val ask = Ask(true)
        val g = GatedTools(tools, ask, Clock())
        g.execute("tap", JSONObject())
        g.execute("read_clipboard", JSONObject())
        g.execute("look_at_screen", JSONObject())
        assertEquals(listOf("tap", "read_clipboard", "look_at_screen"), ask.asked.map { it.first })
        assertTrue(PermissionPolicy.summary("tap", JSONObject().put("x", 5).put("y", 6)).contains("tap at 5,6"))
    }

    @Test fun readingTheScreenWaitsForAnAnswer() {
        val ask = Ask(true)
        assertTrue(gate(ask).execute("read_screen", JSONObject()).ok)
        assertEquals(listOf("read_screen"), ask.asked.map { it.first })
        val no = Ask(false)
        assertFalse(gate(no).execute("read_screen", JSONObject()).ok)
        assertEquals(Tier.CONFIRM, PermissionPolicy.tier("read_screen"))
    }

    @Test fun clipboardToolsWaitForAnAnswer() {
        val ask = Ask(true)
        assertTrue(gate(ask).execute("read_clipboard", JSONObject()).ok)
        assertTrue(gate(ask).execute("write_clipboard", args("hello")).ok)
        assertEquals(listOf("read_clipboard", "write_clipboard"), ask.asked.map { it.first })
        assertTrue(ask.asked[1].second.contains("hello"))
    }

    @Test fun aDeclinedToolNeverRuns() {
        val ask = Ask(false)
        val r = gate(ask).execute("write_clipboard", args("secret"))
        assertFalse(r.ok)
        assertTrue(r.text.contains("declined"))
        assertTrue(tools.executed.isEmpty())
    }

    @Test fun aBrokenApprovalScreenCountsAsNo() {
        val r = gate(Ask(true, boom = true)).execute("read_clipboard", JSONObject())
        assertFalse(r.ok)
        assertTrue(tools.executed.isEmpty())
    }

    @Test fun unknownToolsAreBlockedAndNotRun() {
        val ask = Ask(true)
        val r = gate(ask).execute("run_shell", JSONObject().put("cmd", "id"))
        assertFalse(r.ok)
        assertTrue(ask.asked.isEmpty()) // blocked, not "ask and maybe"
        assertTrue(tools.executed.isEmpty())
        assertEquals(Tier.BLOCK, PermissionPolicy.tier("anything_else"))
    }

    @Test fun theSummaryIsShortAndOnOneLine() {
        val s = PermissionPolicy.summary("write_clipboard", args("a\nb " + "x".repeat(300)))
        assertFalse(s.contains("\n"))
        assertTrue(s.length < 130)
    }

    // ---- the broker: worker thread asks, the screen answers

    private class Screen {
        val shown = ArrayList<ApprovalRequest?>()
        val lastShown = AtomicReference<ApprovalRequest?>()
        val latch = CountDownLatch(1)
        fun show(r: ApprovalRequest?) {
            synchronized(shown) { shown.add(r) }
            if (r != null) { lastShown.set(r); latch.countDown() }
        }
    }

    private fun ask(broker: ApprovalBroker, out: AtomicReference<Boolean?>): Thread =
        Thread { out.set(broker.approve("read_clipboard", "Read the clipboard")) }.also { it.start() }

    @Test fun allowReachesTheWaitingThread() {
        val screen = Screen()
        val broker = ApprovalBroker({ it() }, 5, screen::show)
        val out = AtomicReference<Boolean?>()
        val t = ask(broker, out)
        assertTrue(screen.latch.await(2, TimeUnit.SECONDS))
        broker.resolve(screen.lastShown.get()!!.id, true)
        t.join(2000)
        assertEquals(true, out.get())
        assertNull(synchronized(screen.shown) { screen.shown.last() }) // the card is taken down
    }

    @Test fun denyAndStopBothRefuse() {
        val screen = Screen()
        val broker = ApprovalBroker({ it() }, 5, screen::show)
        val out = AtomicReference<Boolean?>()
        var t = ask(broker, out)
        assertTrue(screen.latch.await(2, TimeUnit.SECONDS))
        broker.resolve(screen.lastShown.get()!!.id, false)
        t.join(2000)
        assertEquals(false, out.get())

        val screen2 = Screen()
        val broker2 = ApprovalBroker({ it() }, 5, screen2::show)
        val out2 = AtomicReference<Boolean?>()
        t = ask(broker2, out2)
        assertTrue(screen2.latch.await(2, TimeUnit.SECONDS))
        broker2.denyAll()
        t.join(2000)
        assertEquals(false, out2.get())
    }

    @Test fun silenceIsAnswerNo() {
        val broker = ApprovalBroker({ it() }, 1, {})
        val start = System.nanoTime()
        assertFalse(broker.approve("read_clipboard", "x"))
        assertTrue((System.nanoTime() - start) / 1_000_000 < 3000)
    }

    @Test fun anOldIdCannotApproveANewRequest() {
        val screen = Screen()
        val broker = ApprovalBroker({ it() }, 5, screen::show)
        val out = AtomicReference<Boolean?>()
        val t = ask(broker, out)
        assertTrue(screen.latch.await(2, TimeUnit.SECONDS))
        broker.resolve(screen.lastShown.get()!!.id + 99, true) // wrong id
        Thread.sleep(150)
        assertNull(out.get()) // still waiting
        broker.denyAll()
        t.join(2000)
        assertEquals(false, out.get())
    }
}
