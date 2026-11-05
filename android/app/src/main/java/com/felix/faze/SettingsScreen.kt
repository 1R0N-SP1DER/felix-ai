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
        Column(modifier = Modifier.padding(vertical = 10.dp)) {
            Text("Voice engine", color = Palette.Text)
            Text("Gemini voices sound natural but need internet", color = Palette.Muted, fontSize = 12.5.sp, modifier = Modifier.padding(bottom = 8.dp))
            Seg(listOf("system" to "Device", "gemini" to "Gemini"), "system", small = true, soonValues = setOf("gemini")) {}
        }
        WideButton("Browse voices", soon = true) {}
        SettingRow("Device voice", soon = true) { SelectBox("", emptyList(), soon = true) {} }
        SettingRow("Offline voices only", soon = true) { FazeSwitch(false, {}, enabled = false) }
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
    SettingsCard("Approval mode") {
        Seg(listOf("ask" to "Ask", "yolo" to "Yolo", "off" to "Off"), "ask", soonValues = setOf("yolo", "off")) {}
        Hint("Ask: tools that read or change what you copied wait for your yes. In the app, the clipboard tools always ask first.")
    }
    SettingsCard("Phone control") {
        Seg(listOf("off" to "Off", "ask" to "Ask", "on" to "On"), "off", soonValues = setOf("ask", "on")) {}
        Hint("Lets Gemini see your screen, tap, swipe, type and listen through the mic. Needs higher-level tools. Touches show as circles while it works.")
        SettingRow("Allow higher-level tools", "Network, shell, files outside the sandbox. Also /elevated", last = true, soon = true) { FazeSwitch(false, {}, enabled = false) }
    }
    SettingsCard("Saved tools") {
        Hint("Nothing saved yet.")
        WideButton("Clear all tools", soon = true) {}
    }
    SettingsCard("Saved tasks") {
        Hint("Nothing saved yet.")
        WideButton("Clear all tasks", soon = true) {}
    }
    MemoryCard(core)
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
    var gemini by remember { mutableStateOf(s.geminiKey) }
    var openrouter by remember { mutableStateOf(s.openrouterKey) }
    var orModel by remember { mutableStateOf(s.openrouterModel) }
    var user by remember { mutableStateOf(s.user) }
    var city by remember { mutableStateOf(s.city) }
    var hideDot by remember { mutableStateOf(s.hideMicDot) }
    var chord by remember { mutableStateOf(s.chordEnabled) }
    var note by remember { mutableStateOf("") }
    val main = remember { Handler(Looper.getMainLooper()) }

    SettingsCard("FAZE") {
        SettingRow("Groq key") { FieldBox(groq, true, Modifier.widthIn(max = 190.dp)) { groq = it; s.groqKey = it } }
        SettingRow("Gemini key") { FieldBox(gemini, true, Modifier.widthIn(max = 190.dp)) { gemini = it; s.geminiKey = it } }
        SettingRow("OpenRouter key") { FieldBox(openrouter, true, Modifier.widthIn(max = 190.dp)) { openrouter = it; s.openrouterKey = it } }
        SettingRow("OpenRouter model", "Leave blank to pick a free one automatically") { FieldBox(orModel, false, Modifier.widthIn(max = 190.dp)) { orModel = it; s.openrouterModel = it } }
        SettingRow("Tavily key", "Web search") { FieldBox(tavily, true, Modifier.widthIn(max = 190.dp)) { tavily = it; s.tavily = it } }
        SettingRow("Your name") { FieldBox(user, false, Modifier.widthIn(max = 190.dp)) { user = it; s.user = it } }
        SettingRow("Default city", "For weather", last = true) { FieldBox(city, false, Modifier.widthIn(max = 190.dp)) { city = it; s.city = it } }
        Hint("Keys stay in this app's private storage and are never sent anywhere except to the service they belong to.")
    }

    SettingsCard("Diagnostics") {
        WideButton("Open activity log", soon = true) {}
    }

    SettingsCard("Overlay and volume buttons") {
        val overlayOk = android.provider.Settings.canDrawOverlays(context)
        val a11yOk = (android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "").contains("com.felix.faze/")
        SettingRow("Hold both volume keys to open", "Opens the overlay over any app. With it open, hold volume up to force-close it.") {
            FazeSwitch(chord, { chord = it; s.chordEnabled = it })
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
