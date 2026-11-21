package com.felix.faze

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class Ico { MIC, MIC_OFF, CHEVRON, WAVE, ARROW_UP, PLUS, CAMERA, SCREEN, CLOSE, STOP, COPY, SPEAKER, THUMB_UP, THUMB_DOWN, REFRESH }

/** Simple line icons drawn on a 24 x 24 grid (no icon library: the app has none). */
@Composable
fun FazeIcon(kind: Ico, color: Color, size: Dp = 22.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) { drawIco(kind, color) }
}

private fun DrawScope.drawIco(kind: Ico, color: Color) {
    val s = this.size.minDimension / 24f
    val w = 1.9f * s
    val line = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun p(x: Float, y: Float) = Offset(x * s, y * s)
    fun seg(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, p(x1, y1), p(x2, y2), strokeWidth = w, cap = StrokeCap.Round)
    fun poly(vararg pts: Float, close: Boolean = false) {
        val path = Path()
        path.moveTo(pts[0] * s, pts[1] * s)
        var i = 2
        while (i < pts.size) { path.lineTo(pts[i] * s, pts[i + 1] * s); i += 2 }
        if (close) path.close()
        drawPath(path, color, style = line)
    }
    when (kind) {
        Ico.MIC, Ico.MIC_OFF -> {
            drawRoundRect(color, p(9f, 2.5f), Size(6f * s, 11.5f * s), CornerRadius(3f * s), style = line)
            drawArc(color, 0f, 180f, false, p(5.5f, 6.5f), Size(13f * s, 11f * s), style = line)
            seg(12f, 17.5f, 12f, 21f)
            seg(9f, 21f, 15f, 21f)
            if (kind == Ico.MIC_OFF) seg(4f, 4f, 20f, 20f)
        }
        Ico.CHEVRON -> poly(7f, 10f, 12f, 15f, 17f, 10f)
        Ico.WAVE -> {
            seg(4f, 10f, 4f, 14f)
            seg(8f, 6.5f, 8f, 17.5f)
            seg(12f, 3f, 12f, 21f)
            seg(16f, 7.5f, 16f, 16.5f)
            seg(20f, 10.5f, 20f, 13.5f)
        }
        Ico.ARROW_UP -> {
            seg(12f, 19f, 12f, 6f)
            poly(6.5f, 11.5f, 12f, 6f, 17.5f, 11.5f)
        }
        Ico.PLUS -> {
            seg(12f, 5f, 12f, 19f)
            seg(5f, 12f, 19f, 12f)
        }
        Ico.CAMERA -> {
            drawRoundRect(color, p(3.5f, 6.5f), Size(12f * s, 11f * s), CornerRadius(2.5f * s), style = line)
            poly(15.5f, 10.5f, 20.5f, 7.5f, 20.5f, 16.5f, 15.5f, 13.5f, close = true)
        }
        Ico.SCREEN -> {
            drawRoundRect(color, p(3f, 4.5f), Size(18f * s, 13f * s), CornerRadius(2.5f * s), style = line)
            seg(12f, 14f, 12f, 8.5f)
            poly(9.5f, 11f, 12f, 8.5f, 14.5f, 11f)
            seg(8f, 21f, 16f, 21f)
        }
        Ico.CLOSE -> {
            seg(6f, 6f, 18f, 18f)
            seg(18f, 6f, 6f, 18f)
        }
        Ico.COPY -> {
            drawRoundRect(color, p(8.5f, 8.5f), Size(11.5f * s, 11.5f * s), CornerRadius(2.5f * s), style = line)
            poly(15.5f, 8.5f, 15.5f, 5.5f, 13f, 4f, 6f, 4f, 4f, 6f, 4f, 13f, 5.5f, 15f, 8.5f, 15f)
        }
        Ico.SPEAKER -> {
            poly(4f, 9.5f, 8f, 9.5f, 12.5f, 5.5f, 12.5f, 18.5f, 8f, 14.5f, 4f, 14.5f, close = true)
            drawArc(color, -50f, 100f, false, p(9f, 8f), Size(8f * s, 8f * s), style = line)
            drawArc(color, -50f, 100f, false, p(7f, 5f), Size(14f * s, 14f * s), style = line)
        }
        Ico.THUMB_UP, Ico.THUMB_DOWN -> {
            val up = kind == Ico.THUMB_UP
            fun y(v: Float) = if (up) v else 24f - v
            drawRoundRect(color, p(3.5f, minOf(y(11f), y(20.5f))), Size(3.5f * s, 9.5f * s), CornerRadius(1.2f * s), style = line)
            poly(7f, y(11f), 11f, y(3.5f), 13f, y(4.5f), 12f, y(9.5f), 18.5f, y(9.5f), 20.5f, y(11.5f), 19f, y(19f), 17.5f, y(20.5f), 7f, y(20.5f))
        }
        Ico.REFRESH -> {
            drawArc(color, -60f, 290f, false, p(4.5f, 4.5f), Size(15f * s, 15f * s), style = line)
            poly(15.5f, 2.5f, 19.5f, 4.5f, 17.5f, 8.5f)
        }
        Ico.STOP -> drawRoundRect(color, p(6.5f, 6.5f), Size(11f * s, 11f * s), CornerRadius(2.5f * s))
    }
}
