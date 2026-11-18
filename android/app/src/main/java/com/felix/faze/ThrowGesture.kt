package com.felix.faze

import kotlin.math.abs
import kotlin.math.sign

/** Dragging a device card sideways "throws" this phone's screen onto that computer. Pure maths so it can be tested. */
object ThrowGesture {
    const val FRACTION = 0.4f

    /** True once the drag has gone far enough across the card. */
    fun fires(total: Float, width: Float, fraction: Float = FRACTION): Boolean = width > 0f && abs(total) >= width * fraction

    /** How far the card follows the finger: it resists more the further it is pulled, and never leaves the screen. */
    fun pull(total: Float, width: Float): Float {
        if (width <= 0f) return 0f
        val limit = width * 0.6f
        val x = abs(total).coerceAtMost(width)
        return sign(total) * limit * (1f - (1f - x / width) * (1f - x / width))
    }
}
