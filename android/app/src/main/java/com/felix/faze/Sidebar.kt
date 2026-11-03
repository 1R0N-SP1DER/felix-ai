package com.felix.faze

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Calendar

private fun startOfToday(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

/**
 * The chats sidebar of the web UI (#sidebar): the brand with its little core, a collapse button, New chat, a search box, the
 * collapsible Tools and Actions sections, chats grouped by age, and Settings with the profile at the bottom.
 * Width is min(86% of the screen, 330 dp), as on the page for phones.
 */
@Composable
fun ChatDrawer(core: FazeCore, onNew: () -> Unit, onOpen: (String) -> Unit, onSettings: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val cfg = LocalConfiguration.current
    var query by remember { mutableStateOf("") }
    var toolsOpen by remember { mutableStateOf(false) }
    var actionsOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<ChatRecord?>(null) }
    var deleting by remember { mutableStateOf<ChatRecord?>(null) }
    val tools = PermissionPolicy.all()
    val groups = ChatGroups.group(core.chats.toList(), startOfToday(), query)
    val name = core.settings.user.ifEmpty { "You" }

    ModalDrawerSheet(
        modifier = Modifier.width(minOf(cfg.screenWidthDp * 0.86f, 330f).dp),
        drawerContainerColor = Palette.Bg1.copy(alpha = 0.88f),
        drawerShape = RectangleShape,
    ) {
        Column(modifier = Modifier.fillMaxHeight().padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // brand + collapse
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CoreView(CoreMode.IDLE, 52.dp, Modifier.offset(x = (-8).dp))
                    Text("FAZE", color = Palette.Text, fontWeight = FontWeight.Bold, fontSize = 13.8.sp, letterSpacing = 4.4.sp, modifier = Modifier.offset(x = (-16).dp))
                }
                Box(modifier = Modifier.weight(1f))
                IconButtonText("«", onClose)
            }

            // New chat
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Brush.linearGradient(listOf(Palette.GlowSoft, androidx.compose.ui.graphics.Color.Transparent)))
                    .border(1.dp, Palette.Line2, RoundedCornerShape(14.dp))
                    .clickable { onNew() }
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("+", color = Palette.Accent, fontSize = 20.sp)
                Text("New chat", color = Palette.Text, fontWeight = FontWeight.SemiBold)
            }

            // search
            Box(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.Faint).border(1.dp, Palette.Line, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 9.dp),
            ) {
                if (query.isEmpty()) Text("Search chats", color = Palette.Muted)
                BasicTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    textStyle = TextStyle(color = Palette.Text, fontSize = 16.sp), cursorBrush = SolidColor(Palette.Accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Tools and Actions
            SideSection("Tools", tools.size, toolsOpen, { toolsOpen = !toolsOpen }) {
                for ((tool, tier) in tools) {
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(tool, color = Palette.Dim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (tier == Tier.CONFIRM) Text("!", color = Palette.Warn, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 6.dp))
                    }
                }
            }
            SideSection("Actions", 3, actionsOpen, { actionsOpen = !actionsOpen }) {
                QuickActions(listOf("Open app", "Play on YouTube", "Open link")) { soonToast(context) }
            }

            // chats
            LazyColumn(modifier = Modifier.weight(1f)) {
                for (group in groups) {
                    item(key = "h-" + group.title) {
                        Text(
                            group.title.uppercase(), color = Palette.Muted, fontSize = 10.9.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp,
                            modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                        )
                    }
                    items(group.items, key = { it.id }) { chat ->
                        ChatRow(
                            chat, chat.id == core.chatId,
                            onOpen = { onOpen(chat.id) }, onRename = { renaming = chat }, onMove = { soonToast(context) }, onDelete = { deleting = chat },
                        )
                    }
                    item(key = "s-" + group.title) { Box(Modifier.size(16.dp)) }
                }
            }

            // Settings + profile
            Row(
                modifier = Modifier.fillMaxWidth().drawBehind { drawLine(Palette.Line, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }.padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Settings", color = Palette.Dim, modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable { onSettings() }.padding(horizontal = 12.dp, vertical = 9.dp))
                Box(modifier = Modifier.weight(1f))
                Row(
                    modifier = Modifier.widthIn(max = 150.dp).clip(RoundedCornerShape(50)).clickable { onSettings() }.padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(modifier = Modifier.size(30.dp).clip(CircleShape).background(accentGradient()), contentAlignment = Alignment.Center) {
                        Text(name.take(1).uppercase(), color = Palette.AccentInk, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                    Text(name, color = Palette.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }

    renaming?.let { chat -> RenameDialog(chat.title, onDismiss = { renaming = null }) { core.renameChat(chat.id, it); renaming = null } }
    deleting?.let { chat ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete this chat?") },
            text = { Text(chat.title, color = Palette.Dim) },
            confirmButton = { TextButton(onClick = { core.deleteChat(chat.id); deleting = null }) { Text("Delete", color = Palette.Bad) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun IconButtonText(glyph: String, onClick: () -> Unit) {
    Box(modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).clickable { onClick() }, contentAlignment = Alignment.Center) {
        Text(glyph, color = Palette.Dim, fontSize = 18.sp)
    }
}

/** A collapsible group in the sidebar (.side-sec): uppercase label, a count badge and a chevron that turns when open. */
@Composable
private fun SideSection(label: String, count: Int, open: Boolean, onToggle: () -> Unit, body: @Composable () -> Unit) {
    val turn by animateFloatAsState(if (open) 90f else 0f, label = "chevron")
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onToggle() }.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(label.uppercase(), color = Palette.Dim, fontSize = 10.9.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp, modifier = Modifier.weight(1f))
            Text(count.toString(), color = Palette.Muted, fontSize = 11.8.sp, modifier = Modifier.clip(RoundedCornerShape(50)).border(1.dp, Palette.Line, RoundedCornerShape(50)).padding(horizontal = 8.dp))
            Text("▸", color = Palette.Muted, fontSize = 12.8.sp, modifier = Modifier.rotate(turn))
        }
        if (open) Column(modifier = Modifier.padding(top = 2.dp, bottom = 6.dp).rise(0, label)) { body() }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickActions(labels: List<String>, onClick: (String) -> Unit) {
    FlowRow(modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (l in labels) {
            Text(
                l, color = Palette.Dim, fontSize = 12.5.sp,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Palette.Faint).border(1.dp, Palette.Line, RoundedCornerShape(50)).clickable { onClick(l) }.padding(horizontal = 11.dp, vertical = 5.dp),
            )
        }
    }
}

@Composable
private fun ChatRow(chat: ChatRecord, on: Boolean, onOpen: () -> Unit, onRename: () -> Unit, onMove: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .let { if (on) it.background(Palette.GlowSoft).drawBehind { drawRect(Palette.Accent, Offset.Zero, Size(2.dp.toPx(), size.height)) } else it }
            .clickable { onOpen() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            chat.title, color = if (on) Palette.Text else Palette.Dim, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 9.dp),
        )
        ActionGlyph("✎", Palette.Muted, onRename)
        ActionGlyph("▣", Palette.Muted, onMove, soon = true)
        ActionGlyph("✕", Palette.Muted, onDelete)
    }
}

@Composable
private fun ActionGlyph(glyph: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit, soon: Boolean = false) {
    Box(
        modifier = Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).let { if (soon) it.alpha(0.5f) else it }.clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) { Text(glyph, color = color, fontSize = 12.sp) }
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename chat") },
        text = {
            Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Palette.Faint).border(1.dp, Palette.Line2, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 8.dp)) {
                BasicTextField(value = text, onValueChange = { text = it }, singleLine = true, textStyle = TextStyle(color = Palette.Text, fontSize = 16.sp), cursorBrush = SolidColor(Palette.Accent), modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
