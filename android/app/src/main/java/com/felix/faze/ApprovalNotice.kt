package com.felix.faze

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/** When a tool needs a yes while another app is in front, the question comes as a notification with Allow and Deny, so it is not stuck behind the app. */
class ApprovalNotice(private val app: Context) {
    private val nm = app.getSystemService(NotificationManager::class.java)

    fun show(r: ApprovalRequest) {
        nm.createNotificationChannel(NotificationChannel("approval", "Faze asks first", NotificationManager.IMPORTANCE_HIGH))
        fun action(allow: Boolean) = PendingIntent.getBroadcast(
            app, (r.id * 2 + if (allow) 1 else 0).toInt(),
            Intent(app, ApprovalActionReceiver::class.java).setAction(ApprovalActionReceiver.ACTION).putExtra("id", r.id).putExtra("allow", allow),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(app, 0, Intent(app, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(app, "approval")
            .setSmallIcon(R.drawable.ic_stat_faze)
            .setContentTitle("Faze asks: " + r.summary.take(80))
            .setContentText(r.detail.ifBlank { "Allow this?" }.take(120))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(open)
            .setTimeoutAfter(60_000)
            .addAction(0, "Allow", action(true))
            .addAction(0, "Deny", action(false))
            .build()
        try { nm.notify(NOTE_ID, n) } catch (e: SecurityException) { /* notifications not allowed: the question waits in the app */ }
    }

    fun cancel() = nm.cancel(NOTE_ID)

    companion object { const val NOTE_ID = 42 }
}

class ApprovalActionReceiver : BroadcastReceiver() {
    companion object { const val ACTION = "com.felix.faze.APPROVAL" }

    override fun onReceive(context: Context, intent: Intent) {
        val core = (context.applicationContext as FazeApp).core
        core.resolveApproval(intent.getLongExtra("id", -1L), intent.getBooleanExtra("allow", false))
        context.getSystemService(NotificationManager::class.java).cancel(ApprovalNotice.NOTE_ID)
    }
}
