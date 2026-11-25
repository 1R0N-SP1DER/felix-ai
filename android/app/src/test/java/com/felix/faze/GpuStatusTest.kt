package com.felix.faze

import org.junit.Assert.assertTrue
import org.junit.Test

class GpuStatusTest {
    @Test fun saysWhatHappened() {
        assertTrue(GpuStatus.describe("", 0).startsWith("no"))
        assertTrue(GpuStatus.describe("", 16).startsWith("unknown"))
        assertTrue(GpuStatus.describe("GPU: offloaded 16/32 layers to GPU", 16).startsWith("yes. offloaded 16/32"))
        assertTrue(GpuStatus.describe("The GPU did not work with this llama-server, so the model runs on the CPU.", 16).startsWith("no."))
    }
}
