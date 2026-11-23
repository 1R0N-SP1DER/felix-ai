package com.felix.faze

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
class PadState(private val send: (JSONObject) -> Unit, private val key: (String, Boolean) -> Unit = { _, _ -> }) {
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

    private val keysHeld = linkedSetOf<String>()

    fun button(name: String, down: Boolean) {
        if (PadNames.isKey(name)) {
            if (if (down) keysHeld.add(name) else keysHeld.remove(name)) key(PadNames.label(name), down)
            return
        }
        if (!(if (down) held.add(name) else held.remove(name))) return
        send(json())
    }

    fun releaseAll() {
        keysHeld.toList().forEach { key(PadNames.label(it), false) }; keysHeld.clear()
        axes.keys.forEach { axes[it] = 0f }; held.clear(); send(json())
    }

    fun json(): JSONObject = JSONObject().apply {
        axes.forEach { (k, v) -> put(k, v.toDouble()) }
        put("buttons", JSONArray(held.toList()))
    }
}

/** The on-screen controller drawn over the PC picture, built from the layout in use. In edit mode the pieces are dragged, resized, renamed, added and removed instead of pressed. */
@Composable
fun GamepadOverlay(pad: PadState, layouts: PadLayouts, editing: Boolean, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val parts = layouts.active.parts
    var selected by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<PadPart?>(null) }
    var layoutMenu by remember { mutableStateOf(false) }
    val tilt = remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }
    TiltSteering(tilt.filterValues { it }.keys.mapNotNull { id -> parts.firstOrNull { it.id == id } }, pad)
    LaunchedEffect(editing) { if (editing) pad.releaseAll() else selected = null }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        for (p in parts) {
            PartView(p, pad, w, h, editing, selected == p.id, tilt[p.id] == true,
                onSelect = { selected = p.id },
                onMove = { nx, ny, done -> layouts.setParts(layouts.active.parts.map { if (it.id == p.id) it.copy(x = nx.coerceIn(0.02f, 0.98f), y = ny.coerceIn(0.04f, 0.98f)) else it }, done) },
                onTilt = { tilt[p.id] = !(tilt[p.id] ?: false); if (tilt[p.id] != true) pad.axis(if (p.right) "rx" else "lx", 0f) })
        }
        if (editing) {
            val sel = parts.firstOrNull { it.id == selected }
            fun add(p: PadPart) { layouts.setParts(layouts.active.parts + p); selected = p.id }
            fun nid() = "p" + System.nanoTime()
            Column(Modifier.align(Alignment.TopCenter).padding(top = 84.dp).clip(RoundedCornerShape(16.dp)).background(Palette.Glass).border(1.dp, Palette.Glow.copy(alpha = 0.5f), RoundedCornerShape(16.dp)).padding(8.dp)) {
                Row {
                    EditChip("Done") { onDone() }
                    EditChip("+ Button") { add(PadPart(nid(), "button", "New", listOf("A"), .5f, .5f, 52f, "round")) }
                    EditChip("+ Stick") { add(PadPart(nid(), "stick", "", emptyList(), .5f, .5f, 120f, right = parts.any { it.kind == "stick" && !it.right })) }
                    EditChip("+ Tilt") { add(PadPart(nid(), "tilt", "Tilt steer", emptyList(), .5f, .5f, 44f)) }
                }
                Row {
                    Box {
                        EditChip("Layout: " + layouts.current + " ▾") { layoutMenu = true }
                        DropdownMenu(expanded = layoutMenu, onDismissRequest = { layoutMenu = false }) {
                            for (l in layouts.layouts) DropdownMenuItem(text = { Text(l.name) }, onClick = { layoutMenu = false; layouts.select(l.name) })
                            DropdownMenuItem(text = { Text("Reset all to the originals") }, onClick = { layoutMenu = false; layouts.resetAll() })
                        }
                    }
                    EditChip("Copy") { layouts.copy() }
                    EditChip("Delete layout") { layouts.delete() }
                }
                if (sel != null) Row {
                    EditChip("Smaller") { layouts.setParts(layouts.active.parts.map { if (it.id == sel.id) it.copy(size = (it.size * 0.88f).coerceAtLeast(28f)) else it }) }
                    EditChip("Bigger") { layouts.setParts(layouts.active.parts.map { if (it.id == sel.id) it.copy(size = (it.size * 1.14f).coerceAtMost(200f)) else it }) }
                    if (sel.kind != "stick") EditChip("Edit") { dialog = sel }
                    EditChip("Remove") { layouts.setParts(layouts.active.parts.filter { it.id != sel.id }); selected = null }
                }
            }
        }
    }
    dialog?.let { part ->
        var label by remember(part.id) { mutableStateOf(part.label) }
        var binds by remember(part.id) { mutableStateOf(part.binds.joinToString(" + ")) }
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Button") },
            text = {
                Column {
                    OutlinedTextField(label, { label = it }, label = { Text("Name on the button") }, singleLine = true)
                    if (part.kind == "button") OutlinedTextField(binds, { binds = it }, label = { Text("Presses: A, LS + RS … or keys: key:space, key:w") }, singleLine = true)
                }
            },
            confirmButton = { TextButton({ layouts.setParts(layouts.active.parts.map { if (it.id == part.id) it.copy(label = label.take(12), binds = if (part.kind == "button") PadNames.parse(binds) else it.binds) else it }); dialog = null }) { Text("Save") } },
            dismissButton = { TextButton({ dialog = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun EditChip(text: String, onClick: () -> Unit) {
    Text(text, color = Palette.Text, fontSize = 13.sp, modifier = Modifier.padding(3.dp).clip(RoundedCornerShape(10.dp)).background(Palette.Surface2).clickable { onClick() }.padding(horizontal = 12.dp, vertical = 8.dp))
}

/** While a tilt switch is on, the phone's sideways tilt becomes stick movement. */
@Composable
private fun TiltSteering(active: List<PadPart>, pad: PadState) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val key = active.map { it.id + it.right }
    androidx.compose.runtime.DisposableEffect(key) {
        val sm = context.getSystemService(android.content.Context.SENSOR_SERVICE) as? android.hardware.SensorManager
        val sensor = sm?.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)
        val listener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(e: android.hardware.SensorEvent) {
                @Suppress("DEPRECATION") val rot = (context.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.rotation
                val v = Tilt.steer(rot, e.values[0], e.values[1])
                for (p in active) pad.axis(if (p.right) "rx" else "lx", v)
            }
            override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
        }
        if (active.isNotEmpty() && sensor != null) sm.registerListener(listener, sensor, android.hardware.SensorManager.SENSOR_DELAY_GAME)
        onDispose { sm?.unregisterListener(listener) }
    }
}

@Composable
private fun PartView(p: PadPart, pad: PadState, boxW: Float, boxH: Float, editing: Boolean, selected: Boolean, tiltOn: Boolean, onSelect: () -> Unit, onMove: (Float, Float, Boolean) -> Unit, onTilt: () -> Unit) {
    val d = androidx.compose.ui.platform.LocalDensity.current
    val wDp = when { p.kind == "stick" || p.shape == "round" || p.shape == "square" -> p.size; else -> p.size * 1.5f }.dp
    val hDp = when { p.kind == "stick" || p.shape == "round" || p.shape == "square" -> p.size; else -> p.size * 0.8f }.dp
    val wPx = with(d) { wDp.toPx() }
    val hPx = with(d) { hDp.toPx() }
    val shape = if (p.kind == "stick" || p.shape == "round") CircleShape else RoundedCornerShape(14.dp)
    val cur = androidx.compose.runtime.rememberUpdatedState(p)
    var down by remember { mutableStateOf(false) }
    val lit = down || tiltOn
    var tx by remember { mutableStateOf(0f) }
    var ty by remember { mutableStateOf(0f) }
    var m = Modifier.offset { IntOffset((p.x * boxW - wPx / 2).toInt(), (p.y * boxH - hPx / 2).toInt()) }.size(wDp, hDp).clip(shape)
        .background(if (lit) Palette.Accent.copy(alpha = 0.55f) else Palette.Glass)
        .border(if (selected) 2.dp else 1.dp, if (selected || lit) Palette.Accent else Palette.Glow.copy(alpha = 0.45f), shape)
    m = if (editing) {
        m.pointerInput(p.id) {
            detectDragGestures(onDragStart = { onSelect() }, onDragEnd = { onMove(cur.value.x, cur.value.y, true) }) { change, drag ->
                change.consume(); onMove(cur.value.x + drag.x / boxW, cur.value.y + drag.y / boxH, false)
            }
        }.pointerInput(p.id + "tap") { detectTapGestures(onTap = { onSelect() }) }
    } else when (p.kind) {
        "stick" -> m.pointerInput(p.id) {
            val radius = (wPx - with(d) { 52.dp.toPx() }) / 2f
            val left = !cur.value.right
            fun move(dx: Float, dy: Float) {
                val dist = hypot(dx, dy)
                val k = if (dist > radius) radius / dist else 1f
                tx = dx * k; ty = dy * k
                pad.stick(left, tx / radius, ty / radius)
            }
            detectDragGestures(
                onDragStart = { tx = 0f; ty = 0f },
                onDragEnd = { tx = 0f; ty = 0f; pad.stick(left, 0f, 0f) },
                onDragCancel = { tx = 0f; ty = 0f; pad.stick(left, 0f, 0f) },
            ) { change, drag -> change.consume(); move(tx + drag.x, ty + drag.y) }
        }
        "tilt" -> m.pointerInput(p.id) { detectTapGestures(onTap = { onTilt() }) }
        else -> m.pointerInput(p.id) {
            detectTapGestures(onPress = {
                down = true; cur.value.binds.forEach { pad.button(it, true) }
                try { tryAwaitRelease() } finally { down = false; cur.value.binds.forEach { pad.button(it, false) } }
            })
        }
    }
    Box(m, contentAlignment = Alignment.Center) {
        if (p.kind == "stick") Box(Modifier.offset { IntOffset(tx.toInt(), ty.toInt()) }.size(52.dp).clip(CircleShape).background(Palette.Accent.copy(alpha = 0.5f)).border(1.dp, Palette.Accent, CircleShape))
        else Text(p.label.ifEmpty { p.binds.joinToString("+") { PadNames.label(it) } }, color = Palette.Text, fontSize = if (p.size < 50) 12.sp else 14.sp)
    }
}
