package com.felix.faze

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

private val Pill = RoundedCornerShape(28.dp)

@Composable
fun ChatScreen(core: FazeCore, onMenu: () -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    var insertTick by remember { mutableStateOf(0) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(insertTick) { if (insertTick > 0) focus.requestFocus() }
    val listState = rememberLazyListState()
    val lastLength = core.messages.lastOrNull()?.text?.length ?: 0
    var plusOpen by remember { mutableStateOf(false) }
    var toolsOpen by remember { mutableStateOf(false) }
    var modelOpen by remember { mutableStateOf(false) }
    var composerHeight by remember { mutableStateOf(64.dp) }

    LaunchedEffect(core.messages.size, lastLength) {
        if (core.messages.isNotEmpty()) listState.scrollToItem(core.messages.size - 1)
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) core.startRecording()
    }

    val mode = core.coreMode()
    val active = mode != CoreMode.IDLE

    Box(modifier = Modifier.fillMaxSize().background(Palette.Bg)) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopBar(core, onMenu) { modelOpen = true }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (core.messages.isEmpty()) {
                    HomeScreen(core, mode) { text -> input = text; insertTick++ }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(core.messages, key = { it.id }) { Bubble(it) }
                    }
                }
            }

            core.approval?.let { ApprovalCard(it, onAllow = { core.resolveApproval(it.id, true) }, onDeny = { core.resolveApproval(it.id, false) }) }

            if (core.status.isNotEmpty()) {
                Text(
                    core.status,
                    color = if (core.recording || core.transcribing) Palette.Accent else Palette.Bad,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            if (active || core.timing.isNotEmpty()) {
                LiveBar(mode, label(core), core.timing, onStop = { if (core.recording) core.finishRecording() else core.stop() })
            }

            Composer(
                value = input,
                focus = focus,
                onValue = { input = it },
                recording = core.recording,
                busy = core.busy,
                onSend = {
                    if (core.busy) core.stop()
                    else if (input.isNotBlank()) {
                        core.send(input)
                        input = ""
                    }
                },
                onMic = {
                    if (core.recording) core.finishRecording()
                    else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) core.startRecording()
                    else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                },
                onPlus = { plusOpen = !plusOpen; toolsOpen = false },
                onTools = { toolsOpen = !toolsOpen; plusOpen = false },
                onModel = { modelOpen = true },
                onHeight = { composerHeight = it },
            )
        }

        val menuBottom = composerHeight + 34.dp
        PlusMenu(core, plusOpen, menuBottom, { plusOpen = false }, onThinking = { modelOpen = true }, onNew = { core.newChat() }, onSettings = onSettings)
        ToolsMenu(toolsOpen, menuBottom, { toolsOpen = false }) { text -> input = text; insertTick++ }
        ModelSheetView(core, modelOpen) { modelOpen = false }
    }
}

private fun label(core: FazeCore): String = when {
    core.recording -> "Listening… tap Done when you finish"
    core.heard -> "Listening…"
    core.transcribing -> "Transcribing…"
    core.speaking -> "Speaking…"
    core.busy -> "Thinking…"
    else -> if (core.listening) "Wake word on" else ""
}

@Composable
private fun Bubble(m: ChatMessage) {
    val mine = m.role == "user"
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        if (m.steps.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 4.dp)) {
                for (step in m.steps) {
                    val failed = step.endsWith("(failed)")
                    Text(
                        (if (failed) "✕ " else "✓ ") + step.removeSuffix(" (failed)"),
                        color = if (failed) Palette.Bad else Palette.Dim,
                        fontSize = 11.sp,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Palette.Surface).border(1.dp, Palette.Line, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
        }
        Text(
            if (m.text.isEmpty()) "…" else m.text,
            color = Palette.Text,
            fontSize = 16.sp,
            fontFamily = LocalReadFont.current,
            modifier = Modifier.widthIn(max = 330.dp)
                .background(if (mine) Palette.Surface2 else Palette.Bg, if (mine) RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp) else RoundedCornerShape(16.dp))
                .padding(horizontal = if (mine) 12.dp else 4.dp, vertical = 8.dp),
        )
    }
}

/** A tool that needs a yes before it runs. Nothing happens until one of the two buttons is pressed (or it times out: no). */
@Composable
private fun ApprovalCard(request: ApprovalRequest, onAllow: () -> Unit, onDeny: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(14.dp)).background(Palette.Surface)
            .border(1.dp, Palette.Warn.copy(alpha = 0.5f), RoundedCornerShape(14.dp)).padding(12.dp),
    ) {
        Text("Approval needed", color = Palette.Warn, fontSize = 12.sp)
        Text(request.summary, color = Palette.Text, fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp, bottom = 10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Allow", color = Palette.AccentInk, fontWeight = FontWeight.Medium,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Palette.Accent).clickable { onAllow() }.padding(horizontal = 18.dp, vertical = 8.dp),
            )
            Text(
                "Deny", color = Palette.Text,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).border(1.dp, Palette.Line, RoundedCornerShape(10.dp)).clickable { onDeny() }.padding(horizontal = 18.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun LiveBar(mode: CoreMode, label: String, timing: String, onStop: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(Palette.Surface)
            .border(1.dp, Palette.Line, RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CoreView(mode, 30.dp)
        Column(modifier = Modifier.weight(1f)) {
            if (label.isNotEmpty()) Text(label, color = Palette.Text, fontSize = 13.sp)
            if (timing.isNotEmpty()) Text(timing, color = Palette.Muted, fontSize = 11.sp)
        }
        if (mode != CoreMode.IDLE) {
            Box(
                modifier = Modifier.size(30.dp).clip(CircleShape).border(1.dp, Palette.Line, CircleShape).clickable { onStop() },
                contentAlignment = Alignment.Center,
            ) { Text("■", color = Palette.Dim, fontSize = 12.sp) }
        }
    }
}
