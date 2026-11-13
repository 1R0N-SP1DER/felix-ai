package com.felix.faze

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private fun copyToClipboard(context: android.content.Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("faze", text))
}

/** The 2 dp line down the left of a turn. [kind]: "user" fades from the accent, "faze" is a hairline, "pending" is the moving glow. */
@Composable
private fun Modifier.gutter(kind: String): Modifier {
    val shift = if (kind == "pending" && LocalMotion.current) {
        val t = rememberInfiniteTransition(label = "gutter")
        val v by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "g")
        v
    } else {
        0f
    }
    val accent = Palette.Accent
    val partner = Palette.Blue
    val line = Palette.Line
    return this.drawBehind {
        val w = 2.dp.toPx()
        val h = size.height
        val brush = when (kind) {
            "user" -> Brush.verticalGradient(listOf(accent, Color.Transparent))
            "pending" -> Brush.verticalGradient(listOf(partner, accent, Color.Transparent, partner), startY = h * (shift * 2f - 1f), endY = h * (shift * 2f + 1f))
            else -> Brush.verticalGradient(listOf(line, line))
        }
        drawRoundRect(brush, androidx.compose.ui.geometry.Offset(0f, 2.dp.toPx()), Size(w, (h - 4.dp.toPx()).coerceAtLeast(0f)), CornerRadius(w))
    }
}

@Composable
private fun Who(label: String, glyph: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 4.dp)) {
        if (glyph) Box(Modifier.size(9.dp).rotate(45f).clip(RoundedCornerShape(3.dp)).background(accentGradient()))
        Text(label, color = Palette.Muted, fontSize = 10.9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.74.sp)
    }
}

@Composable
private fun Acts(text: String, core: FazeCore) {
    val context = LocalContext.current
    Row(modifier = Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for ((label, action) in listOf<Pair<String, () -> Unit>>("Copy" to { copyToClipboard(context, text) }, "Listen" to { core.speakText(text) })) {
            Text(label, color = Palette.Dim, fontSize = 11.8.sp, fontWeight = FontWeight.Medium, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { action() }.padding(horizontal = 10.dp, vertical = 4.dp))
        }
    }
}

/** One turn of the conversation, drawn like the web page: no bubbles, a gutter line, a small caps label, then the text. */
@Composable
fun TurnView(m: ChatMessage, pending: Boolean, core: FazeCore) {
    val mine = m.role == "user"
    Column(modifier = Modifier.fillMaxWidth().padding(top = if (mine) 24.dp else 0.dp, bottom = if (mine) 12.dp else 24.dp).rise(0, m.id).gutter(if (mine) "user" else if (pending) "pending" else "faze").padding(start = 16.dp)) {
        if (mine) {
            Who("YOU", false)
            AttachmentRow(m.attachments)
            if (m.text.isNotEmpty()) Text(m.text, color = Palette.Text, fontSize = 17.sp, fontFamily = LocalReadFont.current)
            if (m.text.isNotEmpty()) Acts(m.text, core)
        } else {
            Who("FAZE", true)
            for (step in m.steps) StepRow(step)
            if (pending && m.text.isEmpty() && m.attachments.isEmpty()) Skeleton() else if (m.text.isNotEmpty()) MarkdownBody(m.text)
            AttachmentRow(m.attachments)
            if (!pending && m.text.isNotEmpty()) Acts(m.text, core)
        }
    }
}

@Composable
private fun StepRow(step: String) {
    val failed = step.endsWith("(failed)")
    val name = step.removeSuffix(" (failed)")
    Row(modifier = Modifier.padding(start = 4.dp, top = 3.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(6.dp).clip(RoundedCornerShape(50)).background(if (failed) Palette.Bad else Palette.Ok))
        Text(name, color = Palette.Dim, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    }
}

@Composable
private fun Skeleton() {
    val motion = LocalMotion.current
    val t = rememberInfiniteTransition(label = "skeleton")
    val shift by t.animateFloat(-1f, 2f, infiniteRepeatable(tween(1500, easing = LinearEasing)), label = "s")
    Column(modifier = Modifier.padding(top = 8.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        for (w in listOf(0.92f, 0.74f, 0.48f)) {
            val x = if (motion) shift else 0.5f
            Box(
                Modifier.fillMaxWidth(w).widthIn(max = 520.dp * w).height(11.dp).clip(RoundedCornerShape(6.dp))
                    .background(Brush.horizontalGradient(listOf(Palette.Line, Palette.Line2, Palette.Line), startX = x * 300f, endX = x * 300f + 300f)),
            )
        }
    }
}

private fun styled(spans: List<Span>): AnnotatedString = buildAnnotatedString {
    for (s in spans) when (s.kind) {
        Span.CODE -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 13.8.sp, background = Palette.GlowSoft)) { append(s.text) }
        Span.STRONG -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Color.White)) { append(s.text) }
        Span.EM -> withStyle(SpanStyle(color = Palette.Dim)) { append(s.text) }
        else -> append(s.text)
    }
}

