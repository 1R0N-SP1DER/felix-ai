package com.felix.faze

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime

class ScheduleTest {
    private val zone = ZoneId.of("Africa/Lagos")
    private val now = ZonedDateTime.of(2026, 11, 10, 21, 30, 0, 0, zone).toInstant().toEpochMilli()

    private fun tmp(): File = File.createTempFile("sched", ".json").also { it.delete(); it.deleteOnExit() }

    private fun fails(block: () -> Unit): String = try { block(); fail("should have failed"); "" } catch (e: ToolFailure) { e.message!! }

    @Test fun inMinutesAddsToNow() {
        assertEquals(now + 20 * 60_000L, ScheduleTime.resolve(20, null, now, zone))
    }

    @Test fun aClockTimeMeansTheNextTimeItIsThatTime() {
        val tomorrow7 = ZonedDateTime.of(2026, 11, 11, 7, 0, 0, 0, zone).toInstant().toEpochMilli()
        val tonight22 = ZonedDateTime.of(2026, 11, 10, 22, 15, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(tomorrow7, ScheduleTime.resolve(null, "07:00", now, zone))
        assertEquals(tomorrow7, ScheduleTime.resolve(null, "7:00", now, zone))
        assertEquals(tonight22, ScheduleTime.resolve(null, "22:15", now, zone))
    }

    @Test fun badTimesAreRefusedWithAReason() {
        assertTrue(fails { ScheduleTime.resolve(null, null, now, zone) }.contains("either"))
        assertTrue(fails { ScheduleTime.resolve(5, "07:00", now, zone) }.contains("either"))
        assertTrue(fails { ScheduleTime.resolve(0, null, now, zone) }.contains("between"))
        assertTrue(fails { ScheduleTime.resolve(null, "25:99", now, zone) }.contains("07:30"))
        assertTrue(fails { ScheduleTime.resolve(null, "soon", now, zone) }.contains("07:30"))
    }

    @Test fun repeatsHaveALimit() {
        assertEquals(0, ScheduleTime.repeat(null))
        assertEquals(60, ScheduleTime.repeat(60))
        assertTrue(fails { ScheduleTime.repeat(5) }.contains("15"))
        assertTrue(fails { ScheduleTime.repeat(99999) }.contains("week"))
    }

    @Test fun aRepeatingTaskSkipsSlotsThatWereMissed() {
        val hour = 60 * 60_000L
        assertEquals(10 * hour + hour, ScheduleTime.advance(10 * hour, 60, 10 * hour + 5))
        assertEquals(15 * hour, ScheduleTime.advance(10 * hour, 60, 14 * hour + 1))
    }

    @Test fun tasksAreSavedAndReloaded() {
        val f = tmp()
        val a = ScheduleStore(f) { now }
        val t = a.add("tell me the weather", now + 1000, 1440)
        assertEquals("t1", t.id)
        val b = ScheduleStore(f) { now }
        assertEquals(listOf("tell me the weather"), b.list().map { it.prompt })
        assertEquals(1440, b.list()[0].everyMinutes)
    }

    @Test fun aDueOneOffRunsOnceThenIsGone() {
        val s = ScheduleStore(tmp()) { now }
        s.add("stretch", now + 1000, 0)
        assertTrue(s.takeDue(now).isEmpty())
        assertEquals(1, s.takeDue(now + 2000).size)
        assertTrue(s.takeDue(now + 3000).isEmpty())
        assertTrue(s.list().isEmpty())
    }

    @Test fun aDueRepeatMovesOnAndDoesNotRunTwice() {
        val s = ScheduleStore(tmp()) { now }
        s.add("battery", now + 1000, 60)
        assertEquals(1, s.takeDue(now + 2000).size)
        assertTrue(s.takeDue(now + 3000).isEmpty())
        assertEquals(1, s.list().size)
        assertTrue(s.list()[0].nextAt > now + 3000)
    }

    @Test fun limitsAndCancel() {
        val s = ScheduleStore(tmp()) { now }
        assertTrue(fails { s.add("  ", now, 0) }.contains("what to do"))
        assertTrue(fails { s.add("x".repeat(301), now, 0) }.contains("300"))
        repeat(ScheduleStore.MAX_TASKS) { s.add("t", now + it, 0) }
        assertTrue(fails { s.add("one more", now, 0) }.contains("cancel one"))
        assertTrue(s.cancel("T3"))
        assertFalse(s.cancel("t3"))
        s.add("again", now, 0)
        assertEquals(ScheduleStore.MAX_TASKS, s.list().size)
    }

    private class FakeScheduler(val store: ScheduleStore, val at: Long) : SchedulerApi {
        override fun add(prompt: String, atMillis: Long, everyMinutes: Int, action: ScheduledAction?) = store.add(prompt, atMillis, everyMinutes, action)
        override fun list() = store.list()
        override fun cancel(id: String) = store.cancel(id)
        override fun now() = at
        override fun zone(): ZoneId = ZoneId.of("Africa/Lagos")
    }

    private fun tools(): Tools = Tools(FakeDevice(), FakeTransport(), FakeSettings(), scheduler = FakeScheduler(ScheduleStore(tmp()) { now }, now))

    @Test fun theToolsScheduleListAndCancel() {
        val t = tools()
        val made = t.execute("schedule_task", JSONObject().put("task", "remind me to stretch").put("in_minutes", 20).put("at", "").put("repeat_minutes", 0))
        assertTrue(made.text, made.ok)
        assertTrue(made.text.contains("t1") && made.text.contains("21:50") && made.text.contains("once"))
        assertTrue(t.execute("list_scheduled", JSONObject()).text.contains("remind me to stretch"))
        assertTrue(t.execute("cancel_scheduled", JSONObject().put("id", "t1")).ok)
        assertEquals("Nothing is scheduled.", t.execute("list_scheduled", JSONObject()).text)
        assertFalse(t.execute("cancel_scheduled", JSONObject().put("id", "t9")).ok)
    }

    @Test fun aBadScheduleFailsWithTheReasonForTheModel() {
        val r = tools().execute("schedule_task", JSONObject().put("task", "x").put("in_minutes", 0).put("at", "").put("repeat_minutes", 0))
        assertFalse(r.ok)
        assertTrue(r.text.contains("either"))
    }

    @Test fun theToolsOnlyExistWhenThereIsAScheduler() {
        val names = { t: Tools -> (0 until t.schema().length()).map { t.schema().getJSONObject(it).getJSONObject("function").getString("name") } }
        assertTrue(names(tools()).containsAll(listOf("schedule_task", "list_scheduled", "cancel_scheduled")))
        assertFalse(names(Tools(FakeDevice(), FakeTransport(), FakeSettings())).contains("schedule_task"))
    }

    @Test fun schedulingNeedsApprovalButListingAndCancellingDoNot() {
        assertEquals(Tier.CONFIRM, PermissionPolicy.tier("schedule_task"))
        assertEquals(Tier.SAFE, PermissionPolicy.tier("list_scheduled"))
        assertEquals(Tier.SAFE, PermissionPolicy.tier("cancel_scheduled"))
    }
}
