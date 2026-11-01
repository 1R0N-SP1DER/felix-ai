package com.felix.faze

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/** The phone side of the tools. Each call is a thin wrapper over one Android API, with the failure explained in words. */
class AndroidDevice(private val context: Context) : DeviceApi {

    override fun now(): String {
        val zone = TimeZone.getDefault()
        val format = SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", Locale.getDefault())
        format.timeZone = zone
        return format.format(Date()) + " (" + zone.id + ")"
    }

    override fun battery(): String {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: throw ToolFailure("battery information is not available")
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) throw ToolFailure("battery information is not available")
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return "Battery is at ${(level * 100f / scale).roundToInt()}%" + if (charging) ", charging" else ", not charging"
    }

    override fun wifi(): String {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: throw ToolFailure("network information is not available")
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        return when {
            caps == null -> "There is no network connection."
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Connected to Wi-Fi."
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Not on Wi-Fi; using mobile data."
            else -> "Connected, not through Wi-Fi."
        }
    }

    override fun setVolume(level: Int): String {
        val audio = context.getSystemService(AudioManager::class.java) ?: throw ToolFailure("the audio service is not available")
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (level / 15f * max).roundToInt(), 0)
        return "Media volume set to $level of 15."
    }

    private fun launcherApps(): List<Pair<String, String>> {
        val pm = context.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(main, 0)
            .map { Pair(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }
    }

    override fun openApp(name: String): String {
        val match = AppMatch.find(name, launcherApps())
            ?: throw ToolFailure("I couldn't find an app called $name. Try list_apps to see what is installed.")
        val intent = context.packageManager.getLaunchIntentForPackage(match.second)
            ?: throw ToolFailure("${match.first} cannot be launched")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return "Opened ${match.first}."
    }

    override fun listApps(search: String): String {
        val q = search.trim().lowercase()
        val apps = launcherApps().map { it.first }.filter { q.isEmpty() || it.lowercase().contains(q) }
        if (apps.isEmpty()) return if (q.isEmpty()) "No launchable apps were found." else "No installed app matches \"$search\"."
        val shown = apps.take(60)
        return "Installed apps (${apps.size}): " + shown.joinToString(", ") + if (apps.size > shown.size) ", and ${apps.size - shown.size} more." else "."
    }

    override fun openUrl(url: String): String {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return "Opened $url."
    }

    override fun readClipboard(): String {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: throw ToolFailure("the clipboard is not available")
        val item = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
        val text = item?.coerceToText(context)?.toString().orEmpty()
        return if (text.isEmpty()) "The clipboard is empty." else text
    }

    override fun writeClipboard(text: String): String {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: throw ToolFailure("the clipboard is not available")
        cm.setPrimaryClip(ClipData.newPlainText("faze", text))
        return "Copied to the clipboard."
    }

    override fun vibrate(): String {
        val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        vibrator.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
        return "Vibrated."
    }
}
