package com.felix.faze

import org.json.JSONObject
import java.io.IOException

/**
 * Drives another paired phone's screen, the way PcRemote drives a computer. The other phone is a normal Faze phone, so this speaks the same screen_* messages
 * a computer would: it needs "Allow screen view and control" on that phone, and it works with root or without (the other phone decides how, see ControlMode).
 * Points are fractions of the picture (0 to 1).
 */
class PhoneRemote(private val ask: (JSONObject, Long) -> JSONObject) {
    /** One picture of the other phone's screen as JPEG bytes. Throws IOException with the phone's own words when it refuses. */
    fun screen(): ByteArray {
        val r = ask(JSONObject().put("t", "screen_get"), 20_000)
        if (r.optString("t") != "screen_ok") throw IOException(r.optString("message", "the phone did not send its screen"))
        return java.util.Base64.getDecoder().decode(r.getString("jpeg"))
    }

    private fun act(m: JSONObject): String {
        val r = ask(m, 15_000)
        if (r.optString("t") == "error") throw IOException(r.optString("message", "the phone refused"))
        return r.optString("note")
    }

    fun tap(x: Float, y: Float) = act(JSONObject().put("t", "screen_tap").put("x", x.toDouble()).put("y", y.toDouble()))
    fun longPress(x: Float, y: Float) = act(JSONObject().put("t", "screen_long").put("x", x.toDouble()).put("y", y.toDouble()))
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float) = act(JSONObject().put("t", "screen_swipe").put("x1", x1.toDouble()).put("y1", y1.toDouble()).put("x2", x2.toDouble()).put("y2", y2.toDouble()))
    fun key(name: String) = act(JSONObject().put("t", "screen_key").put("key", name))
    fun text(text: String) = act(JSONObject().put("t", "screen_text").put("text", text.take(300)))
}

/** Where a touch on the shown picture lands on the other phone's screen. The picture is drawn to fit the box, with bars at the sides or top. */
object FitMap {
    /** The point as fractions of the picture, or null when it is on a bar. */
    fun fraction(x: Float, y: Float, boxW: Float, boxH: Float, imgW: Int, imgH: Int): Pair<Float, Float>? {
        if (boxW <= 0f || boxH <= 0f || imgW <= 0 || imgH <= 0) return null
        val scale = minOf(boxW / imgW, boxH / imgH)
        val w = imgW * scale
        val h = imgH * scale
        val left = (boxW - w) / 2f
        val top = (boxH - h) / 2f
        val fx = (x - left) / w
        val fy = (y - top) / h
        return if (fx in 0f..1f && fy in 0f..1f) fx to fy else null
    }
}
