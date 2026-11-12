package com.felix.faze

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import java.time.ZoneId

/** The real scheduler: the saved list, plus one phone alarm set for the earliest task. */
class AndroidScheduler(private val app: Context, val store: ScheduleStore) : SchedulerApi {
    private val alarms = app.getSystemService(AlarmManager::class.java)

    override fun add(prompt: String, atMillis: Long, everyMinutes: Int, action: ScheduledAction?): ScheduledTask = store.add(prompt, atMillis, everyMinutes, action).also { rearm() }
    override fun list(): List<ScheduledTask> = store.list()
    override fun cancel(id: String): Boolean = store.cancel(id).also { if (it) rearm() }
    override fun now(): Long = System.currentTimeMillis()
    override fun zone(): ZoneId = ZoneId.systemDefault()

    private fun pending(): PendingIntent =
        PendingIntent.getBroadcast(app, 7, Intent(app, ScheduleReceiver::class.java).setAction(ScheduleReceiver.ACTION), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** Points the one alarm at the earliest task (or removes it when nothing is scheduled). Exact when Android allows it, otherwise within a few minutes. */
    fun rearm() {
        val next = store.next()
        val pi = pending()
        if (next == null) { alarms.cancel(pi); return }
        try {
            if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.nextAt, pi)
            else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.nextAt, pi)
        } catch (e: SecurityException) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.nextAt, pi)
        }
    }
}

/** Fires when a scheduled task is due (and after a reboot, to set the alarm again). */
class ScheduleReceiver : BroadcastReceiver() {
    companion object { const val ACTION = "com.felix.faze.SCHEDULE_DUE" }

    override fun onReceive(context: Context, intent: Intent) {
        val core = (context.applicationContext as FazeApp).core
        if (intent.action == ACTION) {
            // goAsync keeps the process alive until the switches have been flipped (the phone may be locked and asleep)
            val pending = goAsync()
            core.runDueTasks { try { pending.finish() } catch (e: Exception) { } }
        } else core.rearmSchedule()
    }
}

/** Runs due tasks one at a time through the normal chat, then tells the person the result. */
class ScheduleRunner(private val app: Context, private val core: FazeCore, private val scheduler: AndroidScheduler) {
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val queue = ArrayDeque<ScheduledTask>()
    private var running = false
    private var lock: PowerManager.WakeLock? = null

    private val actions = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** [done] is called when every direct action has run (prompts carry on afterwards, through the chat). */
    fun runDue(done: () -> Unit) {
        val due = scheduler.store.takeDue(System.currentTimeMillis())
        scheduler.rearm()
        val direct = due.filter { it.action != null }
        val chats = due.filter { it.action == null }
        if (chats.isNotEmpty()) {
            queue.addAll(chats)
            if (!running) { running = true; hold(); next() }
        }
        if (direct.isEmpty()) { done(); return }
        val wake = app.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "faze:action").apply { acquire(60_000L) }
        actions.execute {
            try {
                for (t in direct) {
                    val a = t.action!!
                    notify("Running: ${t.prompt}", "Doing it now…", idFor(t), running = true)
                    val r = core.runApprovedAction(a.tool, a.enabled)
                    notify(t.prompt, if (r.ok) r.text else "Didn't work: ${r.text}", idFor(t))
                }
            } finally {
                try { if (wake.isHeld) wake.release() } catch (e: Exception) { }
                done()
            }
        }
    }

    private fun hold() {
        if (lock?.isHeld == true) return
        lock = app.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "faze:schedule").apply { acquire(10 * 60_000L) }
    }

    private fun release() { try { if (lock?.isHeld == true) lock?.release() } catch (e: Exception) { } }

    private fun next() {
        val t = queue.removeFirstOrNull()
        if (t == null) { running = false; release(); return }
        whenFree(t, 40)
    }

    /** A chat that is still busy (or a voice call) is given up to ~10 minutes; then the task is reported as missed. */
    private fun whenFree(t: ScheduledTask, tries: Int) {
        if (core.busy || core.voiceOn || core.liveOn) {
            if (tries <= 0) { notify("Missed: ${t.prompt}", "Faze was busy when it was time.", idFor(t)); next(); return }
            main.postDelayed({ whenFree(t, tries - 1) }, 15_000)
            return
        }
        val before = core.messages.size
        notify("Running now: ${t.prompt}", "Faze is working on it…", idFor(t), running = true) // so a task starting by itself is never a surprise
        core.send("(Scheduled task, run now) ${t.prompt}")
        waitFor(t, before, 360)
    }

    private fun waitFor(t: ScheduledTask, before: Int, tries: Int) {
        if (core.busy && tries > 0) { main.postDelayed({ waitFor(t, before, tries - 1) }, 500); return }
        val reply = core.messages.drop(before).lastOrNull { it.role == "assistant" }?.text.orEmpty()
        notify(t.prompt, if (reply.isBlank()) "It ran, but there was no reply (an approval may be waiting in the app)." else reply, idFor(t))
        next()
    }

    /** One notification per task: "Running" first, then the result takes its place. */
    private fun idFor(t: ScheduledTask): Int = 2000 + (t.id.removePrefix("t").toIntOrNull() ?: 0)

    private fun notify(title: String, text: String, id: Int, running: Boolean = false) {
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("scheduled", "Scheduled tasks", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel("scheduled_running", "Scheduled task started", NotificationManager.IMPORTANCE_LOW)) // quiet: the result is the one that sounds
        val open = PendingIntent.getActivity(app, 0, Intent(app, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(app, if (running) "scheduled_running" else "scheduled")
            .setSmallIcon(R.drawable.ic_stat_faze)
            .setContentTitle(title.take(60))
            .setContentText(text.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(600)))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try { nm.notify(id, n) } catch (e: SecurityException) { /* notifications not allowed */ }
    }
}
