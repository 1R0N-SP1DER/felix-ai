package com.felix.faze

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip

@Composable
fun SettingsScreen(core: FazeCore, onBack: () -> Unit) {
    val context = LocalContext.current
    val s = core.settings
    var groq by remember { mutableStateOf(s.groqKey) }
    var tavily by remember { mutableStateOf(s.tavily) }
    var user by remember { mutableStateOf(s.user) }
    var city by remember { mutableStateOf(s.city) }
    var phrase by remember { mutableStateOf(s.wakePhrase) }
    var speak by remember { mutableStateOf(s.speakReplies) }
    var note by remember { mutableStateOf("") }
    var engine by remember { mutableStateOf(s.wakeEngine) }
    var pico by remember { mutableStateOf(s.picovoiceKey) }
    var keyword by remember { mutableStateOf(s.porcupineKeyword) }
    var hasKeywordFile by remember { mutableStateOf(ModelStore.keywordFile(context) != null) }
    var hideDot by remember { mutableStateOf(s.hideMicDot) }
    var chord by remember { mutableStateOf(s.chordEnabled) }
    val main = remember { Handler(Looper.getMainLooper()) }

    val keywordPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) Thread {
            val message = try {
                ModelStore.importKeyword(context, uri)
                "Custom wake word file imported."
            } catch (e: Exception) {
                "Import failed: ${e.message ?: e.javaClass.simpleName}"
            }
            main.post {
                hasKeywordFile = ModelStore.keywordFile(context) != null
                note = message
            }
        }.start()
    }

    fun startListening() {
        s.wakePhrase = phrase
        s.wakeEnabled = true
        ContextCompat.startForegroundService(context, Intent(context, ListenService::class.java))
    }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) startListening() else note = "Listening needs the microphone permission."
    }

    fun setWake(on: Boolean) {
        note = ""
        if (!on) {
            s.wakeEnabled = false
            context.stopService(Intent(context, ListenService::class.java))
        } else if (!core.modelReady) {
            note = "Download the wake word model first."
        } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startListening()
        } else {
            val wanted = ArrayList<String>().apply {
                add(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            }
            permissions.launch(wanted.toTypedArray())
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Palette.Bg).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Back", color = Palette.Accent) }
            Text("Settings", color = Palette.Text, fontSize = 20.sp)
        }

        Section("Keys")
        Field("Groq key", groq, true) { groq = it }
        Field("Tavily key (web search)", tavily, true) { tavily = it }

        Section("You")
        Field("Your name", user, false) { user = it }
        Field("Default city for weather", city, false) { city = it }

        Section("Voice")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Speak replies", color = Palette.Text, modifier = Modifier.weight(1f))
            Switch(checked = speak, onCheckedChange = { speak = it; s.speakReplies = it })
        }
        Field("Wake phrase (blank: hey faze)", phrase, false) { phrase = it }
        Text("Say it with the screen off too. The listening runs on this phone with a small offline model; nothing is sent anywhere until the phrase is heard.", color = Palette.Muted, fontSize = 12.sp)
        if (!core.modelReady) {
            val progress = core.modelProgress
            Button(onClick = { core.downloadModel() }, enabled = progress < 0) {
                Text(if (progress < 0) "Download wake word model (about 40 MB)" else "Downloading… $progress%")
            }
        } else {
            Text("Wake word model installed.", color = Palette.Ok, fontSize = 13.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Always listening", color = Palette.Text, modifier = Modifier.weight(1f))
            Switch(checked = core.listening, onCheckedChange = { setWake(it) })
        }

        Text("Wake word engine", color = Palette.Text, modifier = Modifier.padding(top = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Offline (Vosk)", engine == "vosk") { engine = "vosk"; s.wakeEngine = "vosk"; note = "Applies the next time listening starts." }
            Pill("Porcupine", engine == "porcupine") { engine = "porcupine"; s.wakeEngine = "porcupine"; note = "Applies the next time listening starts." }
        }
        if (engine == "vosk") {
            Text("Works with no account and no internet. Needs the model above. A word-by-word check rejects coughs, sneezes and look-alike words.", color = Palette.Muted, fontSize = 12.sp)
        } else {
            Text("More accurate. Needs a free Picovoice AccessKey, which checks in online. Pick a built-in word, or import your own .ppn made on the Picovoice console.", color = Palette.Muted, fontSize = 12.sp)
            Field("Picovoice AccessKey", pico, true) { pico = it; s.picovoiceKey = it }
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (k in PorcupineKeywords.builtIn) Pill(k.lowercase(), keyword == k && !hasKeywordFile) { keyword = k; s.porcupineKeyword = k }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { keywordPicker.launch(arrayOf("*/*")) }) { Text(if (hasKeywordFile) "Replace custom word" else "Import custom word (.ppn)") }
                if (hasKeywordFile) TextButton(onClick = { ModelStore.removeKeyword(context); hasKeywordFile = false }) { Text("Remove", color = Palette.Bad) }
            }
            if (hasKeywordFile) Text("Using your custom word file.", color = Palette.Ok, fontSize = 13.sp)
        }

        Section("Microphone dot (root)")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Hide the dot until I'm heard", color = Palette.Text, modifier = Modifier.weight(1f))
            Switch(checked = hideDot, onCheckedChange = {
                hideDot = it
                s.hideMicDot = it
                if (!it) Thread { AndroidRootShell().run(RootCommands.SHOW_MIC_DOT) }.start()
            })
        }
        Text("Android shows a green dot whenever the microphone is open. With root, Faze keeps it off while only waiting for the wake word and shows it while it records your request. It hides the dot for every app while waiting, so leave it off if you don't want that. Untested on Android 16.", color = Palette.Muted, fontSize = 12.sp)
        Button(onClick = {
            Thread {
                val out = AndroidRootShell().run(RootCommands.READ_MIC_DOT)
                main.post { note = if (out == null) "Root not available (allow Faze in Magisk, then try again)." else "Root works. The dot setting reads: $out" }
            }.start()
        }) { Text("Test root access") }

        Section("Overlay and volume buttons")
        val overlayOk = android.provider.Settings.canDrawOverlays(context)
        val a11yOk = (android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "").contains("com.felix.faze/")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Hold both volume keys to open", color = Palette.Text, modifier = Modifier.weight(1f))
            Switch(checked = chord, onCheckedChange = { chord = it; s.chordEnabled = it })
        }
        Text("Opens the overlay from any app. With it open, hold volume up to force-close it. A normal volume press still works.", color = Palette.Muted, fontSize = 12.sp)
        Text(if (overlayOk) "Display over other apps: allowed" else "Display over other apps: not allowed yet", color = if (overlayOk) Palette.Ok else Palette.Warn, fontSize = 13.sp)
        Text(if (a11yOk) "Volume-key service: on" else "Volume-key service: off", color = if (a11yOk) Palette.Ok else Palette.Warn, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                context.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }) { Text("Allow overlay") }
            Button(onClick = {
                context.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }) { Text("Accessibility") }
        }
        Text("With root you can do both in one step: su -c \"sh \$HOME/felix-ai/scripts/faze_enable_android.sh\"", color = Palette.Muted, fontSize = 12.sp)

        Button(onClick = {
            s.groqKey = groq
            s.tavily = tavily
            s.user = user
            s.city = city
            s.wakePhrase = phrase
            note = "Saved. A new wake phrase applies the next time listening starts."
        }, modifier = Modifier.fillMaxWidth()) { Text("Save") }
        if (note.isNotEmpty()) Text(note, color = Palette.Dim, fontSize = 13.sp)
        if (core.status.isNotEmpty()) Text(core.status, color = Palette.Bad, fontSize = 13.sp)
    }
}

@Composable
private fun Section(title: String) {
    Text(title.uppercase(), color = Palette.Accent, fontSize = 12.sp, letterSpacing = 2.sp, modifier = Modifier.padding(top = 10.dp))
}

@Composable
private fun Field(label: String, value: String, secret: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Pill(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) Palette.AccentInk else Palette.Text,
        fontSize = 13.sp,
        modifier = Modifier.clip(RoundedCornerShape(16.dp))
            .background(if (selected) Palette.Accent else Palette.Bg)
            .border(1.dp, Palette.Line, RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}
