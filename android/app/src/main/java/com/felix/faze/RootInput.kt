package com.felix.faze

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** One line of the little language between Faze and its root input helper: tap x y, swipe x1 y1 x2 y2 ms, key KEYCODE_X, text chars, ping. Plain logic, tested without a phone. */
sealed class InputLine {
    data class Tap(val x: Int, val y: Int) : InputLine()
    data class Swipe(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val ms: Int) : InputLine()
    data class Key(val name: String) : InputLine()
    data class Text(val chars: String) : InputLine()
    object Ping : InputLine()

    companion object {
        fun parse(line: String): InputLine? {
            val p = line.trimEnd('\r', '\n')
            val word = p.substringBefore(' ')
            val rest = if (p.contains(' ')) p.substringAfter(' ') else ""
            return try {
                when (word) {
                    "ping" -> Ping
                    "tap" -> rest.split(' ').map { it.toInt() }.let { Tap(it[0], it[1]) }
                    "swipe" -> rest.split(' ').map { it.toInt() }.let { Swipe(it[0], it[1], it[2], it[3], it[4].coerceIn(1, 5000)) }
                    "key" -> rest.takeIf { it.matches(Regex("KEYCODE_[A-Z0-9_]+")) }?.let { Key(it) }
                    "text" -> if (rest.isEmpty() || rest.any { it == '\n' || it == '\r' }) null else Text(rest)
                    else -> null
                }
            } catch (_: Exception) { null }
        }
    }
}

/**
 * Runs as root, started with app_process from Faze's own apk, and injects touches and keys straight into Android's input system. `su -c input tap` starts a
 * whole Java program for every single tap or key, which takes a few hundred milliseconds on a mid-range phone; this one starts once and answers each line at once.
 * It is not used without root, and Faze falls back to the slow commands if it cannot start.
 */
object RootInput {
    private const val SOURCE_TOUCH = 0x1002
    private const val SOURCE_KEYBOARD = 0x101

    @JvmStatic
    fun main(args: Array<String>) {
        val inj = Injector()
        val input = BufferedReader(InputStreamReader(System.`in`))
        while (true) {
            val line = input.readLine() ?: break
            val cmd = InputLine.parse(line)
            val reply = try {
                when (cmd) {
                    null -> "err unknown"
                    InputLine.Ping -> "ok"
                    is InputLine.Tap -> { inj.tap(cmd.x, cmd.y); "ok" }
                    is InputLine.Swipe -> { inj.swipe(cmd); "ok" }
                    is InputLine.Key -> if (inj.key(cmd.name)) "ok" else "err key"
                    is InputLine.Text -> if (inj.text(cmd.chars)) "ok" else "err text"
                }
            } catch (e: Throwable) { "err " + (e.message ?: e.javaClass.simpleName).replace('\n', ' ').take(100) }
            println(reply)
            System.out.flush()
        }
    }

    private class Injector {
        private val manager: Any
        private val inject: java.lang.reflect.Method
        private val setSource = android.view.InputEvent::class.java.getMethod("setSource", Int::class.javaPrimitiveType)

