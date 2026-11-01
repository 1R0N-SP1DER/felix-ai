package com.felix.faze

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class CoreMode { IDLE, LISTENING, THINKING, SPEAKING }

/** The morphing core from the web UI, redrawn natively: rings turn, the middle breathes, faster when it is working. */
@Composable
fun CoreView(mode: CoreMode, dp: Dp, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "core")
    val spin by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(14000, easing = LinearEasing)), label = "spin")
    val beat by transition.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(if (mode == CoreMode.THINKING) 650 else 1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "beat",
    )
    val energy = when (mode) {
        CoreMode.IDLE -> 0.25f
        CoreMode.LISTENING -> 0.9f
        CoreMode.THINKING -> 0.7f
        CoreMode.SPEAKING -> 1f
    }
    Canvas(modifier.size(dp)) {
        val c = center
        val r = this.size.minDimension / 2f
        val hair = 1.dp.toPx()
        drawCircle(Palette.Accent.copy(alpha = 0.12f), radius = r - hair, center = c, style = Stroke(hair))
        drawCircle(Palette.Accent.copy(alpha = 0.22f), radius = r * 0.80f, center = c, style = Stroke(hair))
        rotate(spin, c) {
            drawCircle(
                Palette.Blue.copy(alpha = 0.5f), radius = r * 0.6f, center = c,
                style = Stroke(hair * 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(hair * 4, hair * 9))),
            )
        }
        if (mode == CoreMode.THINKING) {
            rotate(spin * 4f, c) {
                drawArc(
                    Palette.Accent, startAngle = 0f, sweepAngle = 90f, useCenter = false,
                    topLeft = Offset(c.x - r * 0.8f, c.y - r * 0.8f), size = Size(r * 1.6f, r * 1.6f),
                    style = Stroke(hair * 2f, cap = StrokeCap.Round),
                )
            }
        }
        val body = r * (0.36f + 0.05f * beat * energy)
        drawCircle(Palette.Surface, radius = body, center = c)
        drawCircle(Palette.Accent.copy(alpha = 0.8f), radius = body, center = c, style = Stroke(hair * 1.5f))
        drawCircle(Palette.Accent, radius = r * (0.14f + 0.06f * beat * energy), center = c)
    }
}
