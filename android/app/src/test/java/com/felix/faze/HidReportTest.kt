package com.felix.faze

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HidReportTest {
    private fun u(b: ByteArray, i: Int) = b[i].toInt() and 0xFF

    @Test fun idleReportIsCentred() {
        val r = HidReport.build(emptySet(), 0f, 0f, 0f, 0f)
        assertEquals(HidReport.SIZE, r.size)
        assertEquals(0, u(r, 0)); assertEquals(0, u(r, 1)); assertEquals(0, u(r, 2))
        for (i in 3..6) assertEquals(128, u(r, i))
        assertEquals(0, u(r, 7)); assertEquals(0, u(r, 8))
    }

    @Test fun buttonsSetTheirBits() {
        val r = HidReport.build(setOf("A", "X", "LB", "START", "RS"), 0f, 0f, 0f, 0f)
        assertEquals(1 or (1 shl 3) or (1 shl 6), u(r, 0))
        assertEquals((1 shl 3) or (1 shl 6), u(r, 1))
    }

    @Test fun hatDirectionsRunClockwise() {
        assertEquals(1, HidReport.hat(setOf("UP")))
        assertEquals(2, HidReport.hat(setOf("UP", "RIGHT")))
        assertEquals(3, HidReport.hat(setOf("RIGHT")))
        assertEquals(5, HidReport.hat(setOf("DOWN")))
        assertEquals(7, HidReport.hat(setOf("LEFT")))
        assertEquals(8, HidReport.hat(setOf("LEFT", "UP")))
        assertEquals(0, HidReport.hat(setOf("UP", "DOWN")))
        assertEquals(0, HidReport.hat(emptySet()))
    }

    @Test fun sticksAndTriggers() {
        val r = HidReport.build(setOf("LT"), 1f, -1f, 0.5f, 2f)
        assertEquals(255, u(r, 3)); assertEquals(1, u(r, 4)); assertEquals(192, u(r, 5)); assertEquals(255, u(r, 6))
        assertEquals(255, u(r, 7)); assertEquals(0, u(r, 8))
        assertEquals(1 shl 0, u(HidReport.build(setOf("LT"), 0f, 0f, 0f, 0f), 1) and 1)
    }

    @Test fun readsTheOnScreenPadState() {
        val o = JSONObject("""{"lx":-1,"ly":0,"rx":0,"ry":0,"buttons":["B","RT"]}""")
        val r = HidReport.fromState(o)
        assertEquals(1, u(r, 3)); assertEquals(2, u(r, 0) and 2); assertEquals(255, u(r, 8))
    }

    @Test fun descriptorIsWellFormed() {
        val d = HidReport.DESCRIPTOR
        assertEquals(0xC0, u(d, d.size - 1))
        var open = 0; var i = 0
        while (i < d.size) {
            val p = u(d, i); val n = when (p and 3) { 3 -> 4; else -> p and 3 }
            if (p == 0xA1) open++; if (p == 0xC0) open--
            i += 1 + n
        }
        assertEquals(i, d.size); assertEquals(0, open)
        // 16 buttons + 4 hat + 4 padding + 6 bytes of axes and triggers = the report without its id
        assertTrue(HidReport.SIZE == 2 + 1 + 4 + 2)
    }
}

class WheelTiltTest {
    @Test fun levelPhoneDoesNotSteer() {
        assertEquals(0f, WheelTilt.steer(floatArrayOf(0f, 0.2f, 9.8f), 1), 0.001f)
    }

    @Test fun clockwiseLeanSteersRightWhenTurnedLeft() {
        val v = WheelTilt.steer(floatArrayOf(0f, 4.9f, 8.5f), 1)
        assertTrue(v > 0.8f && v < 0.9f)
        assertEquals(-v, WheelTilt.steer(floatArrayOf(0f, -4.9f, 8.5f), 1), 0.001f)
    }

    @Test fun sidesAgreeAcrossRotations() {
        val right = WheelTilt.steer(floatArrayOf(0f, -4.9f, 8.5f), 3)
        assertTrue(right > 0.8f)
        assertTrue(WheelTilt.steer(floatArrayOf(-4.9f, 0f, 8.5f), 0) > 0.8f)
        assertTrue(WheelTilt.steer(floatArrayOf(4.9f, 0f, 8.5f), 2) > 0.8f)
    }

    @Test fun fullLockAndReverse() {
        assertEquals(1f, WheelTilt.steer(floatArrayOf(0f, 9f, 3f), 1), 0.001f)
        assertEquals(-1f, WheelTilt.steer(floatArrayOf(0f, 9f, 3f), 1, invert = true), 0.001f)
    }
}

class RumbleTest {
    @Test fun readsTheStrongerMotor() {
        assertEquals(200, HidReport.rumble(2, byteArrayOf(200.toByte(), 40)))
        assertEquals(0, HidReport.rumble(2, byteArrayOf(0, 0)))
    }

    @Test fun ignoresOtherReports() {
        assertEquals(null, HidReport.rumble(1, byteArrayOf(9, 9)))
        assertEquals(null, HidReport.rumble(2, byteArrayOf()))
    }
}