        init {
            var m: Any? = null
            try { m = Class.forName("android.hardware.input.InputManager").getMethod("getInstance").invoke(null) } catch (_: Throwable) { }
            if (m != null) {
                manager = m
            } else {
                val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "input") as android.os.IBinder
                manager = Class.forName("android.hardware.input.IInputManager\$Stub").getMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)!!
            }
            inject = manager.javaClass.getMethod("injectInputEvent", android.view.InputEvent::class.java, Int::class.javaPrimitiveType)
        }

        private fun send(e: android.view.InputEvent, source: Int) {
            setSource.invoke(e, source)
            inject.invoke(manager, e, 0)
        }

        private fun touch(down: Long, action: Int, x: Float, y: Float) {
            val e = android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), action, x, y, 0)
            try { send(e, SOURCE_TOUCH) } finally { e.recycle() }
        }

        fun tap(x: Int, y: Int) {
            val t = android.os.SystemClock.uptimeMillis()
            touch(t, android.view.MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat())
            touch(t, android.view.MotionEvent.ACTION_UP, x.toFloat(), y.toFloat())
        }

        fun swipe(s: InputLine.Swipe) {
            val t = android.os.SystemClock.uptimeMillis()
            touch(t, android.view.MotionEvent.ACTION_DOWN, s.x1.toFloat(), s.y1.toFloat())
            val steps = maxOf(1, s.ms / 16)
            for (i in 1..steps) {
                Thread.sleep(16)
                val f = i.toFloat() / steps
                touch(t, android.view.MotionEvent.ACTION_MOVE, s.x1 + (s.x2 - s.x1) * f, s.y1 + (s.y2 - s.y1) * f)
            }
            touch(t, android.view.MotionEvent.ACTION_UP, s.x2.toFloat(), s.y2.toFloat())
        }

        fun key(name: String): Boolean {
            val code = android.view.KeyEvent.keyCodeFromString(name)
            if (code == android.view.KeyEvent.KEYCODE_UNKNOWN) return false
            val t = android.os.SystemClock.uptimeMillis()
            send(android.view.KeyEvent(t, t, android.view.KeyEvent.ACTION_DOWN, code, 0), SOURCE_KEYBOARD)
            send(android.view.KeyEvent(t, android.os.SystemClock.uptimeMillis(), android.view.KeyEvent.ACTION_UP, code, 0), SOURCE_KEYBOARD)
            return true
        }

        fun text(chars: String): Boolean {
            val events = android.view.KeyCharacterMap.load(android.view.KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(chars.toCharArray()) ?: return false
            for (e in events) send(e, SOURCE_KEYBOARD)
            return true
        }
    }
}

/** Talks to the helper over one long-lived root shell. [call] gives the helper's answer or null when there is no helper (the caller then uses the slow command). After a failed start it does not try again for a minute. */
class RootInputClient(
    private val launch: () -> Pair<OutputStream, InputStream>?,
    private val close: () -> Unit = {},
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private var out: OutputStream? = null
    private val answers = LinkedBlockingQueue<String>()
    private var failedAt = -1L
    @Volatile private var ready = false
    private var gen = 0

    @Synchronized private fun ensure(): Boolean {
        if (ready) return true
        if (failedAt >= 0 && now() - failedAt < 60_000) return false
        answers.clear()
        val streams = try { launch() } catch (_: Exception) { null }
        if (streams == null) { failedAt = now(); return false }
        out = streams.first
        val mine = ++gen
        Thread {
            try { BufferedReader(InputStreamReader(streams.second)).forEachLine { if (mine == gen) answers.put(it) } } catch (_: Exception) { }
            if (mine == gen) { ready = false; answers.put("") } // the helper ended: wakes anything waiting
        }.also { it.isDaemon = true; it.start() }
        ready = true
        val first = exchange("ping", 5000)
        if (first != "ok") { stop(); failedAt = now(); return false }
        failedAt = -1L
        return true
    }

    private fun exchange(line: String, timeoutMs: Long): String? {
        val o = out ?: return null
        return try {
            o.write((line + "\n").toByteArray()); o.flush()
            answers.poll(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: Exception) { null }
    }

    /** "ok" when the helper did it, otherwise null (not running, it failed or it did not answer in time). */
    @Synchronized fun call(line: String, timeoutMs: Long = 4000): String? {
        if (!ensure()) return null
        val r = exchange(line, timeoutMs)
        if (r == "ok") return r
        if (r == null || r.isEmpty()) { stop(); failedAt = now() }
        return null
    }

    @Synchronized fun stop() { ready = false; gen++; try { out?.close() } catch (_: Exception) { }; try { close() } catch (_: Exception) { }; out = null }

    companion object {
        /** The helper as a root process: its class comes from the installed apk. */
        fun forApk(apkPath: String): RootInputClient {
            var proc: Process? = null
            return RootInputClient({
                val pb = ProcessBuilder("su", "-c", "CLASSPATH='$apkPath' exec app_process /system/bin com.felix.faze.RootInput")
                pb.redirectErrorStream(false)
                pb.environment().remove("LD_PRELOAD"); pb.environment().remove("LD_LIBRARY_PATH")
                val p = pb.start().also { proc = it }
                p.outputStream to p.inputStream
            }, { try { proc?.destroy() } catch (_: Exception) { } })
        }
    }
}
