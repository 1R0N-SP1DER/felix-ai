package com.felix.faze

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The full-screen live conversation (live mode or voice chat), like a call: the bar along the bottom takes the place of the message box,
 * and the stage above it is where FAZE will show what it is making (illustrations, diagrams, code); until then the morphing core
 * and what it is doing fill it. The bar holds: camera and screen share (planned, dimmed), the listening bar that moves with your voice, mute, and close.
 */
@Composable
fun LiveScreen(core: FazeCore) {
    BackHandler { core.closeLiveScreen() }
    val context = LocalContext.current
    val speaking = (core.liveOn && core.liveState == LiveSession.SPEAKING) || (core.voiceOn && core.speaking)
    val connecting = core.liveOn && core.liveState.let { it.isEmpty() || it.startsWith("Connecting") }
    val mode = when {
        speaking -> CoreMode.SPEAKING
        connecting || (core.voiceOn && (core.busy || core.voiceState == "Thinking…" || core.voiceState == "Transcribing…")) -> CoreMode.THINKING
        else -> CoreMode.LISTENING
    }
    val state = if (core.liveOn) core.liveState else core.voiceState
    fun toast(text: String) = android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_LONG).show()
    fun startShare(mode: String) = core.startShare(mode) { failed -> if (failed != null) toast(failed) }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startShare(ShareMode.CAMERA) else toast("Allow the camera to show it to Faze")
    }
    fun toggleShare(mode: String) {
        when {
            core.shareMode == mode -> core.stopShare()
            !core.liveOn -> toast("Camera and screen share work in live mode (Gemini)")
            mode == ShareMode.CAMERA && context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED -> cameraPermission.launch(Manifest.permission.CAMERA)
            else -> startShare(mode)
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.Bg) // solid, so the chat and its message box never show through
            .background(Brush.verticalGradient(0f to Palette.Bg, 0.55f to Palette.Bg1, 1f to Palette.Accent.copy(alpha = 0.26f)))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        val statusLine = if (core.micMuted) "Muted" else state.ifEmpty { "Live" } + when (core.shareMode) { ShareMode.CAMERA -> " · sharing camera"; ShareMode.SCREEN -> " · sharing screen"; else -> "" }
        if (core.shareMode == ShareMode.CAMERA) {
            // the camera is the background: you see what Faze sees, and the logo steps aside
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        core.showCameraPreview(surfaceProvider)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            DisposableEffect(Unit) { onDispose { core.showCameraPreview(null) } }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.45f), 0.22f to Color.Transparent, 0.65f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.7f))))
            Text(
                statusLine,
                color = if (core.micMuted) Palette.Warn else Color.White, fontSize = 14.sp, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 14.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.4f)).padding(horizontal = 14.dp, vertical = 6.dp),
            )
        } else {
            Column(modifier = Modifier.align(Alignment.Center).padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CoreView(mode, 150.dp)
                Spacer(Modifier.height(22.dp))
                Text(statusLine, color = if (core.micMuted) Palette.Warn else Palette.Dim, fontSize = 15.sp, textAlign = TextAlign.Center)
            }
        }
        Row(
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 14.dp, end = 14.dp, bottom = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RoundButton(Ico.CAMERA, tint = if (core.shareMode == ShareMode.CAMERA) Palette.Accent else Palette.Text) { toggleShare(ShareMode.CAMERA) }
            RoundButton(Ico.SCREEN, tint = if (core.shareMode == ShareMode.SCREEN) Palette.Accent else Palette.Text) { toggleShare(ShareMode.SCREEN) }
            ListeningBar(if (speaking) core.outLevel else core.micLevel, core.micMuted, Modifier.weight(1f).height(76.dp))
            RoundButton(if (core.micMuted) Ico.MIC_OFF else Ico.MIC, tint = if (core.micMuted) Palette.Warn else Palette.Text) { core.toggleMute() }
            RoundButton(Ico.CLOSE) { core.closeLiveScreen() }
        }
    }
}

@Composable
private fun RoundButton(icon: Ico, tint: Color = Palette.Text, soon: Boolean = false, size: Dp = 56.dp, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(size).clip(CircleShape).background(Palette.Surface2).border(1.dp, Palette.Line, CircleShape)
            .let { if (soon) it.alpha(0.45f) else it }
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) { FazeIcon(icon, tint, 24.dp) }
}

/** The pill in the middle of the bottom bar: bars that rise with your voice (or with the voice you are hearing). */
@Composable
fun ListeningBar(level: Float, muted: Boolean, modifier: Modifier = Modifier) {
    val smooth by animateFloatAsState(if (muted) 0f else level, tween(60), label = "level")
    val ripple = rememberInfiniteTransition(label = "ripple")
    val moving by ripple.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    val phase = if (LocalMotion.current) moving else 0f
    val shape = RoundedCornerShape(50)
    val accent = Palette.Accent
    val partner = Palette.Blue
    Canvas(modifier = modifier.clip(shape).background(Brush.verticalGradient(listOf(Palette.Bg, Palette.Surface))).border(1.dp, Palette.Line2, shape)) {
        drawRect(
            Brush.verticalGradient(listOf(Color.Transparent, accent.copy(alpha = 0.32f))),
            topLeft = Offset(0f, size.height * 0.5f), size = Size(size.width, size.height * 0.5f),
        )
        val n = 23
        val heights = LiveBars.heights(smooth, phase, n)
        val gap = size.width / (n + 1)
        val maxH = size.height * 0.88f
        val brush = Brush.horizontalGradient(listOf(accent, partner), startX = 0f, endX = size.width)
        for (i in 0 until n) {
            val h = heights[i] * maxH
            val x = gap * (i + 1)
            drawLine(brush, Offset(x, size.height / 2f - h / 2f), Offset(x, size.height / 2f + h / 2f), strokeWidth = gap * 0.42f, cap = StrokeCap.Round)
        }
    }
}
