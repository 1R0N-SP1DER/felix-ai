package com.felix.faze

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.hypot

/** The whole controller as one small state: four stick directions, two triggers' worth of buttons, and which buttons are held. Every change sends the full state, so a lost message heals on the next one. */
class PadState(private val send: (JSONObject) -> Unit) {
    private val axes = linkedMapOf("lx" to 0f, "ly" to 0f, "rx" to 0f, "ry" to 0f)
    private val held = linkedSetOf<String>()

    fun axis(name: String, v: Float) {
        require(name in axes) { "unknown axis $name" }
        val c = v.coerceIn(-1f, 1f)
        if (axes[name] == c) return
        axes[name] = c; send(json())
    }

    fun stick(left: Boolean, x: Float, y: Float) {
        val p = if (left) "l" else "r"
        axes[p + "x"] = x.coerceIn(-1f, 1f); axes[p + "y"] = y.coerceIn(-1f, 1f); send(json())
    }

    fun button(name: String, down: Boolean) {
        if (!(if (down) held.add(name) else held.remove(name))) return
        send(json())
    }

    fun releaseAll() {
        axes.keys.forEach { axes[it] = 0f }; held.clear(); send(json())
    }

    fun json(): JSONObject = JSONObject().apply {
        axes.forEach { (k, v) -> put(k, v.toDouble()) }
        put("buttons", JSONArray(held.toList()))
    }
}

/** The on-screen controller drawn over the PC picture: sticks, d-pad, A B X Y, bumpers and triggers, Back / Guide / Start. */
@Composable
fun GamepadOverlay(pad: PadState, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize()) {
        Cluster(Alignment.TopStart, 28.dp, 8.dp) { PadButton("LT", pad, 64.dp, 38.dp); PadButton("LB", pad, 64.dp, 38.dp, x = 74.dp) }
        Cluster(Alignment.TopEnd, 28.dp, 8.dp) { PadButton("RT", pad, 64.dp, 38.dp); PadButton("RB", pad, 64.dp, 38.dp, x = (-74).dp) }
        Stick(pad, true, Modifier.align(Alignment.CenterStart).padding(start = 36.dp, bottom = 70.dp))
        Cluster(Alignment.BottomStart, 190.dp, 16.dp) {
            PadButton("UP", pad, 44.dp, 44.dp, label = "▲", x = 48.dp); PadButton("LEFT", pad, 44.dp, 44.dp, label = "◀", y = 48.dp)
            PadButton("RIGHT", pad, 44.dp, 44.dp, label = "▶", x = 96.dp, y = 48.dp); PadButton("DOWN", pad, 44.dp, 44.dp, label = "▼", x = 48.dp, y = 96.dp)
        }
        Cluster(Alignment.CenterEnd, 80.dp, 0.dp) {
            PadButton("Y", pad, 52.dp, 52.dp, x = 58.dp, round = true); PadButton("X", pad, 52.dp, 52.dp, y = 58.dp, round = true)
            PadButton("B", pad, 52.dp, 52.dp, x = 116.dp, y = 58.dp, round = true); PadButton("A", pad, 52.dp, 52.dp, x = 58.dp, y = 116.dp, round = true)
        }
        Stick(pad, false, Modifier.align(Alignment.BottomEnd).padding(end = 190.dp, bottom = 16.dp))
        Row3(Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp), pad)
    }
}

@Composable
private fun BoxScope.Cluster(at: Alignment, edge: androidx.compose.ui.unit.Dp, top: androidx.compose.ui.unit.Dp, content: @Composable BoxScope.() -> Unit) {
    val p = if (at == Alignment.BottomStart) Modifier.padding(start = 40.dp, bottom = 20.dp) else if (at == Alignment.CenterEnd) Modifier.padding(end = edge) else Modifier.padding(horizontal = edge, vertical = top)
    Box(Modifier.align(at).then(p).size(width = if (at == Alignment.BottomStart || at == Alignment.CenterEnd) 150.dp else 150.dp, height = if (at == Alignment.BottomStart || at == Alignment.CenterEnd) 150.dp else 46.dp), content = content)
}

@Composable
private fun Row3(modifier: Modifier, pad: PadState) {
    Box(modifier.size(width = 230.dp, height = 36.dp)) {
        PadButton("BACK", pad, 66.dp, 34.dp, label = "Back")
        PadButton("GUIDE", pad, 66.dp, 34.dp, x = 82.dp, label = "Guide")
        PadButton("START", pad, 66.dp, 34.dp, x = 164.dp, label = "Start")
    }
}

@Composable
private fun PadButton(name: String, pad: PadState, w: androidx.compose.ui.unit.Dp, h: androidx.compose.ui.unit.Dp, x: androidx.compose.ui.unit.Dp = 0.dp, y: androidx.compose.ui.unit.Dp = 0.dp, round: Boolean = false, label: String = name) {
    var down by remember { mutableStateOf(false) }
    val shape = if (round) CircleShape else RoundedCornerShape(14.dp)
    Box(
        Modifier.offset(x, y).size(w, h).clip(shape)
            .background(if (down) Palette.Accent.copy(alpha = 0.55f) else Palette.Glass)
            .border(1.dp, if (down) Palette.Accent else Palette.Glow.copy(alpha = 0.45f), shape)
            .pointerInput(name) {
                detectTapGestures(onPress = { down = true; pad.button(name, true); try { tryAwaitRelease() } finally { down = false; pad.button(name, false) } })
            },
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Palette.Text, fontSize = 13.sp) }
}

@Composable
private fun Stick(pad: PadState, left: Boolean, modifier: Modifier) {
    val base = 120.dp
    val thumb = 52.dp
    var tx by remember { mutableStateOf(0f) }
    var ty by remember { mutableStateOf(0f) }
    Box(
        modifier.size(base).clip(CircleShape).background(Palette.Glass).border(1.dp, Palette.Glow.copy(alpha = 0.45f), CircleShape)
            .pointerInput(left) {
                val radius = (base.toPx() - thumb.toPx()) / 2f
                fun move(dx: Float, dy: Float) {
                    val d = hypot(dx, dy)
                    val k = if (d > radius) radius / d else 1f
                    tx = dx * k; ty = dy * k
                    pad.stick(left, tx / radius, ty / radius)
                }
                detectDragGestures(
                    onDragStart = { tx = 0f; ty = 0f },
                    onDragEnd = { tx = 0f; ty = 0f; pad.stick(left, 0f, 0f) },
                    onDragCancel = { tx = 0f; ty = 0f; pad.stick(left, 0f, 0f) },
                ) { change, drag -> change.consume(); move(tx + drag.x, ty + drag.y) }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.offset { IntOffset(tx.toInt(), ty.toInt()) }.size(thumb).clip(CircleShape).background(Palette.Accent.copy(alpha = 0.5f)).border(1.dp, Palette.Accent, CircleShape))
    }
}
