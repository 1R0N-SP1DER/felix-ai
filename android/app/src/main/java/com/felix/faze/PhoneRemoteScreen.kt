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
    var view by remember { mutableStateOf("screen") } // "screen", "front" or "back" camera
    val micPlayer = remember(deviceId) { PhoneAudio("audio", { m -> core.linkClient.ask(deviceId, m, 15_000) }, AndroidSink(), { e -> note = e }) }
    val soundPlayer = remember(deviceId) { PhoneAudio("sound", { m -> core.linkClient.ask(deviceId, m, 15_000) }, AndroidSink(), { e -> note = e }) }
    var live by remember { mutableStateOf(false) }
    var liveSize by remember { mutableStateOf(720 to 1280) }
    var micOn by remember { mutableStateOf(false) }
    var soundOn by remember { mutableStateOf(false) }
    androidx.compose.runtime.DisposableEffect(deviceId) { onDispose { micPlayer.stop(); soundPlayer.stop(); Thread { remote.stopCamera(); remote.stopVideo() }.start() } }

    fun run(block: () -> Unit) {
        Thread { try { block() } catch (e: Exception) { note = e.message ?: "that did not work" } }.start()
    }

    LaunchedEffect(live) {
        while (isActive && live) {
            withContext(Dispatchers.IO) { remote.pingVideo() }
            val e = core.phoneFrames.error
            if (e.isNotEmpty()) { note = e + " Using pictures."; live = false; Thread { remote.stopVideo() }.start() }
            delay(2500)
        }
    }

    LaunchedEffect(deviceId, view, live) {
        while (isActive && !live) {
            val t0 = System.currentTimeMillis()
            try {
                val jpeg = withContext(Dispatchers.IO) { if (view == "screen") remote.screen() else remote.camera(view) }
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
                .pointerInput(frame, view, live) {
                    if (view != "screen") return@pointerInput
                    detectTapGestures(
                        onTap = { o -> val sz = shownSize(live, liveSize, frame); if (sz != null) FitMap.fraction(o.x, o.y, box.width.toFloat(), box.height.toFloat(), sz.first, sz.second)?.let { (x, y) -> run { remote.tap(x, y) } } },
                        onLongPress = { o -> val sz = shownSize(live, liveSize, frame); if (sz != null) FitMap.fraction(o.x, o.y, box.width.toFloat(), box.height.toFloat(), sz.first, sz.second)?.let { (x, y) -> run { remote.longPress(x, y) } } },
                    )
                }
                .pointerInput(frame, view, live) {
                    if (view != "screen") return@pointerInput
                    var start = Offset.Zero
                    var end = Offset.Zero
                    detectDragGestures(
                        onDragStart = { start = it; end = it },
                        onDrag = { change, _ -> end = change.position },
                        onDragEnd = {
                            val b = shownSize(live, liveSize, frame)
                            if (b != null) {
                                val a = FitMap.fraction(start.x, start.y, box.width.toFloat(), box.height.toFloat(), b.first, b.second)
                                val z = FitMap.fraction(end.x, end.y, box.width.toFloat(), box.height.toFloat(), b.first, b.second)
                                if (a != null && z != null) run { remote.swipe(a.first, a.second, z.first, z.second) }
                            }
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            val b = frame
            if (live) VideoSurface(core.phoneFrames, liveSize.first, liveSize.second, "fit", null) { m -> note = "Live video stopped: $m Using pictures."; live = false }
            else if (b != null) Image(b.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            if (note.isNotEmpty()) Text(note, color = Palette.Dim, fontSize = 14.sp, modifier = Modifier.padding(24.dp))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            fun chip(label: String, on: Boolean, click: () -> Unit) = @Composable {
                Text(
                    label, color = if (on) Palette.Accent else Palette.Text, fontSize = 13.sp,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Palette.Surface2).border(1.dp, if (on) Palette.Accent else Palette.Line, RoundedCornerShape(50)).clickable { click() }.padding(horizontal = 14.dp, vertical = 9.dp),
                )
            }
            chip("Its mic", micOn) { micOn = !micOn; if (micOn) micPlayer.start() else micPlayer.stop() }()
            chip("Its sound", soundOn) { soundOn = !soundOn; if (soundOn) soundPlayer.start() else soundPlayer.stop() }()
            chip(if (live) "Live video: on" else "Live video", live) {
                if (live) { live = false; Thread { remote.stopVideo() }.start() }
                else { view = "screen"; note = "Starting live video…"; core.phoneFrames.reset(); run { liveSize = remote.startVideo(); live = true; note = "" } }
            }()
            chip(when (view) { "front" -> "Front cam"; "back" -> "Back cam"; else -> "Camera" }, view != "screen") { view = when (view) { "screen" -> "front"; "front" -> "back"; else -> "screen" }; if (view == "screen") Thread { remote.stopCamera() }.start() }()
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

/** The size of what is on screen, for mapping a touch back to the other phone: the live video's size or the latest picture's. */
private fun shownSize(live: Boolean, liveSize: Pair<Int, Int>, frame: android.graphics.Bitmap?): Pair<Int, Int>? =
    if (live) liveSize else frame?.let { it.width to it.height }
