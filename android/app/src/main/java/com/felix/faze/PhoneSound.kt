package com.felix.faze

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream

/** Where this phone's own sound (what its apps are playing) comes from. */
interface SoundSource {
    /** Starts capturing if not already. Throws with a plain reason when it cannot. */
    fun start()
    /** What was played since the last call (44.1 kHz stereo 16-bit), possibly empty. */
    fun take(): ByteArray
    /** Pauses capturing (the one-time permission stays). */
    fun stop()
}

object NoSound : SoundSource {
    override fun start() = throw IllegalStateException("This phone cannot share its sound.")
    override fun take() = ByteArray(0)
    override fun stop() {}
}

/**
 * The phone's playback, captured with Android's AudioPlaybackCapture. Android makes the person say yes once (a "start recording or casting" box), and keeps a
 * notification up while it is allowed. [SoundService] holds that permission; this object does the capturing. Apps that forbid capture (some video and music
 * apps) come out silent: that is the app's choice, not something Faze can change.
 */
object SoundCapture {
    const val RATE = 44100
    private const val FRAME = 4                    // bytes per stereo 16-bit frame
    @Volatile private var projection: MediaProjection? = null
    private var rec: AudioRecord? = null
    private var thread: Thread? = null
    private val buffer = ByteArrayOutputStream()
    @Volatile private var running = false

    fun ready() = projection != null

    fun attach(p: MediaProjection) { projection = p }

    @SuppressLint("MissingPermission")
    @Synchronized fun startRecording(context: Context) {
        if (running) return
        val p = projection ?: throw IllegalStateException("Phone sound is not allowed yet. On the phone: Faze > Settings > Devices > Allow phone sound.")
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) throw IllegalStateException("Faze has no permission for audio. Allow the microphone in the phone's app settings (Android asks for it to capture sound too).")
        val cfg = AudioPlaybackCaptureConfiguration.Builder(p)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME).addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()
        val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord.Builder().setAudioFormat(format).setBufferSizeInBytes(maxOf(min, RATE * FRAME / 4)).setAudioPlaybackCaptureConfig(cfg).build()
        if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); throw IllegalStateException("Android would not start capturing the phone's sound.") }
        r.startRecording()
        rec = r; running = true
        synchronized(buffer) { buffer.reset() }
        thread = Thread {
            val chunk = ByteArray(4096)
            while (running) {
                val n = r.read(chunk, 0, chunk.size)
                if (n > 0) synchronized(buffer) {
                    buffer.write(chunk, 0, n)
                    if (buffer.size() > RATE * FRAME * 2) { val keep = buffer.toByteArray().takeLast(RATE * FRAME / 4).toByteArray(); buffer.reset(); buffer.write(keep) } // far behind: keep the newest quarter second
                } else if (n < 0) break
            }
        }.also { it.isDaemon = true; it.start() }
    }

    fun take(): ByteArray = synchronized(buffer) { buffer.toByteArray().also { buffer.reset() } }

    @Synchronized fun pause() {
        running = false
        try { rec?.stop(); rec?.release() } catch (_: Exception) { }
        rec = null
        synchronized(buffer) { buffer.reset() }
    }

    @Synchronized fun shutdown() {
        pause()
        try { projection?.stop() } catch (_: Exception) { }
        projection = null
    }
}

class AndroidSound(private val context: Context) : SoundSource {
    override fun start() = SoundCapture.startRecording(context)
    override fun take(): ByteArray = SoundCapture.take()
    override fun stop() = SoundCapture.pause()
}

/** Holds the "allow phone sound" permission while it lasts, with the notification Android requires. Started right after the person says yes. */
class SoundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { SoundCapture.shutdown(); stopSelf(); return START_NOT_STICKY }
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra("data")
        val code = intent?.getIntExtra("code", 0) ?: 0
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Phone sound", NotificationManager.IMPORTANCE_LOW))
        val stop = android.app.PendingIntent.getService(this, 2, Intent(this, SoundService::class.java).setAction(ACTION_STOP), android.app.PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(applicationInfo.icon).setContentTitle("Phone sound can be shared with your computer")
            .setContentText("Tap Stop to take that permission back").setOngoing(true).addAction(0, "Stop", stop).build()
        return try {
            ServiceCompat.startForeground(this, 42, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            val p = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { SoundCapture.shutdown(); stopSelf() }
            }, Handler(Looper.getMainLooper()))
            SoundCapture.attach(p)
            START_NOT_STICKY
        } catch (e: Exception) {
            stopSelf(); START_NOT_STICKY
        }
    }

    override fun onDestroy() { SoundCapture.shutdown(); super.onDestroy() }

    companion object {
        const val CHANNEL = "faze_sound"
        const val ACTION_STOP = "com.felix.faze.SOUND_STOP"
    }
}
