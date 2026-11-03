package com.felix.faze

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

/**
 * The design tokens of the web UI (:root in core/web/app.css), with the same names where possible. The accent pair is state, so
 * changing the accent in Settings recolours every screen that reads it.
 */
object Palette {
    private var accentState by mutableStateOf(Color(0xFF5CC8FF))
    private var partnerState by mutableStateOf(Color(0xFF3D7BFF))

    val Accent: Color get() = accentState
    val Blue: Color get() = partnerState // --accent-2
    val GlowSoft: Color get() = accentState.copy(alpha = 0.14f) // --glow-soft
    val Glow: Color get() = accentState.copy(alpha = 0.40f) // --glow

    fun setAccent(a: Accent) {
        accentState = Color(a.light)
        partnerState = Color(a.partner)
    }

    val Bg = Color(0xFF04060B) // --bg-0
    val Bg1 = Color(0xFF080C15)
    val Surface = Color(0xFF0E1421) // --bg-2
    val Surface2 = Color(0xFF151D2F) // --bg-3
    val Glass = Color(0xA80E1422) // --glass
    val Scrim = Color(0x9E020409)
    val AccentInk = Color(0xFF04101C)
    val Text = Color(0xFFE9EEFB)
    val Dim = Color(0xFFA5B1CA) // --text-2
    val Muted = Color(0xFF6D7A98) // --text-3
    val Line = Color(0x2182AFFF) // --line (.13)
    val Line2 = Color(0x4282AFFF) // --line-2 (.26)
    val Ok = Color(0xFF59D9A0)
    val Warn = Color(0xFFF3C969)
    val Bad = Color(0xFFFF7A8A)
    val Faint = Color(0x08FFFFFF) // rgba(255,255,255,.03)
}

/** Whether the animated core and entrance animations run (the "Animations" switch). */
val LocalMotion = compositionLocalOf { true }

/** The font for reading text (chat messages): System, Serif or Mono, as in --font-read. */
val LocalReadFont = compositionLocalOf<FontFamily> { FontFamily.Default }

@Composable
fun FazeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.Accent,
            onPrimary = Palette.AccentInk,
            background = Palette.Bg,
            onBackground = Palette.Text,
            surface = Palette.Surface,
            onSurface = Palette.Text,
            surfaceVariant = Palette.Surface2,
            onSurfaceVariant = Palette.Dim,
            outline = Palette.Line2,
        ),
        content = content,
    )
}
