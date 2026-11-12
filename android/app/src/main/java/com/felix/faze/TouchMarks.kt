package com.felix.faze

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings as AndroidSettings
import android.view.View
import android.view.WindowManager

/** Shows where Faze touched the screen. */
interface TouchMarker {
    fun tap(x: Int, y: Int)
    fun longPress(x: Int, y: Int)
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int)
}

/** Hands that first show the touch (when the setting is on), then do it. A problem with the markers never stops the touch itself. */
class MarkedHands(private val inner: Hands, private val marker: TouchMarker, private val enabled: () -> Boolean) : Hands {
    private inline fun mark(block: () -> Unit) { if (enabled()) try { block() } catch (e: Exception) { } }

    override fun tap(x: Int, y: Int): String { mark { marker.tap(x, y) }; return inner.tap(x, y) }
    override fun longPress(x: Int, y: Int): String { mark { marker.longPress(x, y) }; return inner.longPress(x, y) }
    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int): String { mark { marker.swipe(x1, y1, x2, y2) }; return inner.swipe(x1, y1, x2, y2) }
    override fun typeText(text: String): String = inner.typeText(text)
    override fun pressKey(key: String): String = inner.pressKey(key)
}

/** What one mark looks like over time: where the dot is and how see-through it is, from 0 (just touched) to 1 (gone). Plain maths, tested. */
object MarkLook {
    const val TAP_MS = 750L
    const val SWIPE_MS = 900L
    const val LONG_MS = 1100L

    /** A tap: a ring that grows and fades around a steady dot. Returns (ring radius factor 0..1, alpha 0..1). */
    fun ring(age: Long, total: Long): Pair<Float, Float> {
        val t = (age.toFloat() / total).coerceIn(0f, 1f)
        return Pair(t, 1f - t)
    }

    /** A swipe: the dot travels from start to end in the first 40% of the time, then everything fades. Returns (progress 0..1, alpha 0..1). */
    fun travel(age: Long, total: Long): Pair<Float, Float> {
        val t = (age.toFloat() / total).coerceIn(0f, 1f)
        val move = (t / 0.4f).coerceIn(0f, 1f)
        val fade = if (t < 0.6f) 1f else 1f - (t - 0.6f) / 0.4f
        return Pair(move, fade.coerceIn(0f, 1f))
    }
}

/**
 * A see-through layer over everything, in the screen's own pixel coordinates (the ones the tap commands use), that cannot be touched
 * itself: it draws a dot where Faze taps and a line and a travelling dot for a swipe, then removes itself a moment later.
 * Needs "Display over other apps"; without it nothing is drawn.
 */
class TouchOverlay(private val context: Context, private val colour: () -> Int) : TouchMarker {
    private val main = Handler(Looper.getMainLooper())
    private val wm = context.getSystemService(WindowManager::class.java)

    private class Mark(val kind: Int, val x1: Float, val y1: Float, val x2: Float, val y2: Float, val born: Long)

    private val marks = ArrayList<Mark>()
    private var view: DrawView? = null
    private val sweep = Runnable { removeIfIdle() }

    private inner class DrawView : View(context) {
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

        override fun onDraw(canvas: Canvas) {
            val now = SystemClock.uptimeMillis()
            val c = colour()
            val d = resources.displayMetrics.density
            marks.removeAll { now - it.born > MarkLook.LONG_MS + 200 }
            for (m in marks) {
                val age = now - m.born
                when (m.kind) {
                    0, 1 -> { // tap, long press
                        val total = if (m.kind == 0) MarkLook.TAP_MS else MarkLook.LONG_MS
                        val (grow, alpha) = MarkLook.ring(age, total)
                        ring.color = c; ring.alpha = (alpha * 255).toInt(); ring.strokeWidth = 3 * d
                        canvas.drawCircle(m.x1, m.y1, (14 + 40 * grow) * d, ring)
                        dot.color = c; dot.alpha = (if (m.kind == 1) 255 * (1f - grow * 0.2f) else alpha * 255).toInt()
                        canvas.drawCircle(m.x1, m.y1, (if (m.kind == 1) 16 else 12) * d, dot)
                    }
                    else -> { // swipe
                        val (move, alpha) = MarkLook.travel(age, MarkLook.SWIPE_MS)
                        val x = m.x1 + (m.x2 - m.x1) * move
                        val y = m.y1 + (m.y2 - m.y1) * move
                        line.color = c; line.alpha = (alpha * 150).toInt(); line.strokeWidth = 6 * d
                        canvas.drawLine(m.x1, m.y1, x, y, line)
                        dot.color = c; dot.alpha = (alpha * 255).toInt()
                        canvas.drawCircle(x, y, 14 * d, dot)
                    }
                }
            }
            if (marks.isNotEmpty()) postInvalidateOnAnimation()
        }
    }

    private fun add(m: Mark) {
        if (!AndroidSettings.canDrawOverlays(context)) return
        main.post {
            if (view == null) {
                val v = DrawView()
                val p = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT,
                )
                p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                try { wm.addView(v, p); view = v } catch (e: Exception) { return@post }
            }
            marks.add(m)
            view?.invalidate()
            main.removeCallbacks(sweep)
            main.postDelayed(sweep, MarkLook.LONG_MS + 600)
        }
    }

    private fun removeIfIdle() {
        val now = SystemClock.uptimeMillis()
        if (marks.none { now - it.born <= MarkLook.LONG_MS + 200 }) {
            marks.clear()
            view?.let { try { wm.removeView(it) } catch (e: Exception) { } }
            view = null
        } else main.postDelayed(sweep, 400)
    }

    override fun tap(x: Int, y: Int) = add(Mark(0, x.toFloat(), y.toFloat(), 0f, 0f, SystemClock.uptimeMillis()))
    override fun longPress(x: Int, y: Int) = add(Mark(1, x.toFloat(), y.toFloat(), 0f, 0f, SystemClock.uptimeMillis()))
    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int) = add(Mark(2, x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), SystemClock.uptimeMillis()))
}
