package com.felix.faze

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockPolicyTest {
    @Test fun onlyHarmlessToolsAreAllowedWhileLocked() {
        for (t in listOf("get_current_datetime", "search_web", "get_weather", "find_images")) assertTrue(t, LockPolicy.allows(t))
        for (t in listOf("recall", "remember", "open_app", "tap", "type_text", "read_clipboard", "schedule_task", "generate_image", "read_screen")) assertFalse(t, LockPolicy.allows(t))
    }

    @Test fun everyAllowedToolIsOneTheGateAlreadyTreatsAsSafe() {
        for (t in LockPolicy.ALLOWED) assertEquals(t, Tier.SAFE, PermissionPolicy.tier(t))
    }

    @Test fun theGateRefusesOtherToolsWhileLockedButNotWhenUnlocked() {
        val inner = FakeTools(mapOf("recall" to ToolResult(true, "secret"), "search_web" to ToolResult(true, "ok")))
        var locked = true
        val gate = GatedTools(inner, object : Approver { override fun approve(tool: String, summary: String) = true }, locked = { locked })
        val refused = gate.execute("recall", JSONObject())
        assertFalse(refused.ok)
        assertTrue(refused.text.contains("locked"))
        assertTrue(gate.execute("search_web", JSONObject()).ok)
        locked = false
        assertEquals("secret", gate.execute("recall", JSONObject()).text)
    }
}
