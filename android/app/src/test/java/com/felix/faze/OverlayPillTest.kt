package com.felix.faze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayPillTest {
    @Test fun theBarOpensAfterAboutASecondWhetherOrNotAnythingIsHeard() {
        val m = PillMode()
        m.open(0, 6000)
        assertFalse(m.listening(true, false, 500))  // "Message FAZE" first
        assertTrue(m.listening(true, false, 1200))  // then the bar, with nobody speaking
        assertTrue(m.listening(true, false, 6900))
        assertFalse(m.listening(true, false, 7100)) // nobody spoke for the idle time: back to "Message FAZE"
    }

    @Test fun speechShowsTheBarAtOnceAndKeepsItAMomentAfterTheLastWord() {
        val m = PillMode()
        m.open(0, 6000)
        assertTrue(m.listening(true, true, 300))      // spoke during the first second
        assertTrue(m.listening(true, false, 1300))
        assertFalse(m.listening(true, false, 7100))
        assertTrue(m.listening(true, true, 20000))    // speech much later brings it back
        assertTrue(m.listening(true, false, 21000))
        assertFalse(m.listening(true, false, 21200))
    }

    @Test fun aSwitchedOffMicNeverShowsTheBarAndSwitchingItOnRestartsIt() {
        val m = PillMode()
        m.open(0, 6000)
        assertFalse(m.listening(false, true, 2000))
        m.restart(30000, 6000)
        assertTrue(m.listening(true, false, 30010))
        assertFalse(m.listening(true, false, 36100))
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

    @Test fun soundShowsTheListeningBarWithoutWaitingForTheLoop() {
        assertTrue(PillState.heard(false, 0.6f, false))
        assertFalse(PillState.heard(false, 0.2f, false)) // a quiet room
        assertFalse(PillState.heard(false, 0.9f, true)) // Faze's own voice from the speaker
        assertTrue(PillState.heard(true, 0f, true)) // the loop confirmed speech
    }
}
