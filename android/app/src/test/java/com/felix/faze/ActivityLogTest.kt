package com.felix.faze

import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityLogTest {
    private fun file() = Files.createTempDirectory("act").toFile().resolve("activity.log")

    @Test fun entriesComeBackNewestFirstAndSurviveARestart() {
        val f = file()
        var t = 100L
        val log = ActivityLog(f, { t++ })
        log.add("wake", "Both volume keys held")
        log.add("tool", "get_battery_status -> done")
        assertEquals(listOf("tool", "wake"), log.entries().map { it.kind })
        val again = ActivityLog(f)
        assertEquals(listOf("get_battery_status -> done", "Both volume keys held"), again.entries().map { it.text })
    }

    @Test fun onlyTheNewestAreKeptAndClearEmptiesIt() {
        val f = file()
        val log = ActivityLog(f, max = 5)
        for (i in 1..9) log.add("tool", "t$i")
        assertEquals(listOf("t9", "t8", "t7", "t6", "t5"), log.entries().map { it.text })
        assertEquals(listOf("t9", "t8", "t7", "t6", "t5"), ActivityLog(f, max = 5).entries().map { it.text })
        log.clear()
        assertTrue(log.entries().isEmpty())
        assertTrue(ActivityLog(f).entries().isEmpty())
    }

    @Test fun summariesSayWhatHappenedAndNeverRecordWhatWasTyped() {
        assertEquals("get_battery_status -> done", ActivityLog.summary("get_battery_status", JSONObject(), ToolResult(true, "80%")))
        assertEquals("type_text(11 characters) -> done", ActivityLog.summary("type_text", JSONObject().put("text", "hunter2pass"), ToolResult(true, "ok")))
        assertFalse(ActivityLog.summary("type_text", JSONObject().put("text", "hunter2pass"), ToolResult(true, "ok")).contains("hunter2"))
        assertTrue(ActivityLog.summary("tap", JSONObject().put("x", 5), ToolResult(false, PermissionPolicy.DECLINED + " by the user")).endsWith("declined"))
        assertTrue(ActivityLog.summary("search_web", JSONObject(), ToolResult(false, "no key")).contains("failed: no key"))
    }

    @Test fun aDamagedLineDoesNotLoseTheRest() {
        val f = file()
        f.writeText("not json\n{\"t\":1,\"k\":\"tool\",\"x\":\"ok one\"}\n")
        assertEquals(listOf("ok one"), ActivityLog(f).entries().map { it.text })
    }
}
