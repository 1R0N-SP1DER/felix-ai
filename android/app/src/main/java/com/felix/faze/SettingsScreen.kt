package com.felix.faze

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

private val TABS = listOf("general" to "General", "voice" to "Voice", "agent" to "Agent", "advanced" to "Advanced")

/**
 * The settings page of the web UI (#settings-view): a back arrow and title, four tabs (General, Voice, Agent, Advanced) and
 * glassy section cards with the same labels. Controls that already work in the app are real; the rest stay in place, dimmed and
 * marked "soon", so each later patch only has to switch its controls on.
 */
@Composable
fun SettingsScreen(core: FazeCore, onBack: () -> Unit) {
    var tab by remember { mutableStateOf("general") }
    Column(modifier = Modifier.fillMaxSize().background(Palette.Bg)) {
        Row(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).clickable { onBack() }, contentAlignment = Alignment.Center) {
                Text("←", color = Palette.Dim, fontSize = 18.sp)
            }
            Text("Settings", color = Palette.Text, fontSize = 20.8.sp, fontWeight = FontWeight.SemiBold)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .drawBehind { drawLine(Palette.Line, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for ((id, label) in TABS) {
                val on = id == tab
                val brush = accentGradient()
                Text(
                    label,
                    color = if (on) Palette.Text else Palette.Muted,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clickable { tab = id }
                        .drawBehind {
                            if (on) drawRoundRect(brush, Offset(12.dp.toPx(), size.height - 2.dp.toPx()), Size(size.width - 24.dp.toPx(), 2.dp.toPx()), CornerRadius(2.dp.toPx()))
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 40.dp).rise(0, tab)) {
            when (tab) {
                "general" -> GeneralTab(core)
                "voice" -> VoiceTab(core)
                "agent" -> AgentTab(core)
                else -> AdvancedTab(core)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------- General

@Composable
private fun GeneralTab(core: FazeCore) {
    var accent by remember { mutableStateOf(core.settings.accent) }
    SettingsCard("Look") {
        SettingRow("Accent") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (a in Accents.all) {
                    val on = a.name == accent
                    Box(
                        modifier = Modifier
                            .let { if (on) it.scale(1.12f) else it }
                            .size(28.dp)
                            .clip(CircleShape)
                            .border(2.dp, if (on) Palette.Text else Color.Transparent, CircleShape)
                            .padding(3.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(Color(a.light), Color(a.partner))))
                            .clickable { accent = a.name; core.setAccent(a) },
                    )
                }
            }
        }
        Column(modifier = Modifier.padding(vertical = 10.dp)) {
            Text("Text size", color = Palette.Text, modifier = Modifier.padding(bottom = 8.dp))
            Seg(listOf("s" to "S", "m" to "M", "l" to "L"), core.textSize, small = true) { core.useTextSize(it) }
        }
        Column(modifier = Modifier.padding(vertical = 10.dp)) {
            Text("Font", color = Palette.Text, modifier = Modifier.padding(bottom = 8.dp))
            Seg(listOf("system" to "System", "serif" to "Serif", "mono" to "Mono", "custom" to "Custom"), core.fontChoice, small = true, soonValues = setOf("custom")) { core.useFont(it) }
            Hint("The font applies to chat text.")
        }
        SettingRow("Animations") { FazeSwitch(core.motion, { core.useMotion(it) }) }
        SettingRow("Greeting line", last = true) { FazeSwitch(core.greetingOn, { core.useGreeting(it) }) }
    }
}

// ---------------------------------------------------------------------------------------------------------------- Voice

@Composable
private fun VoiceTab(core: FazeCore) {
    val context = LocalContext.current
    val s = core.settings
    var phrase by remember { mutableStateOf(s.wakePhrase) }
    var speak by remember { mutableStateOf(s.speakReplies) }
    var rate by remember { mutableStateOf(s.voiceRate) }
    var pitch by remember { mutableStateOf(s.voicePitch) }
    var note by remember { mutableStateOf("") }

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

    SettingsCard("Wake word") {
        SettingRow("Listen for the wake word", if (core.listening) "Listening" else if (!core.modelReady) "Model needed" else "Off") {
            FazeSwitch(core.listening, { setWake(it) })
        }
        SettingRow("Recognition", "Offline on this phone. Nothing leaves it until the phrase is heard.", soon = true) {
            SelectBox("offline", listOf("auto" to "Auto: online, offline backup", "gemini" to "Online only", "offline" to "Offline only"), soon = true) {}
        }
        SettingRow("Phrase", "2 to 40 letters") {
            FieldBox(phrase, false, Modifier.widthIn(max = 190.dp)) { phrase = it; s.wakePhrase = it }
        }
        if (!core.modelReady) {
            val progress = core.modelProgress
            WideButton(if (progress < 0) "Download wake word model (about 40 MB)" else "Downloading… $progress%", ghost = false) { if (progress < 0) core.downloadModel() }
        } else {
            Hint("Wake word model installed. A new phrase applies the next time listening starts.")
        }
        if (note.isNotEmpty()) Hint(note)
        if (core.status.isNotEmpty()) Text(core.status, color = Palette.Bad, fontSize = 12.5.sp, modifier = Modifier.padding(top = 8.dp))
    }

    SettingsCard("Voice") {
        SettingRow("Speak replies aloud") { FazeSwitch(speak, { speak = it; s.speakReplies = it }) }
        var engine by remember { mutableStateOf(s.voiceEngine) }
        var offlineOnly by remember { mutableStateOf(s.offlineVoicesOnly) }
        var deviceVoice by remember { mutableStateOf(s.deviceVoice) }
        Column(modifier = Modifier.padding(vertical = 10.dp)) {
            Text("Voice engine", color = Palette.Text)
            Text("Auto uses the natural Gemini voice (the same voices as live chat, picked under Voice chat) when you are online, and the phone's own voice when you are not.", color = Palette.Muted, fontSize = 12.5.sp, modifier = Modifier.padding(bottom = 8.dp))
            Seg(listOf("auto" to "Auto", "gemini" to "Gemini", "device" to "Phone"), engine, small = true) { engine = it; s.voiceEngine = it }
        }
        SettingRow("Offline voices only", "Only offer phone voices that work without internet.") {
            FazeSwitch(offlineOnly, { offlineOnly = it; s.offlineVoicesOnly = it })
        }
        val voices = core.deviceVoices()
        SettingRow("Phone voice", if (voices.isEmpty()) "Voices load a moment after the app starts, or none are installed for your language." else "Used offline, and when the engine is set to Phone.") {
            SelectBox(deviceVoice, listOf("" to "Default") + voices.map { it.name to (it.label + if (it.offline) "" else " (online)") }) { deviceVoice = it; core.setDeviceVoice(it) }
        }
        var savedLines by remember { mutableStateOf(core.linesSaved()) }
        val linesNote = core.linesStatus
        LaunchedEffect(linesNote) { savedLines = core.linesSaved() }
        SettingRow("Opening lines", if (linesNote.isNotEmpty()) linesNote else "$savedLines of ${GoogleTakeover.LINES.size} saved as sound for your Gemini voice. Saved once, they play offline and use no credits. Changing the voice means downloading again.") {
            Text(if (savedLines == GoogleTakeover.LINES.size) "Saved" else "Download", color = Palette.Dim, fontSize = 13.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { core.downloadLines() }.padding(horizontal = 10.dp, vertical = 6.dp))
        }
        WideButton("Get better phone voices") {
            try {
                context.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                note = "Open Android Settings > System > Languages > Text-to-speech output and install voice data."
            }
        }
        Column(modifier = Modifier.padding(vertical = 10.dp)) {
            Row { Text("Speed ", color = Palette.Text); Text("%.2f".format(rate), color = Palette.Muted, fontSize = 12.5.sp) }
            VoiceSlider(rate) { rate = it; core.setVoice(rate, pitch) }
        }
        Column(modifier = Modifier.padding(vertical = 10.dp)) {
            Row { Text("Pitch ", color = Palette.Text); Text("%.2f".format(pitch), color = Palette.Muted, fontSize = 12.5.sp) }
            VoiceSlider(pitch) { pitch = it; core.setVoice(rate, pitch) }
        }
        WideButton("Test voice") { core.testVoice() }
        SettingRow("Mic button", soon = true) { FazeSwitch(true, {}, enabled = false) }
        SettingRow("Mic engine", "Speech to text runs on Groq Whisper", last = true, soon = true) {
            SelectBox("server", listOf("auto" to "Auto (Chrome online, whisper offline)", "server" to "Groq Whisper", "browser" to "Chrome"), soon = true) {}
        }
    }
}

@Composable
private fun VoiceSlider(value: Float, onChange: (Float) -> Unit) {
    Slider(
        value = value, onValueChange = onChange, valueRange = 0.6f..1.6f,
        colors = SliderDefaults.colors(thumbColor = Palette.Accent, activeTrackColor = Palette.Accent, inactiveTrackColor = Palette.Surface2),
    )
}

// ---------------------------------------------------------------------------------------------------------------- Agent

@Composable
private fun AgentTab(core: FazeCore) {
    var powered by remember { mutableStateOf(core.settings.allowPowered) }
    var liveVoice by remember { mutableStateOf(core.settings.liveVoice) }
    var fillers by remember { mutableStateOf(core.settings.voiceFillers) }
    var liveModel by remember { mutableStateOf(core.settings.liveModel) }
    SettingsCard("Approval mode") {
        var approval by remember { mutableStateOf(PermissionPolicy.approvalMode(core.settings.approvalMode)) }
        Seg(listOf("ask" to "Ask", "yolo" to "Yolo"), approval) { approval = it; core.settings.approvalMode = it }
        Hint(if (approval == "yolo") "Yolo: every tool runs at once with no question, including clipboard, switches, scheduling, new tools and phone control. Anything Faze reads from the web or your screen could steer it, so use it knowingly. Stop still cancels a request." else "Ask: tools that read or change things (clipboard, switches, scheduling, new tools) wait for your yes.")
    }
    SettingsCard("Phone control") {
        var phone by remember { mutableStateOf(PermissionPolicy.phoneMode(core.settings.phoneControl)) }
        Seg(listOf("off" to "Off", "ask" to "Ask", "on" to "Always on"), phone) { phone = it; core.settings.phoneControl = it }
        Hint(when (phone) {
            "off" -> "Off: Faze can't read your screen, look at it, tap, swipe or type. Needs root."
            "on" -> "Always on: Faze can read the screen, look at it, tap, swipe, type and press keys with no question and no time limit. It also works from a scheduled task or with the phone locked."
            else -> "Ask: one yes lets Faze control the screen for 2 minutes. Needs root. Press Stop to take it back."
        })
        SettingRow("Allow higher-level tools", "Network, shell, files outside the sandbox. Also /elevated") { FazeSwitch(powered, { powered = it; core.settings.allowPowered = it }) }
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val filesOk = if (Build.VERSION.SDK_INT >= 30) android.os.Environment.isExternalStorageManager() else true
        SettingRow("Access to all files", if (filesOk) "Allowed" else "Not allowed yet (tools with the files power can only reach Faze's own folder)", last = true) {
            Text("Allow", color = Palette.Accent, modifier = Modifier.clickable {
                if (Build.VERSION.SDK_INT >= 30) ctx.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.padding(8.dp))
        }
    }
    SettingsCard("Fast routes") {
        var fast by remember { mutableStateOf(core.settings.fastRoutes) }
        var needle by remember { mutableStateOf(core.settings.needleOn) }
        var count by remember { mutableStateOf(core.learned.size()) }
        SettingRow("Fast routes", "Common requests run their tool at once; the model only says what was done") { FazeSwitch(fast, { fast = it; core.settings.fastRoutes = it }) }
        SettingRow("Needle", "Also ask the Needle server in Termux (port 8765) which tool you meant. Skipped quietly when it is not running") { FazeSwitch(needle, { needle = it; core.settings.needleOn = it }) }
        SettingRow("Learned shortcuts", if (count == 0) "None yet. A request that works is remembered by its words (never the reply)" else "$count remembered by their words", last = true) {
            if (count > 0) Text("Forget all", color = Palette.Accent, modifier = Modifier.clickable { core.learned.clear(); count = 0 }.padding(8.dp))
        }
    }
    SettingsCard("Offline model") {
        var threads by remember { mutableStateOf(core.settings.offlineThreads.toString()) }
        var ctxSize by remember { mutableStateOf(core.settings.offlineCtx.toString()) }
        SettingRow("Threads", "CPU cores the model uses. Fewer is gentler on the phone and its memory; more is faster") {}
        Seg(OfflineLimits.THREADS.map { it.toString() to it.toString() }, threads, small = true) { threads = it; core.settings.offlineThreads = it.toInt() }
        SettingRow("Context", "How much of the chat it can hold. A smaller number uses less memory (Android closes apps that use too much)") {}
        Seg(OfflineLimits.CTX.map { it.toString() to it.toString() }, ctxSize, small = true) { ctxSize = it; core.settings.offlineCtx = it.toInt() }
        Hint("Applies the next time the offline model starts: switch to another model and back, or restart the app. Pick the model itself from the model button in the chat.")
    }
    SettingsCard("Auto mode") {
        var casual by remember { mutableStateOf(core.settings.autoCasualModel) }
        var deep by remember { mutableStateOf(core.settings.autoDeepModel) }
        val found = core.modelLists["offline"].orEmpty()
        val options = listOf("" to "Find by name") + found.map { it.id to it.label }
        Hint("Pick Auto in the model button. Online, chat goes to Groq or OpenRouter, and code or deep questions go to Gemini. A provider that hits its limit is skipped for a while, so tasks keep going. Offline, a small model answers chat and a bigger one answers code and deep questions.")
        SettingRow("Offline chat model", "Small and quick, like LFM. Blank looks for a file with lfm in its name, then uses your offline model.") {
            SelectBox(casual, options) { casual = it; core.settings.autoCasualModel = it }
        }
        SettingRow("Offline code and deep model", "Bigger, like Qwen. Blank looks for a file with qwen in its name, then uses your offline model.") {
            SelectBox(deep, options) { deep = it; core.settings.autoDeepModel = it }
        }
        SettingRow("Model files", if (found.isEmpty()) "Not loaded yet. Needs root, and loads when you pick Auto." else "${found.size} found", last = true) {
            Text("Load", color = Palette.Dim, fontSize = 13.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { core.loadModels("offline", true) }.padding(horizontal = 10.dp, vertical = 6.dp))
        }
    }
    SettingsCard("Vision model") {
        var vision by remember { mutableStateOf(core.settings.visionModel) }
        SettingRow("Model", "The Groq model that looks at screenshots (look at screen). Leave blank for the default: ${ScreenEyes.DEFAULT_MODEL}", last = true) {
            FieldBox(vision, false, Modifier.widthIn(max = 190.dp)) { vision = it; core.settings.visionModel = it }
        }
    }
    SettingsCard("Voice chat") {
        SettingRow("Filler phrases", "A short phrase like \"let me look that up\" while a slow answer is on its way") { FazeSwitch(fillers, { fillers = it; core.settings.voiceFillers = it }) }
        Hint("Start it from the arrow next to the mic in the chat. Talk and it answers out loud; Groq Whisper does the listening. With headphones you can talk over it; without them the mic waits until it has finished. It keeps going outside the app in a floating bubble (needs the overlay permission).")
    }
    SettingsCard("Live voice") {
        Seg(LiveProtocol.MODELS, liveModel, small = true) { liveModel = it; core.settings.liveModel = it }
        Hint("Start it with the waveform button in the chat. 2.5 Audio is the one that works on a free key; 3.1 and 3.8 can answer \"quota exceeded\".")
        Seg(LiveProtocol.VOICES.map { it to it }, liveVoice, small = true) { liveVoice = it; core.settings.liveVoice = it }
        Hint("A spoken conversation with Gemini Live. With headphones you can talk over it; without them the mic is muted while it speaks.")
    }
    SavedToolsCard(core)
    ScheduledTasksCard(core)
    MemoryCard(core)
}

/** The tools Faze made and you approved: see what each does and its code, delete one, or clear them all. */
@Composable
private fun SavedToolsCard(core: FazeCore) {
    var items by remember { mutableStateOf(core.toolStore.list()) }
    var shown by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }
    SettingsCard("Saved tools") {
        if (items.isEmpty()) Hint("Nothing saved yet. Ask Faze for something it can't do, and approve the tool it writes.")
        for (t in items) {
            Row(modifier = Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(t.name, color = Palette.Accent, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 14.sp)
                    Text(t.description, color = Palette.Dim, fontSize = 13.sp)
                    Text(if (t.domains.isEmpty()) "reaches no websites" else "reaches " + t.domains.joinToString(", "), color = Palette.Muted, fontSize = 12.sp)
                }
                Text("Code", color = Palette.Dim, fontSize = 13.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { shown = if (shown == t.name) null else t.name }.padding(horizontal = 10.dp, vertical = 6.dp))
                Text("✕", color = Palette.Muted, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { core.toolStore.remove(t.name); items = core.toolStore.list() }.padding(horizontal = 10.dp, vertical = 6.dp))
            }
            if (shown == t.name) {
                Box(modifier = Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xB8030610)).border(1.dp, Palette.Line, RoundedCornerShape(12.dp)).padding(10.dp)) {
                    Text(t.code, color = Palette.Text, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 12.5.sp)
                }
            }
        }
        if (items.isNotEmpty()) WideButton("Clear all tools") { confirm = true }
    }
    if (confirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Delete all saved tools?") },
            text = { Text("This removes all ${items.size} tools. It can't be undone.", color = Palette.Dim) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { core.toolStore.clear(); items = core.toolStore.list(); confirm = false }) { Text("Delete", color = Palette.Bad) } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

/** The notes Faze remembers: read them, add one, delete one, or clear all. The model can only add one with your approval. */
@Composable
private fun MemoryCard(core: FazeCore) {
    var items by remember { mutableStateOf(core.memory.list()) }
    var on by remember { mutableStateOf(core.settings.useMemory) }
    var draft by remember { mutableStateOf("") }
    var draftCat by remember { mutableStateOf("me") }
    var note by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val main = remember { Handler(Looper.getMainLooper()) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) Thread {
            val message = try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { readLimited(it, 2 * 1024 * 1024) }
                if (bytes == null) "That file is too big or couldn't be read." else core.memory.importJson(String(bytes, Charsets.UTF_8)).summary()
            } catch (e: Exception) {
                "Import failed: ${e.message ?: e.javaClass.simpleName}"
            }
            main.post { note = message; items = core.memory.list() }
        }.start()
    }
    SettingsCard("Memory") {
        SettingRow("Use memory", "Faze reads these notes at the start of every chat", last = true) { FazeSwitch(on, { on = it; core.settings.useMemory = it }) }
        if (items.isEmpty()) Hint("Nothing remembered yet. Ask Faze to remember something, then approve it.")
        for ((cat, list) in items.groupBy { it.category }.toSortedMap()) {
            Text(cat.uppercase(), color = Palette.Accent, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp, modifier = Modifier.padding(top = 14.dp, start = (cat.count { it == '/' } * 12).dp))
            for (e in list) {
                Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp, start = (cat.count { it == '/' } * 12).dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(e.text, color = Palette.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Text("✕", color = Palette.Muted, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { core.memory.remove(e.id); items = core.memory.list() }.padding(horizontal = 10.dp, vertical = 6.dp))
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Category", color = Palette.Muted, fontSize = 13.sp)
            FieldBox(draftCat, false, Modifier.weight(1f)) { draftCat = it }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FieldBox(draft, false, Modifier.weight(1f)) { draft = it }
            Text(
                "Add", color = Palette.AccentInk, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(accentGradient()).clickable {
                    val r = core.memory.add(draft, draftCat)
                    note = r.message
                    if (r.ok) draft = ""
                    items = core.memory.list()
                }.padding(horizontal = 16.dp, vertical = 9.dp),
            )
        }
        Hint("Only the \"me\" notes and a list of categories are sent with each chat. Faze reads other categories when it needs them, which saves tokens. It can create categories and sub-categories such as work/faze when you approve a note.")
        if (note.isNotEmpty()) Hint(note)
        WideButton("Import from the Termux version") { picker.launch(arrayOf("application/json", "text/plain", "*/*")) }
        if (items.isNotEmpty()) WideButton("Clear all memory") { confirm = true }
    }
    if (confirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Clear all memory?") },
            text = { Text("This deletes all ${items.size} notes. It can't be undone.", color = Palette.Dim) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { core.memory.clear(); items = core.memory.list(); confirm = false }) { Text("Clear", color = Palette.Bad) } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

// ---------------------------------------------------------------------------------------------------------------- Advanced

@Composable
private fun AdvancedTab(core: FazeCore) {
    val context = LocalContext.current
    val s = core.settings
    var groq by remember { mutableStateOf(s.groqKey) }
    var tavily by remember { mutableStateOf(s.tavily) }
    var imageModel by remember { mutableStateOf(s.imageModel) }
    var gemini by remember { mutableStateOf(s.geminiKey) }
    var openrouter by remember { mutableStateOf(s.openrouterKey) }
    var orModel by remember { mutableStateOf(s.openrouterModel) }
    var user by remember { mutableStateOf(s.user) }
    var city by remember { mutableStateOf(s.city) }
    var hideDot by remember { mutableStateOf(s.hideMicDot) }
    var chord by remember { mutableStateOf(s.chordEnabled) }
    var chordVoice by remember { mutableStateOf(s.chordVoice) }
    var limitLocked by remember { mutableStateOf(s.limitWhenLocked) }
    var gTake by remember { mutableStateOf(s.googleTakeover) }
    var gQuips by remember { mutableStateOf(s.googleQuips) }
    var gWords by remember { mutableStateOf(s.googleWords) }
    var gLast by remember { mutableStateOf(s.lastGoogleWindow) }
    var gSkip by remember { mutableStateOf(s.googleSkip) }
    var note by remember { mutableStateOf("") }
    val main = remember { Handler(Looper.getMainLooper()) }

    SettingsCard("FAZE") {
        SettingRow("Groq key") { FieldBox(groq, true, Modifier.widthIn(max = 190.dp)) { groq = it; s.groqKey = it } }
        SettingRow("Gemini key") { FieldBox(gemini, true, Modifier.widthIn(max = 190.dp)) { gemini = it; s.geminiKey = it } }
        SettingRow("OpenRouter key") { FieldBox(openrouter, true, Modifier.widthIn(max = 190.dp)) { openrouter = it; s.openrouterKey = it } }
        SettingRow("OpenRouter model", "Leave blank to pick a free one automatically") { FieldBox(orModel, false, Modifier.widthIn(max = 190.dp)) { orModel = it; s.openrouterModel = it } }
        for (info in OpenAiProviders.all.values) {
            var pKey by remember { mutableStateOf(s.compatKey(info.id)) }
            var pModel by remember { mutableStateOf(s.compatModel(info.id)) }
            SettingRow("${info.label} key") { FieldBox(pKey, true, Modifier.widthIn(max = 190.dp)) { pKey = it; s.setCompatKey(info.id, it) } }
            SettingRow("${info.label} model", "Default ${info.defaultModel}; or pick from the model button") { FieldBox(pModel, false, Modifier.widthIn(max = 190.dp)) { pModel = it; s.setCompatModel(info.id, it) } }
        }
        var cName by remember { mutableStateOf(s.customName.let { if (it == "Custom") "" else it }) }
        var cUrl by remember { mutableStateOf(s.customUrl) }
        var cKey by remember { mutableStateOf(s.customKey) }
        var cModel by remember { mutableStateOf(s.customModel) }
        SettingRow("Custom provider name", "Any service that speaks the OpenAI chat format") { FieldBox(cName, false, Modifier.widthIn(max = 190.dp)) { cName = it; s.customName = it } }
        SettingRow("Custom address", "For example https://api.mistral.ai/v1 or https://api.openai.com/v1") { FieldBox(cUrl, false, Modifier.widthIn(max = 190.dp)) { cUrl = it; s.customUrl = it } }
        SettingRow("Custom key", "Optional for a server on this phone (http://127.0.0.1:port/v1)") { FieldBox(cKey, true, Modifier.widthIn(max = 190.dp)) { cKey = it; s.customKey = it } }
        SettingRow("Custom model", "The model name, or pick it from the model button after the address is set") { FieldBox(cModel, false, Modifier.widthIn(max = 190.dp)) { cModel = it; s.customModel = it } }
        SettingRow("Picture model", "Makes pictures when you ask, using your Gemini key. Default ${GeminiImages.DEFAULT_MODEL}") { FieldBox(imageModel, false, Modifier.widthIn(max = 190.dp)) { imageModel = it; s.imageModel = it } }
        SettingRow("Tavily key", "Web search") { FieldBox(tavily, true, Modifier.widthIn(max = 190.dp)) { tavily = it; s.tavily = it } }
        SettingRow("Your name") { FieldBox(user, false, Modifier.widthIn(max = 190.dp)) { user = it; s.user = it } }
        SettingRow("Default city", "For weather", last = true) { FieldBox(city, false, Modifier.widthIn(max = 190.dp)) { city = it; s.city = it } }
        Hint("Keys stay in this app's private storage and are never sent anywhere except to the service they belong to.")
    }

    SettingsCard("Diagnostics") {
        var logOpen by remember { mutableStateOf(false) }
        WideButton("Open activity log") { logOpen = true }
        if (logOpen) ActivityLogDialog(core) { logOpen = false }
    }

    SettingsCard("Ways to wake Faze") {
        var wakeOn by remember { mutableStateOf(s.wakeEnabled) }
        var wakeNote by remember { mutableStateOf("") }
        SettingRow("Hold both volume keys", "Opens the overlay over any app. With it open, hold volume up to force-close it. Needs the accessibility service below.") {
            FazeSwitch(chord, { chord = it; s.chordEnabled = it })
        }
        SettingRow("Google's assistant", "Hey Google, the power-button hold and the corner swipe. Faze dismisses Google's window and opens its overlay instead. Keep Google as your default assistant so Hey Google keeps working. Those three open the same window, so they switch on and off together.") {
            FazeSwitch(gTake, { gTake = it; s.googleTakeover = it })
        }
        SettingRow("Wake word", "Say the phrase from the Voice tab. " + if (core.listening) "Listening now." else "Not listening.", last = true) {
            FazeSwitch(wakeOn, { on ->
                wakeNote = ""
                if (!on) {
                    wakeOn = false
                    s.wakeEnabled = false
                    context.stopService(Intent(context, ListenService::class.java))
                } else if (!core.modelReady) {
                    wakeNote = "Download the wake word model in the Voice tab first."
                } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    wakeNote = "Allow the microphone from the Voice tab first."
                } else {
                    wakeOn = true
                    s.wakeEnabled = true
                    ContextCompat.startForegroundService(context, Intent(context, ListenService::class.java))
                }
            })
        }
        if (wakeNote.isNotEmpty()) Hint(wakeNote)
    }

    SettingsCard("Overlay") {
        val overlayOk = android.provider.Settings.canDrawOverlays(context)
        val a11yOk = (android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "").contains("com.felix.faze/")
        SettingRow("Listening bar waits", "After the overlay opens, the sound bar waits this long for you to speak, then goes back to Message FAZE.") {
            SelectBox("${s.overlayIdleSeconds}", listOf("3" to "3 seconds", "6" to "6 seconds", "10" to "10 seconds", "15" to "15 seconds", "30" to "30 seconds")) { s.overlayIdleSeconds = it.toInt() }
        }
        SettingRow("Say a line when it does", "Faze jokes about Google not being around, out loud, and listens after it.") {
            FazeSwitch(gQuips, { gQuips = it; s.googleQuips = it })
        }
        SettingRow("Extra window names", "Only if it does not trigger: words from the Google window name below, separated by commas.") {
            FieldBox(gWords, false, Modifier.widthIn(max = 190.dp)) { gWords = it; s.googleWords = it }
        }
        SettingRow("Leave these alone", "Words from a window name below that Faze must not replace. To keep the full Gemini app: open it, come back, tap Refresh, and put a word from its name here.") {
            FieldBox(gSkip, false, Modifier.widthIn(max = 190.dp)) { gSkip = it; s.googleSkip = it }
        }
        SettingRow("Google windows seen", if (gLast.isEmpty()) "None yet. Say Hey Google or open the Gemini app, then come back here and tap Refresh. Newest first." else gLast) {
            Text("Refresh", color = Palette.Dim, fontSize = 13.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { gLast = s.lastGoogleWindow }.padding(horizontal = 10.dp, vertical = 6.dp))
        }
        SettingRow("Limit Faze on the lock screen", "While the phone is locked, Faze can still talk, search and tell the time, but won't use your memories or act on your phone until you unlock.") {
            FazeSwitch(limitLocked, { limitLocked = it; s.limitWhenLocked = it })
        }
        SettingRow("Start voice chat with it", "The overlay starts listening as soon as it opens. Swipe up on its top edge to open the full app.") {
            FazeSwitch(chordVoice, { chordVoice = it; s.chordVoice = it })
        }
        SettingRow("Display over other apps", if (overlayOk) "Allowed" else "Not allowed yet") {
            Text("Allow", color = Palette.Accent, modifier = Modifier.clickable {
                context.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.padding(8.dp))
        }
        SettingRow("Volume-key service", if (a11yOk) "On" else "Off", last = true) {
            Text("Open", color = Palette.Accent, modifier = Modifier.clickable {
                context.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.padding(8.dp))
        }
        Hint("With root, both in one step: su -c \"sh \$HOME/felix-ai/scripts/faze_enable_android.sh\"")
    }

    SettingsCard("Microphone dot (root)") {
        SettingRow("Hide the dot until I'm heard", "Android shows a green dot whenever the microphone is open. This keeps it off while only waiting for the wake word and shows it while recording.", last = true) {
            FazeSwitch(hideDot, {
                hideDot = it
                s.hideMicDot = it
                if (!it) Thread { AndroidRootShell().run(RootCommands.SHOW_MIC_DOT) }.start()
            })
        }
        Hint("It hides the dot for every app while waiting. Untested on Android 16.")
        WideButton("Test root access") {
            Thread {
                val out = AndroidRootShell().run(RootCommands.READ_MIC_DOT)
                main.post { note = if (out == null) "Root not available (allow Faze in Magisk, then try again)." else "Root works. The dot setting reads: $out" }
            }.start()
        }
        if (note.isNotEmpty()) Hint(note)
    }
}

// ---------------------------------------------------------------------------------------------------------------- pieces

/** The text box of the settings page: a small field with a faint fill and a hairline border. */
@Composable
private fun FieldBox(value: String, secret: Boolean, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    Box(modifier = modifier.clip(RoundedCornerShape(8.dp)).background(Palette.Faint).border(1.dp, Palette.Line, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 8.dp)) {
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = TextStyle(color = Palette.Text, fontSize = 15.sp), cursorBrush = SolidColor(Palette.Accent),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Tasks Faze will run later or on repeat (made by asking, for example "remind me in 20 minutes to stretch"). */
@Composable
private fun ScheduledTasksCard(core: FazeCore) {
    var items by remember { mutableStateOf(core.scheduler.list()) }
    SettingsCard("Scheduled tasks") {
        if (items.isEmpty()) Hint("Nothing scheduled. Ask Faze, for example: remind me in 20 minutes to stretch, or every day at 7 tell me the weather.")
        items.forEachIndexed { i, t ->
            val zone = core.scheduler.zone()
            SettingRow(t.prompt, "${ScheduleTime.describe(t.nextAt, core.scheduler.now(), zone)}, ${ScheduleTime.everyText(t.everyMinutes)}", last = i == items.lastIndex) {
                Text("Cancel", color = Palette.Accent, modifier = Modifier.clickable { core.scheduler.cancel(t.id); items = core.scheduler.list() }.padding(8.dp))
            }
        }
        Hint("Runs when the phone wakes at the time. For exact times, allow \"Alarms & reminders\" for Faze in the phone's app settings; otherwise a task can be a few minutes late. A task that needs your approval waits for you in the app.")
    }
}

/** What Faze did lately: tools it ran, how it was woken, scheduled tasks. Newest first. Kept on this phone only. */
@Composable
private fun ActivityLogDialog(core: FazeCore, onClose: () -> Unit) {
    var items by remember { mutableStateOf(core.activity.entries()) }
    val fmt = remember { java.text.SimpleDateFormat("d MMM HH:mm:ss", java.util.Locale.getDefault()) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        Column(modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(Palette.Bg).border(1.dp, Palette.Line, RoundedCornerShape(20.dp)).padding(16.dp)) {
            Text("Activity log", color = Palette.Text, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            Hint("Newest first. Stays on this phone. Text typed into other apps is never recorded.")
            Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (items.isEmpty()) Text("Nothing yet.", color = Palette.Muted, fontSize = 14.sp)
                for (e in items) {
                    Column {
                        Text(fmt.format(java.util.Date(e.time)) + "  " + ActivityLog.label(e.kind), color = Palette.Muted, fontSize = 11.5.sp)
                        Text(e.text, color = Palette.Text, fontSize = 13.5.sp)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.align(Alignment.End)) {
                Text("Clear", color = Palette.Dim, fontSize = 14.sp, modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { core.activity.clear(); items = emptyList() }.padding(horizontal = 12.dp, vertical = 6.dp))
                Text("Close", color = Palette.Dim, fontSize = 14.sp, modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { onClose() }.padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }
    }
}
