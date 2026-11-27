package com.felix.faze

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FazeTheme {
                FazeRoot()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // The overlay's wave button: live mode starts here, with the app on screen (the one moment Android lets a microphone service start).
        if (intent?.getBooleanExtra(EXTRA_LIVE, false) == true) {
            intent.removeExtra(EXTRA_LIVE)
            val core = (application as FazeApp).core
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                core.stopVoiceChat()
                core.startLive()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        (application as FazeApp).core.appVisible = false // a live conversation now shows the floating bubble
    }

    companion object {
        const val EXTRA_LIVE = "faze_start_live"
    }

    override fun onStart() {
        super.onStart()
        (application as FazeApp).core.appVisible = true
        window.decorView.post { (application as FazeApp).core.shareClipboard() }
        // The app is on screen, which is the one moment Android lets a microphone service start: bring listening back.
        val core = (application as FazeApp).core
        if (core.settings.wakeEnabled && !core.listening &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) {
            ContextCompat.startForegroundService(this, Intent(this, ListenService::class.java))
        }
    }
}

@Composable
fun FazeRoot(vm: ChatViewModel = viewModel()) {
    val core = vm.core
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(false) }
    var deviceId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var page by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") } // "devices", "activity" or "pcremote", each its own full screen
    val shown = settings || page.isNotEmpty()

    BackHandler(enabled = shown) { if (page.isNotEmpty()) page = "" else settings = false }

    // The look from Settings > General: text size scales everything (the page's --fs), the font is for chat text, motion gates animations.
    val density = LocalDensity.current
    val factor = when (core.textSize) { "s" -> 14.5f / 16f; "l" -> 18f / 16f; else -> 1f }
    val readFont = when (core.fontChoice) { "serif" -> FontFamily.Serif; "mono" -> FontFamily.Monospace; else -> FontFamily.Default }
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, density.fontScale * factor),
        LocalMotion provides core.motion,
        LocalReadFont provides readFont,
    ) {

    Box(modifier = Modifier.fillMaxSize()) {
    ModalNavigationDrawer(
        drawerState = drawer,
        scrimColor = Palette.Scrim,
        gesturesEnabled = !shown,
        drawerContent = {
            ChatDrawer(
                core = core,
                onNew = { core.newChat(); scope.launch { drawer.close() } },
                onOpen = { id -> core.openChat(id); scope.launch { drawer.close() } },
                onSettings = { settings = true; scope.launch { drawer.close() } },
                onDevices = { page = "devices"; scope.launch { drawer.close() } },
                onActivity = { page = "activity"; scope.launch { drawer.close() } },
                onPcRemote = { deviceId = core.deviceRows().firstOrNull { it.connected }?.id ?: ""; page = "pcremote"; scope.launch { drawer.close() } },
                onClose = { scope.launch { drawer.close() } },
            )
        },
    ) {
        if (page == "devices") DevicesScreen(core, { page = "" }, onOpen = { deviceId = it; page = if (core.isPhone(it)) "phoneremote" else "device" }, onPad = { page = "btpad" })
        else if (page == "btpad") BtPadScreen(core) { page = "devices" }
        else if (page == "phoneremote") PhoneRemoteScreen(core, deviceId) { page = "devices" }
        else if (page == "device") DevicePage(core, deviceId, { page = "devices" }, onControl = { page = "pcremote" }, onOverlay = { page = "pcoverlay" }, onMedia = { page = "pcmedia:$it" })
        else if (page == "activity") ActivityScreen(core) { page = "" }
        else if (page.startsWith("pcmedia:")) PcMediaScreen(core, page.removePrefix("pcmedia:")) { page = "device" }
        else if (page == "pcremote") PcRemoteScreen(core) { page = "device" }
        else if (page == "pcoverlay") PcRemoteScreen(core, overlayOnly = true) { page = "device" }
        else if (settings) SettingsScreen(core) { settings = false }
        else ChatScreen(core, onMenu = { scope.launch { drawer.open() } }, onSettings = { settings = true }, onActivity = { page = "activity" })
    }
    if (core.liveOn || core.voiceOn) LiveScreen(core)
    }
    }
}
