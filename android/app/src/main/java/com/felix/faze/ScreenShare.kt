package com.felix.faze

import org.json.JSONObject

/**
 * Lets a paired computer see and control this phone's screen, but only while the person has switched that on (Settings > Devices) and never
 * while the phone is locked. It uses what Faze already has: root screenshots (a few pictures a second) and root taps. Points arrive as
 * fractions of the screen (0 to 1) so the computer needs no idea of the phone's pixel size.
 */
class ScreenShare(
    private val capture: () -> ByteArray,
    private val hands: Hands,
    private val size: () -> Pair<Int, Int>,
    private val allowed: () -> Boolean,
    private val locked: () -> Boolean,
    private val log: (String) -> Unit = {},
) {
    companion object {
        val TYPES = setOf("screen_get", "screen_tap", "screen_long", "screen_swipe", "screen_key", "screen_text")
    }

    private var lastLogged = 0L

    fun handle(msg: JSONObject): JSONObject? {
        val t = msg.optString("t")
        if (t !in TYPES) return null
        if (!allowed()) return error("Screen sharing is off on the phone. Turn on Allow screen view and control in Settings > Devices.")
        if (locked()) return error("The phone is locked.")
        return try {
            val now = System.currentTimeMillis()
            if (now - lastLogged > 60_000) { lastLogged = now; log("A computer is viewing or controlling the screen") }
            when (t) {
                "screen_get" -> {
                    val jpeg = capture()
                    if (jpeg.isEmpty()) error("no screenshot came back (is root granted?)")
                    else JSONObject().put("t", "screen_ok").put("jpeg", java.util.Base64.getEncoder().encodeToString(jpeg))
                }
                "screen_tap" -> { val (x, y) = point(msg, "x", "y"); ok(hands.tap(x, y)) }
                "screen_long" -> { val (x, y) = point(msg, "x", "y"); ok(hands.longPress(x, y)) }
                "screen_swipe" -> { val (a, b) = point(msg, "x1", "y1"); val (c, d) = point(msg, "x2", "y2"); ok(hands.swipe(a, b, c, d)) }
                "screen_key" -> ok(hands.pressKey(msg.optString("key")))
                else -> ok(hands.typeText(msg.optString("text")))
            }
        } catch (e: ToolFailure) {
            error(e.message ?: "that did not work")
        } catch (e: IllegalArgumentException) {
            error(e.message ?: "bad request")
        }
    }

    private fun point(m: JSONObject, kx: String, ky: String): Pair<Int, Int> {
        val x = m.optDouble(kx, -1.0)
        val y = m.optDouble(ky, -1.0)
        require(x in 0.0..1.0 && y in 0.0..1.0) { "that point is not on the screen" }
        val (w, h) = size()
        return (x * (w - 1)).toInt() to (y * (h - 1)).toInt()
    }

    private fun ok(note: String) = JSONObject().put("t", "screen_done").put("note", note)

    private fun error(message: String) = JSONObject().put("t", "error").put("message", message)
}
