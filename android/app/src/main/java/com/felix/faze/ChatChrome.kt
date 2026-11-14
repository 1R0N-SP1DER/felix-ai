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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.graphics.asImageBitmap
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
        if (core.speaking || core.busy) IconBox("■") { core.stopSpeaking() }
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
 * The message box (the look of #composer, with the buttons redone): text on top; under it "+" on the left and, on the right, the mic with
 * its menu arrow and one action button that changes with the box: the Live button while it is empty, the send button (a rounded square)
 * once there is text, and a stop square while a reply is being written. Nothing sits under the buttons: the model is picked in the
 * top bar, and Attach and Tools are not in the box.
 */
@Composable
fun Composer(
    value: String, focus: FocusRequester, onValue: (String) -> Unit, recording: Boolean, busy: Boolean,
    onSend: () -> Unit, onMic: () -> Unit, onMicMenu: () -> Unit, onLive: () -> Unit,
    onPlus: () -> Unit, onHeight: (Dp) -> Unit,
    pending: List<Attachment> = emptyList(), onRemove: (Attachment) -> Unit = {},
) {
    val context = LocalContext.current
    var focused by remember { mutableStateOf(false) }
    val open = focused || value.isNotEmpty()
    val shape = RoundedCornerShape(28.dp)
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
        if (pending.isNotEmpty()) Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 14.dp, end = 14.dp, top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (a in pending) AttachmentChip(a) { onRemove(a) }
        }
        Box(modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 6.dp)) {
            if (value.isEmpty()) Text("Ask FAZE anything...", color = Palette.Muted, fontSize = 17.sp)
            BasicTextField(
                value = value,
                onValueChange = onValue,
                textStyle = TextStyle(color = Palette.Text, fontSize = 17.sp, lineHeight = 24.sp),
                cursorBrush = SolidColor(Palette.Accent),
                maxLines = 5,
                modifier = Modifier.fillMaxWidth().heightIn(max = 140.dp).focusRequester(focus).onFocusChanged { focused = it.isFocused },
            )
        }
        Row(modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 12.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(44.dp).clip(CircleShape).clickable { onPlus() }, contentAlignment = Alignment.Center) { FazeIcon(Ico.PLUS, Palette.Dim, 24.dp) }
            Spacer(Modifier.weight(1f))
            if (recording) Text("Done", color = Palette.Warn, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(end = 2.dp))
            Box(modifier = Modifier.size(44.dp).clip(CircleShape).clickable { onMic() }, contentAlignment = Alignment.Center) {
                FazeIcon(Ico.MIC, if (recording) Palette.Warn else Palette.Text, 24.dp)
            }
            Box(modifier = Modifier.size(width = 28.dp, height = 44.dp).clip(CircleShape).clickable { onMicMenu() }, contentAlignment = Alignment.Center) {
                FazeIcon(Ico.CHEVRON, Palette.Muted, 16.dp)
            }
            Spacer(Modifier.size(6.dp))
            when {
                busy -> ActionSquare(Ico.STOP) { onSend() }
                value.isNotBlank() || pending.isNotEmpty() -> ActionSquare(Ico.ARROW_UP) { onSend() }
                else -> Box(modifier = Modifier.size(44.dp).clip(CircleShape).clickable { onLive() }, contentAlignment = Alignment.Center) {
                    FazeIcon(Ico.WAVE, Palette.Text, 26.dp)
                }
            }
        }
    }
}

/** The send / stop button: a rounded square in the accent colors. */
@Composable
private fun ActionSquare(icon: Ico, onClick: () -> Unit) {
    val square = RoundedCornerShape(14.dp)
    Box(modifier = Modifier.size(46.dp).clip(square).background(accentGradient()).clickable { onClick() }, contentAlignment = Alignment.Center) {
        FazeIcon(icon, Palette.AccentInk, 24.dp)
    }
}

