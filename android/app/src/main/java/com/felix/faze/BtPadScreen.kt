package com.felix.faze

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Use this phone as a Bluetooth controller: pick the phone to play on, then the on-screen pad sends normal gamepad input to it. */
@Composable
fun BtPadScreen(core: FazeCore, onBack: () -> Unit) {
    val context = LocalContext.current
    val pad = core.btPad
    var problem by remember { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf(false) }
    var listVersion by remember { mutableStateOf(0) }
    val layouts = remember {
        val prefs = context.getSharedPreferences("faze_pad", android.content.Context.MODE_PRIVATE)
        PadLayouts({ prefs.getString("layouts", null) }, { prefs.edit().putString("layouts", it).apply() })
    }
    val state = remember { PadState({ _, _ -> }, { pad.send(it) }) }

    fun visible() {
        try { context.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)) } catch (_: Exception) { problem = "Open Bluetooth settings and make the phone visible." }
    }
    fun begin() { problem = pad.start(); listVersion++; if (problem == null && pad.host == null) visible() }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) begin() else problem = "Allow Bluetooth for Faze, then try again."
    }
    fun go() {
        if (Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) ask.launch(Manifest.permission.BLUETOOTH_CONNECT)
        else begin()
    }

    // Opening this screen starts everything: the controller role, visibility, and (once the other phone is paired in Bluetooth settings) the connection and the pad itself.
    androidx.compose.runtime.LaunchedEffect(Unit) { if (!pad.active) go() else pad.reconnect() }
    androidx.compose.runtime.LaunchedEffect(pad.host) { if (pad.host != null) playing = true }

    if (playing && pad.host != null) {
        Box(Modifier.fillMaxSize().background(Palette.Bg)) {
            GamepadOverlay(state, layouts, false, onDone = {})
            Text("Close", color = Palette.Dim, fontSize = 14.sp, modifier = Modifier.padding(12.dp).clickable { state.releaseAll(); playing = false })
        }
        return
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Bluetooth controller", color = Palette.Text, fontSize = 20.sp, modifier = Modifier.padding(bottom = 12.dp))
        SettingsCard("This phone as a controller") {
            Hint("Turns this phone into a normal Bluetooth game controller for another phone. Nothing needs installing on that phone. Games that do not support controllers will not react. On the other phone, open Bluetooth settings and pair with this phone once. The pad opens here by itself when it connects.")
            WideButton("Start again", ghost = false) { go() }
            Text(pad.status, color = Palette.Accent, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
            problem?.let { Text(it, color = Palette.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
        }
        SettingsCard("Connect to the other phone") {
            Hint("Already paired before? Pick the phone here if it did not connect by itself.")
            WideButton("Make this phone visible again") { visible() }
            val list = remember(listVersion, pad.status) { pad.paired() }
            if (list.isEmpty()) Text("No paired phones yet.", color = Palette.Muted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp))
            for (d in list) {
                val label = try { d.name ?: d.address } catch (e: SecurityException) { "device" }
                Text(label, color = Palette.Accent, fontSize = 15.sp, modifier = Modifier.fillMaxWidth().clickable { pad.connect(d) }.padding(vertical = 10.dp))
            }
            if (pad.host != null) WideButton("Open the controller", ghost = false) { playing = true }
            if (pad.active) WideButton("Stop the controller") { pad.stop() }
        }
        WideButton("Back") { onBack() }
    }
}
