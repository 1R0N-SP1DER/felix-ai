package com.felix.faze

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The top bar of the web UI (#topbar): menu, model chip, status chips on the right, activity log and stop-speaking buttons. */
@Composable
fun TopBar(core: FazeCore, onMenu: () -> Unit, onModel: () -> Unit) {
    val context = LocalContext.current
    val chips = ModelSheet.chips(core.provider, core.settings.hasKey(core.provider), core.settings.wakeEnabled, core.modelReady)
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconBox("☰") { onMenu() }
        Row(
            modifier = Modifier.clip(RoundedCornerShape(50)).background(Palette.Glass).border(1.dp, Palette.Line, RoundedCornerShape(50)).clickable { onModel() }.padding(horizontal = 13.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("◎", color = Palette.Accent, fontSize = 14.sp)
            Text(ModelSheet.label(core.provider), color = Palette.Dim, fontSize = 13.8.sp, letterSpacing = 0.3.sp)
            Text("▾", color = Palette.Dim.copy(alpha = 0.6f), fontSize = 10.sp)
        }
        Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            for (c in chips) StatusChipView(c)
        }
        IconBox("≡") { soonToast(context) }
        if (core.speaking) IconBox("■") { core.stopSpeaking() }
    }
}

@Composable
private fun IconBox(glyph: String, onClick: () -> Unit) {
    Box(modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).clickable { onClick() }, contentAlignment = Alignment.Center) {
        Text(glyph, color = Palette.Dim, fontSize = 18.sp)
    }
}

@Composable
private fun StatusChipView(c: StatusChip) {
    val color = when (c.kind) { "ok" -> Palette.Ok; "warn" -> Palette.Warn; "bad" -> Palette.Bad; else -> Palette.Muted }
    Row(
        modifier = Modifier.clip(RoundedCornerShape(50)).border(1.dp, Palette.Line, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Text(c.text, color = color, fontSize = 11.5.sp, modifier = Modifier.padding(start = 6.dp), maxLines = 1)
    }
}

private fun blendOver(top: Color, bottom: Color): Color {
    val a = top.alpha
    return Color(top.red * a + bottom.red * (1 - a), top.green * a + bottom.green * (1 - a), top.blue * a + bottom.blue * (1 - a), 1f)
}

/**
 * The message box of the web UI (#composer): a rounded glass card with the "+" button, the text area, mic and send. While it has
 * focus or text the card "opens" and shows the Attach / Tools / model row under the text.
 */
@Composable
fun Composer(
    value: String, focus: FocusRequester, onValue: (String) -> Unit, recording: Boolean, busy: Boolean,
    onSend: () -> Unit, onMic: () -> Unit, onPlus: () -> Unit, onTools: () -> Unit, onModel: () -> Unit, onHeight: (Dp) -> Unit,
) {
    val context = LocalContext.current
    var focused by remember { mutableStateOf(false) }
    val open = focused || value.isNotEmpty()
    val shape = RoundedCornerShape(24.dp)
    val border = if (open) blendOver(Palette.Accent.copy(alpha = 0.45f), Palette.Line2.copy(alpha = 1f)) else Palette.Line
    val density = androidx.compose.ui.platform.LocalDensity.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 10.dp, bottom = 8.dp)
            .onSizeChanged { size -> onHeight(with(density) { size.height.toDp() }) }
            .clip(shape)
            .background(Palette.Glass)
            .border(1.dp, border, shape),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                modifier = Modifier.size(38.dp).clip(CircleShape).border(1.dp, Palette.Line, CircleShape).clickable { onPlus() },
                contentAlignment = Alignment.Center,
            ) { Text("+", color = Palette.Dim, fontSize = 22.sp) }
            Box(modifier = Modifier.weight(1f).padding(horizontal = 4.dp, vertical = 8.dp)) {
                if (value.isEmpty()) Text("Ask FAZE anything...", color = Palette.Muted, fontSize = 16.sp)
                BasicTextField(
                    value = value,
                    onValueChange = onValue,
                    textStyle = TextStyle(color = Palette.Text, fontSize = 16.sp, lineHeight = 24.sp),
                    cursorBrush = SolidColor(Palette.Accent),
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 140.dp).focusRequester(focus).onFocusChanged { focused = it.isFocused },
                )
            }
            Box(
                modifier = Modifier.size(38.dp).clip(CircleShape).border(1.dp, if (recording) Palette.Warn else Palette.Line, CircleShape).clickable { onMic() },
                contentAlignment = Alignment.Center,
            ) { Text(if (recording) "Done" else "🎤", color = if (recording) Palette.Warn else Palette.Dim, fontSize = if (recording) 11.sp else 16.sp) }
            Box(
                modifier = Modifier.size(38.dp).clip(CircleShape).background(accentGradient()).clickable { onSend() },
                contentAlignment = Alignment.Center,
            ) { Text(if (busy) "■" else "↑", color = Palette.AccentInk, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold) }
        }
        AnimatedVisibility(visible = open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Row(modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                ToolChip("📎", "Attach", soon = true) { soonToast(context) }
                ToolChip("⚙", "Tools") { onTools() }
                ToolChip("◎", ModelSheet.label(core.provider), accentGlyph = true) { onModel() }
            }
        }
    }
}

