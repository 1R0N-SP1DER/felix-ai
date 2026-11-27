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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.asAndroidBitmap

fun Context.findActivity(): Activity? {
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
fun PcRemoteScreen(core: FazeCore, overlayOnly: Boolean = false, onBack: () -> Unit) {
    val pc = core.deviceRows().firstOrNull { it.connected }
    var frame by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var problem by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val remote = remember { core.pcRemote { problem = it } }
    // What the stream is made of right now; the chip at the bottom left changes these while it plays.
    var quality by remember { mutableStateOf(core.settings.remoteQuality) }
    var fps by remember { mutableStateOf(core.settings.remoteFps) }
    var codec by remember { mutableStateOf(core.settings.remoteCodec) }
    var video by remember { mutableStateOf(false) }   // the hardware decoder is drawing the picture
    val videoGrab = remember { VideoGrab() }
    val longEdge = LocalContext.current.resources.displayMetrics.let { maxOf(it.widthPixels, it.heightPixels) }

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
    LaunchedEffect(pc?.id, quality, fps, codec) {
        if (pc == null || overlayOnly) return@LaunchedEffect
        core.pcFrames.reset()
        video = false
        try {
            val q = RemoteQuality.params(quality, longEdge, fps)
            val engine = withContext(Dispatchers.IO) { remote.startStream(q.width, q.fps, q.quality, codec) }
            streaming = true
            problem = ""
            note = engine
            video = remote.streamCodec == "h264"
            if (codec == "h264" && !video) problem = "This PC does not do fast video yet (update Faze on the PC). Using normal video."
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
            if (streaming && video) {
                // Fast video: the decoder takes the units itself. Only watch that it starts, and fall back to normal video if it does not.
                if (now - pinged > 3000) { pinged = now; launch(Dispatchers.IO) { remote.pingStream() } }
                if (core.pcFrames.seq > 0) sawFrame = true
                if (!sawFrame && (core.pcFrames.error.isNotEmpty() || now - began > 6000)) {
                    val why = core.pcFrames.error.ifEmpty { "No video arrived." }
                    launch(Dispatchers.IO) { remote.stopStream() }
                    problem = "Fast video did not start: $why Using normal video."
                    codec = "jpeg"
                    return@LaunchedEffect
                }
                delay(100)
            } else if (streaming) {
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
    var sound by remember { mutableStateOf(!overlayOnly) }
    var camFrame by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    val soundPlayer = remember { PcAudio({ core.pcAsk(it, 10_000) }, AndroidSink(), { m -> problem = m; sound = false }) }
    val micPlayer = remember { PcAudio({ core.pcAsk(it, 10_000) }, AndroidSink(), { m -> problem = m; mic = false }) }
    LaunchedEffect(sound, pc != null) { try { if (sound && pc != null) soundPlayer.start("monitor") else soundPlayer.stop() } catch (e: Exception) { problem = "PC sound: " + (e.message ?: "failed"); sound = false } }
    LaunchedEffect(mic) { try { if (mic) micPlayer.start("mic") else micPlayer.stop() } catch (e: Exception) { problem = "PC mic: " + (e.message ?: "failed"); mic = false } }
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

    val padCtx = androidx.compose.ui.platform.LocalContext.current
    val layouts = remember {
        val prefs = padCtx.getSharedPreferences("faze_pad", android.content.Context.MODE_PRIVATE)
        PadLayouts({ prefs.getString("layouts", null) }, { prefs.edit().putString("layouts", it).apply() })
    }
    var panel by remember { mutableStateOf("") }
    var procs by remember { mutableStateOf<List<ProcRow>>(emptyList()) }
    var devices by remember { mutableStateOf<List<PcDeviceRow>>(emptyList()) }
    var panelError by remember { mutableStateOf("") }
    LaunchedEffect(panel) {
        while (panel.isNotEmpty()) {
            try {
                val r = withContext(Dispatchers.IO) { core.pcAsk(JSONObject().put("t", if (panel == "tasks") "pc_procs" else "pc_devices"), 15_000) }
                if (r.optString("t") == "error") throw java.io.IOException(r.optString("message"))
                if (panel == "tasks") procs = r.getJSONArray("procs").let { a -> (0 until a.length()).map { a.getJSONObject(it).let { o -> ProcRow(o.getInt("pid"), o.getString("name"), o.optDouble("cpu"), o.optDouble("mem")) } } }
                else devices = r.getJSONArray("devices").let { a -> (0 until a.length()).map { a.getJSONObject(it).let { o -> PcDeviceRow(o.getString("kind"), o.getString("name")) } } }
                panelError = ""
            } catch (e: Exception) { panelError = e.message ?: "the PC did not answer" }
            delay(if (panel == "tasks") 2500 else 8000)
        }
    }
    val scope = rememberCoroutineScope()
    // What the PC is using of this phone right now (its mic, camera or sound as the PC's own).
    var pcUses by remember { mutableStateOf(setOf<String>()) }
    fun useOnPc(what: String) {
        scope.launch {
            try {
                val want = what !in pcUses
                pcUses = withContext(Dispatchers.IO) { remote.usePhone(what, want) }
                if (want && what !in pcUses) {
                    delay(1200)
                    pcUses = withContext(Dispatchers.IO) { remote.usePhone("status", false) }
                    if (what !in pcUses) problem = "The PC could not switch on this phone's " + (if (what == "cam") "camera" else what) + ". Look at the notice on the PC (it may need pactl, pacat or a virtual webcam), and turn on Let computers use my camera and microphone in Faze settings here."
                }
            } catch (e: Exception) { problem = e.message ?: "the PC did not answer" }
        }
    }
    fun act(what: String) {
        when {
            what == "tasks" || what == "devices" -> { panel = what; procs = emptyList(); devices = emptyList(); panelError = "" }
            what == "close" -> panel = ""
            what.startsWith("use:") -> useOnPc(what.removePrefix("use:"))
            what == "shot" && video -> videoGrab.grab { bmp ->
                android.widget.Toast.makeText(padCtx, if (bmp != null && saveToGallery(padCtx, bmp, "faze-pc-" + System.currentTimeMillis())) "Saved to Pictures/Faze" else "No picture to save yet", android.widget.Toast.LENGTH_SHORT).show()
            }
            what == "shot" -> {
                val f = frame
                android.widget.Toast.makeText(padCtx, if (f != null && saveToGallery(padCtx, f.asAndroidBitmap(), "faze-pc-" + System.currentTimeMillis())) "Saved to Pictures/Faze" else "No picture to save yet", android.widget.Toast.LENGTH_SHORT).show()
            }
            what == "photo" -> {
                val f = camFrame
                android.widget.Toast.makeText(padCtx, if (f != null && saveToGallery(padCtx, f.asAndroidBitmap(), "faze-cam-" + System.currentTimeMillis())) "Photo saved to Pictures/Faze" else "Turn on the remote camera first", android.widget.Toast.LENGTH_SHORT).show()
            }
            what.startsWith("end:") || what.startsWith("force:") -> scope.launch {
                val pid = what.substringAfter(":").toIntOrNull() ?: return@launch
                try {
                    val r = withContext(Dispatchers.IO) { core.pcAsk(JSONObject().put("t", "pc_kill").put("pid", pid).put("force", what.startsWith("force:")), 10_000) }
                    panelError = if (r.optString("t") == "error") r.optString("message") else ""
                } catch (e: Exception) { panelError = e.message ?: "the PC did not answer" }
            }
        }
    }
    RemoteView(
        pc?.name, frame, problem, note, remote, onBack, onRotate = { landscape = !landscape },
        panel = panel, procs = procs, devices = devices, panelError = panelError, onAction = { act(it) },
        camFrame = camFrame, cam = cam, mic = mic, sound = sound,
        layouts = layouts,
        shape = core.settings.remoteShape, overlayOnly = overlayOnly,
        video = if (video) ({ val g = RemoteQuality.params(quality, longEdge, fps); VideoSurface(core.pcFrames, g.width, g.width * 9 / 16, core.settings.remoteShape, videoGrab) { m -> problem = "Fast video stopped: $m Using normal video."; codec = "jpeg" } }) else null,
        streamCodec = codec, streamQuality = quality, streamFps = fps,
        pcUses = pcUses,
        onStream = { c, qual, f ->
            codec = c; quality = qual; fps = f
            core.settings.remoteCodec = c; core.settings.remoteQuality = qual; core.settings.remoteFps = f
        },
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
    panel: String = "", procs: List<ProcRow> = emptyList(), devices: List<PcDeviceRow> = emptyList(), panelError: String = "", onAction: (String) -> Unit = {}, startDrawing: Boolean = false, shape: String = "stretch", overlayOnly: Boolean = false,
    video: (@Composable () -> Unit)? = null, streamCodec: String = "jpeg", streamQuality: String = "720", streamFps: Int = 30, onStream: (String, String, Int) -> Unit = { _, _, _ -> }, startStreamPanel: Boolean = false, pcUses: Set<String> = emptySet(),
) {
    var streamPanel by remember { mutableStateOf(startStreamPanel) }
    val pc = pcName
    var typed by remember { mutableStateOf("") }
    var toolsOpen by remember { mutableStateOf(true) }
    var keysOpen by remember { mutableStateOf(startKeysOpen) }
    var speed by remember { mutableStateOf(PointerSpeed.NORMAL) }
    var armed by remember { mutableStateOf(startArmed) }
    var pointerMenu by remember { mutableStateOf(false) }
    var touchOn by remember { mutableStateOf(!overlayOnly) }
    val touch = androidx.compose.runtime.rememberUpdatedState(touchOn)
    var moreMenu by remember { mutableStateOf(false) }
    var drawing by remember { mutableStateOf(startDrawing) }
    var padOn by remember { mutableStateOf(startPad || startEdit || overlayOnly) }
    var padEdit by remember { mutableStateOf(startEdit) }
    val padState = remember(remote) { PadState({ k, down -> if (down) remote.keyDown(k) else remote.keyUp(k) }, { remote.pad(it) }) }
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
                    detectTapGestures(onTap = { if (touch.value) remote.click() }, onDoubleTap = { if (touch.value) remote.click(double = true) }, onLongPress = { if (touch.value) remote.click("right") })
                }
                .pointerInput(speed) { detectDragGestures { change, drag -> change.consume(); if (touch.value) remote.move(drag.x * speed.factor, drag.y * speed.factor) } }
                .pointerInput(speed) { detectDragGesturesAfterLongPress(onDragStart = { if (touch.value) remote.button(true) }, onDragEnd = { if (touch.value) remote.button(false) }, onDragCancel = { if (touch.value) remote.button(false) }) { change, drag -> change.consume(); if (touch.value) remote.move(drag.x * speed.factor, drag.y * speed.factor) } },
            contentAlignment = Alignment.Center,
        ) {
            val f = frame
            if (overlayOnly) Text("Controller only. The picture is off.", color = Palette.Muted, fontSize = 13.sp)
            else if (video != null) video()
            else if (f != null) Image(f, contentDescription = "PC screen", contentScale = when (shape) { "fit" -> ContentScale.Fit; "fill" -> ContentScale.Crop; else -> ContentScale.FillBounds }, modifier = Modifier.fillMaxSize())
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

        if (drawing) DrawLayer(onDone = { drawing = false; remote.draw("clear") }, onDraw = { op, color, x, y -> remote.draw(op, color, x, y) })
        if (panel == "tasks") TaskPanel(procs, panelError, { pid, force -> onAction((if (force) "force:" else "end:") + pid) }, { onAction("close") })
        if (panel == "devices") DevicePanel(devices, panelError, { onAction("close") })

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
                        RemoteTool(Ico.POINTER, touchOn) { pointerMenu = true }
                        DropdownMenu(expanded = pointerMenu, onDismissRequest = { pointerMenu = false }) {
                            DropdownMenuItem(text = { Text(if (touchOn) "Phone touch moves the mouse: on" else "Phone touch moves the mouse: off") }, onClick = { touchOn = !touchOn; pointerMenu = false })
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
                            DropdownMenuItem(text = { Text((if (drawing) "✓  " else "     ") + "Drawing") }, onClick = { moreMenu = false; drawing = !drawing })
                            DropdownMenuItem(text = { Text("     Screen shot") }, onClick = { moreMenu = false; onAction("shot") })
                            DropdownMenuItem(text = { Text((if (cam) "✓  " else "     ") + "Remote camera (PC webcam)") }, onClick = { moreMenu = false; onToggle("cam") })
                            DropdownMenuItem(text = { Text("     Take photo (from the webcam)") }, onClick = { moreMenu = false; onAction("photo") })
                            DropdownMenuItem(text = { Text((if (mic) "✓  " else "     ") + "Remote microphone (listen)") }, onClick = { moreMenu = false; onToggle("mic") })
                            DropdownMenuItem(text = { Text((if ("mic" in pcUses) "✓  " else "     ") + "Local microphone (this phone is the PC's mic)") }, onClick = { moreMenu = false; onAction("use:mic") })
                            DropdownMenuItem(text = { Text((if ("cam" in pcUses) "✓  " else "     ") + "Local camera (this phone is the PC's webcam)") }, onClick = { moreMenu = false; onAction("use:cam") })
                            DropdownMenuItem(text = { Text((if ("sound" in pcUses) "✓  " else "     ") + "Local sound (this phone plays through the PC)") }, onClick = { moreMenu = false; onAction("use:sound") })
                            DropdownMenuItem(text = { Text("     Task manager") }, onClick = { moreMenu = false; onAction("tasks") })
                            DropdownMenuItem(text = { Text("     Device manager") }, onClick = { moreMenu = false; onAction("devices") })
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
            Column(modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = if (keysOpen) 120.dp else 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (streamPanel && !overlayOnly) StreamPanel(streamCodec, streamQuality, streamFps, onStream)
                Row(
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Palette.Glass).clickable(enabled = !overlayOnly) { streamPanel = !streamPanel }.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(if (problem.isNotEmpty()) problem else "Live · $note" + (if (streamCodec == "h264") " · H.264" else ""), color = Palette.Dim, fontSize = 11.sp)
                    if (!overlayOnly) FazeIcon(Ico.CHEVRON, Palette.Accent, 14.dp, Modifier.rotate(if (streamPanel) 0f else 180f))
                }
            }
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
        FazeIcon(ico, if (active) Palette.Accent else (if (ico == Ico.POINTER) Palette.Muted else Palette.Text), 21.dp)
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

/** The little panel over the Live chip: the faster codec on or off, the resolution and the frame rate. Every change applies at once (the stream restarts with the new settings). */
@Composable
fun StreamPanel(codec: String, quality: String, fps: Int, onChange: (String, String, Int) -> Unit) {
    Column(
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(Palette.Glass).padding(12.dp).width(260.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Faster video (H.264)", color = Palette.Text, fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                Text("Hardware decoded, least delay. Needs the PC to encode it.", color = Palette.Muted, fontSize = 10.sp)
            }
            FazeSwitch(codec == "h264", { onChange(if (it) "h264" else "jpeg", quality, fps) })
        }
        StreamPick("Resolution", RemoteQuality.CHOICES.firstOrNull { it.first == quality }?.second ?: quality, RemoteQuality.CHOICES.map { it.second }) { label ->
            onChange(codec, RemoteQuality.CHOICES.first { it.second == label }.first, fps)
        }
        StreamPick("Frame rate", "$fps fps", RemoteQuality.FPS_CHOICES.map { "$it fps" }) { label -> onChange(codec, quality, label.substringBefore(" ").toInt()) }
    }
}

@Composable
private fun StreamPick(label: String, value: String, options: List<String>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Palette.Dim, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Box {
            Row(
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Palette.Surface2).clickable { open = true }.padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(value, color = Palette.Text, fontSize = 12.sp)
                FazeIcon(Ico.CHEVRON, Palette.Dim, 14.dp)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for (o in options) DropdownMenuItem(text = { Text((if (o == value) "✓  " else "     ") + o) }, onClick = { open = false; onPick(o) })
            }
        }
    }
}
