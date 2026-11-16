package com.felix.faze

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/**
 * An invisible window that lives for a split second. Android lets only an app in focus read the clipboard, so when the root watcher sees a copy
 * it opens this, reads the clipboard the moment the window has focus, hands the text to the sync, and closes.
 */
class ClipGrabActivity : Activity() {
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Handler(Looper.getMainLooper()).postDelayed({ finishQuietly() }, 1500) // never linger if focus does not come
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !done) {
            done = true
            try { (application as FazeApp).core.shareClipboard() } catch (_: Exception) { }
            finishQuietly()
        }
    }

    private fun finishQuietly() {
        if (!isFinishing) { finish(); overridePendingTransition(0, 0) }
    }
}
