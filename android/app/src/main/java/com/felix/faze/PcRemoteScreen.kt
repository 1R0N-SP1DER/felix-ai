package com.felix.faze

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.IntOffset
import org.json.JSONObject
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/**
 * Control the connected PC from the phone: the PC's live screen fills the whole display in landscape, and everything else floats over it.
 * The picture is a touchpad (drag to move, tap to click, double tap, hold for right click, hold and drag to drag). The top pill collapses; the key
 * bar types on the PC and arms Ctrl, Shift, Alt and Win for shortcuts. The rotate button switches between landscape and portrait.
 */
@Composable
fun PcRemoteScreen(core: FazeCore, onBack: () -> Unit) {
    val pc = core.deviceRows().firstOrNull { it.connected }
    var frame by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var problem by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val remote = remember { core.pcRemote { problem = it } }

    val ctx = LocalContext.current
    val act = remember(ctx) { ctx.findActivity() }
    var landscape by remember { mutableStateOf(true) }
    BackHandler { onBack() }

    // Full screen: no status or navigation bar (a swipe from the edge shows them for a moment), and the orientation is ours while this is open.
    DisposableEffect(act) {
        val before = act?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        val controller = act?.let { WindowCompat.getInsetsController(it.window, it.window.decorView) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            act?.requestedOrientation = before
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    LaunchedEffect(landscape) {
        act?.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    }

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
        val began = System.currentTimeMillis()
        var sawFrame = false
        while (true) {
            val now = System.currentTimeMillis()
            if (streaming && !sawFrame && now - began > 7000) {
                // The stream started but nothing has been drawn: use one picture at a time instead, and say why.
                val why = core.pcFrames.error.ifEmpty { "No picture arrived from the PC (is a share box waiting on its screen?)." }
                launch(Dispatchers.IO) { remote.stopStream() }
                streaming = false
                note = ""
                problem = "$why Showing slower pictures instead."
            }
            if (streaming) {
                if (now - pinged > 3000) { pinged = now; launch(Dispatchers.IO) { remote.pingStream() } }
                val latest = core.pcFrames.latest()
                if (latest != null && latest.first != shown) {
                    shown = latest.first
                    val bmp = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(latest.second, 0, latest.second.size) }
                    if (bmp != null) { frame = bmp.asImageBitmap(); sawFrame = true; problem = "" }
                } else {
                    if (core.pcFrames.error.isNotEmpty() && !sawFrame) problem = core.pcFrames.error
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

    // The PC's webcam as a small picture over the screen, its microphone, and its sound (on by default: the picture and the sound belong together).
    var cam by remember { mutableStateOf(false) }
    var mic by remember { mutableStateOf(false) }
    var sound by remember { mutableStateOf(true) }
    var camFrame by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    val soundPlayer = remember { PcAudio({ core.pcAsk(it, 10_000) }, AndroidSink(), { m -> problem = m; sound = false }) }
    val micPlayer = remember { PcAudio({ core.pcAsk(it, 10_000) }, AndroidSink(), { m -> problem = m; mic = false }) }
    LaunchedEffect(sound, pc != null) { if (sound && pc != null) soundPlayer.start("monitor") else soundPlayer.stop() }
    LaunchedEffect(mic) { if (mic) micPlayer.start("mic") else micPlayer.stop() }
    DisposableEffect(Unit) { onDispose { soundPlayer.stop(); micPlayer.stop() } }
    LaunchedEffect(cam) {
        while (cam) {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    val r = core.pcAsk(JSONObject().put("t", "pc_cam_get"), 20_000)
                    if (r.optString("t") != "pc_cam_ok") throw java.io.IOException(r.optString("message", "no picture from the webcam"))
                    java.util.Base64.getDecoder().decode(r.getString("jpeg"))
                }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { camFrame = it.asImageBitmap() }
            } catch (e: Exception) {
                problem = e.message ?: "could not reach the webcam"; cam = false
            }
            delay(100)
        }
    }

    val ctx = androidx.compose.ui.platform.LocalContext.current
    val layouts = remember {
        val prefs = ctx.getSharedPreferences("faze_pad", android.content.Context.MODE_PRIVATE)
        PadLayouts({ prefs.getString("layouts", null) }, { prefs.edit().putString("layouts", it).apply() })
    }
    RemoteView(
        pc?.name, frame, problem, note, remote, onBack, onRotate = { landscape = !landscape },
        camFrame = camFrame, cam = cam, mic = mic, sound = sound,
        layouts = layouts,
        onToggle = { which -> when (which) { "cam" -> cam = !cam; "mic" -> mic = !mic; "sound" -> sound = !sound } },
    )
}

/**
 * What is drawn over the PC's picture: the touchpad, the scroll strip, the floating pill and the key bar. It holds no connection of its own,
 * so it can be drawn with a made-up picture (the screenshot tests do exactly that).
 */
@Composable
fun RemoteView(
    pcName: String?, frame: androidx.compose.ui.graphics.ImageBitmap?, problem: String, note: String, remote: PcRemote, onBack: () -> Unit, onRotate: () -> Unit,
    startKeysOpen: Boolean = false, startArmed: Set<String> = emptySet(),
    camFrame: androidx.compose.ui.graphics.ImageBitmap? = null, cam: Boolean = false, mic: Boolean = false, sound: Boolean = true, onToggle: (String) -> Unit = {}, startPad: Boolean = false, startEdit: Boolean = false, layouts: PadLayouts = remember { PadLayouts.memory() },
) {
    val pc = pcName
    var typed by remember { mutableStateOf("") }
    var toolsOpen by remember { mutableStateOf(true) }
    var keysOpen by remember { mutableStateOf(startKeysOpen) }
    var speed by remember { mutableStateOf(PointerSpeed.NORMAL) }
    var armed by remember { mutableStateOf(startArmed) }
    var pointerMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var padOn by remember { mutableStateOf(startPad || startEdit) }
    var padEdit by remember { mutableStateOf(startEdit) }
    val padState = remember(remote) { PadState { remote.pad(it) } }
    var camX by remember { mutableStateOf(24f) }
    var camY by remember { mutableStateOf(90f) }

    fun press(key: String) {
        remote.combo(RemoteKeys.combo(armed, key))
        armed = emptySet()
    }

    Box(
        modifier = Modifier.fillMaxSize().background(Palette.Bg).drawBehind {
            drawRect(Brush.radialGradient(listOf(Palette.GlowSoft, Color.Transparent), center = Offset(size.width, 0f), radius = size.maxDimension * 0.7f))
        },
    ) {
        if (pc == null) {
            Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("No computer is connected. Pair one from Devices.", color = Palette.Dim)
                Text("Back", color = Palette.Accent, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable { onBack() }.padding(horizontal = 18.dp, vertical = 10.dp))
            }
            return@Box
        }

        // The PC's screen, which is also the touchpad.
        Box(
            modifier = Modifier.fillMaxSize()
                .pointerInput(speed) {
                    detectTapGestures(onTap = { remote.click() }, onDoubleTap = { remote.click(double = true) }, onLongPress = { remote.click("right") })
                }
                .pointerInput(speed) { detectDragGestures { change, drag -> change.consume(); remote.move(drag.x * speed.factor, drag.y * speed.factor) } }
                .pointerInput(speed) { detectDragGesturesAfterLongPress(onDragStart = { remote.button(true) }, onDragEnd = { remote.button(false) }, onDragCancel = { remote.button(false) }) { change, drag -> change.consume(); remote.move(drag.x * speed.factor, drag.y * speed.factor) } },
            contentAlignment = Alignment.Center,
        ) {
            val f = frame
            if (f != null) Image(f, contentDescription = "PC screen", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            else Text("Waiting for the PC…", color = Palette.Dim)
        }

        // A thin strip on the right edge scrolls.
        Box(
            modifier = Modifier.align(Alignment.CenterEnd).width(26.dp).fillMaxHeight(0.5f).clip(RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp)).background(Palette.Glass)
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
        ) { Text("↕", color = Palette.Dim, fontSize = 15.sp) }

        if (padOn) GamepadOverlay(padState, layouts, padEdit, onDone = { padEdit = false })

        // The PC's webcam: a small square you can drag anywhere. Tap the cross to turn it off.
        if (cam) {
            Box(
                modifier = Modifier.offset { IntOffset(camX.toInt(), camY.toInt()) }.size(width = 168.dp, height = 126.dp).clip(RoundedCornerShape(16.dp))
                    .border(1.dp, Palette.Glow.copy(alpha = 0.5f), RoundedCornerShape(16.dp)).background(Palette.Surface)
                    .pointerInput(Unit) { detectDragGestures { change, drag -> change.consume(); camX += drag.x; camY += drag.y } },
                contentAlignment = Alignment.Center,
            ) {
                if (camFrame != null) Image(camFrame, contentDescription = "PC webcam", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Text("Webcam…", color = Palette.Dim, fontSize = 12.sp)
                Box(modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).clip(RoundedCornerShape(50)).background(Palette.Glass).clickable { onToggle("cam") }.padding(6.dp)) { FazeIcon(Ico.CLOSE, Palette.Text, 14.dp) }
            }
        }

        // The floating pill: close, pointer speed, keyboard, rotate, more. The little arrow under it folds it away.
        Column(modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (toolsOpen) {
                val shape = RoundedCornerShape(22.dp)
                Row(
                    modifier = Modifier.clip(shape).background(Palette.Glass).border(1.dp, Palette.Glow.copy(alpha = 0.35f), shape).padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RemoteTool(Ico.CLOSE, false) { onBack() }
                    Box(Modifier.width(1.dp).height(22.dp).background(Palette.Dim.copy(alpha = 0.25f)))
                    Box {
                        RemoteTool(Ico.POINTER, pointerMenu) { pointerMenu = true }
                        DropdownMenu(expanded = pointerMenu, onDismissRequest = { pointerMenu = false }) {
                            for (s in PointerSpeed.values()) DropdownMenuItem(text = { Text((if (s == speed) "✓  " else "     ") + s.label + " pointer") }, onClick = { speed = s; pointerMenu = false })
                        }
                    }
                    RemoteTool(Ico.KEYBOARD, keysOpen) { keysOpen = !keysOpen }
                    RemoteTool(Ico.ROTATE, false) { onRotate() }
                    Box {
                        RemoteTool(Ico.DOTS, moreMenu) { moreMenu = true }
                        DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                            DropdownMenuItem(text = { Text((if (padOn) "✓  " else "     ") + "Gamepad") }, onClick = { moreMenu = false; padOn = !padOn; padEdit = false; if (!padOn) padState.releaseAll() })
                            if (padOn) {
                                DropdownMenuItem(text = { Text("     Next layout (" + layouts.current + ")") }, onClick = { moreMenu = false; layouts.next() })
                                DropdownMenuItem(text = { Text("     Edit gamepad layout") }, onClick = { moreMenu = false; padEdit = true })
                            }
                            DropdownMenuItem(text = { Text((if (cam) "✓  " else "     ") + "PC webcam") }, onClick = { moreMenu = false; onToggle("cam") })
                            DropdownMenuItem(text = { Text((if (mic) "✓  " else "     ") + "Listen to PC mic") }, onClick = { moreMenu = false; onToggle("mic") })
                            DropdownMenuItem(text = { Text((if (sound) "✓  " else "     ") + "PC sound on this phone") }, onClick = { moreMenu = false; onToggle("sound") })
                            for ((label, key) in listOf("Volume down" to "voldown", "Volume up" to "volup", "Mute" to "mute", "Play / pause" to "playpause", "Right click" to "")) {
                                DropdownMenuItem(text = { Text(label) }, onClick = { moreMenu = false; if (key.isEmpty()) remote.click("right") else remote.key(key) })
                            }
                        }
                    }
                }
            }
            Box(modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { toolsOpen = !toolsOpen }.padding(horizontal = 18.dp, vertical = 4.dp)) {
                FazeIcon(Ico.CHEVRON, Palette.Dim, 18.dp, Modifier.rotate(if (toolsOpen) 180f else 0f))
            }
        }

        if (problem.isNotEmpty() || note.isNotEmpty()) {
            Text(
                if (problem.isNotEmpty()) problem else "Live · $note",
                color = Palette.Dim, fontSize = 11.sp,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = if (keysOpen) 120.dp else 8.dp).clip(RoundedCornerShape(10.dp)).background(Palette.Glass).padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }

        // The key bar. Tap Ctrl, Shift, Alt or Win to arm it, then the next key goes with it; letters for the usual shortcuts appear while one is armed.
        if (keysOpen) {
            Column(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Palette.Glass).padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (armed.isNotEmpty()) {
                    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        KeyChip("Send " + armed.joinToString("+"), true) { remote.combo(RemoteKeys.MODS.filter { it in armed }); armed = emptySet() }
                        for (k in RemoteKeys.QUICK) KeyChip(k.uppercase(), false) { press(k) }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    KeyChip("ESC", false) { press("esc") }
                    KeyChip("Shift", "shift" in armed) { armed = RemoteKeys.toggle(armed, "shift") }
                    KeyChip("Ctrl", "ctrl" in armed) { armed = RemoteKeys.toggle(armed, "ctrl") }
                    KeyChip("Alt", "alt" in armed) { armed = RemoteKeys.toggle(armed, "alt") }
                    KeyChip("Win", "super" in armed) { armed = RemoteKeys.toggle(armed, "super") }
                    KeyChip("Tab", false) { press("tab") }
                    KeyChip("Enter", false) { press("enter") }
                    KeyChip("⌫", false) { press("backspace") }
                    KeyChip("←", false) { press("left") }
                    KeyChip("↑", false) { press("up") }
                    KeyChip("↓", false) { press("down") }
                    KeyChip("→", false) { press("right") }
                    KeyChip("F5", false) { press("f5") }
                }
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextField(
                        typed, { typed = it }, modifier = Modifier.weight(1f), singleLine = true, shape = RoundedCornerShape(12.dp), placeholder = { Text("Type on the PC") },
                        colors = androidx.compose.material3.TextFieldDefaults.colors(
                            focusedContainerColor = Palette.Surface2, unfocusedContainerColor = Palette.Surface2, focusedTextColor = Palette.Text, unfocusedTextColor = Palette.Text,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, cursorColor = Palette.Accent,
                            focusedPlaceholderColor = Palette.Dim, unfocusedPlaceholderColor = Palette.Dim,
                        ),
                    )
                    Text("Send", color = Palette.Accent, modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { if (typed.isNotEmpty()) { remote.text(typed); typed = "" } }.padding(horizontal = 12.dp, vertical = 10.dp))
                }
            }
        }
    }
}

/** One icon of the floating pill. */
@Composable
private fun RemoteTool(ico: Ico, active: Boolean, onClick: () -> Unit) {
    Box(modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable { onClick() }.padding(horizontal = 12.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        FazeIcon(ico, if (active) Palette.Accent else Palette.Text, 21.dp)
    }
}

/** One key of the key bar; lit while armed. */
@Composable
private fun KeyChip(label: String, lit: Boolean, onClick: () -> Unit) {
    Text(
        label, color = if (lit) Palette.AccentInk else Palette.Text, fontSize = 13.sp,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(if (lit) Palette.Accent else Palette.Surface2).clickable { onClick() }.padding(horizontal = 12.dp, vertical = 9.dp),
    )
}
