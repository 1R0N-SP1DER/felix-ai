package com.felix.faze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThrowGestureTest {
    @Test fun firesOnlyAfterEnoughDragInEitherDirection() {
        assertFalse(ThrowGesture.fires(100f, 1000f))
        assertTrue(ThrowGesture.fires(400f, 1000f))
        assertTrue(ThrowGesture.fires(-450f, 1000f))
        assertFalse(ThrowGesture.fires(500f, 0f))
    }

    @Test fun theCardFollowsButNeverLeavesTheScreen() {
        assertEquals(0f, ThrowGesture.pull(0f, 1000f), 0.001f)
        assertTrue(ThrowGesture.pull(300f, 1000f) in 1f..600f)
        assertTrue(ThrowGesture.pull(5000f, 1000f) <= 600f)
        assertEquals(-ThrowGesture.pull(300f, 1000f), ThrowGesture.pull(-300f, 1000f), 0.001f)
        assertEquals(0f, ThrowGesture.pull(300f, 0f), 0.001f)
    }
}
