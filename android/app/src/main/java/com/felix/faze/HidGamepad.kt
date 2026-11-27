package com.felix.faze

import org.json.JSONObject

/** The bytes a Bluetooth game controller sends: one 9 byte report (16 buttons, a hat, four stick axes, two triggers) and the descriptor that tells the other phone how to read it. Pure data, so it is tested without a phone. */
object HidReport {
    const val ID = 1
    const val SIZE = 9

    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    val DESCRIPTOR: ByteArray = b(
        0x05, 0x01, 0x09, 0x05, 0xA1, 0x01,       // Generic Desktop, Game Pad, Application
        0x85, ID,                                   // Report ID
        0x05, 0x09, 0x19, 0x01, 0x29, 0x10,         // Buttons 1..16
        0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x10, 0x81, 0x02,
        0x05, 0x01, 0x09, 0x39,                     // Hat switch
        0x15, 0x01, 0x25, 0x08, 0x35, 0x00, 0x46, 0x3B, 0x01, 0x65, 0x14,
        0x75, 0x04, 0x95, 0x01, 0x81, 0x42,         // 4 bits, null state allowed
        0x65, 0x00, 0x75, 0x04, 0x95, 0x01, 0x81, 0x03, // 4 bits padding
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x32, 0x09, 0x35, // X, Y, Z, Rz
        0x15, 0x00, 0x26, 0xFF, 0x00, 0x75, 0x08, 0x95, 0x04, 0x81, 0x02,
        0x05, 0x02, 0x09, 0xC5, 0x09, 0xC4,         // Brake, Accelerator (the triggers)
        0x15, 0x00, 0x26, 0xFF, 0x00, 0x75, 0x08, 0x95, 0x02, 0x81, 0x02,
        0xC0,
    )

    /** Button number minus one is the bit, in the order Android reads a Game Pad: A, B, (C), X, Y, (Z), LB, RB, LT, RT, Back, Start, Guide, LS, RS. */
    val BITS = mapOf("A" to 0, "B" to 1, "X" to 3, "Y" to 4, "LB" to 6, "RB" to 7, "LT" to 8, "RT" to 9, "BACK" to 10, "START" to 11, "GUIDE" to 12, "LS" to 13, "RS" to 14)

    /** 0 means nothing pressed; 1 is up and the numbers run clockwise to 8, up and left. */
    fun hat(held: Set<String>): Int {
        val v = (if ("UP" in held) -1 else 0) + (if ("DOWN" in held) 1 else 0)
        val h = (if ("LEFT" in held) -1 else 0) + (if ("RIGHT" in held) 1 else 0)
        return when {
            v < 0 && h == 0 -> 1
            v < 0 && h > 0 -> 2
            v == 0 && h > 0 -> 3
            v > 0 && h > 0 -> 4
            v > 0 && h == 0 -> 5
            v > 0 && h < 0 -> 6
            v == 0 && h < 0 -> 7
            v < 0 && h < 0 -> 8
            else -> 0
        }
    }

    fun axis(v: Float): Int = (Math.round(v.coerceIn(-1f, 1f) * 127f) + 128)

    fun build(held: Set<String>, lx: Float, ly: Float, rx: Float, ry: Float): ByteArray {
        var bits = 0
        for (n in held) BITS[n]?.let { bits = bits or (1 shl it) }
        val out = ByteArray(SIZE)
        out[0] = (bits and 0xFF).toByte(); out[1] = (bits shr 8 and 0xFF).toByte()
        out[2] = hat(held).toByte()
        out[3] = axis(lx).toByte(); out[4] = axis(ly).toByte(); out[5] = axis(rx).toByte(); out[6] = axis(ry).toByte()
        out[7] = (if ("LT" in held) 255 else 0).toByte(); out[8] = (if ("RT" in held) 255 else 0).toByte()
        return out
    }

    /** The state the on-screen controller sends (axes lx, ly, rx, ry and a buttons list) as a report. */
    fun fromState(o: JSONObject): ByteArray {
        val a = o.optJSONArray("buttons")
        val held = (0 until (a?.length() ?: 0)).map { a!!.getString(it) }.toSet()
        return build(held, o.optDouble("lx").toFloat().orZero(), o.optDouble("ly").toFloat().orZero(), o.optDouble("rx").toFloat().orZero(), o.optDouble("ry").toFloat().orZero())
    }

    private fun Float.orZero() = if (isNaN()) 0f else this
}

/** Steering by tilting the phone like a wheel: the lean of the screen's left and right edges, turned into a left stick position. Pure maths, so it is tested without a phone. */
object Tilt {
    private const val G = 9.81f

    /**
     * [accel] is the accelerometer (x right, y up the phone, z out of the screen) and [rotation] the display rotation 0..3 (0 upright, 1 turned left, 2 upside down, 3 turned right).
     * Returns -1 (full left) to 1 (full right). A few degrees around level do nothing, and about 35 degrees is full lock.
     */
    fun steer(accel: FloatArray, rotation: Int, invert: Boolean = false, dead: Float = 3f, full: Float = 35f): Float {
        if (accel.size < 2) return 0f
        // the reading along the screen's right direction is negative when the phone leans clockwise (the right edge goes down)
        val along = when (rotation) { 1 -> -accel[1]; 2 -> -accel[0]; 3 -> accel[1]; else -> accel[0] }
        val lean = -along // positive = leaning clockwise = steer right
        val deg = Math.toDegrees(Math.asin((lean / G).coerceIn(-1f, 1f)).toDouble()).toFloat()
        val mag = ((Math.abs(deg) - dead) / (full - dead)).coerceIn(0f, 1f)
        val v = if (deg < 0) -mag else mag
        return if (invert) -v else v
    }
}
