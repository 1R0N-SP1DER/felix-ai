package com.felix.faze

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** The colors of the Termux web UI, so the app and the page look like the same assistant. */
object Palette {
    val Bg = Color(0xFF04060B)
    val Surface = Color(0xFF0E1421)
    val Surface2 = Color(0xFF151D2F)
    val Line = Color(0x4282AFFF)
    val Accent = Color(0xFF5CC8FF)
    val AccentInk = Color(0xFF04101C)
    val Blue = Color(0xFF3D7BFF)
    val Text = Color(0xFFE9EEFB)
    val Dim = Color(0xFFA5B1CA)
    val Muted = Color(0xFF6D7A98)
    val Ok = Color(0xFF59D9A0)
    val Warn = Color(0xFFF3C969)
    val Bad = Color(0xFFFF7A8A)
}

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
            outline = Palette.Line,
        ),
        content = content,
    )
}
