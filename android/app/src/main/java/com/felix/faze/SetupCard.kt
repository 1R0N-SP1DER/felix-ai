package com.felix.faze

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** One permission or setting Faze needs for a feature, whether it is on, and what it unlocks. */
data class SetupItem(val id: String, val title: String, val unlocks: String, val ok: Boolean)

/** The checklist as plain data, so it is tested without a phone. [hasRoot] true makes the non-root-only items optional ("not needed with root"). */
object SetupChecks {
    fun items(accessibility: Boolean, capture: Boolean, allFiles: Boolean, termuxInstalled: Boolean, termuxAllowed: Boolean, microphone: Boolean): List<SetupItem> = listOf(
        SetupItem("accessibility", "Accessibility service", "touch, keys, typing and screenshots for a computer or phone controlling this one", accessibility),
        SetupItem("capture", "Screen capture", "live video of this screen and this phone's sound", capture),
        SetupItem("files", "Access to all files", "sharing models and receiving files in Downloads", allFiles),
        SetupItem("mic", "Microphone", "this phone's mic for a computer or phone, and capturing sound", microphone),
        SetupItem("termux", "Termux commands", if (termuxInstalled) "starting offline models without root" else "starting offline models without root (Termux is not installed)", termuxInstalled && termuxAllowed),
    )

    /** "3 of 5 ready", or a plain all-set line. */
    fun summary(items: List<SetupItem>): String {
        val n = items.count { it.ok }
        return if (n == items.size) "Everything is set up" else "$n of ${items.size} ready"
    }
}

@Composable
fun SetupCard() {
    val context = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }
    val micAsk = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val captureAsk = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val d = r.data
        if (r.resultCode == android.app.Activity.RESULT_OK && d != null) ContextCompat.startForegroundService(context, Intent(context, SoundService::class.java).putExtra("code", r.resultCode).putExtra("data", d))
        tick++
    }
    val items = remember(tick) { state(context) }
    SettingsCard("Set up without root") {
        Hint("With root none of this is needed. Without it, each of these unlocks something. " + SetupChecks.summary(items) + ".")
        for (i in items) {
            Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.size(10.dp).clip(CircleShape).background(if (i.ok) Color(0xFF5EE6A8) else Color(0xFF6D7A98))) {}
                Column(Modifier.weight(1f)) {
                    Text(i.title, color = Palette.Text, fontSize = 14.sp)
                    Text(i.unlocks, color = Palette.Muted, fontSize = 12.sp)
                }
                if (!i.ok) Text("Fix", color = Palette.Accent, fontSize = 14.sp, modifier = Modifier.clickable {
                    when (i.id) {
                        "accessibility" -> open(context, Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
                        "capture" -> captureAsk.launch(context.getSystemService(android.media.projection.MediaProjectionManager::class.java).createScreenCaptureIntent())
                        "files" -> open(context, if (Build.VERSION.SDK_INT >= 30) Intent(AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + context.packageName)) else appSettings(context))
                        "mic" -> micAsk.launch(Manifest.permission.RECORD_AUDIO)
                        "termux" -> open(context, appSettings(context))
                    }
                }.padding(8.dp))
            }
        }
    }
}

private fun appSettings(c: Context) = Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + c.packageName))

private fun open(c: Context, i: Intent) { try { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { } }

private fun state(c: Context): List<SetupItem> = SetupChecks.items(
    accessibility = a11yEnabled(c),
    capture = ProjectionVideo.ready(),
    allFiles = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else c.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED,
    termuxInstalled = TermuxRun.installed(c),
    termuxAllowed = TermuxRun.allowed(c),
    microphone = c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
)
