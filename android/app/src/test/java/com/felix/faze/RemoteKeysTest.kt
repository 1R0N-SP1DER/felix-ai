package com.felix.faze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteKeysTest {
    @Test fun tappingAModifierTwiceTakesItBack() {
        val on = RemoteKeys.toggle(emptySet(), "ctrl")
        assertEquals(setOf("ctrl"), on)
        assertTrue(RemoteKeys.toggle(on, "ctrl").isEmpty())
    }

    @Test fun modifiersGoFirstInAFixedOrder() {
        assertEquals(listOf("ctrl", "shift", "alt", "t"), RemoteKeys.combo(setOf("alt", "shift", "ctrl"), "t"))
    }

    @Test fun aKeyWithNothingArmedIsJustThatKey() {
        assertEquals(listOf("esc"), RemoteKeys.combo(emptySet(), "esc"))
    }

    @Test fun pointerSpeedsGetFaster() {
        val f = PointerSpeed.values().map { it.factor }
        assertEquals(f.sorted(), f)
    }
}
