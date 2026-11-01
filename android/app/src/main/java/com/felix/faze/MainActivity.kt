package com.felix.faze

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel

private val Bg = Color(0xFF11131A)
private val Card = Color(0xFF1A1E29)
private val Mine = Color(0xFF232C4A)
private val Accent = Color(0xFF7AA2FF)
private val Ink = Color(0xFFEEF0F6)
private val Dim = Color(0xFF99A0B4)
private val Bad = Color(0xFFFF7A8A)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = Card, onSurface = Ink, onBackground = Ink)) {
                ChatScreen()
            }
        }
    }
}

@Composable
fun ChatScreen(vm: ChatViewModel = viewModel()) {
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    var showKeys by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val lastLength = vm.messages.lastOrNull()?.text?.length ?: 0

    LaunchedEffect(vm.messages.size, lastLength) {
        if (vm.messages.isNotEmpty()) listState.scrollToItem(vm.messages.size - 1)
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.startRecording()
    }

    Column(modifier = Modifier.fillMaxSize().background(Bg)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Faze", color = Ink, fontSize = 22.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { showKeys = true }) { Text("Keys", color = Accent) }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(vm.messages, key = { it.id }) { Bubble(it) }
        }

        if (vm.status.isNotEmpty()) {
            Text(vm.status, color = if (vm.recording) Accent else Bad, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask Faze anything", color = Dim) },
                maxLines = 4,
            )
            Button(onClick = {
                if (vm.recording) {
                    vm.finishRecording()
                } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    vm.startRecording()
                } else {
                    micPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
            }) { Text(if (vm.recording) "Done" else "Mic") }
            Button(onClick = {
                if (vm.busy) {
                    vm.stop()
                } else if (input.isNotBlank()) {
                    vm.send(input)
                    input = ""
                }
            }) { Text(if (vm.busy) "Stop" else "Send") }
        }
    }

    if (showKeys) KeysDialog(vm.settings) { showKeys = false }
}

@Composable
private fun Bubble(m: ChatMessage) {
    val mine = m.role == "user"
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(modifier = Modifier.widthIn(max = 320.dp).background(if (mine) Mine else Card, RoundedCornerShape(16.dp)).padding(12.dp)) {
            if (m.steps.isNotEmpty()) {
                Text("used " + m.steps.joinToString(", "), color = Dim, fontSize = 12.sp)
            }
            Text(if (m.text.isEmpty()) "..." else m.text, color = Ink, fontSize = 16.sp)
        }
    }
}

@Composable
private fun KeysDialog(settings: AppSettings, onClose: () -> Unit) {
    var groq by remember { mutableStateOf(settings.groqKey) }
    var tavily by remember { mutableStateOf(settings.tavily) }
    var model by remember { mutableStateOf(settings.modelName) }
    var city by remember { mutableStateOf(settings.city) }
    var user by remember { mutableStateOf(settings.user) }
    var speak by remember { mutableStateOf(settings.speakReplies) }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Keys and settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("Groq key", groq, true) { groq = it }
                Field("Tavily key (web search)", tavily, true) { tavily = it }
                Field("Model (blank: ${Groq.DEFAULT_MODEL})", model, false) { model = it }
                Field("Default city for weather", city, false) { city = it }
                Field("Your name", user, false) { user = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Speak replies", modifier = Modifier.weight(1f))
                    Switch(checked = speak, onCheckedChange = { speak = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                settings.groqKey = groq
                settings.tavily = tavily
                settings.modelName = model
                settings.city = city
                settings.user = user
                settings.speakReplies = speak
                onClose()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
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
