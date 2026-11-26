package com.felix.faze

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Path
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Which way Faze controls the screen: with root (fast, full), without root (Android's accessibility service), or whichever works. */
object ControlMode {
    const val AUTO = "auto"
    const val ROOT = "root"
    const val NOROOT = "noroot"

    fun normalize(mode: String): String = if (mode == ROOT || mode == NOROOT) mode else AUTO

    fun useRoot(mode: String, rootAvailable: () -> Boolean): Boolean = when (normalize(mode)) {
        ROOT -> true
        NOROOT -> false
        else -> rootAvailable()
    }
}

/** Whether root is available, asked at most once a minute (asking can pop a permission dialog and takes a moment). */
class RootCheck(private val ask: () -> Boolean, private val now: () -> Long = { System.currentTimeMillis() }) {
    @Volatile private var at = -1L
    @Volatile private var last = false

    fun available(): Boolean {
        val t = now()
        if (at < 0 || t - at > 60_000) { last = try { ask() } catch (e: Exception) { false }; at = t }
        return last
    }
}

/** Picks root or non-root hands for each action, following the setting. In Auto it uses root when root answers and the accessibility way otherwise. */
class ChoosingHands(private val root: Hands, private val noRoot: Hands, private val mode: () -> String, private val rootOk: () -> Boolean) : Hands {
    private fun pick(): Hands = if (ControlMode.useRoot(mode(), rootOk)) root else noRoot

    override fun tap(x: Int, y: Int) = pick().tap(x, y)
    override fun longPress(x: Int, y: Int) = pick().longPress(x, y)
    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int) = pick().swipe(x1, y1, x2, y2)
    override fun typeText(text: String) = pick().typeText(text)
    override fun pressKey(key: String) = pick().pressKey(key)
}

/** What the accessibility service can do for Faze. A fake in the tests, the real service on the phone. */
interface A11yPort {
    /** Draws a touch along the points (fractions are already pixels) and returns whether it was carried out. */
    fun gesture(points: List<Pair<Int, Int>>, durationMs: Long): Boolean
    fun global(action: String): Boolean
    /** Puts text into the focused text field, after what is there. */
    fun typeInto(text: String): Boolean
    fun media(key: String): Boolean
    /** A key that edits or moves inside the focused text box or list: "enter", "backspace", "delete", "left", "right", "up", "down". */
    fun edit(key: String): Boolean
}

/** Touch, keys and text without root, through the accessibility service. Not every key exists this way: arrows, enter and tab need root. */
class NoRootHands(private val port: A11yPort) : Hands {
    companion object {
        val GLOBAL = mapOf("back" to "back", "home" to "home", "recents" to "recents", "notifications" to "notifications", "search" to "")
        val MEDIA = setOf("volume_up", "volume_down", "play_pause", "next", "previous")
        /** Keys carried out inside the focused field or list through accessibility actions (Android 11 for enter). Escape is the Back button. */
        val EDIT = setOf("enter", "return", "backspace", "delete", "left", "right", "up", "down")
    }

    private fun need(done: Boolean, what: String) { if (!done) throw ToolFailure("$what did not work. Turn on Faze's accessibility service in the phone's settings (it is how control works without root).") }

    override fun tap(x: Int, y: Int): String { need(port.gesture(listOf(x to y), 50), "The tap"); return "Tapped at $x,$y." }

    override fun longPress(x: Int, y: Int): String { need(port.gesture(listOf(x to y), 650), "The long press"); return "Long-pressed at $x,$y." }

    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int): String { need(port.gesture(listOf(x1 to y1, x2 to y2), 300), "The swipe"); return "Swiped from $x1,$y1 to $x2,$y2." }

    override fun pressKey(key: String): String {
        val k = key.trim().lowercase()
        if (k in MEDIA) { need(port.media(k), "That key"); return "Pressed $k." }
        if (k == "escape" || k == "esc") { need(port.global("back"), "That key"); return "Pressed $k." }
        if (k in EDIT) { need(port.edit(when (k) { "return" -> "enter"; "delete" -> "backspace"; else -> k }), "That key (tap a text box or list first)"); return "Pressed $k." }
        val g = GLOBAL[k]
        if (g.isNullOrEmpty()) throw ToolFailure("the key '$k' needs root. Without root these work: back, home, recents, escape, enter, backspace, left, right, up, down, volume_up, volume_down, play_pause, next, previous.")
        need(port.global(g), "That key")
        return "Pressed $k."
    }

    override fun typeText(text: String): String {
        InputCommands.checkText(text)
        need(port.typeInto(text), "Typing (tap a text box first)")
        return "Typed ${text.length} characters."
    }
}

/** What backspace, delete, left and right do to a text and its cursor (start..end is the selection). Null for a key that does not edit. */
object TextEdit {
    fun apply(key: String, text: String, start: Int, end: Int): Pair<String, Int>? {
        val a = start.coerceIn(0, text.length)
        val z = end.coerceIn(a, text.length)
        return when (key) {
            "backspace" -> if (z > a) text.removeRange(a, z) to a else if (a > 0) text.removeRange(a - 1, a) to a - 1 else text to 0
            "delete" -> if (z > a) text.removeRange(a, z) to a else if (a < text.length) text.removeRange(a, a + 1) to a else text to a
            "left" -> text to (if (z > a) a else maxOf(0, a - 1))
            "right" -> text to (if (z > a) z else minOf(text.length, z + 1))
            else -> null
        }
    }
}

