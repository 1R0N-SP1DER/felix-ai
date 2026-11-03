package com.felix.faze

import android.accessibilityservice.AccessibilityService
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Watches the two volume keys (and nothing else: it does not read the screen) so that holding both opens the overlay from any
 * app, and holding volume up while it is open force-closes it. See [ChordDetector] for the rules.
 */
class FazeAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val detector = ChordDetector()
    private var overlay: OverlayWindow? = null
    private var savedMusic = -1
    private var savedRing = -1

    private val core: FazeCore get() = (application as FazeApp).core

    private val ticker = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            act(detector.check(now))
            step(detector.poll(now))
            if (detector.anyHeld()) handler.postDelayed(this, 80)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        overlay = OverlayWindow(this, core)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_UP && event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        if (!core.settings.chordEnabled) return false
        val down = event.action == KeyEvent.ACTION_DOWN
        detector.overlayOpen = overlay?.isShowing() == true
        if (down && event.repeatCount == 0 && !detector.anyHeld()) rememberVolumes()
        val result = detector.onKey(event.keyCode, down, event.repeatCount, SystemClock.uptimeMillis())
        act(result.action)
        step(result.adjust)
        if (detector.anyHeld()) {
            handler.removeCallbacks(ticker)
            handler.postDelayed(ticker, 80)
        }
        return result.consume
    }

    private fun act(action: ChordAction) {
        val o = overlay ?: return
        when (action) {
            ChordAction.OPEN -> {
                restoreVolumes()
                buzz()
                if (o.show()) detector.overlayOpen = true
            }
            ChordAction.CLOSE -> {
                restoreVolumes()
                buzz()
                o.close(true)
                detector.overlayOpen = false
            }
            ChordAction.NONE -> {}
        }
    }

    /** Every volume key is swallowed (so a chord never shows the volume slider); a real single press is replayed here. */
    private fun step(direction: Int) {
        if (direction == 0) return
        val am = getSystemService(AudioManager::class.java)
        am.adjustSuggestedStreamVolume(
            if (direction > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            AudioManager.USE_DEFAULT_STREAM_TYPE,
            AudioManager.FLAG_SHOW_UI,
        )
    }

    // A key held a while before the chord completes may already have stepped the volume once; put it back.
    private fun rememberVolumes() {
        val am = getSystemService(AudioManager::class.java)
        savedMusic = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        savedRing = am.getStreamVolume(AudioManager.STREAM_RING)
    }

    private fun restoreVolumes() {
        val am = getSystemService(AudioManager::class.java)
        try {
            if (savedMusic >= 0) am.setStreamVolume(AudioManager.STREAM_MUSIC, savedMusic, 0)
            if (savedRing >= 0) am.setStreamVolume(AudioManager.STREAM_RING, savedRing, 0)
        } catch (e: SecurityException) {
            // Do Not Disturb can forbid changing the ring volume
        }
    }

    private fun buzz() {
        try {
            getSystemService(Vibrator::class.java).vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {
            // no vibration is fine
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        overlay?.close(true)
        handler.removeCallbacks(ticker)
        super.onDestroy()
    }
}
