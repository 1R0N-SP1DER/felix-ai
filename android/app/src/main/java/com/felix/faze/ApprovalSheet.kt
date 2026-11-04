package com.felix.faze

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The approval dialog (#approval-sheet): centred over a dim scrim, with Deny and Approve. Nothing runs until Approve is pressed.
 * A tap outside does not answer (so it can't be answered by accident); the Back button means Deny.
 */
@Composable
fun ApprovalSheet(request: ApprovalRequest, onAllow: () -> Unit, onDeny: () -> Unit) {
    val context = LocalContext.current
    val info = remember(request.id) { ApprovalText.build(request.tool, request.summary) }
    BackHandler(enabled = true) { onDeny() }
    Box(
        modifier = Modifier.fillMaxSize().background(Palette.Scrim).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
        contentAlignment = Alignment.Center,
    ) {
        val shape = RoundedCornerShape(28.dp)
        Column(
            modifier = Modifier.padding(16.dp).widthIn(max = 560.dp).fillMaxWidth().clip(shape).background(Palette.Bg1).border(1.dp, Palette.Line2, shape).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(info.title, color = Palette.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(info.name, color = Palette.Dim, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                val high = info.level != "normal"
                Text(
                    info.level, color = if (high) Palette.Warn else Palette.Dim, fontSize = 11.5.sp,
                    modifier = Modifier.clip(RoundedCornerShape(50)).border(1.dp, if (high) Palette.Warn.copy(alpha = 0.5f) else Palette.Line, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 1.dp),
                )
            }
            Text(info.what, color = Palette.Text)
            Text("HOW IT WORKS", color = Palette.Muted, fontSize = 11.2.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.57.sp, modifier = Modifier.padding(top = 8.dp))
            Text(info.how, color = Palette.Dim)
            Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Deny", color = Palette.Dim, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).border(1.dp, Palette.Line2, RoundedCornerShape(14.dp)).clickable { onDeny() }.padding(11.dp),
                )
                Text(
                    "Approve", color = Palette.AccentInk, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(accentGradient()).clickable { onAllow() }.padding(11.dp),
                )
            }
            Text(
                "Approve and stop asking (yolo mode)", color = Palette.Accent, fontSize = 14.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().alpha(0.5f).clickable { soonToast(context) }.padding(top = 4.dp, bottom = 2.dp),
            )
        }
    }
}
