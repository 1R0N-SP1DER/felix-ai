package com.felix.faze

import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/** Phone switches that root can flip without the screen: they work with the phone locked. */
interface Switches {
    fun mobileData(on: Boolean): String
    fun wifi(on: Boolean): String
    fun bluetooth(on: Boolean): String
}

object SwitchCommands {
    private fun word(on: Boolean) = if (on) "enable" else "disable"
    fun mobileData(on: Boolean) = "svc data ${word(on)}"
    fun wifi(on: Boolean) = "svc wifi ${word(on)}"
    fun bluetooth(on: Boolean) = "svc bluetooth ${word(on)}"
}

/** What may run by itself at a scheduled time: only these, and only with the yes/no that was approved when it was scheduled. */
object ScheduledActions {
    val ALLOWED = setOf("set_mobile_data", "set_wifi", "set_bluetooth")

    private fun what(tool: String) = when (tool) {
        "set_mobile_data" -> "mobile data"
        "set_wifi" -> "Wi-Fi"
        "set_bluetooth" -> "Bluetooth"
        else -> tool
    }

    fun label(tool: String, enabled: Boolean) = "Turn ${what(tool)} ${if (enabled) "on" else "off"}"
}

class AndroidSwitches : Switches {
    private fun run(command: String) {
        val pb = ProcessBuilder("su", "-c", command)
        pb.redirectErrorStream(true)
        pb.environment().remove("LD_PRELOAD")
        pb.environment().remove("LD_LIBRARY_PATH")
        val p = try { pb.start() } catch (e: Exception) { throw ToolFailure("root is not available, so this switch can't be flipped") }
        p.outputStream.close()
        val out = ByteArrayOutputStream()
        val reader = Thread { try { p.inputStream.copyTo(out) } catch (_: Exception) {} }.also { it.start() }
        if (!p.waitFor(10, TimeUnit.SECONDS)) { p.destroyForcibly(); reader.join(500); throw ToolFailure("the phone did not answer in time") }
        reader.join(1000)
        if (p.exitValue() != 0) throw ToolFailure("the command failed: " + String(out.toByteArray(), Charsets.UTF_8).trim().take(160).ifEmpty { "exit ${p.exitValue()}" })
    }

    override fun mobileData(on: Boolean): String { run(SwitchCommands.mobileData(on)); return "Mobile data is ${if (on) "on" else "off"}." }
    override fun wifi(on: Boolean): String { run(SwitchCommands.wifi(on)); return "Wi-Fi is ${if (on) "on" else "off"}." }
    override fun bluetooth(on: Boolean): String { run(SwitchCommands.bluetooth(on)); return "Bluetooth is ${if (on) "on" else "off"}." }
}
