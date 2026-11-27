package com.felix.faze

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/** What the phone's screen can be told to do. */
interface Hands {
    fun tap(x: Int, y: Int): String
    fun longPress(x: Int, y: Int): String
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int): String
    fun typeText(text: String): String
    fun pressKey(key: String): String
}

/** The exact commands behind each action, and the checks on what the model asked for. Plain logic, tested without a phone. */
object InputCommands {
    const val MAX_COORD = 5000
    const val MAX_TEXT = 300
    const val PASTE = "input keyevent 279"

    /** What the model may press. Power, reboot and the like are not on the list. */
    val KEYS: Map<String, String> = linkedMapOf(
        "back" to "KEYCODE_BACK", "home" to "KEYCODE_HOME", "recents" to "KEYCODE_APP_SWITCH", "enter" to "KEYCODE_ENTER",
        "delete" to "KEYCODE_DEL", "tab" to "KEYCODE_TAB", "space" to "KEYCODE_SPACE", "up" to "KEYCODE_DPAD_UP", "down" to "KEYCODE_DPAD_DOWN",
        "left" to "KEYCODE_DPAD_LEFT", "right" to "KEYCODE_DPAD_RIGHT", "search" to "KEYCODE_SEARCH", "volume_up" to "KEYCODE_VOLUME_UP",
        "volume_down" to "KEYCODE_VOLUME_DOWN", "play_pause" to "KEYCODE_MEDIA_PLAY_PAUSE", "next" to "KEYCODE_MEDIA_NEXT", "previous" to "KEYCODE_MEDIA_PREVIOUS",
    )

    fun coord(v: Int, what: String): Int {
        if (v < 0 || v > MAX_COORD) throw ToolFailure("$what must be between 0 and $MAX_COORD (read_screen shows the points)")
        return v
    }

    fun tap(x: Int, y: Int) = "input tap ${coord(x, "x")} ${coord(y, "y")}"

    fun longPress(x: Int, y: Int) = "input swipe ${coord(x, "x")} ${coord(y, "y")} $x $y 800"

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int) = "input swipe ${coord(x1, "x1")} ${coord(y1, "y1")} ${coord(x2, "x2")} ${coord(y2, "y2")} 300"

    fun key(name: String): String {
        val code = KEYS[name.trim().lowercase()] ?: throw ToolFailure("unknown key '$name' (keys: ${KEYS.keys.joinToString(", ")})")
        return "input keyevent $code"
    }

    private val plain = Regex("[A-Za-z0-9 .,@_:/+=!?()#-]*")

    /** Text that `input text` types faithfully. Anything else (accents, emoji, quotes, percent signs) goes through the clipboard instead. */
    fun isPlain(text: String) = plain.matches(text)

    fun checkText(text: String): String {
        if (text.isEmpty()) throw ToolFailure("there is nothing to type")
        if (text.length > MAX_TEXT) throw ToolFailure("that is too long to type at once (limit $MAX_TEXT characters)")
        return text
    }

    /** Only call with [isPlain] text. Spaces become %s, which is how `input text` takes a space. */
    fun typeCommand(text: String): String = "input text '" + checkText(text).replace(" ", "%s") + "'"
}

/** Root `input` commands on the real phone. Reached only through [GatedTools], which asks the person first. */
class AndroidHands(private val context: Context, private val fast: RootInputClient? = null) : Hands {
    private fun run(command: String): String {
        val pb = ProcessBuilder("su", "-c", command)
        pb.redirectErrorStream(true)
        pb.environment().remove("LD_PRELOAD")
        pb.environment().remove("LD_LIBRARY_PATH")
        val p = try { pb.start() } catch (e: Exception) { throw ToolFailure("root is not available, so the screen can't be controlled") }
        p.outputStream.close()
        val out = ByteArrayOutputStream()
        val reader = Thread { try { p.inputStream.copyTo(out) } catch (_: Exception) {} }.also { it.start() }
        if (!p.waitFor(8, TimeUnit.SECONDS)) { p.destroyForcibly(); reader.join(500); throw ToolFailure("the phone did not answer in time") }
        reader.join(1000)
        if (p.exitValue() != 0) throw ToolFailure("the command failed: " + String(out.toByteArray(), Charsets.UTF_8).trim().take(160).ifEmpty { "exit ${p.exitValue()}" })
        return ""
    }

    /** The quick helper when it is running, otherwise (or when it fails) the slow `input` command. */
    private fun quickOr(line: String, slow: () -> String) { if (fast?.call(line) == null) run(slow()) }

    override fun tap(x: Int, y: Int): String { InputCommands.tap(x, y); quickOr("tap $x $y") { InputCommands.tap(x, y) }; return "Tapped at $x,$y." }

    override fun longPress(x: Int, y: Int): String { InputCommands.longPress(x, y); quickOr("swipe $x $y $x $y 800") { InputCommands.longPress(x, y) }; return "Long-pressed at $x,$y." }

    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int): String { InputCommands.swipe(x1, y1, x2, y2); quickOr("swipe $x1 $y1 $x2 $y2 300") { InputCommands.swipe(x1, y1, x2, y2) }; return "Swiped from $x1,$y1 to $x2,$y2." }

    override fun pressKey(key: String): String {
        val slow = InputCommands.key(key)
        quickOr("key " + InputCommands.KEYS[key.trim().lowercase()]) { slow }
        return "Pressed ${key.trim().lowercase()}."
    }

    override fun typeText(text: String): String {
        InputCommands.checkText(text)
        if (InputCommands.isPlain(text)) {
            quickOr("text $text") { InputCommands.typeCommand(text) }
        } else {
            val cm = context.getSystemService(ClipboardManager::class.java) ?: throw ToolFailure("the clipboard is not available")
            cm.setPrimaryClip(ClipData.newPlainText("faze", text))
            run(InputCommands.PASTE)
        }
        return "Typed ${text.length} characters."
    }
}
