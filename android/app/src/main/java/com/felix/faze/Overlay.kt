package com.felix.faze

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

private object Ink {
    val bg = 0xFF0E1421.toInt()
    val surface2 = 0xFF151D2F.toInt()
    val line = 0x4282AFFF
    val accent = 0xFF5CC8FF.toInt()
    val accentInk = 0xFF04101C.toInt()
    val blue = 0xFF3D7BFF.toInt()
    val text = 0xFFE9EEFB.toInt()
    val dim = 0xFFA5B1CA.toInt()
    val muted = 0xFF6D7A98.toInt()
    val warn = 0xFFF3C969.toInt()
    val bad = 0xFFFF7A8A.toInt()
}

/** The core, drawn with a plain Canvas (the overlay uses ordinary views; Compose inside a service window is not worth the risk). */
class CoreDrawView(context: Context) : View(context) {
    var mode: CoreMode = CoreMode.IDLE
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val t = SystemClock.uptimeMillis()
        val c = width / 2f
        val r = minOf(width, height) / 2f
        val hair = resources.displayMetrics.density
        val energy = when (mode) {
            CoreMode.IDLE -> 0.25f
            CoreMode.LISTENING -> 0.9f
            CoreMode.THINKING -> 0.7f
            CoreMode.SPEAKING -> 1f
        }
        val period = if (mode == CoreMode.THINKING) 650.0 else 1800.0
        val beat = (0.5 - 0.5 * Math.cos(2 * Math.PI * (t % period.toLong()) / period)).toFloat()
        ring.strokeWidth = hair
        ring.color = Ink.accent
        ring.alpha = 40
        canvas.drawCircle(c, c, r - hair, ring)
        ring.alpha = 70
        canvas.drawCircle(c, c, r * 0.8f, ring)
        ring.color = Ink.blue
        ring.alpha = 130
        ring.strokeWidth = hair * 1.5f
        ring.pathEffect = android.graphics.DashPathEffect(floatArrayOf(hair * 4, hair * 9), (t / 40f) % (hair * 13))
        canvas.drawCircle(c, c, r * 0.6f, ring)
        ring.pathEffect = null
        if (mode == CoreMode.THINKING) {
            ring.color = Ink.accent
            ring.alpha = 255
            ring.strokeWidth = hair * 2f
            canvas.drawArc(c - r * 0.8f, c - r * 0.8f, c + r * 0.8f, c + r * 0.8f, (t % 900L) / 900f * 360f, 90f, false, ring)
        }
        val body = r * (0.36f + 0.05f * beat * energy)
        fill.color = Ink.bg
        canvas.drawCircle(c, c, body, fill)
        ring.color = Ink.accent
        ring.alpha = 200
        ring.strokeWidth = hair * 1.5f
        canvas.drawCircle(c, c, body, ring)
        fill.color = Ink.accent
        canvas.drawCircle(c, c, r * (0.14f + 0.06f * beat * energy), fill)
        postInvalidateOnAnimation()
    }
}

/** Listening bars for the overlay: moves with the mic while listening and the voice while the AI speaks. */
class OverlayBarsView(context: Context) : View(context) {
    var level = 0f
    var muted = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val n = 9
        val phase = (SystemClock.uptimeMillis() % 1400L) / 1400f
        val h = LiveBars.heights(if (muted) 0f else level, phase, n)
        val slot = width / n.toFloat()
        paint.color = if (muted) Ink.muted else Ink.accent
        for (i in 0 until n) {
            val bar = height * h[i].coerceIn(0.08f, 1f)
            val cx = slot * (i + 0.5f)
            canvas.drawRoundRect(cx - slot * 0.18f, (height - bar) / 2f, cx + slot * 0.18f, (height + bar) / 2f, slot * 0.18f, slot * 0.18f, paint)
        }
        postInvalidateOnAnimation()
    }
}

/**
 * The overlay that replaces the Tasker scene: a panel at the bottom of the screen over a darkened, blurred view of whatever app is
 * open. Tapping outside the panel closes it. The keyboard works because the window only becomes focusable while the text box is in use.
 */
