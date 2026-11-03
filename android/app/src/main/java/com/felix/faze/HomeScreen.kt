package com.felix.faze

import android.content.res.Configuration
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val EaseInOutCss = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

private val STARTERS = listOf(
    "Search the web" to "search the web for ",
    "Plan a project" to "Help me plan ",
    "Explain something" to "Explain how ",
)

/**
 * The home screen of the web UI (#home): the floating core, "What are we building?", the line under it (sometimes a greeting) and
 * three starter chips that put their text in the message box. Sizes follow the page's media rules for phones and landscape.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(core: FazeCore, mode: CoreMode, onInsert: (String) -> Unit) {
    val cfg = LocalConfiguration.current
    val phone = cfg.screenWidthDp <= 560
    val shortLandscape = cfg.screenHeightDp <= 520 && cfg.orientation == Configuration.ORIENTATION_LANDSCAPE
    val hero = when {
        shortLandscape -> 110f
        phone -> (0.30f * cfg.screenHeightDp).coerceIn(150f, 230f)
        else -> (0.40f * cfg.screenHeightDp).coerceIn(190f, 360f)
    }
    val sub = remember(core.chatId, core.greetingOn) {
        if (core.greetingOn && Math.random() < 0.5) {
            Greetings.greetingFor(java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY), core.settings.user, "Faze")
        } else {
            "Ask anything. Build anything."
        }
    }
    val floatY = if (LocalMotion.current) {
        val t = rememberInfiniteTransition(label = "float")
        val y by t.animateFloat(0f, -8f, infiniteRepeatable(tween(4500, easing = EaseInOutCss), RepeatMode.Reverse), label = "floatY")
        y
    } else {
        0f
    }
    val headSize = if (shortLandscape) 20.8f else (0.042f * cfg.screenWidthDp).coerceIn(27.2f, 40f)

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = if (phone) 16.dp else 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (phone) 8.dp else 12.dp, Alignment.CenterVertically),
    ) {
        CoreView(mode, hero.dp, Modifier.offset(y = floatY.dp))
        Text(
            "What are we building?",
            color = Palette.Text,
            fontSize = headSize.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.025f * headSize).sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.rise(100),
        )
        Text(sub, color = Palette.Dim, fontSize = 16.sp, textAlign = TextAlign.Center, modifier = Modifier.rise(220))
        if (!shortLandscape) {
            FlowRow(
                modifier = Modifier.padding(top = 12.dp).rise(340),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for ((label, insert) in STARTERS) {
                    Text(
                        label,
                        color = Palette.Dim,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Palette.Faint)
                            .border(1.dp, Palette.Line, RoundedCornerShape(50))
                            .clickable { onInsert(insert) }
                            .padding(horizontal = 15.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}
