package com.felix.faze

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Another phone's screen, live-ish (a picture after picture, as fast as it can send them) with touch relayed to it. Works whether that phone uses root or the accessibility way. */
@Composable
fun PhoneRemoteScreen(core: FazeCore, deviceId: String, onBack: () -> Unit) {
    val name = core.deviceRows().firstOrNull { it.id == deviceId }?.name ?: "Phone"
    val remote = remember(deviceId) { PhoneRemote { m, ms -> core.linkClient.ask(deviceId, m, ms) } }
    var frame by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var note by remember { mutableStateOf("Waiting for the phone…") }
    var box by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    var typed by remember { mutableStateOf("") }

    fun run(block: () -> Unit) {
        Thread { try { block() } catch (e: Exception) { note = e.message ?: "that did not work" } }.start()
    }

    LaunchedEffect(deviceId) {
        while (isActive) {
            val t0 = System.currentTimeMillis()
            try {
                val jpeg = withContext(Dispatchers.IO) { remote.screen() }
                val bmp = withContext(Dispatchers.Default) { android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) }
                if (bmp != null) { frame = bmp; note = "" }
            } catch (e: Exception) {
                note = e.message ?: "the phone did not answer"
                delay(1500)
            }
            delay(maxOf(0L, 250 - (System.currentTimeMillis() - t0)))
        }
    }

    Column(Modifier.fillMaxSize().background(Palette.Bg)) {
        PageHeader(name, onBack)
        Box(
            Modifier.weight(1f).fillMaxWidth().onSizeChanged { box = it }
                .pointerInput(frame) {
                    detectTapGestures(
                        onTap = { o -> val b = frame; if (b != null) FitMap.fraction(o.x, o.y, box.width.toFloat(), box.height.toFloat(), b.width, b.height)?.let { (x, y) -> run { remote.tap(x, y) } } },
                        onLongPress = { o -> val b = frame; if (b != null) FitMap.fraction(o.x, o.y, box.width.toFloat(), box.height.toFloat(), b.width, b.height)?.let { (x, y) -> run { remote.longPress(x, y) } } },
                    )
                }
                .pointerInput(frame) {
                    var start = Offset.Zero
                    var end = Offset.Zero
                    detectDragGestures(
                        onDragStart = { start = it; end = it },
                        onDrag = { change, _ -> end = change.position },
                        onDragEnd = {
                            val b = frame
                            if (b != null) {
                                val a = FitMap.fraction(start.x, start.y, box.width.toFloat(), box.height.toFloat(), b.width, b.height)
                                val z = FitMap.fraction(end.x, end.y, box.width.toFloat(), box.height.toFloat(), b.width, b.height)
                                if (a != null && z != null) run { remote.swipe(a.first, a.second, z.first, z.second) }
                            }
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            val b = frame
            if (b != null) Image(b.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            if (note.isNotEmpty()) Text(note, color = Palette.Dim, fontSize = 14.sp, modifier = Modifier.padding(24.dp))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((label, key) in listOf("Back" to "back", "Home" to "home", "Recents" to "recents", "Vol −" to "volume_down", "Vol +" to "volume_up")) {
                Text(
                    label, color = Palette.Text, fontSize = 13.sp,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Palette.Surface2).border(1.dp, Palette.Line, RoundedCornerShape(50)).clickable { run { remote.key(key) } }.padding(horizontal = 14.dp, vertical = 9.dp),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FieldBox(typed, false, Modifier.weight(1f)) { typed = it }
            Text(
                "Type", color = Palette.Accent, fontSize = 14.sp,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { val t = typed; if (t.isNotBlank()) { typed = ""; run { remote.text(t) } } }.padding(10.dp),
            )
        }
    }
}