/** The menu behind the arrow next to the mic: the two spoken conversations. */
@Composable
fun MicMenu(visible: Boolean, bottomPad: Dp, onDismiss: () -> Unit, onVoiceChat: () -> Unit, onLive: () -> Unit) {
    PopMenu(visible, Alignment.BottomEnd, bottomPad, onDismiss, 230.dp) {
        MenuItem("Voice chat", "Whisper") { onDismiss(); onVoiceChat() }
        MenuItem("Live voice", "Gemini") { onDismiss(); onLive() }
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

/** The "+" menu: what can be attached to the next message. */
@Composable
fun PlusMenu(visible: Boolean, bottomPad: Dp, onDismiss: () -> Unit, onPhotos: () -> Unit, onCamera: () -> Unit, onFiles: () -> Unit) {
    PopMenu(visible, Alignment.BottomStart, bottomPad, onDismiss, 200.dp) {
        MenuItem("Photos") { onDismiss(); onPhotos() }
        MenuItem("Camera") { onDismiss(); onCamera() }
        MenuItem("Files") { onDismiss(); onFiles() }
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
    var expanded by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    core.modelRev // reading it redraws the sheet when a model is picked
    var think by remember { mutableStateOf(core.settings.thinkLevel) }
    var effort by remember { mutableStateOf(core.settings.effortLevel) }
    var length by remember { mutableStateOf(core.settings.lengthStep.toFloat()) }
    val keys = ModelSheet.live.filter { core.settings.hasKey(it) }.toSet()
    val rows = ModelSheet.rows(keys, ModelSheet.live.associateWith { core.settings.modelFor(it) })
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
                            val open = expanded == row.id
                            Box(
                                modifier = Modifier.size(42.dp).clip(CircleShape).border(1.dp, if (open) Palette.Accent else Palette.Line, CircleShape).background(Palette.Faint)
                                    .let { if (row.available) it else it.alpha(0.5f) }
                                    .clickable {
                                        if (!row.available) soonToast(context)
                                        else if (open) expanded = null
                                        else { expanded = row.id; query = ""; core.loadModels(row.id) }
                                    },
                                contentAlignment = Alignment.Center,
                            ) { Text(if (open) "▴" else "▾", color = if (open) Palette.Accent else Palette.Muted, fontSize = 15.sp) }
                        }
                    }
                    if (expanded == row.id) ModelPicker(core, row.id, query, { query = it }) { id -> core.pickModel(row.id, id); expanded = null; onDismiss() }
                }
                Hint("Tap a model to stay on it until you turn it off. Tap the ✓ or arrow to pick how long instead.")
                SheetHeading("Thinking")
                SelectBox(think, ModelSheet.thinkLevels, enabled = ModelSheet.thinkEnabled(core.provider), modifier = Modifier.fillMaxWidth()) { think = it; core.settings.thinkLevel = it }
                Hint(ModelSheet.thinkHint(core.provider))
                SheetHeading("Effort level")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Low", color = Palette.Muted, fontSize = 12.sp)
                    Slider(
                        value = Effort.index(effort).toFloat(),
                        onValueChange = { effort = Effort.at(it.toInt()); core.settings.effortLevel = effort },
                        valueRange = 0f..(Effort.levels.size - 1).toFloat(),
                        steps = Effort.levels.size - 2,
                        colors = SliderDefaults.colors(thumbColor = Palette.Accent, activeTrackColor = Palette.Accent, inactiveTrackColor = Palette.Line2),
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    Text("Max", color = Palette.Muted, fontSize = 12.sp)
                }
                Hint(Effort.label(effort) + ". " + Effort.hint(effort))
                SheetHeading("Response length")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Short", color = Palette.Muted, fontSize = 12.sp)
                    Slider(
                        value = length,
                        onValueChange = { length = it; core.settings.lengthStep = it.toInt() },
                        valueRange = 0f..(ResponseLength.STOPS - 1).toFloat(),
                        steps = ResponseLength.STOPS - 2,
                        colors = SliderDefaults.colors(thumbColor = Palette.Accent, activeTrackColor = Palette.Accent, inactiveTrackColor = Palette.Line2),
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    Text("Long", color = Palette.Muted, fontSize = 12.sp)
                }
                Hint(ResponseLength.label(length.toInt()) + ". " + ResponseLength.hint(length.toInt()))
            }
        }
    }
}

@Composable
private fun SheetHeading(text: String) {
    Text(text.uppercase(), color = Palette.Muted, fontSize = 11.2.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.57.sp, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
}

/** The models one provider says this key can use, with a filter box. Picking one makes it the answering model. */
@Composable
private fun ModelPicker(core: FazeCore, provider: String, query: String, onQuery: (String) -> Unit, onPick: (String) -> Unit) {
    val all = core.modelLists[provider]
    val current = core.settings.modelFor(provider)
    val shape = RoundedCornerShape(14.dp)
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(shape).background(Palette.Faint).border(1.dp, Palette.Line, shape).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(Palette.Faint).border(1.dp, Palette.Line, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 7.dp)) {
                if (query.isEmpty()) Text("Filter models", color = Palette.Muted, fontSize = 14.sp)
                BasicTextField(value = query, onValueChange = onQuery, singleLine = true, textStyle = TextStyle(color = Palette.Text, fontSize = 14.sp), cursorBrush = SolidColor(Palette.Accent), modifier = Modifier.fillMaxWidth())
            }
            Text("Refresh", color = Palette.Accent, fontSize = 13.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { core.loadModels(provider, true) }.padding(6.dp))
        }
        when {
            core.modelLoading == provider -> Text(if (provider == "offline") "Looking for models on your phone…" else "Loading the models your key can use…", color = Palette.Muted, fontSize = 13.sp)
            all == null -> Text(core.modelError.ifEmpty { "No list yet." }, color = if (core.modelError.isEmpty()) Palette.Muted else Palette.Bad, fontSize = 13.sp)
            else -> {
                val shown = ModelLists.filter(all, query)
                Column(modifier = Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                    for (o in shown) {
                        val on = o.id == current
                        Row(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onPick(o.id) }.padding(horizontal = 8.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(o.label, color = if (on) Palette.Accent else Palette.Text, fontSize = 14.5.sp, modifier = Modifier.weight(1f))
                            if (o.note.isNotEmpty()) Text(o.note, color = Palette.Muted, fontSize = 11.5.sp)
                            if (on) Text("✓", color = Palette.Accent)
                        }
                    }
                    if (shown.isEmpty()) Text("Nothing matches.", color = Palette.Muted, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
                    if (all.size > shown.size && query.isEmpty()) Text("Showing ${shown.size} of ${all.size}. Type to narrow it down.", color = Palette.Muted, fontSize = 11.5.sp, modifier = Modifier.padding(8.dp))
                }
            }
        }
    }
}

/** A queued attachment in the message box: a thumbnail (or the file name) and a cross to take it back out. */
@Composable
private fun AttachmentChip(a: Attachment, onRemove: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier.clip(shape).background(Palette.Faint).border(1.dp, Palette.Line, shape).padding(start = 6.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val bmp = if (a.isImage) remember(a.path) { try { android.graphics.BitmapFactory.decodeFile(a.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 })?.asImageBitmap() } catch (e: Exception) { null } } else null
        if (bmp != null) androidx.compose.foundation.Image(bmp, a.name, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)))
        Text(a.name.take(22), color = Palette.Dim, fontSize = 12.5.sp)
        Text("\u2715", color = Palette.Muted, fontSize = 13.sp, modifier = Modifier.clip(CircleShape).clickable { onRemove() }.padding(horizontal = 8.dp, vertical = 6.dp))
    }
}
