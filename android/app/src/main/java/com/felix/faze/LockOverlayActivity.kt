package com.felix.faze

import android.app.Activity
import android.app.KeyguardManager
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * The overlay on the lock screen. A floating window cannot sit above the lock screen, but a see-through activity that is allowed to
 * show when locked can: it holds the same overlay panel. It does not unlock the phone, and while the phone is locked Faze is limited
 * (see [LockPolicy]): only harmless tools, and no memories.
 */
class LockOverlayActivity : Activity(), OverlayHost {
    private var overlay: OverlayWindow? = null
    private lateinit var holder: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        holder = FrameLayout(this)
        setContentView(holder, ViewGroup.LayoutParams(-1, -1))
        val core = (application as FazeApp).core
        val o = OverlayWindow(this, core, this)
        overlay = o
        current = this
        val line = intent.getStringExtra(EXTRA_LINE)
        if (!o.show(takeoverLine = line, spoken = intent.getBooleanExtra(EXTRA_SPOKEN, false))) finish()
    }

    override fun attach(view: View) { holder.addView(view, FrameLayout.LayoutParams(-1, -1)) }
    override fun detach(view: View) { holder.removeView(view) }
    override fun finish() { if (!isFinishing) super.finish() }

    /** Closes the overlay (and with it this screen), as the volume-up long press does. */
    fun closeNow(force: Boolean) { overlay?.close(force) ?: finish() }

    override fun onDestroy() {
        if (current === this) current = null
        overlay?.close(false)
        overlay = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_LINE = "line"
        const val EXTRA_SPOKEN = "spoken"
        @Volatile var current: LockOverlayActivity? = null

        fun isLocked(context: android.content.Context): Boolean =
            context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
    }
}

/** What Faze may do while the phone is locked: whoever is holding it has not proven they are you. */
object LockPolicy {
    /** Tools with nothing personal in or behind them. */
    val ALLOWED = setOf("get_current_datetime", "get_battery_status", "get_weather", "get_wifi_status", "search_web", "find_images", "set_volume", "vibrate", "end_live")

    fun allows(tool: String): Boolean = tool in ALLOWED

    const val REFUSAL = "The phone is locked, so I can't do that. Ask the user to unlock their phone first."
}
