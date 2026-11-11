package com.felix.faze

/** What the live screen can show the model besides your voice. */
object ShareMode {
    const val NONE = ""
    const val CAMERA = "camera"
    const val SCREEN = "screen"
}

/** A source of still pictures (camera or screen). Frames arrive as small JPEGs. */
interface VideoSource {
    /** Starts without blocking. [ready] is told (on any thread) null when it started, otherwise the reason it could not. */
    fun start(onFrame: (ByteArray) -> Unit, ready: (String?) -> Unit)
    fun stop()
}

/**
 * Lets a frame through at most once per [minGapMs], and never while the last one is still being sent. The model is sent about one picture
 * a second: enough to see what you show it, small enough for the connection and the free quota.
 */
class FrameGate(private val minGapMs: Long = 1000, private val clock: () -> Long = { System.currentTimeMillis() }) {
    private var last = Long.MIN_VALUE / 2
    private var sending = false

    @Synchronized fun tryBegin(): Boolean {
        val now = clock()
        if (sending || now - last < minGapMs) return false
        sending = true
        last = now
        return true
    }

    @Synchronized fun done() { sending = false }
}
