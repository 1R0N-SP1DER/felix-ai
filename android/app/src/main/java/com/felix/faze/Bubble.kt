package com.felix.faze

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.graphics.toArgb
import kotlin.math.abs

/** The small floating circle shown while a conversation carries on outside the app: it pulses with the voice, you can drag it, and a tap brings the app back. */
class BubbleWindow(private val context: Context) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: BubbleView? = null
    private var params: WindowManager.LayoutParams? = null
    private var lastX = 24
    private var lastY = 420

    fun canShow(): Boolean = android.provider.Settings.canDrawOverlays(context)

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (view != null || !canShow()) return
        val size = (SIZE_DP * context.resources.displayMetrics.density).toInt()
        val p = WindowManager.LayoutParams(
            size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT,
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.x = lastX
        p.y = lastY
        val v = BubbleView(context)
        v.setOnTouchListener(Drag())
        try {
            wm.addView(v, p)
            view = v
            params = p
        } catch (e: Exception) {
            view = null
            params = null
        }
    }

    fun hide() {
        view?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        view = null
        params = null
    }

    fun update(level: Float, speaking: Boolean, muted: Boolean) {
        view?.set(level, speaking, muted)
    }

    private fun openApp() {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    private inner class Drag : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var moved = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val p = params ?: return false
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = p.x; startY = p.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && (abs(dx) > 12 || abs(dy) > 12)) moved = true
                    if (moved) {
                        p.x = startX + dx.toInt()
                        p.y = startY + dy.toInt()
                        lastX = p.x
                        lastY = p.y
                        try { wm.updateViewLayout(v, p) } catch (_: Exception) {}
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) openApp()
            }
            return true
        }
    }

    companion object {
        const val SIZE_DP = 60
    }
}

private class BubbleView(context: Context) : View(context) {
    private val d = context.resources.displayMetrics.density
    private var level = 0f
    private var speaking = false
    private var muted = false
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f * d }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeWidth = 4f * d }

    fun set(l: Float, s: Boolean, m: Boolean) {
        level = l
        speaking = s
        muted = m
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val r = width / 2f
        fill.color = Palette.Surface.toArgb()
        canvas.drawCircle(r, r, r - 2f * d, fill)
        ring.color = (if (muted) Palette.Warn else Palette.Accent).toArgb()
        ring.alpha = if (speaking) 255 else 170
        canvas.drawCircle(r, r, r - 4f * d - level * 3f * d, ring)
        bar.color = (if (muted) Palette.Warn else Palette.Text).toArgb()
        val heights = LiveBars.heights(if (muted) 0f else level, (System.currentTimeMillis() % 1000) / 1000f, 5)
        val gap = 8f * d
        for (i in heights.indices) {
            val x = r + (i - 2) * gap
            val h = heights[i] * 0.5f * width
            canvas.drawLine(x, r - h / 2f, x, r + h / 2f, bar)
        }
    }
}
