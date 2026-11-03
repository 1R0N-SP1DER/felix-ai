package com.felix.faze

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

enum class CoreMode { IDLE, LISTENING, THINKING, SPEAKING }

private const val TAU = (Math.PI * 2).toFloat()

/**
 * The morphing core of the web UI (core/web/core.js), drawn natively: the same silhouettes, the same morphing and breathing, the
 * same halo and orbiting rings while it works. [model] is "auto" (cycles through every form) or a backend id such as "groq".
 */
@Composable
fun CoreView(mode: CoreMode, dp: Dp, modifier: Modifier = Modifier, model: String = "auto") {
    val morph = remember { CoreMorph(Math.random() * 100) }
    val animate = LocalMotion.current
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(mode) {
        morph.setState(
            when (mode) {
                CoreMode.IDLE -> "idle"
                CoreMode.LISTENING -> "listening"
                CoreMode.THINKING -> "thinking"
                CoreMode.SPEAKING -> "responding"
            },
        )
    }
    LaunchedEffect(model) { morph.setModel(model) }
    LaunchedEffect(animate, mode) {
        if (animate) {
            var last = 0L
            while (true) {
                androidx.compose.runtime.withFrameNanos { now ->
                    val dt = if (last == 0L) 0.016 else min(0.05, (now - last) / 1e9)
                    last = now
                    // the web page feeds the mic / voice level in; here a gentle stand-in while listening or speaking
                    val target = when (mode) {
                        CoreMode.LISTENING -> 0.22 + 0.16 * sin(morph.time * 5.0)
                        CoreMode.SPEAKING -> 0.30 + 0.20 * sin(morph.time * 9.0)
                        else -> 0.0
                    }
                    morph.level += (target - morph.level) * 0.18
                    morph.step(dt)
                    frame = now
                }
            }
        }
    }
    Canvas(modifier.size(dp)) {
        val tick = frame // reading it makes the canvas redraw on every frame
        if (tick >= 0) drawCore(morph, Palette.Accent, Palette.Blue, !animate)
    }
}

private fun DrawScope.drawCore(m: CoreMorph, accent: Color, partner: Color, reduced: Boolean) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val px = density
    val r = min(size.width, size.height) * 0.3f
    val working = m.state == "thinking" || m.state == "tool"
    val breathe = if (reduced) 0f else (sin(m.time * 1.1) * 0.035).toFloat()
    val scale = 1f + breathe + m.level.toFloat() * 0.16f + m.pulseV.toFloat()
    val glow = 0.5f + m.level.toFloat() * 0.5f + m.pulseV.toFloat() * 2f + (if (m.state == "thinking") 0.25f else 0f)

    // soft light behind the form
    val haloAlpha = (0.20f * glow).coerceIn(0f, 1f)
    drawRect(
        brush = Brush.radialGradient(
            0f to accent.copy(alpha = haloAlpha), 0.087f to accent.copy(alpha = haloAlpha), 1f to accent.copy(alpha = 0f),
            center = Offset(cx, cy), radius = r * 2.3f,
        ),
    )

    // orbiting rings and sparks while it works
    if (working) {
        for (k in 0 until 2) {
            val rx = r * (1.5f + k * 0.28f)
            val ry = r * (0.62f + k * 0.18f)
            val turn = (m.rot * (if (k == 1) -3 else 3) + k).toFloat()
            rotate(Math.toDegrees(turn.toDouble()).toFloat(), Offset(cx, cy)) {
                drawOval(
                    color = partner.copy(alpha = 0.35f - k * 0.12f), topLeft = Offset(cx - rx, cy - ry), size = Size(rx * 2f, ry * 2f),
                    style = Stroke(width = px),
                )
            }
        }
        for (k in 0 until 7) {
            val a = (m.time * (0.9 + (k % 3) * 0.25) + k * 0.9).toFloat()
            drawCircle(
                color = accent.copy(alpha = 0.75f - (k % 3) * 0.2f),
                radius = max(1f, r * 0.045f),
                center = Offset(cx + cos(a) * r * 1.5f, cy + sin(a * 1.05f) * r * 0.7f),
            )
        }
    }

    // the form itself: a smooth closed curve through the radii
    val path = Path()
    val n = CoreShapes.N
    val xs = FloatArray(n)
    val ys = FloatArray(n)
    for (i in 0 until n) {
        val a = i.toFloat() / n * TAU + m.rot.toFloat()
        xs[i] = cx + cos(a) * r * scale * m.cur[i]
        ys[i] = cy + sin(a) * r * scale * m.cur[i]
    }
    for (i in 0 until n) {
        val j = (i + 1) % n
        val mx = (xs[i] + xs[j]) / 2f
        val my = (ys[i] + ys[j]) / 2f
        if (i == 0) path.moveTo(mx, my) else path.quadraticTo(xs[i], ys[i], mx, my)
    }
    path.quadraticTo(xs[0], ys[0], (xs[0] + xs[1]) / 2f, (ys[0] + ys[1]) / 2f)
    path.close()

    drawPath(
        path,
        brush = Brush.radialGradient(
            0f to accent.copy(alpha = (0.34f + 0.2f * glow).coerceIn(0f, 1f)), 0.7f to partner.copy(alpha = 0.10f), 1f to partner.copy(alpha = 0.03f),
            center = Offset(cx - r * 0.25f, cy - r * 0.3f), radius = r * 1.2f,
        ),
    )
    drawPath(path, color = accent.copy(alpha = (0.12f + 0.12f * glow).coerceIn(0f, 1f)), style = Stroke(width = max(3f, r * 0.07f), join = StrokeJoin.Round))
    drawPath(
        path,
        brush = Brush.linearGradient(
            0f to accent.copy(alpha = 0.95f), 1f to partner.copy(alpha = 0.7f),
            start = Offset(cx - r, cy - r), end = Offset(cx + r, cy + r),
        ),
        style = Stroke(width = max(1f, r * 0.022f), join = StrokeJoin.Round),
    )
}
