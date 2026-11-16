package com.felix.faze

import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Copy anything, anywhere, and it reaches the computer. Since Android 10 an app in the background cannot read the clipboard, but the system
 * writes a line to the log each time it refuses one ("Denying clipboard access to <package> ... not in focus"), and Faze is listening to
 * clipboard changes, so every copy leaves that line. With root, Faze reads the log, and on that line opens an invisible window (ClipGrabActivity)
 * for a split second: a window in focus may read the clipboard. The text is read, sent, and the window closes.
 */
class ClipRoot(private val pkg: String, private val trigger: () -> Unit, private val now: () -> Long = { System.currentTimeMillis() }, private val minGapMs: Long = 600) {
    companion object {
        fun matches(line: String, pkg: String) = line.contains("lipboard") && line.contains("Denying") && line.contains(pkg)
    }

    private var lastFire = Long.MIN_VALUE / 2

    /** One log line. Returns true when it started a grab. Lines close together are one copy, not many. */
    fun onLine(line: String): Boolean {
        if (!matches(line, pkg)) return false
        val t = now()
        if (t - lastFire < minGapMs) return false
        lastFire = t
        trigger()
        return true
    }
}

/** Runs the log reader as root in the background, and restarts it if it stops. Gives up after three quick failures (no root, or log access refused). */
class ClipWatch(private val pkg: String, private val note: (String) -> Unit) {
    @Volatile private var running = false
    private var process: Process? = null
    private var thread: Thread? = null

    private fun grab() {
        try { ProcessBuilder("su", "-c", "am start -n $pkg/.ClipGrabActivity --activity-no-animation").redirectErrorStream(true).start().waitFor() } catch (_: Exception) { }
    }

    @Synchronized fun start() {
        if (running) return
        running = true
        thread = Thread {
            val root = ClipRoot(pkg, ::grab)
            var quick = 0
            while (running && quick < 3) {
                val began = System.currentTimeMillis()
                try {
                    val p = ProcessBuilder("su", "-c", "logcat -v brief -T 1").redirectErrorStream(true).start()
                    process = p
                    BufferedReader(InputStreamReader(p.inputStream)).use { r -> while (running) { val line = r.readLine() ?: break; root.onLine(line) } }
                } catch (_: Exception) { }
                if (!running) break
                quick = if (System.currentTimeMillis() - began < 5000) quick + 1 else 0
                try { Thread.sleep(1500) } catch (_: InterruptedException) { }
            }
            if (running && quick >= 3) note("Copies in the background need root and log access. The log reader kept stopping, so it is off. Copies made while Faze is open still go across.")
            running = false
        }.also { it.isDaemon = true; it.start() }
    }

    @Synchronized fun stop() {
        running = false
        try { process?.destroy() } catch (_: Exception) { }
        process = null
        thread?.interrupt()
        thread = null
    }
}
