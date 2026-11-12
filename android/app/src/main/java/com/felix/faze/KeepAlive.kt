package com.felix.faze

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * The tools that leave Faze's own screen (open an app, read or drive the screen). Once Faze is behind another app Android may freeze it
 * and cut its connection, so the first of these in a turn starts [WorkService] to keep it running until the turn is over.
 */
class KeepAliveTools(private val inner: ToolRunner, private val start: () -> Unit) : ToolRunner {
    override fun schema(): JSONArray = inner.schema()
    override fun precheck(name: String, args: JSONObject): String? = inner.precheck(name, args)

    override fun execute(name: String, args: JSONObject): ToolResult {
        if (name in WORK_TOOLS) start()
        return inner.execute(name, args)
    }

    companion object {
        val WORK_TOOLS = PermissionPolicy.phoneTools + setOf("open_app", "open_url")
    }
}

/** A small "working" notification and a wake lock while Faze does something on the phone with another app in front. Stopped by Faze when the turn ends. */
class WorkService : Service() {
    private var lock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("work", "Faze working", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = Notification.Builder(this, "work")
            .setSmallIcon(R.drawable.ic_stat_faze)
            .setContentTitle("Faze is working on your phone")
            .setContentText("Tap to open Faze")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        try {
            startForeground(41, n, SPECIAL_USE)
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (lock == null) lock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "faze:work").apply { acquire(10 * 60_000L) }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try { if (lock?.isHeld == true) lock?.release() } catch (e: Exception) { }
        lock = null
        super.onDestroy()
    }

    companion object {
        /** ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE (Android 14). */
        const val SPECIAL_USE = 0x40000000
    }
}
