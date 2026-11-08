package com.felix.faze

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Keeps a spoken conversation (live mode or voice chat) alive when the app is left. A foreground service of type "microphone", started
 * while the app is on screen (the one moment Android allows it), is what keeps the microphone working in the background. While the app
 * is not on screen it also draws the floating [BubbleWindow], so the conversation, and any tool it runs, carries on outside the app.
 */
class LiveService : Service() {
    private val core: FazeCore get() = (application as FazeApp).core
    private val handler = Handler(Looper.getMainLooper())
    private var bubble: BubbleWindow? = null
    private var ticking = false
    private val tick = object : Runnable {
        override fun run() {
            update()
            if (ticking) handler.postDelayed(this, 120)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            core.closeLiveScreen()
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(this, NOTE_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            core.reportStatus("couldn't keep going outside the app: ${e.message ?: e.javaClass.simpleName}")
            stopSelf()
            return START_NOT_STICKY
        }
        if (bubble == null) bubble = BubbleWindow(this)
        if (!ticking) {
            ticking = true
            handler.post(tick)
        }
        return START_NOT_STICKY
    }

    /** The bubble shows only while a conversation is going and the app is not on screen. */
    private fun update() {
        val b = bubble ?: return
        if (!(core.liveOn || core.voiceOn) || core.appVisible || !b.canShow()) {
            b.hide()
            return
        }
        b.show()
        val speaking = (core.liveOn && core.liveState == LiveSession.SPEAKING) || (core.voiceOn && core.speaking)
        b.update(if (speaking) core.outLevel else core.micLevel, speaking, core.micMuted)
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Live voice", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 2, Intent(this, LiveService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_faze)
            .setContentTitle("Faze is live")
            .setContentText("Tap to open")
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    override fun onDestroy() {
        ticking = false
        handler.removeCallbacks(tick)
        bubble?.hide()
        bubble = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.felix.faze.LIVE_STOP"
        private const val CHANNEL = "live"
        private const val NOTE_ID = 7
    }
}
