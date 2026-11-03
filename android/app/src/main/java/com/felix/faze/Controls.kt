package com.felix.faze

import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The page's ease-out curve (--ease). */
val EaseOutSoft = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

/** A gradient from the accent to its partner, as used on the selected segment, New chat's plus, the avatar. */
fun accentGradient(): Brush = Brush.linearGradient(listOf(Palette.Accent, Palette.Blue))

/**
 * The entrance animation of the web page (@keyframes rise): fade in while sliding up 14 px.
 * (The blur part of the original has no cheap equivalent and is left out.)
 */
@Composable
fun Modifier.rise(delayMs: Int = 0, key: Any? = Unit): Modifier {
    val motion = LocalMotion.current
    val progress = remember(key) { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(key, motion) {
        if (motion) progress.animateTo(1f, tween(durationMillis = 700, delayMillis = delayMs, easing = EaseOutSoft)) else progress.snapTo(1f)
    }
    return this.graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * 14.dp.toPx()
    }
}

/** Placeholder: shown and styled like a real control, but it only says that a later patch will make it work. */
@Composable
fun SoonPill() {
    Text("soon", color = Palette.Warn, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp))
}

fun soonToast(context: android.content.Context) {
    Toast.makeText(context, "Coming in a later patch", Toast.LENGTH_SHORT).show()
}

@Composable
fun Modifier.soon(): Modifier {
    val context = LocalContext.current
    return this.alpha(0.5f).clickable { soonToast(context) }
}

/** The 44 x 26 pill switch of the web page. */
@Composable
fun FazeSwitch(checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val offset by animateDpAsState(if (checked) 20.dp else 2.dp, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium), label = "thumb")
    Box(
        modifier = Modifier
            .size(width = 44.dp, height = 26.dp)
            .clip(RoundedCornerShape(50))
            .background(if (checked) Palette.Accent.copy(alpha = 0.35f).compositeOver(Palette.Surface2) else Palette.Surface2)
            .border(1.dp, Palette.Line2, RoundedCornerShape(50))
            .let { if (enabled) it.clickable { onChange(!checked) } else it.alpha(0.5f) },
    ) {
        Box(
            Modifier
                .offset(x = offset, y = 2.dp)
                .size(20.dp)
                .clip(CircleShape)
                .background(if (checked) Palette.Accent else Palette.Dim),
        )
    }
}

private fun Color.compositeOver(background: Color): Color {
    val a = alpha
    return Color(red * a + background.red * (1 - a), green * a + background.green * (1 - a), blue * a + background.blue * (1 - a), 1f)
}

/** A group of choices in one pill, the selected one filled with the accent gradient (.seg). */
@Composable
fun Seg(options: List<Pair<String, String>>, selected: String, small: Boolean = false, soonValues: Set<String> = emptySet(), onPick: (String) -> Unit) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.Surface).border(1.dp, Palette.Line, RoundedCornerShape(14.dp)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for ((value, label) in options) {
            val on = value == selected
            val isSoon = value in soonValues
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .let { if (on) it.background(accentGradient()) else it }
                    .let { if (isSoon) it.alpha(0.5f) else it }
                    .clickable { if (isSoon) soonToast(context) else onPick(value) }
                    .padding(horizontal = if (small) 10.dp else 14.dp, vertical = if (small) 6.dp else 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (on) Palette.AccentInk else Palette.Dim, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, fontSize = if (small) 13.5.sp else 16.sp)
            }
        }
    }
}

/** The full-width button of the settings page. [ghost] is the outlined one; the other is filled with the accent gradient. */
@Composable
fun WideButton(label: String, ghost: Boolean = true, soon: Boolean = false, onClick: () -> Unit) {
    val context = LocalContext.current
    Text(
        label,
        color = if (ghost) Palette.Dim else Palette.AccentInk,
        fontWeight = if (ghost) FontWeight.Medium else FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .padding(top = 8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .let { if (ghost) it.border(1.dp, Palette.Line2, RoundedCornerShape(14.dp)) else it.background(accentGradient()) }
            .let { if (soon) it.alpha(0.5f) else it }
            .clickable { if (soon) soonToast(context) else onClick() }
            .padding(11.dp),
    )
}

/** One line of a settings section: label (with an optional small note) on the left, the control on the right, a hairline below. */
@Composable
fun SettingRow(label: String, note: String? = null, last: Boolean = false, soon: Boolean = false, control: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind { if (!last) drawLine(Palette.Line, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = Palette.Text)
                if (soon) SoonPill()
            }
            if (note != null) Text(note, color = Palette.Muted, fontSize = 12.5.sp)
        }
        Box(modifier = if (soon) Modifier.alpha(0.5f) else Modifier) { control() }
    }
}

/** A section card of the settings page: rounded, glassy, with an uppercase heading. */
@Composable
fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Palette.Glass)
            .border(1.dp, Palette.Line, RoundedCornerShape(22.dp))
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
    ) {
        Text(
            title.uppercase(),
            color = Palette.Muted,
            fontSize = 11.2.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.57.sp,
            modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
        )
        content()
    }
}

@Composable
fun Hint(text: String) {
    Text(text, color = Palette.Muted, fontSize = 12.5.sp, modifier = Modifier.padding(top = 8.dp))
}

/** A closed drop-down: the current choice in a small bordered field that opens a menu. */
@Composable
fun SelectBox(current: String, options: List<Pair<String, String>>, soon: Boolean = false, enabled: Boolean = true, modifier: Modifier = Modifier, onPick: (String) -> Unit) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    Box(modifier = if (enabled) modifier else modifier.alpha(0.5f)) {
        Row(
            modifier = Modifier
                .then(if (modifier == Modifier) Modifier else Modifier.fillMaxWidth())
                .clip(RoundedCornerShape(8.dp))
                .background(Palette.Faint)
                .border(1.dp, Palette.Line, RoundedCornerShape(8.dp))
                .clickable { if (enabled) { if (soon) soonToast(context) else open = true } }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(options.firstOrNull { it.first == current }?.second ?: current, color = Palette.Text, fontSize = 15.sp, modifier = Modifier.weight(1f, fill = modifier != Modifier))
            Text("  ▾", color = Palette.Muted, fontSize = 12.sp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((value, label) in options) DropdownMenuItem(text = { Text(label) }, onClick = { open = false; onPick(value) })
        }
    }
}
