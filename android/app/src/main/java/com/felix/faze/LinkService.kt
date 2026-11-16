package com.felix.faze

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/** Keeps Faze alive in the background while computers are allowed, so the link, clipboard sync and file access keep working with the screen off. */
class LinkService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val core = (application as FazeApp).core
        if (intent?.action == ACTION_STOP || !core.settings.linkOn) { stopSelf(); return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Computers link", NotificationManager.IMPORTANCE_MIN))
        val stop = android.app.PendingIntent.getService(this, 1, Intent(this, LinkService::class.java).setAction(ACTION_STOP), android.app.PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(applicationInfo.icon).setContentTitle("Faze is linked to your computers")
            .setContentText("Clipboard, files and screen share stay available").setOngoing(true).setPriority(NotificationCompat.PRIORITY_MIN)
            .addAction(0, "Turn off", stop).build()
        return try {
            ServiceCompat.startForeground(this, 41, n, if (android.os.Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
            START_STICKY
        } catch (e: Exception) {
            stopSelf(); START_NOT_STICKY
        }
    }

    companion object {
        const val CHANNEL = "faze_link"
        const val ACTION_STOP = "com.felix.faze.LINK_STOP"
    }
}
