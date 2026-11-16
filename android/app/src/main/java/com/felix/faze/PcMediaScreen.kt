package com.felix.faze

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** The computer's sound, its microphone and its webcam, shown or played on this phone. [first] is what was tapped: "sound", "mic" or "cam". */
@Composable
fun PcMediaScreen(core: FazeCore, first: String, onBack: () -> Unit) {
    var sound by remember { mutableStateOf(first == "sound") }
    var mic by remember { mutableStateOf(first == "mic") }
    var cam by remember { mutableStateOf(first == "cam") }
    var problem by remember { mutableStateOf("") }
    var frame by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    val soundPlayer = remember { PcAudio({ core.pcAsk(it, 10_000) }, AndroidSink(), { m -> problem = m; sound = false }) }
    val micPlayer = remember { PcAudio({ core.pcAsk(it, 10_000) }, AndroidSink(), { m -> problem = m; mic = false }) }

    LaunchedEffect(sound) { if (sound) { problem = ""; soundPlayer.start("monitor") } else soundPlayer.stop() }
    LaunchedEffect(mic) { if (mic) { problem = ""; micPlayer.start("mic") } else micPlayer.stop() }
    DisposableEffect(Unit) { onDispose { soundPlayer.stop(); micPlayer.stop() } }
    LaunchedEffect(cam) {
        while (cam) {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    val r = core.pcAsk(JSONObject().put("t", "pc_cam_get"), 20_000)
                    if (r.optString("t") != "pc_cam_ok") throw java.io.IOException(r.optString("message", "no picture from the webcam"))
                    java.util.Base64.getDecoder().decode(r.getString("jpeg"))
                }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { frame = it.asImageBitmap(); problem = "" }
            } catch (e: Exception) {
                problem = e.message ?: "could not reach the webcam"; cam = false
            }
            delay(100)
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Palette.Bg)) {
        PageHeader("PC sound, mic and webcam", onBack)
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MediaToggle("PC sound", sound) { sound = it }
            MediaToggle("PC mic", mic) { mic = it }
            MediaToggle("Webcam", cam) { cam = it }
        }
        if (problem.isNotEmpty()) Text(problem, color = Palette.Dim, fontSize = 12.5.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(16.dp)).background(Palette.Surface), contentAlignment = Alignment.Center) {
            val f = frame
            if (cam && f != null) Image(f, contentDescription = "PC webcam", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            else Text(if (cam) "Waiting for the webcam…" else if (sound || mic) "Playing on this phone." else "Turn something on.", color = Palette.Dim)
        }
        Text("The PC keeps playing its own sound. Needs \"Let my phone see and control this PC\" on in Faze on the PC.", color = Palette.Muted, fontSize = 11.5.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    }
}

@Composable
private fun MediaToggle(label: String, on: Boolean, set: (Boolean) -> Unit) {
    Text(label, color = if (on) Palette.AccentInk else Palette.Text, fontSize = 13.sp,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) Palette.Accent else Palette.Surface2).clickable { set(!on) }.padding(horizontal = 14.dp, vertical = 9.dp))
}
