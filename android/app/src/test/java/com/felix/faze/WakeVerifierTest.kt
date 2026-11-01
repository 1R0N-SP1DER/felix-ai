package com.felix.faze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeVerifierTest {
    private fun result(vararg w: Triple<String, Double, Pair<Double, Double>>): String {
        val arr = w.joinToString(",") { "{\"conf\":${it.second},\"start\":${it.third.first},\"end\":${it.third.second},\"word\":\"${it.first}\"}" }
        return "{\"result\":[$arr],\"text\":\"${w.joinToString(" ") { it.first }}\"}"
    }

    private val good = result(Triple("hey", 1.0, Pair(0.5, 0.8)), Triple("faze", 0.95, Pair(0.85, 1.3)))

    @Test fun theRealPhraseIsAccepted() {
        assertTrue(WakeVerifier.accept(good, "hey faze"))
    }

    @Test fun aWordWithLowConfidenceIsRefused() {
        val r = result(Triple("hey", 1.0, Pair(0.5, 0.8)), Triple("faze", 0.4, Pair(0.85, 1.3)))
        assertFalse(WakeVerifier.accept(r, "hey faze"))
    }

    @Test fun aLookAlikeOrPartialPhraseIsRefused() {
        assertFalse(WakeVerifier.accept(result(Triple("hey", 1.0, Pair(0.5, 0.8)), Triple("phase", 1.0, Pair(0.85, 1.3))), "hey faze"))
        assertFalse(WakeVerifier.accept(result(Triple("faze", 1.0, Pair(0.5, 1.0))), "hey faze")) // the bare word alone
        assertFalse(WakeVerifier.accept(result(Triple("hey", 1.0, Pair(0.5, 0.9))), "hey faze"))
    }

    @Test fun aBurstTooShortOrTooLongIsRefused() {
        val blip = result(Triple("hey", 1.0, Pair(0.5, 0.55)), Triple("faze", 1.0, Pair(0.55, 0.62))) // a sneeze-length 120 ms
        assertFalse(WakeVerifier.accept(blip, "hey faze"))
        val slow = result(Triple("hey", 1.0, Pair(0.5, 1.5)), Triple("faze", 1.0, Pair(1.6, 4.0)))
        assertFalse(WakeVerifier.accept(slow, "hey faze"))
    }

    @Test fun emptyOrBrokenResultsAreRefused() {
        assertFalse(WakeVerifier.accept("{\"text\":\"\"}", "hey faze"))
        assertFalse(WakeVerifier.accept("{\"result\":[],\"text\":\"\"}", "hey faze"))
        assertFalse(WakeVerifier.accept("not json", "hey faze"))
        assertFalse(WakeVerifier.accept(result(Triple("[unk]", 1.0, Pair(0.0, 1.0))), "hey faze"))
    }

    @Test fun aSingleWordPhraseWorksToo() {
        assertTrue(WakeVerifier.accept(result(Triple("jarvis", 0.9, Pair(0.2, 0.8))), "Jarvis"))
    }

    @Test fun frameBufferCutsAnyReadSizeIntoFixedFrames() {
        val fb = FrameBuffer(4)
        val frames = ArrayList<List<Short>>()
        fb.push(shortArrayOf(1, 2, 3), 3) { frames.add(it.toList()) }
        assertTrue(frames.isEmpty())
        fb.push(shortArrayOf(4, 5, 6, 7, 8, 9), 6) { frames.add(it.toList()) }
        assertEquals(listOf(listOf<Short>(1, 2, 3, 4), listOf<Short>(5, 6, 7, 8)), frames)
        fb.clear()
        fb.push(shortArrayOf(1, 2, 3, 4), 4) { frames.add(it.toList()) }
        assertEquals(3, frames.size) // the leftover 9 was dropped by clear
    }

    @Test fun silentAudioIsDetected() {
        assertTrue(Pcm.isSilent(ByteArray(3200)))
        assertTrue(Pcm.isSilent(byteArrayOf(2, 0, -2, -1, 1, 0))) // tiny noise, below the threshold
        assertFalse(Pcm.isSilent(byteArrayOf(0, 0, 0x10, 0x27))) // 10000
        assertTrue(Pcm.isSilent(ByteArray(0)))
    }

    // ---- the volume-key chord

    private val UP = Keys.VOLUME_UP
    private val DOWN = Keys.VOLUME_DOWN

    @Test fun bothKeysHeldOpensTheOverlayOnce() {
        val c = ChordDetector()
        assertFalse(c.onKey(UP, true, 0, 1000).consume) // the first key is not swallowed
        val second = c.onKey(DOWN, true, 0, 1050)
        assertTrue(second.consume)
        assertEquals(ChordAction.NONE, second.action)
        assertEquals(ChordAction.NONE, c.check(1500))
        assertEquals(ChordAction.OPEN, c.check(1700))
        assertEquals(ChordAction.NONE, c.check(1800)) // only once
        assertEquals(ChordAction.NONE, c.onKey(UP, true, 3, 2000).action)
    }

    @Test fun releasingEarlyDoesNothing() {
        val c = ChordDetector()
        c.onKey(UP, true, 0, 1000)
        c.onKey(DOWN, true, 0, 1020)
        assertTrue(c.onKey(UP, false, 0, 1300).consume) // the release belongs to the chord
        assertEquals(ChordAction.NONE, c.check(2000))
        c.onKey(DOWN, false, 0, 1350)
        assertFalse(c.anyHeld())
    }

    @Test fun aSingleKeyIsNeverSwallowedWhileTheOverlayIsClosed() {
        val c = ChordDetector()
        assertFalse(c.onKey(UP, true, 0, 100).consume)
        assertFalse(c.onKey(UP, true, 1, 700).consume)
        assertEquals(ChordAction.NONE, c.check(5000))
        assertFalse(c.onKey(UP, false, 0, 900).consume)
        assertFalse(c.onKey(DOWN, true, 0, 1000).consume)
        assertEquals(ChordAction.NONE, c.check(5000))
    }

    @Test fun otherKeysAreIgnored() {
        assertFalse(ChordDetector().onKey(4, true, 0, 0).consume)
    }

    @Test fun volumeUpHeldClosesAnOpenOverlay() {
        val c = ChordDetector()
        c.overlayOpen = true
        assertFalse(c.onKey(UP, true, 0, 1000).consume) // the first press still changes the volume
        assertTrue(c.onKey(UP, true, 1, 1500).consume) // the held repeats do not
        assertEquals(ChordAction.NONE, c.check(1600))
        assertEquals(ChordAction.CLOSE, c.check(1750))
        assertEquals(ChordAction.NONE, c.check(1900))
    }

    @Test fun aQuickVolumeUpTapKeepsTheOverlayOpen() {
        val c = ChordDetector()
        c.overlayOpen = true
        c.onKey(UP, true, 0, 1000)
        assertFalse(c.onKey(UP, false, 0, 1200).consume)
        assertEquals(ChordAction.NONE, c.check(3000))
    }

    @Test fun volumeDownHeldDoesNotCloseIt() {
        val c = ChordDetector()
        c.overlayOpen = true
        c.onKey(DOWN, true, 0, 1000)
        assertEquals(ChordAction.NONE, c.check(3000))
    }

    @Test fun theKeysThatOpenedItDoNotCloseItWhileStillHeld() {
        val c = ChordDetector()
        c.onKey(UP, true, 0, 1000)
        c.onKey(DOWN, true, 0, 1010)
        assertEquals(ChordAction.OPEN, c.check(1700))
        c.overlayOpen = true
        c.onKey(DOWN, false, 0, 1800) // let go of one, keep holding volume up
        assertEquals(ChordAction.NONE, c.check(4000))
        c.onKey(UP, false, 0, 4100)
        // a fresh hold afterwards does close it
        c.onKey(UP, true, 0, 5000)
        assertEquals(ChordAction.CLOSE, c.check(5800))
    }

    @Test fun aSecondChordCanOpenItAgainAfterAClose() {
        val c = ChordDetector()
        c.onKey(UP, true, 0, 0); c.onKey(DOWN, true, 0, 10)
        assertEquals(ChordAction.OPEN, c.check(700))
        c.onKey(UP, false, 0, 800); c.onKey(DOWN, false, 0, 810)
        c.onKey(DOWN, true, 0, 2000); c.onKey(UP, true, 0, 2010)
        assertEquals(ChordAction.OPEN, c.check(2700))
    }

    @Test fun anotherChordWhileOpenIsSwallowedButDoesNotReopen() {
        val c = ChordDetector()
        c.overlayOpen = true
        c.onKey(UP, true, 0, 0)
        assertTrue(c.onKey(DOWN, true, 0, 10).consume)
        assertEquals(ChordAction.NONE, c.check(2000))
    }
}
