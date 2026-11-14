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
import android.graphics.Path
import android.graphics.RectF

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

/** The little icons of the pill, drawn by hand so the overlay needs no icon files. */
class GlyphView(context: Context, private val kind: Kind) : View(context) {
    enum class Kind { PLUS, MIC, WAVE, SEND, STOP }

    var ink = Ink.text
        set(value) { field = value; invalidate() }
    var slashed = false
        set(value) { field = value; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val u = minOf(w, h) / 24f
        val cx = w / 2f
        val cy = h / 2f
        paint.color = ink
        paint.strokeWidth = 2f * u
        paint.style = Paint.Style.STROKE
        when (kind) {
            Kind.PLUS -> { canvas.drawLine(cx - 6 * u, cy, cx + 6 * u, cy, paint); canvas.drawLine(cx, cy - 6 * u, cx, cy + 6 * u, paint) }
            Kind.MIC -> {
                canvas.drawRoundRect(RectF(cx - 3.5f * u, cy - 9 * u, cx + 3.5f * u, cy + 2 * u), 3.5f * u, 3.5f * u, paint)
                val p = Path()
                p.moveTo(cx - 7 * u, cy - 1 * u); p.arcTo(RectF(cx - 7 * u, cy - 6 * u, cx + 7 * u, cy + 8 * u), 180f, -180f); 
                canvas.drawPath(p, paint)
                canvas.drawLine(cx, cy + 8 * u, cx, cy + 11 * u, paint)
                if (slashed) canvas.drawLine(cx - 8 * u, cy - 9 * u, cx + 8 * u, cy + 10 * u, paint)
            }
            Kind.WAVE -> {
                val hs = floatArrayOf(4f, 9f, 14f, 9f, 5f)
                for (k in hs.indices) { val x = cx + (k - 2) * 4.5f * u; canvas.drawLine(x, cy - hs[k] * u / 2f, x, cy + hs[k] * u / 2f, paint) }
            }
            Kind.SEND -> { canvas.drawLine(cx, cy + 7 * u, cx, cy - 7 * u, paint); canvas.drawLine(cx - 6 * u, cy - 1 * u, cx, cy - 7 * u, paint); canvas.drawLine(cx + 6 * u, cy - 1 * u, cx, cy - 7 * u, paint) }
            Kind.STOP -> { paint.style = Paint.Style.FILL; canvas.drawRoundRect(RectF(cx - 6 * u, cy - 6 * u, cx + 6 * u, cy + 6 * u), 2 * u, 2 * u, paint) }
        }
    }
}

/**
 * When the pill shows the listening bar instead of the text box. On opening it shows "Message FAZE" for about a second, then the bar, whether
 * or not anything has been heard. With no speech for the idle time it goes back to "Message FAZE". Speech at any time brings the bar back, and
 * it stays a moment after the last word so it does not flicker.
 */
class PillMode(private val introMs: Long = 1000, private val holdMs: Long = 1100) {
    private var introEnd = 0L
    private var barUntil = 0L

    /** The overlay just opened. */
    fun open(now: Long, idleMs: Long) { introEnd = now + introMs; barUntil = introEnd + idleMs }

    /** The mic was switched on again: the bar comes straight back for another idle period. */
    fun restart(now: Long, idleMs: Long) { introEnd = now; barUntil = now + idleMs }

    fun listening(micOn: Boolean, heard: Boolean, now: Long): Boolean {
        if (!micOn) return false
        if (heard) { barUntil = maxOf(barUntil, now + holdMs); return true }
        return now >= introEnd && now < barUntil
    }
}

/** What the pill shows: kept as plain functions so the rule is tested without a phone. */
object PillState {
    /** The mic level above which something is being said. The voice loop confirms speech a little later; the bar should not wait for it. */
    const val LOUD = 0.35f // the level is a square root of the loudness: a quiet room sits near 0.15-0.25, speech at normal volume near 0.5 and up

