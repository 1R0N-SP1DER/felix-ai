package com.felix.faze

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One big tile of a device's page. */
data class DeviceTile(val id: String, val title: String, val text: String, val icon: Ico, val tint: Color)

object DeviceTiles {
    val ALL = listOf(
        DeviceTile("remote", "Remote Desktop", "Control this computer's screen in real time", Ico.SCREEN, Color(0xFF1FB5C9)),
        DeviceTile("overlay", "Controller", "Use this phone as a controller or keyboard pad, without the picture", Ico.GAMEPAD, Color(0xFF9B6BFF)),
        DeviceTile("cam", "Computer camera", "See the cameras connected to this computer", Ico.CAMERA, Color(0xFFFF4D8D)),
        DeviceTile("sound", "Computer sound", "Listen to this computer's sound on this phone", Ico.SPEAKER, Color(0xFF4D7CFF)),
        DeviceTile("mic", "Computer microphone", "Listen to this computer's microphone", Ico.MIC, Color(0xFFFFA630)),
        DeviceTile("show", "Projector", "Show this phone's screen on the computer", Ico.GRID, Color(0xFF5B6DFF)),
        DeviceTile("files", "File explorer", "Browse this computer's files in your phone's Files app", Ico.FOLDER, Color(0xFFE8A020)),
        DeviceTile("send", "Send a file", "Send a file from this phone to the computer", Ico.ARROW_UP, Color(0xFF3DBE6A)),
    )
}

@Composable
private fun TileCard(t: DeviceTile, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier = modifier.height(168.dp).clip(shape)
            .background(Brush.linearGradient(listOf(t.tint.copy(alpha = 0.26f), t.tint.copy(alpha = 0.07f))))
            .border(1.dp, t.tint.copy(alpha = 0.55f), shape).clickable { onClick() }.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(t.tint.copy(alpha = 0.18f)).border(1.dp, t.tint.copy(alpha = 0.7f), CircleShape), contentAlignment = Alignment.Center) { FazeIcon(t.icon, t.tint, 22.dp) }
        Text(t.title, color = Palette.Text, fontSize = 17.sp, fontWeight = FontWeight.Medium)
        Text(t.text, color = Palette.Dim, fontSize = 12.5.sp, lineHeight = 17.sp)
    }
}

/** A connected computer's page: large coloured tiles, one for each thing the phone can do with it. */
@Composable
fun DevicePage(core: FazeCore, deviceId: String, onBack: () -> Unit, onControl: () -> Unit, onOverlay: () -> Unit, onMedia: (String) -> Unit) {
    val d = core.deviceRows().firstOrNull { it.id == deviceId }
    var note by remember { mutableStateOf("") }
    val context = androidx.compose.ui.platform.LocalContext.current
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && d != null) {
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
    fun open(id: String) {
        when (id) {
            "remote" -> onControl()
            "overlay" -> onOverlay()
            "cam", "sound", "mic" -> onMedia(id)
            "show" -> { core.showPhoneOnPc(); note = "Showing this phone on ${d?.name ?: "the computer"}…" }
            "files" -> {
                val root = android.provider.DocumentsContract.buildRootUri(context.packageName + ".pcfiles", deviceId)
                try { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(root, android.provider.DocumentsContract.Root.MIME_TYPE_ITEM).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                catch (e: Exception) { note = "Could not open the Files app: ${e.message ?: ""}" }
            }
            "send" -> picker.launch(arrayOf("*/*"))
        }
    }
    Column(Modifier.fillMaxSize().background(Palette.Bg)) {
        PageHeader(d?.name ?: "Computer", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (d == null || !d.connected) Text("This computer is not connected right now.", color = Palette.Muted, fontSize = 14.sp)
            else {
                Text(DevicePlatform.label(d.os) + (if (d.connection.isNotEmpty()) " · " + d.connection else ""), color = Palette.Dim, fontSize = 13.sp)
                TileGrid { open(it) }
                if (note.isNotEmpty()) Text(note, color = Palette.Dim, fontSize = 12.5.sp)
            }
        }
    }
}

/** The tiles, two to a row. */
@Composable
fun TileGrid(onOpen: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (pair in DeviceTiles.ALL.chunked(2)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (t in pair) TileCard(t, Modifier.weight(1f)) { onOpen(t.id) }
                if (pair.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}
