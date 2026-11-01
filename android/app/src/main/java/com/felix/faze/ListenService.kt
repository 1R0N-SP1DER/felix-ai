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
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer

/**
 * The always-on part: a foreground service of type "microphone" that listens for the wake phrase with the small offline
 * model (nothing leaves the phone until the phrase is heard), then records one request and hands it to [FazeCore].
 * Android only lets a microphone service start while the app is on screen, so it is started from the app, not at boot.
 */
class ListenService : Service() {
    private var loop: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    private var paused = false

    @Volatile
    private var released = true

    private val core: FazeCore get() = (application as FazeApp).core

    override fun onBind(intent: Intent?): IBinder? = null

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
                    val end = System.currentTimeMillis() + 1500
                    while (!released && System.currentTimeMillis() < end) Thread.sleep(20) // wait until the microphone is let go
                }

                override fun resume() {
                    paused = false
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

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Listening", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, ListenService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val phrase = Wake.phrase(core.settings.wakePhrase)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Faze is listening")
            .setContentText("Say \"$phrase\"")
            .setContentIntent(open)
            .addAction(0, "Turn off", stop)
            .setOngoing(true)
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

    private fun heardText(json: String, key: String): String = try {
        JSONObject(json).optString(key, "")
    } catch (e: Exception) {
        ""
    }

    private fun listen() {
        val modelDir = ModelStore.ready(this)
        if (modelDir == null) {
            core.reportStatus("download the wake word model first (Settings)")
            core.settings.wakeEnabled = false
            stopSelf()
            return
        }
        val phrase = Wake.phrase(core.settings.wakePhrase)
        var model: Model? = null
        var recognizer: Recognizer? = null
        var audio: AudioRecord? = null
        try {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            model = Model(modelDir.absolutePath)
            recognizer = Recognizer(model, RATE.toFloat(), Wake.grammarJson(phrase))
            val cooldown = Cooldown(4)
            val buffer = ShortArray(1600)
            core.markListening(true)
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
                    recognizer.reset()
                }
                val n = audio.read(buffer, 0, buffer.size)
                if (n < 0) {
                    audio = closeAudio(audio)
                    Thread.sleep(300)
                    continue
                }
                if (n == 0) continue
                val text = if (recognizer.acceptWaveForm(buffer, n)) heardText(recognizer.result, "text") else heardText(recognizer.partialResult, "partial")
                if (text.isNotEmpty() && Wake.matches(text, phrase) && cooldown.ready()) {
                    cooldown.fire()
                    recognizer.reset()
                    val pcm = capture(audio)
                    if (pcm != null) core.handlePcm(pcm)
                    recognizer.reset()
                }
            }
        } catch (e: Throwable) {
            core.reportStatus("listening stopped: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            closeAudio(audio)
            recognizer?.close()
            model?.close()
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
        core.listenControl = null
        core.markListening(false)
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.felix.faze.STOP_LISTENING"
        private const val CHANNEL = "listen"
        private const val NOTE_ID = 7
        private const val RATE = 16000
    }
}
