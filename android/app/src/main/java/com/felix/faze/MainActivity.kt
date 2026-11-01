package com.felix.faze

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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

    override fun onStart() {
        super.onStart()
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

    BackHandler(enabled = settings) { settings = false }

    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = !settings,
        drawerContent = {
            ChatDrawer(
                core = core,
                onNew = { core.newChat(); scope.launch { drawer.close() } },
                onOpen = { id -> core.openChat(id); scope.launch { drawer.close() } },
                onSettings = { settings = true; scope.launch { drawer.close() } },
            )
        },
    ) {
        if (settings) SettingsScreen(core) { settings = false }
        else ChatScreen(core) { scope.launch { drawer.open() } }
    }
}
