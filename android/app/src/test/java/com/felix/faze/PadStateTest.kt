package com.felix.faze

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class PadStateTest {
    @Test fun buttonsAreHeldAndReleasedAndDuplicatesAreIgnored() {
        val sent = mutableListOf<JSONObject>()
        val pad = PadState { sent += it }
        pad.button("A", true); pad.button("A", true); pad.button("A", false); pad.button("A", false)
        assertEquals(2, sent.size)
        assertEquals("[\"A\"]", sent[0].getJSONArray("buttons").toString())
        assertEquals(0, sent[1].getJSONArray("buttons").length())
    }

    @Test fun sticksAreClampedAndReleaseAllCentres() {
        val sent = mutableListOf<JSONObject>()
        val pad = PadState { sent += it }
        pad.stick(true, 3f, -0.5f); pad.button("LB", true)
        assertEquals(1.0, sent.last().getDouble("lx"), 0.0)
        assertEquals(-0.5, sent.last().getDouble("ly"), 0.0)
        pad.releaseAll()
        assertEquals(0.0, sent.last().getDouble("lx"), 0.0)
        assertEquals(0, sent.last().getJSONArray("buttons").length())
    }
}
