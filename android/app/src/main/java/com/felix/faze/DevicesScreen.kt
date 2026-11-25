package com.felix.faze

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object DevicePlatform {
    /** The big picture for a device: what it runs. */
    fun glyph(os: String): String = when (os.lowercase()) {
        "linux" -> "🐧"
        "windows" -> "🪟"
        "darwin", "macos", "mac" -> "🍎"
        "android" -> "🤖"
        else -> "💻"
    }

    fun label(os: String): String = when (os.lowercase()) {
        "linux" -> "Linux"
        "windows" -> "Windows"
        "darwin", "macos", "mac" -> "macOS"
        "android" -> "Android"
        else -> "Computer"
    }
}

@Composable
fun PageHeader(title: String, onBack: () -> Unit, right: @Composable () -> Unit = {}) {
    Row(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).clickable { onBack() }, contentAlignment = Alignment.Center) {
            Text("←", color = Palette.Dim, fontSize = 18.sp)
        }
        Text(title, color = Palette.Text, fontSize = 20.8.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        right()
    }
}

/** Every device linked to this phone: the platform as the big picture, then the name and how it is connected. */
@Composable
fun DevicesScreen(core: FazeCore, onBack: () -> Unit, onOpen: (String) -> Unit = {}) {
    val rows = core.deviceRows()
    val on = core.settings.linkOn
    Column(modifier = Modifier.fillMaxSize().background(Palette.Bg)) {
        PageHeader("Devices", onBack)
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Overview(core, rows)
            Hint("Screen control and file access are in Settings > Advanced > Devices. This phone is the hub. Any number of computers can pair with it and work with it as one Faze.")
            if (rows.isEmpty()) Text("No devices yet.", color = Palette.Muted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp))
            for (d in rows) DeviceCard(core, d, onOpen) { core.removeDevice(d.id) }
            SettingsCard("Pair another phone") {
                var host by remember { mutableStateOf("") }
                var pcode by remember { mutableStateOf("") }
                Hint("On the other phone open Devices, turn on Allow devices and show a pairing code. Then find it here or type its address, and enter the code. Works without root.")
                for (f in core.foundPhones) if (core.linkRemotes.get(f.id) == null) {
                    Text(f.name + "  " + f.host, color = Palette.Accent, fontSize = 14.sp, modifier = Modifier.clickable { host = f.host + ":" + f.port }.padding(vertical = 8.dp))
                }
                WideButton("Look for phones") { core.findPhones() }
                FieldBox(host, false, Modifier.fillMaxWidth().padding(vertical = 4.dp)) { host = it }
                FieldBox(pcode, false, Modifier.fillMaxWidth().padding(vertical = 4.dp)) { pcode = it }
                Hint("The first box is the address (for example 192.168.1.20), the second the code.")
                WideButton("Pair") { if (host.isNotBlank() && pcode.isNotBlank()) core.pairPhone(host, pcode) }
            }
            SettingsCard("Pair a computer") {
                SettingRow("Allow computers", "Opens a port on this phone so a paired computer can connect. Everything after pairing is encrypted.", last = !on) {
                    FazeSwitch(on, { core.setLink(it) })
                }
                if (on) {
                    if (core.linkCode.isEmpty()) {
                        WideButton("Show a pairing code") { core.startPairing() }
                    } else {
                        Text(
                            core.linkCode.chunked(5).joinToString(" "), color = Palette.Text, fontSize = 30.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        Hint("On the computer, open Devices in Faze, find this phone (or type one of the addresses below) and enter this code. It works for 3 minutes.")
                        for (a in LinkNet.addresses()) Text(a, color = Palette.Dim, fontSize = 13.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(vertical = 2.dp))
                        WideButton("Stop pairing") { core.stopPairing() }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(core: FazeCore, d: DeviceRow, onOpen: (String) -> Unit, onRemove: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    val context = androidx.compose.ui.platform.LocalContext.current
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "file"
            note = "Sending $name…"
            Thread {
                note = try {
                    val saved = context.contentResolver.openInputStream(uri)!!.use { input -> PcSend { m -> core.link.ask(d.id, m, 60_000) }.send(name, input) { sent -> note = "Sending $name… ${sent / 1024} KB" } }
                    "Sent. On the PC: $saved"
                } catch (e: Exception) { "Could not send: ${e.message ?: ""}" }
            }.start()
        }
    }
    var drag by remember { mutableStateOf(0f) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    Column(
        modifier = Modifier.fillMaxWidth()
            .graphicsLayer { translationX = drag }
            .pointerInput(d.id, d.connected) {
                if (!d.connected) return@pointerInput
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = { if (ThrowGesture.fires(total, size.width.toFloat())) { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); core.showPhoneOnPc(); note = "Showing this phone on ${d.name}…" }; drag = 0f },
                    onDragCancel = { drag = 0f },
                ) { _, amount -> total += amount; drag = ThrowGesture.pull(total, size.width.toFloat()) }
            }
            .clip(RoundedCornerShape(20.dp)).background(Palette.Glass).border(1.dp, Palette.Line, RoundedCornerShape(20.dp)).clickable(enabled = d.connected) { onOpen(d.id) }.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(modifier = Modifier.size(84.dp).clip(RoundedCornerShape(24.dp)).background(Palette.Bg).border(1.dp, Palette.Line, RoundedCornerShape(24.dp)), contentAlignment = Alignment.Center) {
            Text(DevicePlatform.glyph(d.os), fontSize = 44.sp)
        }
        Text(d.name, color = Palette.Text, fontSize = 17.sp, fontWeight = FontWeight.Medium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (d.connected) Palette.Ok else Palette.Muted))
            Text(
                (if (d.connected) "Connected" else "Offline") + (if (d.connected && d.connection.isNotEmpty()) " · " + d.connection else "") + " · " + DevicePlatform.label(d.os),
                color = Palette.Dim, fontSize = 13.sp,
            )
        }
        val isPhone = core.linkRemotes.get(d.id) != null
        if (d.connected && !isPhone) Text("Tap to open · drag sideways to show this phone here", color = Palette.Muted, fontSize = 12.sp)
        if (d.connected && isPhone) Text("Tap to see and control this phone", color = Palette.Muted, fontSize = 12.sp)
        if (isPhone && !d.connected) DeviceChip("Connect") { core.connectPhone(d.id) }
        if (note.isNotEmpty()) Text(note, color = Palette.Dim, fontSize = 12.5.sp, modifier = Modifier.padding(top = 6.dp))
        Text(
            if (confirm) "Tap again to remove" else "Remove", color = if (confirm) Palette.Bad else Palette.Muted, fontSize = 13.sp,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { if (confirm) onRemove() else confirm = true }.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun DeviceChip(label: String, onClick: () -> Unit) {
    Text(label, color = Palette.Text, fontSize = 13.sp, modifier = Modifier.clip(RoundedCornerShape(50)).background(Palette.Surface2).border(1.dp, Palette.Line, RoundedCornerShape(50)).clickable { onClick() }.padding(horizontal = 14.dp, vertical = 9.dp))
}

/** What Faze did lately: tools it ran, how it was woken, scheduled tasks. Newest first. Kept on this phone only. */
@Composable
fun ActivityScreen(core: FazeCore, onBack: () -> Unit) {
    var items by remember { mutableStateOf(core.activity.entries()) }
    val fmt = remember { java.text.SimpleDateFormat("d MMM HH:mm:ss", java.util.Locale.getDefault()) }
    Column(modifier = Modifier.fillMaxSize().background(Palette.Bg)) {
        PageHeader("Activity log", onBack) {
            Text("Clear", color = Palette.Dim, fontSize = 14.sp, modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { core.activity.clear(); items = emptyList() }.padding(horizontal = 12.dp, vertical = 6.dp))
        }
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Hint("Newest first. Stays on this phone. Text typed into other apps is never recorded.")
            if (items.isEmpty()) Text("Nothing yet.", color = Palette.Muted, fontSize = 14.sp)
            for (e in items) {
                Column {
                    Text(fmt.format(java.util.Date(e.time)) + "  " + ActivityLog.label(e.kind), color = Palette.Muted, fontSize = 11.5.sp)
                    Text(e.text, color = Palette.Text, fontSize = 13.5.sp)
                }
            }
        }
    }
}


/** One line a device-wide switch on the overview: what this phone shares with the computers linked to it. */
data class Sharing(val label: String, val on: Boolean, val note: String, val set: (Boolean) -> Unit)

/** What to show for "N of M connected". */
fun connectedSummary(connected: Int, total: Int): String = when {
    total == 0 -> "No devices linked yet"
    connected == 0 -> "$total linked, none connected right now"
    connected == total -> if (total == 1) "1 device connected" else "All $total devices connected"
    else -> "$connected of $total devices connected"
}

/** The top of the Devices page: how many are connected, what this phone shares with them (each switch is live), and the last few things that happened. */
@Composable
private fun Overview(core: FazeCore, rows: List<DeviceRow>) {
    var tick by remember { mutableStateOf(0) }
    val items = listOf(
        Sharing("Screen view and control", core.settings.linkScreen, "see and tap this screen (root or accessibility)") { core.settings.linkScreen = it },
        Sharing("Files", core.settings.linkFiles, "browse this phone's public folders") { core.settings.linkFiles = it },
        Sharing("Camera and microphone", core.settings.linkMedia, "use them as a webcam and mic") { core.settings.linkMedia = it },
        Sharing("Receive things", core.settings.linkReceive, "files, text and links into Downloads/Faze") { core.settings.linkReceive = it },
        Sharing("Clipboard", core.settings.linkClip, "copy on one, paste on the other") { core.settings.linkClip = it; core.syncBackground() },
        Sharing("Models", core.settings.linkModels, "copy models between devices") { core.settings.linkModels = it },
    )
    val on = rows.count { it.connected }
    @Suppress("UNUSED_EXPRESSION") tick
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Palette.Glass).border(1.dp, Palette.Line, RoundedCornerShape(22.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(if (on > 0) Palette.Ok else Palette.Muted))
            Text(connectedSummary(on, rows.size), color = Palette.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        Text("This phone is the hub. What it shares with every linked device:", color = Palette.Dim, fontSize = 12.sp)
        for (s in items) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(s.label, color = Palette.Text, fontSize = 14.sp)
                    Text(s.note, color = Palette.Muted, fontSize = 11.sp)
                }
                FazeSwitch(s.on, { s.set(it); tick++ })
            }
        }
        val soundOn = SoundCapture.ready()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Phone sound", color = Palette.Text, fontSize = 14.sp)
                Text(if (soundOn) "allowed: a computer can play what this phone plays" else "not allowed (Settings > Devices > Allow phone sound)", color = Palette.Muted, fontSize = 11.sp)
            }
            Text(if (soundOn) "On" else "Off", color = if (soundOn) Palette.Ok else Palette.Muted, fontSize = 12.sp)
        }
        val recent = core.activity.entries().filter { it.kind == "link" }.take(4)
        if (recent.isNotEmpty()) {
            Text("RECENT", color = Palette.Muted, fontSize = 11.sp, letterSpacing = 1.5.sp, modifier = Modifier.padding(top = 4.dp))
            for (e in recent) Text(e.text, color = Palette.Dim, fontSize = 12.sp, maxLines = 2)
        }
    }
}
