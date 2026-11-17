package com.felix.faze

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Control the connected PC from the phone, like a remote receiver: its screen on top, and the picture works as a touchpad. Drag to move the
 * mouse, tap to click, double tap to double click, hold to right click. The strip on the right scrolls; the keys below type on the PC.
 */
@Composable
fun PcRemoteScreen(core: FazeCore, onBack: () -> Unit) {
    val pc = core.deviceRows().firstOrNull { it.connected }
    var frame by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var problem by remember { mutableStateOf("") }
    var typed by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val remote = remember { core.pcRemote { problem = it } }

    // The live stream: the computer pushes pictures, the newest is drawn. If it cannot stream, fall back to asking for one picture at a time.
    var streaming by remember { mutableStateOf(false) }
    LaunchedEffect(pc?.id) {
        if (pc == null) return@LaunchedEffect
        core.pcFrames.reset()
        try {
            val engine = withContext(Dispatchers.IO) { remote.startStream() }
            streaming = true
            problem = ""
            note = engine
        } catch (e: Exception) {
            problem = (e.message ?: "could not start the PC screen") + " Showing slower pictures instead."
        }
        var shown = 0L
        var pinged = 0L
        while (true) {
            val now = System.currentTimeMillis()
            if (streaming) {
                if (now - pinged > 3000) { pinged = now; launch(Dispatchers.IO) { remote.pingStream() } }
                val latest = core.pcFrames.latest()
                if (latest != null && latest.first != shown) {
                    shown = latest.first
                    val bmp = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(latest.second, 0, latest.second.size) }
                    if (bmp != null) frame = bmp.asImageBitmap()
                } else {
                    if (core.pcFrames.error.isNotEmpty()) problem = core.pcFrames.error
                    delay(8)
                }
            } else {
                try {
                    val bytes = withContext(Dispatchers.IO) { remote.screen() }
                    val bmp = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                    if (bmp != null) frame = bmp.asImageBitmap()
                } catch (e: Exception) { problem = e.message ?: "could not get the PC screen"; delay(2000) }
                delay(150)
            }
        }
    }
    DisposableEffect(Unit) { onDispose { Thread { remote.stopStream() }.start() } }

    Column(modifier = Modifier.fillMaxSize().background(Palette.Bg)) {
        PageHeader(if (pc != null) "Control ${pc.name}${if (note.isNotEmpty()) " · live ($note)" else ""}" else "Control PC", onBack)
        if (pc == null) {
            Text("No computer is connected. Pair one from Devices.", color = Palette.Dim, modifier = Modifier.padding(16.dp))
            return@Column
        }
        if (problem.isNotEmpty()) Text(problem, color = Palette.Dim, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        Row(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                modifier = Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(Palette.Surface)
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { remote.click() }, onDoubleTap = { remote.click(double = true) }, onLongPress = { remote.click("right") })
                    }
                    .pointerInput(Unit) { detectDragGestures { change, drag -> change.consume(); remote.move(drag.x * 1.8f, drag.y * 1.8f) } }
                    .pointerInput(Unit) { detectDragGesturesAfterLongPress(onDragStart = { remote.button(true) }, onDragEnd = { remote.button(false) }, onDragCancel = { remote.button(false) }) { change, drag -> change.consume(); remote.move(drag.x * 1.8f, drag.y * 1.8f) } },
                contentAlignment = Alignment.Center,
            ) {
                val f = frame
                if (f != null) Image(f, contentDescription = "PC screen", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                else Text("Waiting for the PC…", color = Palette.Dim)
            }
            Box(
                modifier = Modifier.width(34.dp).fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(Palette.Surface2)
                    .pointerInput(Unit) {
                        var acc = 0f
                        detectDragGestures { change, drag ->
                            change.consume()
                            acc += drag.y
                            val lines = (acc / 24f).toInt()
                            if (lines != 0) { acc -= lines * 24f; remote.scroll(-lines) }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) { Text("↕", color = Palette.Dim, fontSize = 18.sp) }
        }
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((label, key) in listOf("Left" to "", "Right" to "", "Enter" to "enter", "⌫" to "backspace", "Esc" to "esc", "Tab" to "tab", "←" to "left", "↑" to "up", "↓" to "down", "→" to "right", "Super" to "super", "Vol−" to "voldown", "Vol+" to "volup", "Play" to "playpause")) {
                Text(label, color = Palette.Text, fontSize = 13.sp, modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Palette.Surface2).clickable {
                    when (label) { "Left" -> remote.click(); "Right" -> remote.click("right"); else -> remote.key(key) }
                }.padding(horizontal = 12.dp, vertical = 9.dp))
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextField(typed, { typed = it }, modifier = Modifier.weight(1f), singleLine = true, placeholder = { Text("Type on the PC") })
            Text("Send", color = Palette.Accent, modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { if (typed.isNotEmpty()) { remote.text(typed); typed = "" } }.padding(horizontal = 12.dp, vertical = 10.dp))
        }
    }
}