@Composable
private fun ToolChip(glyph: String, label: String, accentGlyph: Boolean = false, soon: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50)).background(Palette.Faint).border(1.dp, Palette.Line, RoundedCornerShape(50))
            .let { if (soon) it.alpha(0.5f) else it }
            .clickable { onClick() }.padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(glyph, color = if (accentGlyph) Palette.Accent else Palette.Dim, fontSize = 13.sp)
        Text(label, color = Palette.Dim, fontSize = 12.8.sp, fontWeight = FontWeight.Medium)
    }
}

// ------------------------------------------------------------------------------------------------ menus and the model sheet

private val MenuShape = RoundedCornerShape(22.dp)

@Composable
private fun PopMenu(visible: Boolean, alignment: Alignment, bottomPad: Dp, onDismiss: () -> Unit, minWidth: Dp, content: @Composable () -> Unit) {
    if (visible) Box(modifier = Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss() })
    Box(modifier = Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, bottom = bottomPad), contentAlignment = alignment) {
        AnimatedVisibility(visible = visible, enter = scaleIn(initialScale = 0.85f) + fadeIn(), exit = scaleOut(targetScale = 0.9f) + fadeOut()) {
            Column(modifier = Modifier.widthIn(min = minWidth).clip(MenuShape).background(Palette.Surface).border(1.dp, Palette.Line2, MenuShape).padding(6.dp)) { content() }
        }
    }
}

@Composable
private fun MenuItem(label: String, value: String? = null, soon: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).let { if (soon) it.alpha(0.5f) else it }.clickable { onClick() }.padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(label, color = Palette.Dim, modifier = Modifier.weight(1f))
        if (value != null) Text(value, color = Palette.Accent, fontWeight = FontWeight.Medium)
    }
}

/** The "+" menu (#menu): voice mode, thinking, activity log, new chat, settings. */
@Composable
fun PlusMenu(core: FazeCore, visible: Boolean, bottomPad: Dp, onDismiss: () -> Unit, onThinking: () -> Unit, onNew: () -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    PopMenu(visible, Alignment.BottomEnd, bottomPad, onDismiss, 220.dp) {
        MenuItem("Voice mode", "off", soon = true) { onDismiss(); soonToast(context) }
        MenuItem("Thinking", core.settings.thinkLevel) { onDismiss(); onThinking() }
        MenuItem("Activity log", soon = true) { onDismiss(); soonToast(context) }
        MenuItem("New chat") { onDismiss(); onNew() }
        MenuItem("Settings") { onDismiss(); onSettings() }
    }
}

private val QUICK_INSERTS = listOf(
    "Search the web" to "search the web for ", "Ask Gemini" to "ask gemini ", "Battery" to "whats my battery",
    "Time" to "whats the time", "Clipboard" to "read my clipboard", "Vibrate" to "vibrate my phone",
)

