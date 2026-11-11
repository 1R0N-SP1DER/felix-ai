package com.felix.faze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveControlTest {
    @Test fun endsAfterTheGoodbyeIsSpoken() {
        var polls = 0
        var ended = 0
        var slept = 0L
        // not speaking for 2 polls, speaking for 3, then done
        val speaking = { polls++; polls in 3..5 }
        EndAfterSpeech.run(speaking, { slept += it }, { ended++ })
        assertEquals(1, ended)
        assertTrue(polls >= 6)
    }

    @Test fun neverWaitsForEverIfSpeechNeverStarts() {
        var slept = 0L
        var ended = 0
        EndAfterSpeech.run({ false }, { slept += it }, { ended++ })
        assertEquals(1, ended)
        assertTrue(slept <= 3500)
    }

    @Test fun neverWaitsForEverIfSpeechNeverStops() {
        var slept = 0L
        var ended = 0
        EndAfterSpeech.run({ true }, { slept += it }, { ended++ })
        assertEquals(1, ended)
        assertTrue(slept <= 12_500)
    }

    @Test fun endLiveRoutesAreBuiltIn() {
        for (m in listOf("end live", "stop live mode", "hang up", "end call", "leave voice chat", "hey faze end live please"))
            assertEquals(m, "end_live", BuiltInRoutes.route(m)?.tool)
        assertEquals(null, BuiltInRoutes.route("end the war in my story"))
    }

    @Test fun endLiveIsSafeToRun() {
        assertEquals(Tier.SAFE, PermissionPolicy.tier("end_live"))
    }
}
