package com.felix.faze

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WebPortTest {
    private val idx = intArrayOf(0, 7, 30, 60, 91, 119)

    private fun assertRadii(name: String, expected: DoubleArray) {
        val r = CoreShapes.radii(name)
        assertEquals(CoreShapes.N, r.size)
        for ((k, i) in idx.withIndex()) assertEquals("$name[$i]", expected[k], r[i].toDouble(), 1e-5)
    }

    // The numbers below were produced by running the real core/web/core.js under node.
    @Test fun shapesMatchTheWebCore() {
        assertRadii("faze", doubleArrayOf(0.995750, 0.972487, 0.989391, 0.955489, 0.958868, 0.997656))
        assertRadii("gpt", doubleArrayOf(1.0, 0.729999, 0.689536, 1.0, 0.681873, 0.996280))
        assertRadii("claude", doubleArrayOf(1.0, 0.923971, 1.0, 1.0, 0.991833, 0.991833))
        assertRadii("gemini", doubleArrayOf(1.0, 0.704449, 1.0, 1.0, 0.992404, 0.992404))
        assertRadii("qwen", doubleArrayOf(0.840896, 0.895898, 0.840896, 0.840896, 0.842049, 0.842049))
        assertRadii("kimi", doubleArrayOf(0.905074, 0.975640, 0.809801, 0.447751, 0.483283, 0.884637))
        assertRadii("lfm", doubleArrayOf(0.974692, 0.770683, 0.996961, 0.928527, 0.896606, 0.974903))
    }

    @Test fun everyShapeIsScaledToOneAndCached() {
        for (n in CoreShapes.CYCLE) assertEquals(1.0, CoreShapes.radii(n).max().toDouble(), 1e-6)
        assertSame(CoreShapes.radii("kimi"), CoreShapes.radii("kimi"))
        assertEquals("kimi", CoreShapes.MODEL_FORM["groq"])
        assertEquals(listOf("faze", "gpt", "claude", "gemini", "qwen", "kimi", "lfm"), CoreShapes.CYCLE)
    }

    @Test fun springAndSmoothMatch() {
        assertEquals(0.380350, CoreShapes.spring(0.1), 1e-5)
        assertEquals(1.043750, CoreShapes.spring(0.5), 1e-5)
        assertEquals(1.011150, CoreShapes.spring(0.9), 1e-5)
        assertEquals(1.0, CoreShapes.spring(1.0), 1e-9)
        assertEquals(0.156250, CoreShapes.smooth(0.25), 1e-6)
    }

    private fun stepN(m: CoreMorph, n: Int, dt: Double) { repeat(n) { m.step(dt) } }

    @Test fun cyclingFollowsTheWebTrajectory() {
        val m = CoreMorph()
        stepN(m, 1, 0.05)
        assertEquals(1, m.cycleIndex); assertEquals(1.1, m.hold, 1e-6); assertEquals(0.0, m.t, 1e-6)
        assertEquals(0.995750, m.cur[0].toDouble(), 1e-5)
        stepN(m, 19, 0.05) // 20 steps
        assertEquals(1, m.cycleIndex); assertEquals(1.1, m.hold, 1e-6); assertEquals(0.339286, m.t, 1e-5)
        assertEquals(0.999624, m.cur[0].toDouble(), 1e-5); assertEquals(0.716067, m.cur[30].toDouble(), 1e-5)
        assertEquals(0.996062, m.cur[60].toDouble(), 1e-5)
        stepN(m, 40, 0.05) // 60
        assertEquals(0.95, m.hold, 1e-5); assertEquals(1.0, m.t, 1e-9); assertEquals(0.689536, m.cur[30].toDouble(), 1e-5)
        assertEquals(0.210000, m.rot, 1e-6)
        stepN(m, 60, 0.05) // 120
        assertEquals(2, m.cycleIndex); assertEquals(0.732143, m.t, 1e-5)
        assertEquals(1.016050, m.cur[30].toDouble(), 1e-5) // the spring overshoots, as on the web
        stepN(m, 120, 0.05) // 240
        assertEquals(4, m.cycleIndex); assertEquals(0.089286, m.t, 1e-5)
        assertEquals(0.945169, m.cur[0].toDouble(), 1e-5)
        assertEquals(0.840000, m.rot, 1e-6)
    }

    @Test fun aChosenModelMorphsIntoItsFormAndStays() {
        val m = CoreMorph()
        m.setModel("groq")
        stepN(m, 1, 0.05)
        assertEquals(0.052632, m.t, 1e-5); assertEquals(0.976390, m.cur[0].toDouble(), 1e-5); assertEquals(0.847087, m.cur[60].toDouble(), 1e-5)
        stepN(m, 9, 0.05)
        assertEquals(0.526316, m.t, 1e-5); assertEquals(0.420769, m.cur[60].toDouble(), 1e-5)
        stepN(m, 10, 0.05)
        assertEquals(1.0, m.t, 1e-9); assertEquals(0.905074, m.cur[0].toDouble(), 1e-5); assertEquals(0.447751, m.cur[60].toDouble(), 1e-5)
        assertEquals(0, m.cycleIndex); assertEquals(false, m.cycle)
        stepN(m, 200, 0.05)
        assertEquals(0, m.cycleIndex) // it stays put
    }

    @Test fun workingSpeedsEverythingUp() {
        val m = CoreMorph()
        m.setModel("qwen")
        m.setState("thinking")
        stepN(m, 10, 0.02)
        assertEquals(0.210526, m.t, 1e-5); assertEquals(0.889662, m.cur[0].toDouble(), 1e-5); assertEquals(0.033600, m.rot, 1e-6)
        stepN(m, 90, 0.02)
        assertEquals(1.0, m.t, 1e-9); assertEquals(0.840896, m.cur[60].toDouble(), 1e-5); assertEquals(0.336000, m.rot, 1e-6)
    }

    @Test fun backToAutoCyclesAgain() {
        val m = CoreMorph()
        m.setModel("lfm")
        stepN(m, 40, 0.05)
        m.setModel("auto")
        assertTrue(m.cycle); assertEquals(0.4, m.hold, 1e-9)
        stepN(m, 20, 0.05)
        assertTrue(m.cycleIndex > 0)
        assertNotSame(CoreShapes.radii("faze"), m.cur) // the working copy is its own array
    }

    @Test fun pulseDecaysAndIsCapped() {
        val m = CoreMorph()
        m.pulse(0.9)
        assertEquals(0.5, m.pulseV, 1e-9)
        m.step(2.0)
        assertTrue(m.pulseV < 0.01)
    }

    // ---- greetings, grouping, accents

    @Test fun greetingsFillInTheNames() {
        assertEquals("Fire away.", Greetings.greetingFor(9, "Felix", "Faze") { 0.0 }.let { Greetings.any.last() })
        val first = Greetings.greetingFor(23, "Felix", "Faze") { 0.0 }
        assertEquals("Coffee and Faze time?", first)
        assertEquals("What's on your mind, there?", Greetings.greetingFor(9, "", "Faze") { 0.1 })
        val lastNight = Greetings.greetingFor(2, "Felix", "Faze") { 0.999999 }
        assertEquals("Late-night ideas, Felix?", lastNight)
        assertEquals(Greetings.any.size + Greetings.morning.size, Greetings.pool(5).size)
        assertEquals(Greetings.any + Greetings.afternoon, Greetings.pool(16))
        assertEquals(Greetings.any + Greetings.evening, Greetings.pool(21))
        assertEquals(Greetings.any + Greetings.night, Greetings.pool(22))
        assertEquals(Greetings.any + Greetings.night, Greetings.pool(4))
    }

    private fun rec(id: String, title: String, updated: Long, vararg text: String) =
        ChatRecord(id, title, updated, text.mapIndexed { i, t -> ChatMessage(i + 1L, if (i % 2 == 0) "user" else "assistant", t) })

    @Test fun chatsAreGroupedByAgeNewestFirst() {
        val day = 86_400_000L
        val start = 100 * day
        val chats = listOf(
            rec("old", "Old one", start - 30 * day), rec("week", "This week", start - 3 * day), rec("y", "Yesterday chat", start - day / 2),
            rec("t2", "Today b", start + 5000), rec("t1", "Today a", start + 9000),
        )
        val g = ChatGroups.group(chats, start)
        assertEquals(listOf("Today", "Yesterday", "Previous 7 days", "Older"), g.map { it.title })
        assertEquals(listOf("t1", "t2"), g[0].items.map { it.id })
        assertEquals(listOf("y"), g[1].items.map { it.id })
    }

    @Test fun emptyGroupsAreLeftOutAndSearchChecksTitlesAndText() {
        val start = 100 * 86_400_000L
        val chats = listOf(rec("a", "Weather", start + 1, "what's the weather", "sunny"), rec("b", "Plan", start + 2, "make a plan", "ok, step one"))
        assertEquals(listOf("Today"), ChatGroups.group(chats, start).map { it.title })
        assertEquals(listOf("a"), ChatGroups.group(chats, start, "SUNNY")[0].items.map { it.id })
        assertEquals(listOf("b"), ChatGroups.group(chats, start, "plan")[0].items.map { it.id })
        assertTrue(ChatGroups.group(chats, start, "zzz").isEmpty())
        assertTrue(ChatGroups.group(emptyList(), start).isEmpty())
    }

    @Test fun accentsAreTheWebOnes() {
        assertEquals(listOf("cyan", "ice", "violet", "mint", "amber"), Accents.all.map { it.name })
        assertEquals(0xFF5CC8FFL, Accents.byName("cyan").light)
        assertEquals(0xFF3D7BFFL, Accents.byName("cyan").partner)
        assertEquals("cyan", Accents.byName("nonsense").name)
    }

    // ---- renaming a chat

    private fun tmp(): File = File.createTempFile("faze", "dir").let { it.delete(); it.mkdirs(); it }

    @Test fun aRenamedChatKeepsItsNameWhenSavedAgain() {
        val s = ChatStore(tmp())
        s.save("a", listOf(ChatMessage(1, "user", "first words")))
        s.rename("a", "  My   plan  ")
        assertEquals("My plan", s.load("a")!!.title)
        s.save("a", listOf(ChatMessage(1, "user", "first words"), ChatMessage(2, "assistant", "hello")))
        assertEquals("My plan", s.load("a")!!.title) // not replaced by the automatic title
        assertEquals(2, s.load("a")!!.messages.size)
        assertTrue(s.load("a")!!.renamed)
    }

    @Test fun renameIgnoresBlankNamesUnknownChatsAndLongNames() {
        val s = ChatStore(tmp())
        s.save("a", listOf(ChatMessage(1, "user", "hello")))
        s.rename("a", "   ")
        assertEquals("hello", s.load("a")!!.title)
        s.rename("nope", "x") // no such chat: nothing happens
        assertEquals(1, s.list().size)
        s.rename("a", "n".repeat(100))
        assertEquals(60, s.load("a")!!.title.length)
    }
}
