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
