package com.felix.faze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayPillTest {
    @Test fun theBarBecomesTheListeningBarOnlyWhileTheMicIsOnAndSomeoneTalks() {
        assertTrue(PillState.listening(true, true, 1000, 0))
        assertFalse(PillState.listening(true, false, 1000, 0))
        assertFalse(PillState.listening(false, true, 1000, 0))
    }

    @Test fun itStaysAMomentAfterTheLastWordSoItDoesNotFlicker() {
        assertTrue(PillState.listening(true, false, 1000, 2400))
        assertFalse(PillState.listening(true, false, 2500, 2400))
    }

    @Test fun theEndButtonIsStopSendOrTheLiveWave() {
        assertEquals("stop", PillState.trailing(true, true))
        assertEquals("send", PillState.trailing(false, true))
        assertEquals("live", PillState.trailing(false, false))
    }

    @Test fun theMicDictatesMidPhraseOtherwiseSwitchesTheListeningOnOrOff() {
        assertEquals("dictate", PillState.micAction(true, true))
        assertEquals("off", PillState.micAction(true, false))
        assertEquals("on", PillState.micAction(false, false))
        assertEquals("on", PillState.micAction(false, true))
    }

    @Test fun theRecogniserRetriesQuietOutcomesAndStopsWithoutPermission() {
        assertTrue(ListenRetry.again(ListenRetry.ERROR_NO_MATCH))
        assertTrue(ListenRetry.again(ListenRetry.ERROR_SPEECH_TIMEOUT))
        assertTrue(ListenRetry.again(ListenRetry.ERROR_BUSY))
        assertFalse(ListenRetry.again(ListenRetry.ERROR_PERMISSIONS))
        assertEquals("allow the microphone for Faze first", ListenRetry.message(ListenRetry.ERROR_PERMISSIONS))
        assertTrue(ListenRetry.delayMs(ListenRetry.ERROR_NO_MATCH) < ListenRetry.delayMs(ListenRetry.ERROR_BUSY))
    }
}