/** The reply text with the same markdown rules as the web page. */
@Composable
fun MarkdownBody(text: String) {
    val font = LocalReadFont.current
    Column {
        for (b in remember(text) { Markdown.parse(text) }) when (b) {
            is Block.Gap -> Box(Modifier.height(9.dp))
            is Block.Heading -> Text(styled(b.spans), color = Palette.Text, fontSize = 17.3.sp, fontWeight = FontWeight.SemiBold, fontFamily = font, modifier = Modifier.padding(top = 17.dp, bottom = 3.dp))
            is Block.Para -> Text(styled(b.spans), color = Palette.Text, fontSize = 16.sp, lineHeight = 27.5.sp, fontFamily = font)
            is Block.Item -> Row(modifier = Modifier.padding(start = 8.dp, top = 2.dp, bottom = 2.dp)) {
                Text(if (b.ordered) "${b.number}." else "•", color = Palette.Accent, fontSize = 16.sp, lineHeight = 27.5.sp, modifier = Modifier.widthIn(min = 22.dp))
                Text(styled(b.spans), color = Palette.Text, fontSize = 16.sp, lineHeight = 27.5.sp, fontFamily = font)
            }
            is Block.Code -> CodeBlock(b)
        }
    }
}

@Composable
private fun CodeBlock(b: Block.Code) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(14.dp)
    Column(modifier = Modifier.padding(vertical = 11.dp).fillMaxWidth().clip(shape).background(Color(0xB8030610)).border(1.dp, Palette.Line, shape)) {
        Row(
            modifier = Modifier.fillMaxWidth().background(Color(0x06FFFFFF)).padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text((b.lang.ifEmpty { "code" }).uppercase(), color = Palette.Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.1.sp, modifier = Modifier.weight(1f))
            Text("Copy", color = Palette.Dim, fontSize = 11.8.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { copyToClipboard(context, b.text) }.padding(horizontal = 10.dp, vertical = 4.dp))
        }
        Text(
            b.text, color = Palette.Text, fontFamily = FontFamily.Monospace, fontSize = 13.4.sp, lineHeight = 21.sp,
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 12.dp),
        )
    }
}

/** The line above the message box (#livebar): the small core and a word for what is happening. Always there, like on the web page. */
@Composable
fun LiveBarView(core: FazeCore, mode: CoreMode) {
    val last = core.messages.lastOrNull()
    val kind = when {
        core.recording || core.heard || core.transcribing || core.speaking || core.status.isNotEmpty() -> ""
        else -> LiveText.stateFor(core.busy, !last?.text.isNullOrEmpty() && last?.role == "assistant", !last?.steps.isNullOrEmpty())
    }
    val text = when {
        core.status.isNotEmpty() -> "\u2726 " + core.status
        core.recording -> "\u25CC Listening… tap Done when you finish"
        core.heard -> "\u25CC Listening…"
        core.transcribing -> "\u25CC Transcribing…"
        core.speaking -> "\u25CC speaking"
        else -> LiveText.label(kind)
    }
    val color = when {
        core.status.isNotEmpty() && !core.recording && !core.transcribing -> Palette.Bad
        kind == "ready" || kind == "" && !core.busy -> Palette.Muted
        else -> Palette.Dim
    }
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp).height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        CoreView(mode, 40.dp)
        Text(text, color = color, fontSize = 12.8.sp, letterSpacing = 0.3.sp, modifier = Modifier.weight(1f))
        if (core.timing.isNotEmpty()) Text(core.timing, color = Palette.Muted, fontSize = 11.sp)
    }
}

/** Pictures show as thumbnails you can see in the chat; files show as a small named chip. */
@Composable
fun AttachmentRow(list: List<Attachment>) {
    if (list.isEmpty()) return
    Column(modifier = Modifier.padding(top = 6.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (a in list) {
            if (a.isImage) {
                val bmp = remember(a.path) { try { android.graphics.BitmapFactory.decodeFile(a.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 })?.asImageBitmap() } catch (e: Exception) { null } }
                if (bmp != null) androidx.compose.foundation.Image(bmp, a.name, modifier = Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(14.dp)).border(1.dp, Palette.Line, RoundedCornerShape(14.dp)))
                else Text("\u25A3 ${a.name} (picture no longer on this phone)", color = Palette.Muted, fontSize = 13.sp)
            } else {
                Text("\uD83D\uDCC4 ${a.name}", color = Palette.Dim, fontSize = 13.sp, modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Palette.GlowSoft).padding(horizontal = 10.dp, vertical = 6.dp))
            }
        }
    }
}
