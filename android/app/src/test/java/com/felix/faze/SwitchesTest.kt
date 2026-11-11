package com.felix.faze

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime

class SwitchesTest {
    private val zone = ZoneId.of("Africa/Lagos")
    private val now = ZonedDateTime.of(2026, 11, 11, 5, 0, 0, 0, zone).toInstant().toEpochMilli()

    private class FakeSwitches : Switches {
        val log = mutableListOf<String>()
        override fun mobileData(on: Boolean): String { log.add("data $on"); return "Mobile data is ${if (on) "on" else "off"}." }
        override fun wifi(on: Boolean): String { log.add("wifi $on"); return "Wi-Fi is ${if (on) "on" else "off"}." }
        override fun bluetooth(on: Boolean): String { log.add("bt $on"); return "Bluetooth is ${if (on) "on" else "off"}." }
    }

    private class Sched(val store: ScheduleStore, val at: Long) : SchedulerApi {
        override fun add(prompt: String, atMillis: Long, everyMinutes: Int, action: ScheduledAction?) = store.add(prompt, atMillis, everyMinutes, action)
        override fun list() = store.list()
        override fun cancel(id: String) = store.cancel(id)
        override fun now() = at
        override fun zone(): ZoneId = ZoneId.of("Africa/Lagos")
    }

    private fun tmp(): File = File.createTempFile("sw", ".json").also { it.delete(); it.deleteOnExit() }

    private fun tools(sw: FakeSwitches, store: ScheduleStore = ScheduleStore(tmp()) { now }) =
        Tools(FakeDevice(), FakeTransport(), FakeSettings(), scheduler = Sched(store, now), switches = sw)

    @Test fun theCommandsAreTheRootSvcOnes() {
        assertEquals("svc data disable", SwitchCommands.mobileData(false))
        assertEquals("svc data enable", SwitchCommands.mobileData(true))
        assertEquals("svc wifi disable", SwitchCommands.wifi(false))
        assertEquals("svc bluetooth enable", SwitchCommands.bluetooth(true))
    }

    @Test fun theSwitchToolsFlipTheSwitch() {
        val sw = FakeSwitches()
        val t = tools(sw)
        assertTrue(t.execute("set_mobile_data", JSONObject().put("enabled", false)).ok)
        assertTrue(t.execute("set_wifi", JSONObject().put("enabled", true)).ok)
        assertEquals(listOf("data false", "wifi true"), sw.log)
        assertFalse("missing enabled is refused, not guessed", t.execute("set_wifi", JSONObject()).ok)
    }

    @Test fun aScheduledActionIsStoredWithItsActionAndFiresNothingNow() {
        val sw = FakeSwitches()
        val store = ScheduleStore(tmp()) { now }
        val r = tools(sw, store).execute("schedule_action", JSONObject().put("action", "set_mobile_data").put("enabled", false).put("in_minutes", 0).put("at", "23:00").put("repeat_minutes", 1440))
        assertTrue(r.text, r.ok)
        assertTrue(r.text.contains("Turn mobile data off") && r.text.contains("locked"))
        assertTrue(sw.log.isEmpty())
        val task = store.list().single()
        assertEquals(ScheduledAction("set_mobile_data", false), task.action)
        assertEquals(1440, task.everyMinutes)
    }

    @Test fun theActionSurvivesASaveAndReload() {
        val f = tmp()
        ScheduleStore(f) { now }.add("Turn Wi-Fi off", now + 1000, 0, ScheduledAction("set_wifi", false))
        assertEquals(ScheduledAction("set_wifi", false), ScheduleStore(f) { now }.list().single().action)
        // a task saved before actions existed still loads, as a chat task
        f.writeText("[{\"id\":\"t1\",\"prompt\":\"stretch\",\"nextAt\":5,\"every\":0,\"createdAt\":1}]")
        assertEquals(null, ScheduleStore(f) { now }.list().single().action)
    }

    @Test fun onlyTheAllowedSwitchesCanBeScheduled() {
        val r = tools(FakeSwitches()).execute("schedule_action", JSONObject().put("action", "tap").put("enabled", true).put("in_minutes", 5).put("at", "").put("repeat_minutes", 0))
        assertFalse(r.ok)
        assertTrue(r.text.contains("set_mobile_data"))
        assertEquals(setOf("set_mobile_data", "set_wifi", "set_bluetooth"), ScheduledActions.ALLOWED)
    }

    @Test fun theApprovalSaysExactlyWhatWillHappenAndWhen() {
        val s = PermissionPolicy.summary("schedule_action", JSONObject().put("action", "set_mobile_data").put("enabled", false).put("at", "23:00").put("in_minutes", 0).put("repeat_minutes", 1440))
        assertTrue(s, s.contains("Turn mobile data off") && s.contains("at 23:00") && s.contains("every day") && s.contains("locked"))
        assertTrue(PermissionPolicy.summary("schedule_task", JSONObject().put("task", "weather").put("in_minutes", 20)).contains("in 20 minutes"))
        assertEquals("Turn Wi-Fi on now", PermissionPolicy.summary("set_wifi", JSONObject().put("enabled", true)))
        assertNotNull(ApprovalText.build("schedule_action", s))
    }

    @Test fun theNewToolsNeedApproval() {
        for (n in listOf("set_mobile_data", "set_wifi", "set_bluetooth", "schedule_action")) assertEquals(n, Tier.CONFIRM, PermissionPolicy.tier(n))
    }

    @Test fun theToolsOnlyExistWithTheirParts() {
        val names = { t: Tools -> (0 until t.schema().length()).map { t.schema().getJSONObject(it).getJSONObject("function").getString("name") } }
        assertTrue(names(tools(FakeSwitches())).containsAll(listOf("set_mobile_data", "schedule_action")))
        assertFalse(names(Tools(FakeDevice(), FakeTransport(), FakeSettings())).contains("set_wifi"))
        assertFalse(names(Tools(FakeDevice(), FakeTransport(), FakeSettings(), switches = FakeSwitches())).contains("schedule_action"))
    }
}
