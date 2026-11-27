package com.felix.faze

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraTurnTest {
    @Test fun followsTheSensorMounting() {
        assertEquals(90, CameraTurn.jpeg(90)); assertEquals(270, CameraTurn.jpeg(270))
        assertEquals(0, CameraTurn.jpeg(360)); assertEquals(90, CameraTurn.jpeg(null))
    }
}
