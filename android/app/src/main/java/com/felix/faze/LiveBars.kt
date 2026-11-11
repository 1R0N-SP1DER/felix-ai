package com.felix.faze

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** How loud a chunk of microphone audio is, for the listening bar. */
object MicLevel {
    /** 0..1 from 16-bit little-endian PCM, shaped (square root) so that quiet speech still moves the bar. */
    fun of(pcm: ByteArray): Float {
        val n = pcm.size / 2
        if (n == 0) return 0f
        var sum = 0.0
        for (i in 0 until n) {
            val v = ((pcm[2 * i + 1].toInt() shl 8) or (pcm[2 * i].toInt() and 0xff)).toShort().toInt()
            sum += v.toDouble() * v
        }
        return sqrt(min(1.0, sqrt(sum / n) / 5000.0)).toFloat()
    }
}

/** The shape of the listening bar. */
object LiveBars {
    /** Heights 0..1 for [n] bars: a bell (the middle is tallest) that follows [level] and ripples with [phase] (0..1, repeating). */
    fun heights(level: Float, phase: Float, n: Int, floor: Float = 0.08f): FloatArray {
        // boosted and curved so ordinary speech already reaches high, and each bar moves on its own two rhythms
        val l = Math.pow((level * 1.4f).coerceIn(0f, 1f).toDouble(), 0.75).toFloat()
        return FloatArray(n) { i ->
            val x = if (n <= 1) 0f else (i - (n - 1) / 2f) / ((n - 1) / 2f)
            val bell = exp(-1.3 * x * x).toFloat() // flatter than before: the outer bars move too
            val slow = sin(phase * 2.0 * PI + i * 0.9).toFloat()
            val quick = sin(phase * 2.0 * PI * 2.3 + i * 1.7).toFloat()
            val ripple = 0.55f + 0.30f * slow + 0.15f * quick
            (floor + (1f - floor) * l * bell * ripple).coerceIn(floor, 1f)
        }
    }
}
