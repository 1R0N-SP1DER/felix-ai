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

    // Tilt steering: the phone's lean drives the left stick, for racing games.
    val prefs = remember { context.getSharedPreferences("faze_pad", android.content.Context.MODE_PRIVATE) }
    var tiltOn by remember { mutableStateOf(prefs.getBoolean("bt_tilt", false)) }
    var tiltInvert by remember { mutableStateOf(prefs.getBoolean("bt_tilt_invert", false)) }
    androidx.compose.runtime.DisposableEffect(tiltOn, tiltInvert, playing) {
        if (!tiltOn || !playing) { pad.tilt = null; return@DisposableEffect onDispose { } }
        val sm = context.getSystemService(android.content.Context.SENSOR_SERVICE) as android.hardware.SensorManager
        val sensor = sm.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)
        val rot = { @Suppress("DEPRECATION") (context.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.rotation }
        val l = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(e: android.hardware.SensorEvent) { pad.tilted(Tilt.steer(e.values, rot(), tiltInvert)) }
            override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
        }
        if (sensor != null) sm.registerListener(l, sensor, android.hardware.SensorManager.SENSOR_DELAY_GAME)
        onDispose { sm.unregisterListener(l); pad.tilt = null }
    }

    // Opening this screen starts everything: the controller role, visibility, and (once the other phone is paired in Bluetooth settings) the connection and the pad itself.
    androidx.compose.runtime.LaunchedEffect(Unit) { if (!pad.active) go() else pad.reconnect() }
    androidx.compose.runtime.LaunchedEffect(pad.host) { if (pad.host != null) playing = true }

    if (playing && pad.host != null) {
        Box(Modifier.fillMaxSize().background(Palette.Bg)) {
            GamepadOverlay(state, layouts, false, onDone = {})
            Text(if (tiltOn) "Tilt: on" else "Tilt: off", color = if (tiltOn) Palette.Accent else Palette.Dim, fontSize = 14.sp, modifier = Modifier.align(androidx.compose.ui.Alignment.TopEnd).padding(12.dp).clickable { tiltOn = !tiltOn; prefs.edit().putBoolean("bt_tilt", tiltOn).apply() })
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
        SettingsCard("Tilt steering") {
            Hint("Steer racing games by tilting this phone like a wheel. It replaces the left stick's sideways movement while it is on. You can also switch it on in the pad with the Tilt button in the corner. If it steers the wrong way, turn on Reverse.")
            WideButton(if (tiltOn) "Tilt steering: on" else "Tilt steering: off", ghost = !tiltOn) { tiltOn = !tiltOn; prefs.edit().putBoolean("bt_tilt", tiltOn).apply() }
            WideButton(if (tiltInvert) "Reverse: on" else "Reverse: off", ghost = !tiltInvert) { tiltInvert = !tiltInvert; prefs.edit().putBoolean("bt_tilt_invert", tiltInvert).apply() }
        }
        WideButton("Back") { onBack() }
    }
}
