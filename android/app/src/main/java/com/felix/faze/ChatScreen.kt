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
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    ) {
                        items(core.messages, key = { it.id }) { TurnView(it, core.busy && it.id == core.messages.last().id && it.role == "assistant", core) }
                    }
                }
            }

            LiveBarView(core, mode)

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
        core.approval?.let { ApprovalSheet(it, onAllow = { core.resolveApproval(it.id, true) }, onDeny = { core.resolveApproval(it.id, false) }) }
    }
}

