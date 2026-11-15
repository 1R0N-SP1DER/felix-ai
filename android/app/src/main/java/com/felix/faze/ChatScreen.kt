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
fun ChatScreen(core: FazeCore, onMenu: () -> Unit, onSettings: () -> Unit, onActivity: () -> Unit = {}) {
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

    // Voice chat and live voice need the microphone too: ask once, then carry on with what was tapped.
    var afterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }
    val spokenPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) afterPermission?.invoke() else core.reportStatus("Allow the microphone to talk with Faze")
        afterPermission = null
    }
    val withMic = { action: () -> Unit ->
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) action()
        else { afterPermission = action; spokenPermission.launch(Manifest.permission.RECORD_AUDIO) }
    }
    var micMenuOpen by remember { mutableStateOf(false) }

    val mode = core.coreMode()

    // The + menu: Photos (Android's photo picker), Camera (take a picture to send), Files (Android's file picker).
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) core.attach(uri) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) core.attach(uri) }
    var cameraFile by remember { mutableStateOf<java.io.File?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = cameraFile
        if (ok && f != null) core.attach(androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", f), "photo.jpg")
    }
    val launchCamera = {
        val dir = java.io.File(context.cacheDir, "camera").also { it.mkdirs() }
        val f = java.io.File(dir, "shot_${System.currentTimeMillis()}.jpg")
        cameraFile = f
        camera.launch(androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", f))
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else core.reportStatus("Allow the camera to take a picture")
    }

    Box(modifier = Modifier.fillMaxSize().background(Palette.Bg)) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopBar(core, onMenu, onActivity) { modelOpen = true }

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
                    else if (input.isNotBlank() || core.pending.isNotEmpty()) {
                        core.sendWithAttachments(input)
                        input = ""
                    }
                },
                onMic = {
                    if (core.recording) core.finishRecording()
                    else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) core.startRecording()
                    else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                },
                onMicMenu = { micMenuOpen = !micMenuOpen; plusOpen = false; toolsOpen = false },
                onLive = { withMic { core.startLive() } },
                onPlus = { plusOpen = !plusOpen; toolsOpen = false; micMenuOpen = false },
                onHeight = { composerHeight = it },
                pending = core.pending,
                onRemove = { core.removePending(it) },
            )
        }

        val menuBottom = composerHeight + 34.dp
        PlusMenu(
            plusOpen, menuBottom, { plusOpen = false },
            onPhotos = { photoPicker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onCamera = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchCamera()
                else cameraPermission.launch(Manifest.permission.CAMERA)
            },
            onFiles = { filePicker.launch(arrayOf("*/*")) },
        )
        MicMenu(micMenuOpen, menuBottom, { micMenuOpen = false }, onVoiceChat = { withMic { core.startVoiceChat() } }, onLive = { withMic { core.startLive() } })
        ModelSheetView(core, modelOpen) { modelOpen = false }
        core.approval?.let { ApprovalSheet(it, onAllow = { core.resolveApproval(it.id, true) }, onDeny = { core.resolveApproval(it.id, false) }) }
    }
}

