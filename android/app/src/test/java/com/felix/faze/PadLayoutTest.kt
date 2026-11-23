package com.felix.faze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PadLayoutTest {
    @Test fun bindsAreParsedAndUnknownNamesDropped() {
        assertEquals(listOf("LS", "RS"), PadNames.parse("ls + rs"))
        assertEquals(listOf("A", "UP"), PadNames.parse("a, up, banana, a"))
        assertTrue(PadNames.parse("???").isEmpty())
    }

    @Test fun layoutsSurviveSavingAndLoading() {
        var saved: String? = null
        val a = PadLayouts({ saved }, { saved = it })
        a.select("Shooter")
        a.copy()
        a.setParts(a.active.parts + PadPart("z", "button", "Hi", listOf("LS", "RS"), .5f, .5f, 50f))
        val b = PadLayouts({ saved }, { saved = it })
        assertEquals("Shooter 2", b.current)
        assertEquals(listOf("LS", "RS"), b.active.parts.last().binds)
        assertEquals(5, b.layouts.size)
    }

    @Test fun cannotDeleteTheLastLayoutAndResetBringsBackThePresets() {
        val l = PadLayouts.memory()
        repeat(10) { l.delete() }
        assertEquals(1, l.layouts.size)
        l.resetAll()
        assertEquals(4, l.layouts.size)
    }

    @Test fun brokenSavedTextFallsBackToThePresets() {
        val l = PadLayouts({ "{not json" }, {})
        assertEquals("Xbox pad", l.current)
        assertEquals(4, l.layouts.size)
    }

    @Test fun tiltFollowsTheScreenRotationAndHasADeadZone() {
        assertEquals(1f, Tilt.steer(1, 0f, 9f), 0f)
        assertEquals(-1f, Tilt.steer(3, 0f, 9f), 0f)
        assertEquals(0f, Tilt.steer(1, 0f, 0.2f), 0f)
        assertEquals(0.5f, Tilt.steer(1, 0f, 2.75f), 0.001f)
    }

    @Test fun everyPresetUsesOnlyRealButtons() {
        for (l in PadPresets.all()) for (p in l.parts) assertTrue("${l.name}/${p.id}", p.binds.all { it in PadNames.ALL })
    }
}

class PadKeysTest {
    @Test fun keyboardKeysAreWrittenWithAPrefixAndPlainNamesStayOnTheController() {
        assertEquals(listOf("key:space", "A", "key:w"), PadNames.parse("key:SPACE, a, k:w"))
        assertEquals(listOf("key:ctrl", "key:c"), PadNames.parse("key:ctrl + key:c"))
        assertTrue(PadNames.parse("key:banana, key:").isEmpty())
    }

    @Test fun keyButtonsGoToTheKeyboardNotTheControllerState() {
        val sent = mutableListOf<org.json.JSONObject>(); val keys = mutableListOf<String>()
        val pad = PadState({ k, d -> keys += (if (d) "+" else "-") + k }, { sent += it })
        pad.button("key:space", true); pad.button("key:space", true); pad.button("key:space", false)
        assertEquals(listOf("+space", "-space"), keys)
        assertEquals(0, sent.size)
        pad.button("key:w", true); pad.releaseAll()
        assertEquals("-w", keys.last())
    }

    @Test fun qualityChoicesMapToWidths() {
        assertEquals(640, RemoteQuality.params("360", 2400).width)
        assertEquals(1280, RemoteQuality.params("720", 2400).width)
        assertEquals(1920, RemoteQuality.params("1080", 2400).width)
        assertEquals(2400, RemoteQuality.params("match", 2400).width)
        assertEquals(2560, RemoteQuality.params("match", 3200).width)
    }

    @Test fun frameRateIsOneOfTheChoices() {
        assertEquals(30, RemoteQuality.params("720", 2400).fps)
        assertEquals(60, RemoteQuality.params("720", 2400, 60).fps)
        assertEquals(15, RemoteQuality.params("1080", 2400, 15).fps)
        assertEquals(30, RemoteQuality.params("720", 2400, 24).fps)
    }
}