/** The Tools menu (#tools-menu): ready-made messages dropped into the text box. */
@Composable
fun ToolsMenu(visible: Boolean, bottomPad: Dp, onDismiss: () -> Unit, onInsert: (String) -> Unit) {
    val context = LocalContext.current
    PopMenu(visible, Alignment.BottomStart, bottomPad, onDismiss, 210.dp) {
        for ((label, insert) in QUICK_INSERTS) {
            val soon = label == "Ask Gemini"
            MenuItem(label, soon = soon) { onDismiss(); if (soon) soonToast(context) else onInsert(insert) }
        }
    }
}

/** The model sheet (#model-sheet): the models with their status dots, the thinking level, and a hint under each. */
@Composable
fun ModelSheetView(core: FazeCore, visible: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var groqModel by remember { mutableStateOf(core.settings.modelFor("groq")) }
    var think by remember { mutableStateOf(core.settings.thinkLevel) }
    val keys = ModelSheet.live.filter { core.settings.hasKey(it) }.toSet()
    val rows = ModelSheet.rows(keys, ModelSheet.live.associateWith { if (it == "groq") groqModel else core.settings.modelFor(it) })
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        Box(modifier = Modifier.fillMaxSize().background(Palette.Scrim).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss() })
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(visible = visible, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
            val sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
            Column(
                modifier = Modifier
                    .widthIn(max = 580.dp).fillMaxWidth().heightIn(max = 640.dp)
                    .clip(sheet).background(Palette.Bg1).border(1.dp, Palette.Line2, sheet)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
            ) {
                Box(Modifier.align(Alignment.CenterHorizontally).size(width = 38.dp, height = 4.dp).clip(RoundedCornerShape(3.dp)).background(Palette.Line2))
                SheetHeading("Model")
                for (row in rows) {
                    val on = row.id == core.provider
                    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (on) Palette.GlowSoft else Palette.Faint)
                                .border(1.dp, if (on) Palette.Accent else Palette.Line, RoundedCornerShape(14.dp))
                                .let { if (row.available) it else it.alpha(0.5f) }
                                .clickable { if (row.available) { core.useProvider(row.id); onDismiss() } else soonToast(context) }
                                .padding(horizontal = 12.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            val dot = when (row.dot) { "ok" -> Palette.Ok; "warn" -> Palette.Warn; "bad" -> Palette.Bad; else -> Palette.Muted }
                            Box(Modifier.size(9.dp).clip(CircleShape).background(dot))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(row.name, color = Palette.Text)
                                Text(row.sub, color = Palette.Muted, fontSize = 12.5.sp)
                            }
                            if (!row.available) SoonPill()
                        }
                        if (row.id != "auto") {
                            Box(
                                modifier = Modifier.size(42.dp).clip(CircleShape).border(1.dp, Palette.Line, CircleShape).background(Palette.Faint).alpha(0.5f).clickable { soonToast(context) },
                                contentAlignment = Alignment.Center,
                            ) { Text("▾", color = Palette.Muted, fontSize = 15.sp) }
                        }
                    }
                }
                Hint("Tap a model to stay on it until you turn it off. Tap the ✓ or arrow to pick how long instead.")
                SheetHeading("Groq model")
                Seg(ModelChoices.all.map { it.id to it.label }, groqModel, small = true) { groqModel = it; core.settings.modelName = it }
                Hint(ModelChoices.all.firstOrNull { it.id == groqModel }?.note ?: "")
                SheetHeading("Thinking")
                SelectBox(think, ModelSheet.thinkLevels, enabled = ModelSheet.thinkEnabled(core.provider), modifier = Modifier.fillMaxWidth()) { think = it; core.settings.thinkLevel = it }
                Hint(ModelSheet.thinkHint(core.provider))
            }
        }
    }
}

@Composable
private fun SheetHeading(text: String) {
    Text(text.uppercase(), color = Palette.Muted, fontSize = 11.2.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.57.sp, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
}
