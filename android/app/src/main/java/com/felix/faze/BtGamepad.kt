package com.felix.faze

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject

/** This phone as a Bluetooth game controller. Another phone pairs with it in its Bluetooth settings like with any pad, and no app is needed there. Needs Android 9+ and a phone whose Bluetooth offers the HID device role; some makers leave it out. */
@SuppressLint("MissingPermission")
class BtGamepad(private val context: Context) {
    var status by mutableStateOf("Not started")
        private set
    var host by mutableStateOf<String?>(null)
        private set
    private var hid: BluetoothHidDevice? = null
    private var target: BluetoothDevice? = null
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor()

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            status = if (registered) "Ready. Pair from the other phone's Bluetooth settings, or pick it below." else "Controller role not active"
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> { target = device; host = device?.name ?: device?.address; status = "Connected to ${host}" }
                BluetoothProfile.STATE_CONNECTING -> status = "Connecting…"
                BluetoothProfile.STATE_DISCONNECTED -> { if (device == target) { target = null; host = null }; status = "Disconnected. Ready." }
            }
        }

        override fun onGetReport(device: BluetoothDevice?, type: Byte, id: Byte, bufferSize: Int) {
            if (device != null) hid?.replyReport(device, type, id, HidReport.build(emptySet(), 0f, 0f, 0f, 0f))
        }
    }

    private val listener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            val h = proxy as BluetoothHidDevice
            hid = h
            val sdp = BluetoothHidDeviceAppSdpSettings("Faze pad", "Game controller", "Faze", BluetoothHidDevice.SUBCLASS2_GAMEPAD, HidReport.DESCRIPTOR)
            val ok = try { h.registerApp(sdp, null, null, executor, callback) } catch (e: Exception) { false }
            status = if (ok) "Starting…" else "This phone would not start the controller role. Another app may be using it."
        }

        override fun onServiceDisconnected(profile: Int) { hid = null; status = "Bluetooth controller stopped" }
    }

    /** Null when it can start, otherwise the reason it cannot. */
    fun start(): String? {
        if (Build.VERSION.SDK_INT < 28) return "Needs Android 9 or newer."
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return "This phone has no Bluetooth."
        if (!adapter.isEnabled) return "Turn Bluetooth on first."
        if (hid != null) return null
        val got = try { adapter.getProfileProxy(context, listener, BluetoothProfile.HID_DEVICE) } catch (e: Exception) { false }
        if (!got) { status = "This phone's Bluetooth has no controller (HID device) role."; return status }
        status = "Starting…"
        return null
    }

    fun paired(): List<BluetoothDevice> = try { BluetoothAdapter.getDefaultAdapter()?.bondedDevices?.toList().orEmpty() } catch (e: SecurityException) { emptyList() }

    fun connect(d: BluetoothDevice) {
        val h = hid ?: run { status = "Not started"; return }
        status = "Connecting…"
        if (!h.connect(d)) status = "Could not connect. Pair in Bluetooth settings first."
    }

    fun send(state: JSONObject) {
        val h = hid ?: return
        val d = target ?: return
        try { h.sendReport(d, HidReport.ID, HidReport.fromState(state)) } catch (_: Exception) { /* dropped link: the next state heals it */ }
    }

    fun stop() {
        try { target?.let { hid?.disconnect(it) }; hid?.unregisterApp() } catch (_: Exception) { }
        try { BluetoothAdapter.getDefaultAdapter()?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid) } catch (_: Exception) { }
        hid = null; target = null; host = null; status = "Not started"
    }
}
