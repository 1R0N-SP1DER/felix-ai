package com.felix.faze

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class ProcRow(val pid: Int, val name: String, val cpu: Double, val mem: Double)
data class PcDeviceRow(val kind: String, val name: String)

/** Saves a picture into Pictures/Faze (no permission needed on Android 10 and later). Returns whether it worked. */
fun saveToGallery(context: Context, bitmap: Bitmap, name: String): Boolean = try {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "$name.jpg")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Faze")
    }
    val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    uri != null && context.contentResolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) } == true
} catch (_: Exception) { false }

private val INK = listOf(Color(0xFFFF5A5A), Color(0xFFFFD166), Color(0xFF6BE675), Color(0xFF5AB8FF), Color.White)

/** Draw on top of the PC's picture with a finger: marks stay until cleared. The picture below is not touched. */
@Composable
fun DrawLayer(onDone: () -> Unit, modifier: Modifier = Modifier, onDraw: (String, String, Float, Float) -> Unit = { _, _, _, _ -> }) {
    val strokes = remember { mutableStateListOf<Pair<Color, List<Offset>>>() }
    var ink by remember { mutableStateOf(INK[0]) }
    var live by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var size by remember { mutableStateOf(androidx.compose.ui.unit.IntSize(1, 1)) }
    fun hex(c: Color) = "#%06X".format(0xFFFFFF and c.toArgb())
    Box(modifier.fillMaxSize().onSizeChanged { size = it }) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(ink) {
                detectDragGestures(
                    onDragStart = { live = listOf(it); onDraw("start", hex(ink), it.x / size.width, it.y / size.height) },
                    onDragEnd = { if (live.size > 1) strokes.add(ink to live); live = emptyList(); onDraw("end", "", 0f, 0f) },
                    onDragCancel = { live = emptyList(); onDraw("end", "", 0f, 0f) },
                ) { change, _ -> change.consume(); live = live + change.position; onDraw("move", "", change.position.x / size.width, change.position.y / size.height) }
            },
        ) {
            fun draw(c: Color, pts: List<Offset>) {
                if (pts.size < 2) return
                val p = Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) } }
                drawPath(p, c, style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round))
            }
            strokes.forEach { draw(it.first, it.second) }
            draw(ink, live)
        }
        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp).clip(RoundedCornerShape(20.dp)).background(Palette.Glass).border(1.dp, Palette.Glow.copy(alpha = 0.5f), RoundedCornerShape(20.dp)).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (c in INK) Box(Modifier.padding(4.dp).size(28.dp).clip(CircleShape).background(c).border(if (c == ink) 3.dp else 1.dp, if (c == ink) Palette.Accent else Palette.Muted, CircleShape).clickable { ink = c })
            Text("Undo", color = Palette.Text, fontSize = 13.sp, modifier = Modifier.padding(4.dp).clip(RoundedCornerShape(10.dp)).background(Palette.Surface2).clickable { if (strokes.isNotEmpty()) { strokes.removeAt(strokes.lastIndex); onDraw("clear", "", 0f, 0f); strokes.forEach { st -> st.second.forEachIndexed { i, o -> onDraw(if (i == 0) "start" else "move", hex(st.first), o.x / size.width, o.y / size.height) }; onDraw("end", "", 0f, 0f) } } }.padding(horizontal = 12.dp, vertical = 8.dp))
            Text("Clear", color = Palette.Text, fontSize = 13.sp, modifier = Modifier.padding(4.dp).clip(RoundedCornerShape(10.dp)).background(Palette.Surface2).clickable { strokes.clear(); onDraw("clear", "", 0f, 0f) }.padding(horizontal = 12.dp, vertical = 8.dp))
            Text("Done", color = Palette.AccentInk, fontSize = 13.sp, modifier = Modifier.padding(4.dp).clip(RoundedCornerShape(10.dp)).background(Palette.Accent).clickable { onDone() }.padding(horizontal = 12.dp, vertical = 8.dp))
        }
    }
}

@Composable
private fun PanelFrame(title: String, error: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)).clickable { onClose() }, contentAlignment = Alignment.Center) {
        Column(
            Modifier.fillMaxHeight(0.82f).width(520.dp).clip(RoundedCornerShape(20.dp)).background(Palette.Surface).border(1.dp, Palette.Glow.copy(alpha = 0.5f), RoundedCornerShape(20.dp)).clickable(enabled = false) {}.padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Palette.Text, fontSize = 17.sp, modifier = Modifier.weight(1f))
                Text("Close", color = Palette.Accent, fontSize = 14.sp, modifier = Modifier.clickable { onClose() }.padding(8.dp))
            }
            if (error.isNotEmpty()) Text(error, color = Palette.Bad, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
            content()
        }
    }
}

/** What is running on the PC, busiest first, with an End button for each. */
@Composable
fun TaskPanel(rows: List<ProcRow>, error: String, onEnd: (Int, Boolean) -> Unit, onClose: () -> Unit) {
    PanelFrame("Task manager", error, onClose) {
        LazyColumn(Modifier.fillMaxSize()) {
            items(rows, key = { it.pid }) { r ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.name, color = Palette.Text, fontSize = 14.sp, maxLines = 1)
                        Text("%.0f%% CPU · %.0f MB".format(r.cpu, r.mem), color = Palette.Dim, fontSize = 11.sp)
                    }
                    Text("End", color = Palette.Accent, fontSize = 13.sp, modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Palette.Surface2).clickable { onEnd(r.pid, false) }.padding(horizontal = 14.dp, vertical = 8.dp))
                    Text("Force", color = Palette.Bad, fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(10.dp)).background(Palette.Surface2).clickable { onEnd(r.pid, true) }.padding(horizontal = 14.dp, vertical = 8.dp))
                }
            }
        }
    }
}

/** What is plugged into the PC, and its batteries. */
@Composable
fun DevicePanel(rows: List<PcDeviceRow>, error: String, onClose: () -> Unit) {
    PanelFrame("Device manager", error, onClose) {
        LazyColumn(Modifier.fillMaxSize()) {
            items(rows) { r ->
                Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(when (r.kind) { "usb" -> "USB"; "battery" -> "Battery"; else -> "Input" }, color = Palette.Dim, fontSize = 11.sp, modifier = Modifier.width(64.dp))
                    Text(r.name, color = Palette.Text, fontSize = 14.sp)
                }
            }
        }
    }
}