/** The real thing, using the running accessibility service. */
class AndroidA11y(private val context: Context) : A11yPort {
    private val service: FazeAccessibilityService? get() = FazeAccessibilityService.instance

    override fun gesture(points: List<Pair<Int, Int>>, durationMs: Long): Boolean {
        val s = service ?: return false
        val path = Path().apply {
            moveTo(points[0].first.toFloat(), points[0].second.toFloat())
            if (points.size == 1) lineTo(points[0].first.toFloat() + 0.5f, points[0].second.toFloat()) else points.drop(1).forEach { lineTo(it.first.toFloat(), it.second.toFloat()) }
        }
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1))).build()
        val done = CountDownLatch(1)
        var ok = false
        val sent = s.dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { ok = true; done.countDown() }
            override fun onCancelled(d: GestureDescription?) { done.countDown() }
        }, null)
        if (!sent) return false
        done.await(durationMs + 2000, TimeUnit.MILLISECONDS)
        return ok
    }

    override fun global(action: String): Boolean {
        val s = service ?: return false
        return s.performGlobalAction(when (action) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            else -> return false
        })
    }

    override fun typeInto(text: String): Boolean {
        val s = service ?: return false
        val node: AccessibilityNodeInfo = s.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val hint = node.isShowingHintText
        val old = if (hint) "" else node.text?.toString().orEmpty()
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, old + text) }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    override fun edit(key: String): Boolean {
        val s = service ?: return false
        val root = s.rootInActiveWindow ?: return false
        if (key == "up" || key == "down") {
            val list = findScrollable(root) ?: return false
            return list.performAction(if (key == "down") AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        }
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        if (key == "enter") {
            return if (Build.VERSION.SDK_INT >= 30) node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) else false
        }
        val text = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
        val a = node.textSelectionStart.let { if (it < 0) text.length else it }
        val z = node.textSelectionEnd.let { if (it < 0) text.length else it }
        val (newText, cursor) = TextEdit.apply(key, text, minOf(a, z), maxOf(a, z)) ?: return false
        if (newText != text) {
            val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText) }
            if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false
        }
        val sel = Bundle().apply { putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor); putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor) }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
        return true
    }

    private fun findScrollable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (n.isScrollable) return n
        for (i in 0 until n.childCount) { val c = n.getChild(i) ?: continue; findScrollable(c)?.let { return it } }
        return null
    }

    override fun media(key: String): Boolean {
        val am = context.getSystemService(AudioManager::class.java) ?: return false
        when (key) {
            "volume_up" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            "volume_down" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            else -> {
                val code = when (key) { "play_pause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE; "next" -> KeyEvent.KEYCODE_MEDIA_NEXT; else -> KeyEvent.KEYCODE_MEDIA_PREVIOUS }
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)); am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            }
        }
        return true
    }
}

/** A screenshot without root: Android's accessibility screenshot (Android 11 and later). Windows that block screenshots (banking apps, some video) come out black. */
class A11yShot {
    fun jpeg(maxSide: Int = 1280): ByteArray {
        if (Build.VERSION.SDK_INT < 30) throw ToolFailure("a screenshot without root needs Android 11 or newer")
        val s = FazeAccessibilityService.instance ?: throw ToolFailure("Faze's accessibility service is off. Turn it on in the phone's settings.")
        val done = CountDownLatch(1)
        var bmp: Bitmap? = null
        var failure = ""
        s.takeScreenshot(android.view.Display.DEFAULT_DISPLAY, java.util.concurrent.Executors.newSingleThreadExecutor(), object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(r: AccessibilityService.ScreenshotResult) {
                try {
                    val hw = Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)
                    bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    hw?.recycle()
                } finally { r.hardwareBuffer.close(); done.countDown() }
            }
            override fun onFailure(code: Int) { failure = if (code == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) "too fast, try again" else "Android refused the screenshot (code $code)"; done.countDown() }
        })
        if (!done.await(4, TimeUnit.SECONDS)) throw ToolFailure("the screenshot timed out")
        val b = bmp ?: throw ToolFailure(failure.ifEmpty { "no screenshot came back" })
        val scale = maxSide.toFloat() / maxOf(b.width, b.height)
        val small = if (scale < 1f) Bitmap.createScaledBitmap(b, (b.width * scale).toInt().coerceAtLeast(1), (b.height * scale).toInt().coerceAtLeast(1), true) else b
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 70, out)
        if (small !== b) small.recycle()
        b.recycle()
        return out.toByteArray()
    }
}

/** True while Faze's accessibility service is switched on in Android's settings. */
fun a11yEnabled(context: Context): Boolean = try {
    val on = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
    on.contains(context.packageName + "/", true) && on.contains("FazeAccessibilityService", true)
} catch (e: Exception) { false }