class OverlayWindow(private val context: Context, private val core: FazeCore) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val imm = context.getSystemService(InputMethodManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var root: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var coreView: CoreDrawView? = null
    private var status: TextView? = null
    private var reply: TextView? = null
    private var scroll: ScrollView? = null
    private var approvalBox: LinearLayout? = null
    private var approvalText: TextView? = null
    private var edit: EditText? = null
    private var mic: TextView? = null
    private var send: TextView? = null
    private var bars: OverlayBarsView? = null
    private var shown = ""
    private var approvalId = 0L
    private var startedHere = false
    private var sawActive = false

    private val poll = object : Runnable {
        override fun run() {
            refresh()
            if (root != null) handler.postDelayed(this, 150)
        }
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    private fun round(color: Int, radius: Int, stroke: Boolean = false) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (stroke) setStroke(1, Ink.line)
    }

    private fun button(label: String, fill: Int, ink: Int, onClick: () -> Unit) = TextView(context).apply {
        text = label
        setTextColor(ink)
        textSize = 14f
        gravity = Gravity.CENTER
        background = round(fill, 14)
        setPadding(dp(16), dp(9), dp(16), dp(9))
        setOnClickListener { onClick() }
    }

    fun isShowing(): Boolean = root != null

    fun canShow(): Boolean = android.provider.Settings.canDrawOverlays(context)

    /** False if Android refused (usually: "display over other apps" is not allowed yet). */
    fun show(): Boolean {
        if (root != null) return true
        if (!canShow()) {
            core.reportStatus("allow \"display over other apps\" for Faze first")
            return false
        }
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = round(Ink.bg, 22, true)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        // The grab handle and header: a swipe up on them opens the full app.
        val grab = View(context).apply { background = round(Ink.line, 3) }
        panel.addView(grab, LinearLayout.LayoutParams(dp(40), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(8) })
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; orientation = LinearLayout.HORIZONTAL }
        val cv = CoreDrawView(context)
        header.addView(cv, LinearLayout.LayoutParams(dp(44), dp(44)))
        val st = TextView(context).apply { setTextColor(Ink.dim); textSize = 13f }
        header.addView(st, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(10) })
        val bv = OverlayBarsView(context)
        header.addView(bv, LinearLayout.LayoutParams(dp(54), dp(28)))
        header.addView(TextView(context).apply {
            text = "✕"
            setTextColor(Ink.dim)
            textSize = 18f
            setPadding(dp(12), dp(4), dp(4), dp(4))
            setOnClickListener { close(false) }
        })
        panel.addView(header)
        swipeUp(grab)
        swipeUp(header)

        val body = TextView(context).apply { setTextColor(Ink.text); textSize = 16f; setLineSpacing(0f, 1.1f) }
        val sc = ScrollView(context).apply { addView(body) }
        panel.addView(sc, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(170)).apply { topMargin = dp(6) })

        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = round(Ink.surface2, 14, true)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            visibility = View.GONE
        }
        box.addView(TextView(context).apply { text = "Approval needed"; setTextColor(Ink.warn); textSize = 12f })
        val at = TextView(context).apply { setTextColor(Ink.text); textSize = 14f; setPadding(0, dp(2), 0, dp(8)) }
        box.addView(at)
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("Allow", Ink.accent, Ink.accentInk) { core.resolveApproval(approvalId, true) })
        row.addView(button("Deny", Ink.surface2, Ink.text) { core.resolveApproval(approvalId, false) }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        box.addView(row)
        panel.addView(box, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        val input = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = round(Ink.surface2, 26, true)
            setPadding(dp(14), dp(4), dp(6), dp(4))
        }
        val et = EditText(context).apply {
            hint = "Message FAZE"
            setHintTextColor(Ink.muted)
            setTextColor(Ink.text)
            textSize = 16f
            background = null
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnTouchListener { _, e ->
                if (e.action == MotionEvent.ACTION_DOWN) takeKeyboard()
                false
            }
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_SEND) { doSend(); true } else false
            }
            setOnKeyListener { _, code, ev ->
                if (code == KeyEvent.KEYCODE_BACK && ev.action == KeyEvent.ACTION_UP) { releaseKeyboard(); true } else false
            }
        }
        input.addView(et, LinearLayout.LayoutParams(0, -2, 1f))
        val mc = button("Mic", Ink.surface2, Ink.text) { doMic() }
        val sd = button("↑", Ink.accent, Ink.accentInk) { doSend() }
        input.addView(mc, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
        input.addView(sd)
        panel.addView(input, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        // The whole screen is the window: a dark scrim (and, where the phone supports it, blur) behind the panel. Tapping the scrim closes it.
        val frame = FrameLayout(context).apply {
            setBackgroundColor(0x99000000.toInt())
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { close(false) }
        }
        panel.setOnClickListener { } // a tap on the panel itself is not a tap on the scrim
        frame.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_BLUR_BEHIND,
            PixelFormat.TRANSLUCENT,
        ).apply {
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            if (Build.VERSION.SDK_INT >= 31) blurBehindRadius = dp(18) // needs a phone whose compositor supports window blur; harmless otherwise
        }
        try {
            wm.addView(frame, p)
        } catch (e: Exception) {
            core.reportStatus("couldn't open the overlay: ${e.message ?: e.javaClass.simpleName}")
            return false
        }
        root = frame; params = p; coreView = cv; status = st; reply = body; scroll = sc; bars = bv
        core.overlayUp = true
        startedHere = false
        sawActive = core.voiceOn || core.liveOn
        approvalBox = box; approvalText = at; edit = et; mic = mc; send = sd
        shown = ""
        handler.post(poll)
        if (!sawActive && core.settings.chordVoice && context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            core.startVoiceChat()
            if (core.voiceOn) { startedHere = true; sawActive = true }
        }
        return true
    }

    /** A swipe up on [v] opens the full app; the conversation carries on there. */
    private fun swipeUp(v: View) {
        var downY = 0f
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY = e.rawY; true }
                MotionEvent.ACTION_UP -> {
                    if (downY - e.rawY > dp(60)) openApp()
                    true
                }
                else -> true
            }
        }
    }

    private fun openApp() {
        startedHere = false // the session now belongs to the app
        try {
            context.startActivity(android.content.Intent(context, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        } catch (e: Exception) {
            core.reportStatus("couldn't open the app: ${e.message ?: e.javaClass.simpleName}")
        }
        close(false)
    }

    // ---- the keyboard: the window is only focusable while the text box is being used

    private fun takeKeyboard() {
        val r = root ?: return
        val p = params ?: return
        if (p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE == 0) return
        p.flags = p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        wm.updateViewLayout(r, p)
        edit?.requestFocus()
        handler.postDelayed({ edit?.let { imm.showSoftInput(it, InputMethodManager.SHOW_IMPLICIT) } }, 120)
    }

    private fun releaseKeyboard() {
        val r = root ?: return
        val p = params ?: return
        edit?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
        edit?.clearFocus()
        p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        wm.updateViewLayout(r, p)
    }

    private fun doSend() {
        val e = edit ?: return
        val t = e.text.toString().trim()
        if (core.busy) core.stop()
        else if (t.isNotEmpty()) {
            core.send(t)
            e.setText("")
            releaseKeyboard()
        }
    }

    private fun doMic() {
        if (core.voiceOn || core.liveOn) core.toggleMute()
        else if (core.recording) core.finishRecording()
        else if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) core.startRecording()
        else core.reportStatus("open the Faze app once and allow the microphone")
    }

    // ---- keeping the panel in step with the core

    private fun refresh() {
        coreView?.mode = core.coreMode()
        val label = core.liveLabel()
        val line = if (core.status.isNotEmpty()) core.status else label
        status?.text = line
        status?.setTextColor(if (core.status.isNotEmpty() && !core.recording && !core.transcribing) Ink.bad else Ink.dim)
        val lastUser = core.messages.lastOrNull { it.role == "user" }?.text.orEmpty()
        val lastReply = core.messages.lastOrNull { it.role == "assistant" }?.text.orEmpty()
        val text = if (lastUser.isEmpty() && lastReply.isEmpty()) "Ask me anything." else (if (lastUser.isNotEmpty()) "You: $lastUser\n\n" else "") + lastReply
        if (text != shown) {
            shown = text
            reply?.text = text
            scroll?.post { scroll?.fullScroll(View.FOCUS_DOWN) }
        }
        val a = core.approval
        approvalBox?.visibility = if (a == null) View.GONE else View.VISIBLE
        if (a != null) {
            approvalId = a.id
            approvalText?.text = a.summary
        }
        val inCall = core.voiceOn || core.liveOn
        if (inCall) sawActive = true
        // A conversation this overlay started ends the overlay when it ends (said "end live", or hung up elsewhere).
        if (startedHere && sawActive && !inCall) { close(false); return }
        bars?.level = if (core.speaking || (core.liveOn && core.liveState == LiveSession.SPEAKING)) core.outLevel else core.micLevel
        bars?.muted = core.micMuted
        bars?.visibility = if (inCall) View.VISIBLE else View.GONE
        mic?.text = if (inCall) (if (core.micMuted) "Unmute" else "Mute") else if (core.recording) "Done" else "Mic"
        send?.text = if (core.busy) "■" else "↑"
    }

    /** [force] is the volume-up long press: stop everything (recording, speech, the request, a pending approval) and close. */
    fun close(force: Boolean) {
        val r = root ?: return
        handler.removeCallbacks(poll)
        core.overlayUp = false
        core.cancelRecording()
        if (startedHere) { startedHere = false; core.closeLiveScreen() }
        if (force) core.stop()
        try {
            edit?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
            wm.removeView(r)
        } catch (e: Exception) {
            // already gone
        }
        root = null; params = null; coreView = null; status = null; reply = null; scroll = null
        approvalBox = null; approvalText = null; edit = null; mic = null; send = null; bars = null
    }
}