    /** Speech is happening: the loop says so, or the mic is clearly picking up sound (and it is not Faze's own voice coming out of the speaker). */
    fun heard(loopHearing: Boolean, level: Float, fazeSpeaking: Boolean): Boolean = loopHearing || (!fazeSpeaking && level > LOUD)

    /** The end button: the stop square while the AI works, the arrow once there is text, the wave (live mode) when the box is empty. */
    fun trailing(busy: Boolean, hasText: Boolean): String = if (busy) "stop" else if (hasText) "send" else "live"

    /** What the mic button does: dictate this phrase into the box when pressed mid-phrase, otherwise switch the always-listening mic off or on. */
    fun micAction(micOn: Boolean, hearing: Boolean): String = if (micOn && hearing) "dictate" else if (micOn) "off" else "on"
}

/**
 * The overlay: a pill at the bottom of the screen over a darkened view of whatever is open (or the lock screen, see OverlayActivity).
 * Tapping outside closes it; a swipe up on the handle opens the app. + attaches, the blue mic listens (and sends by itself after a
 * pause), the wave opens live mode in the app. The keyboard works because the window only becomes focusable while the text box is used.
 */
class OverlayWindow(private val context: Context, private val core: FazeCore) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val imm = context.getSystemService(InputMethodManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var root: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var status: TextView? = null
    private var reply: TextView? = null
    private var replyCard: LinearLayout? = null
    private var scroll: ScrollView? = null
    private var approvalBox: LinearLayout? = null
    private var approvalText: TextView? = null
    private var edit: EditText? = null
    private var plus: GlyphView? = null
    private var micBtn: FrameLayout? = null
    private var micGlyph: GlyphView? = null
    private var trail: FrameLayout? = null
    private var trailGlyph: GlyphView? = null
    private var bars: OverlayBarsView? = null
    private var shown = ""
    private var approvalId = 0L
    private var startedHere = false
    private var sawActive = false
    private var userOff = false
    private val mode = PillMode()
    private var trailKind = ""

    private val poll = object : Runnable {
        override fun run() {
            refresh()
            if (root != null) handler.postDelayed(this, 50)
        }
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    private fun round(color: Int, radius: Int, stroke: Boolean = false) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (stroke) setStroke(1, Ink.line)
    }

    private fun circle(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    private fun button(label: String, fill: Int, ink: Int, onClick: () -> Unit) = TextView(context).apply {
        text = label
        setTextColor(ink)
        textSize = 14f
        gravity = Gravity.CENTER
        background = round(fill, 14)
        setPadding(dp(16), dp(9), dp(16), dp(9))
        setOnClickListener { onClick() }
    }

    private fun roundButton(glyph: GlyphView, fill: Int?, onClick: () -> Unit): FrameLayout = FrameLayout(context).apply {
        if (fill != null) background = circle(fill)
        addView(glyph, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
        setOnClickListener { onClick() }
    }

    fun isShowing(): Boolean = root != null

    fun canShow(): Boolean = android.provider.Settings.canDrawOverlays(context)

    private fun micAllowed() = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** False if Android refused (usually: "display over other apps" is not allowed yet). */
    fun show(takeoverLine: String? = null, spoken: Boolean = false): Boolean {
        if (root != null) return true
        if (!canShow()) {
            core.reportStatus("allow \"display over other apps\" for Faze first")
            return false
        }
        // Every opening starts a fresh chat, so no old window is ever there: the first reply appears once something is sent.
        if (!core.busy && !core.liveOn && !core.voiceOn) core.newChat()
        val panel = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        // The grab handle: a swipe up on it opens the full app.
        val grabZone = FrameLayout(context)
        grabZone.addView(View(context).apply { background = round(Ink.dim, 3) }, FrameLayout.LayoutParams(dp(40), dp(4), Gravity.CENTER))
        panel.addView(grabZone, LinearLayout.LayoutParams(-1, dp(24)))
        swipeUp(grabZone)

        val st = TextView(context).apply { setTextColor(Ink.dim); textSize = 12f; setPadding(dp(8), 0, dp(8), dp(4)); visibility = View.GONE }
        panel.addView(st)

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = round(Ink.bg, 22, true)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            visibility = View.GONE
        }
        val body = TextView(context).apply { setTextColor(Ink.text); textSize = 16f; setLineSpacing(0f, 1.1f) }
        val sc = ScrollView(context).apply { addView(body) }
        card.addView(sc, LinearLayout.LayoutParams(-1, dp(170)))
        panel.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

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
        panel.addView(box, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        // The pill: + | text box (or listening bars) | blue mic | wave / send / stop
        val pillBg = round(Ink.surface2, 30, true)
        val pill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pillBg
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        mode.open(SystemClock.uptimeMillis(), core.settings.overlayIdleSeconds * 1000L)
        val pg = GlyphView(context, GlyphView.Kind.PLUS)
        val pb = roundButton(pg, null) { core.reportStatus("Attachments (photos, camera, files) arrive in the next patch") }
        pill.addView(pb, LinearLayout.LayoutParams(dp(44), dp(44)))
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
        pill.addView(et, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(4) })
        val bv = OverlayBarsView(context).apply { visibility = View.GONE }
        pill.addView(bv, LinearLayout.LayoutParams(0, dp(30), 1f).apply { leftMargin = dp(8); rightMargin = dp(8) })
        val mg = GlyphView(context, GlyphView.Kind.MIC).apply { ink = 0xFFFFFFFF.toInt() }
        val mb = roundButton(mg, Ink.blue) { doMic() }
        pill.addView(mb, LinearLayout.LayoutParams(dp(44), dp(44)).apply { leftMargin = dp(4) })
        val tg = GlyphView(context, GlyphView.Kind.WAVE)
        val tb = roundButton(tg, null) { doTrail() }
        pill.addView(tb, LinearLayout.LayoutParams(dp(44), dp(44)).apply { leftMargin = dp(2) })
        panel.addView(pill, LinearLayout.LayoutParams(-1, -2))

        pillDrawable = pillBg
        panelView = panel

        // The whole screen is the window: a dark scrim (and, where the phone supports it, blur) behind the panel. Tapping the scrim closes it.
        val frame = FrameLayout(context).apply {
            setBackgroundColor(0x99000000.toInt())
            setPadding(dp(10), dp(8), dp(10), dp(14))
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
        root = frame; params = p; status = st; reply = body; replyCard = card; scroll = sc; bars = bv
        core.overlayUp = true
        core.dictateSink = { text -> appendToBox(text) }
        startedHere = false
        userOff = false
        trailKind = ""
        sawActive = core.voiceOn || core.liveOn
        approvalBox = box; approvalText = at; edit = et; plus = pg; micBtn = mb; micGlyph = mg; trail = tb; trailGlyph = tg
        shown = ""
        handler.post(poll)
        if (takeoverLine != null) morphFromGoogle(takeoverLine, spoken)
        // The mic is on by default and stays on until it is switched off here. After a takeover line it waits for the line to finish, so it does not hear it.
        if (!sawActive && core.settings.chordVoice && micAllowed()) {
            if (takeoverLine != null && spoken) startMicWhenQuiet(SystemClock.uptimeMillis() + 5000) else startMic()
        }
        return true
    }

    private fun startMic() {
        if (root == null || core.voiceOn || core.liveOn) return
        core.startVoiceChat()
        if (core.voiceOn) { startedHere = true; sawActive = true }
    }

    private fun startMicWhenQuiet(deadline: Long) {
        if (root == null) return
        if (!core.speaking && SystemClock.uptimeMillis() > spokenFrom || SystemClock.uptimeMillis() > deadline) startMic()
        else handler.postDelayed({ startMicWhenQuiet(deadline) }, 150)
    }

    private var pillDrawable: GradientDrawable? = null
    private var panelView: View? = null
    private var spokenFrom = 0L

    /**
     * The opening when Google's assistant was the one that popped up: the bar starts looking like Google's (a light bar, "Ask Google") and
     * morphs into Faze's while it slides up, and Faze says a line about it.
     */
    private fun morphFromGoogle(line: String, spoken: Boolean) {
        val r = root ?: return
        val pillBg = pillDrawable ?: return
        val panel = panelView ?: return
        val googleBar = 0xFFF1F3F4.toInt()
        val googleHint = 0xFF5F6368.toInt()
        val googleInk = 0xFF3C4043.toInt()
        val eval = android.animation.ArgbEvaluator()
        edit?.hint = "Ask Google"
        edit?.setHintTextColor(googleHint)
        pillBg.setColor(googleBar)
        plus?.ink = googleInk
        quip = line
        quipUntil = SystemClock.uptimeMillis() + 7000
        if (spoken) { spokenFrom = SystemClock.uptimeMillis() + 600; handler.postDelayed({ core.speakOpening(line) }, 450) }
        panel.translationY = dp(70).toFloat()
        panel.alpha = 0f
        r.alpha = 0f
        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 620
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { a ->
                val t = a.animatedFraction
                r.alpha = minOf(1f, t * 2.2f)
                panel.alpha = minOf(1f, t * 2.5f)
                panel.translationY = dp(70) * (1f - t)
                val m = ((t - 0.35f) / 0.5f).coerceIn(0f, 1f) // the colour change starts a beat after the slide
                pillBg.setColor(eval.evaluate(m, googleBar, Ink.surface2) as Int)
                edit?.setHintTextColor(eval.evaluate(m, googleHint, Ink.muted) as Int)
                plus?.ink = eval.evaluate(m, googleInk, Ink.text) as Int
                if (m >= 0.5f && edit?.hint?.toString() != "Message FAZE") edit?.hint = "Message FAZE"
            }
            start()
        }
    }

    private var quip = ""
    private var quipUntil = 0L

    /** A swipe up on [v] opens the full app; the conversation carries on there. */
    private fun swipeUp(v: View) {
        var downY = 0f
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY = e.rawY; true }
                MotionEvent.ACTION_UP -> {
                    if (downY - e.rawY > dp(40)) openApp(false)
                    true
                }
                else -> true
            }
        }
    }

    private fun openApp(live: Boolean) {
        startedHere = false // the session now belongs to the app
        try {
            val i = android.content.Intent(context, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            if (live) i.putExtra(MainActivity.EXTRA_LIVE, true)
            context.startActivity(i)
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

    /** Typed (or edited) text is sent as typed: its reply is not read out. */
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

    private fun doTrail() {
        when (PillState.trailing(core.busy, edit?.text?.isNotBlank() == true)) {
            "stop", "send" -> doSend()
            else -> goLive()
        }
    }

    /** The wave: live mode, which runs in the app. */
    private fun goLive() {
        if (!micAllowed()) { core.reportStatus("open the Faze app once and allow the microphone"); return }
        core.stopVoiceChat()
        openApp(true)
    }

    /** What was dictated goes into the box after anything already typed, ready to edit. */
    private fun appendToBox(text: String) {
        val e = edit ?: return
        val old = e.text.toString().trimEnd()
        e.setText(if (old.isEmpty()) text else "$old $text")
        e.setSelection(e.text.length)
    }

    private fun doMic() {
        val inCall = core.voiceOn
        when (PillState.micAction(inCall, PillState.heard(core.hearingSpeech(), core.micLevel, core.speaking))) {
            "dictate" -> { core.dictateThis(); core.reportStatus("Whatever you are saying now goes into the box") }
            "off" -> {
                userOff = true
                core.settings.chordVoice = false
                core.stopVoiceChat()
            }
            else -> {
                if (!micAllowed()) { core.reportStatus("open the Faze app once and allow the microphone"); return }
                userOff = false
                core.settings.chordVoice = true
                mode.restart(SystemClock.uptimeMillis(), core.settings.overlayIdleSeconds * 1000L)
                startedHere = true
                sawActive = false
                core.startVoiceChat()
            }
        }
    }

    // ---- keeping the pill in step with the core

    private fun refresh() {
        val quipNow = if (SystemClock.uptimeMillis() < quipUntil && !core.busy && !core.recording) quip else ""
        val line = core.status.ifEmpty { quipNow.ifEmpty { if (core.voiceState.contains("Android's own") || core.voiceState.startsWith("Voice chat could not")) core.voiceState else "" } }
        status?.text = line
        status?.visibility = if (line.isEmpty()) View.GONE else View.VISIBLE
        status?.setTextColor(if (core.status.isNotEmpty() && !core.recording && !core.transcribing) Ink.bad else if (quipNow.isNotEmpty() && core.status.isEmpty()) Ink.text else Ink.dim)
        val lastUser = core.messages.lastOrNull { it.role == "user" }?.text.orEmpty()
        val lastReply = core.messages.lastOrNull { it.role == "assistant" }?.text.orEmpty()
        val has = lastUser.isNotEmpty() || lastReply.isNotEmpty()
        val text = (if (lastUser.isNotEmpty()) "You: $lastUser\n\n" else "") + lastReply
        replyCard?.visibility = if (has) View.VISIBLE else View.GONE
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
        // A conversation this overlay started ends the overlay when it ends (said "end live", or hung up elsewhere), but not when the mic was switched off here.
        if (startedHere && sawActive && !inCall && !userOff) { close(false); return }

        val now = SystemClock.uptimeMillis()
        val micOn = core.voiceOn
        val heard = PillState.heard(core.hearingSpeech(), core.micLevel, core.speaking)
        val listening = mode.listening(micOn, heard, now)
        edit?.visibility = if (listening) View.GONE else View.VISIBLE
        bars?.visibility = if (listening) View.VISIBLE else View.GONE
        trail?.visibility = if (listening) View.GONE else View.VISIBLE
        bars?.level = if (core.speaking) core.outLevel else core.micLevel
        micGlyph?.slashed = !micOn
        micBtn?.background = circle(if (micOn) Ink.blue else Ink.surface2)
        micGlyph?.ink = if (micOn) 0xFFFFFFFF.toInt() else Ink.dim
        val kind = PillState.trailing(core.busy, edit?.text?.isNotBlank() == true)
        if (kind != trailKind) {
            trailKind = kind
            trail?.removeAllViews()
            val g = GlyphView(context, when (kind) { "stop" -> GlyphView.Kind.STOP; "send" -> GlyphView.Kind.SEND; else -> GlyphView.Kind.WAVE })
            g.ink = if (kind == "live") Ink.text else Ink.accentInk
            trail?.background = if (kind == "live") null else circle(Ink.accent)
            trail?.addView(g, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
            trailGlyph = g
        }
    }

    /** [force] is the volume-up long press: stop everything (recording, speech, the request, a pending approval) and close. */
    fun close(force: Boolean) {
        val r = root ?: return
        handler.removeCallbacks(poll)
        core.overlayUp = false
        quip = ""
        pillDrawable = null
        panelView = null
        core.dictateSink = null
        core.cancelRecording()
        if (startedHere) { startedHere = false; core.closeLiveScreen() }
        if (force) core.stop()
        try {
            edit?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
            wm.removeView(r)
        } catch (e: Exception) {
            // already gone
        }
        root = null; params = null; status = null; reply = null; replyCard = null; scroll = null
        approvalBox = null; approvalText = null; edit = null; plus = null; micBtn = null; micGlyph = null; trail = null; trailGlyph = null; bars = null
    }
}
