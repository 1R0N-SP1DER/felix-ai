package com.felix.faze

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * The always-on part: a foreground service of type "microphone" that waits for the wake phrase with a [WakeEngine] (offline
 * Vosk), then records one request and hands it to [FazeCore]. Android only lets a microphone service start while
 * the app is on screen, so it is started from the app, not at boot.
 */
class ListenService : Service() {
    private var loop: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    private var paused = false

    @Volatile
    private var released = true

    private val rootExec = Executors.newSingleThreadExecutor()
    private lateinit var guard: MicDotGuard

    private val core: FazeCore get() = (application as FazeApp).core

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        guard = MicDotGuard(AndroidRootShell(), { core.settings.hideMicDot }, { core.reportStatus(it) })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            core.settings.wakeEnabled = false
            stopSelf()
            return START_NOT_STICKY
        }
        if (!goForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) {
            running = true
            core.listenControl = object : ListenControl {
                override fun pause() {
                    paused = true
                    rootExec.execute { guard.restore() } // someone is really recording: the dot should show
                    val end = System.currentTimeMillis() + 1500
                    while (!released && System.currentTimeMillis() < end) Thread.sleep(20) // wait until the microphone is let go
                }

                override fun resume() {
                    paused = false
                    rootExec.execute { guard.waiting() }
                }
            }
            loop = Thread { listen() }.also { it.start() }
        }
        return START_NOT_STICKY
    }

    private fun goForeground(): Boolean = try {
        ServiceCompat.startForeground(this, NOTE_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        true
    } catch (e: Exception) {
        core.reportStatus("couldn't start listening: ${e.message ?: e.javaClass.simpleName}")
        false
    }

    /** A quiet notification: lowest importance, so there is no icon in the status bar, only an entry in the shade. */
    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel("listen") // the old channel (patch 29) showed an icon
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Listening", NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) })
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, ListenService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_faze)
            .setContentTitle("Faze is listening")
            .setContentText("Say \"${Wake.phrase(core.settings.wakePhrase)}\"")
            .setContentIntent(open)
            .addAction(0, "Turn off", stop)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .build()
    }

    private fun openAudio(): AudioRecord? = try {
        val size = maxOf(AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), 8192)
        val r = AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            null
        } else {
            r.startRecording()
            released = false
            r
        }
    } catch (e: Exception) {
        null
    }

    private fun closeAudio(audio: AudioRecord?): AudioRecord? {
        if (audio != null) {
            try {
                audio.stop()
            } catch (e: Exception) {
                // already stopped
            }
            audio.release()
        }
        released = true
        return null
    }

    /** Builds the wake engine, or says why it can't start. */
    private fun makeEngine(): WakeEngine? {
        val dir = ModelStore.ready(this)
        if (dir == null) {
            core.reportStatus("download the wake word model first (Settings)")
            return null
        }
        return try {
            VoskWake(dir, Wake.phrase(core.settings.wakePhrase))
        } catch (e: Throwable) {
            core.reportStatus("wake word failed to start: ${e.message ?: e.javaClass.simpleName}")
            null
        }
    }

    private fun listen() {
        val engine = makeEngine()
        if (engine == null) {
            core.settings.wakeEnabled = false
            running = false
            stopSelf()
            return
        }
        var audio: AudioRecord? = null
        try {
            val cooldown = Cooldown(4)
            val frames = FrameBuffer(engine.frameSize)
            val buffer = ShortArray(1600)
            core.markListening(true)
            rootExec.execute { guard.waiting() }
            while (running) {
                if (paused || core.occupied()) { // push-to-talk has the microphone, or the assistant is talking
                    audio = closeAudio(audio)
                    Thread.sleep(150)
                    continue
                }
                if (audio == null) {
                    audio = openAudio()
                    if (audio == null) {
                        core.reportStatus("couldn't open the microphone for listening")
                        break
                    }
                    engine.reset()
                    frames.clear()
                }
                val n = audio.read(buffer, 0, buffer.size)
                if (n < 0) {
                    audio = closeAudio(audio)
                    Thread.sleep(300)
                    continue
                }
                if (n == 0) continue
                var woke = false
                frames.push(buffer, n) { frame -> if (!woke && engine.process(frame)) woke = true }
                if (woke && cooldown.ready()) {
                    cooldown.fire()
                    engine.reset()
                    frames.clear()
                    rootExec.execute { guard.recording() }
                    val pcm = capture(audio)
                    rootExec.execute { guard.waiting() }
                    if (pcm != null) core.handlePcm(pcm)
                    engine.reset()
                    frames.clear()
                }
            }
        } catch (e: Throwable) {
            core.reportStatus("listening stopped: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            closeAudio(audio)
            try {
                engine.close()
            } catch (e: Throwable) {
                // nothing more to do
            }
            rootExec.execute { guard.restore() }
            core.markListening(false)
            core.markHeard(false)
            core.listenControl = null
            running = false
            stopSelf()
        }
    }

    /** After the wake phrase: a short beep, then record until the person stops talking. Null if they said nothing. */
    private fun capture(audio: AudioRecord): ByteArray? {
        core.markHeard(true)
        try {
            ToneGenerator(AudioManager.STREAM_MUSIC, 50).startTone(ToneGenerator.TONE_PROP_ACK, 120)
        } catch (e: Exception) {
            // no beep is fine
        }
        val scratch = ShortArray(1600)
        repeat(3) { audio.read(scratch, 0, scratch.size) } // let the beep die away so it doesn't count as the room's noise
        val endpointer = Endpointer()
        val out = ByteArrayOutputStream()
        val frame = ShortArray(480)
        var result = Heard.WAITING
        while (running && !paused) {
            val n = audio.read(frame, 0, frame.size)
            if (n <= 0) break
            for (i in 0 until n) {
                out.write(frame[i].toInt() and 0xff)
                out.write((frame[i].toInt() shr 8) and 0xff)
            }
            result = endpointer.feed(frame, n)
            if (result == Heard.SPEECH_DONE || result == Heard.NOTHING) break
        }
        core.markHeard(false)
        return if (result == Heard.SPEECH_DONE) out.toByteArray() else null
    }

    override fun onDestroy() {
        running = false
        try {
            loop?.join(1500)
        } catch (e: InterruptedException) {
            // shutting down anyway
        }
        rootExec.execute { guard.restore() }
        rootExec.shutdown()
        core.listenControl = null
        core.markListening(false)
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.felix.faze.STOP_LISTENING"
        private const val CHANNEL = "listen_quiet"
        private const val NOTE_ID = 7
        private const val RATE = 16000
    }
}
