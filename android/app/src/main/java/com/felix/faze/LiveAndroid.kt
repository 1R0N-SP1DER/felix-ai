package com.felix.faze

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import androidx.core.content.ContextCompat
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/** The real microphone for live mode: 16 kHz mono PCM in 100 ms chunks, with the phone's echo canceller and noise suppressor when it has them. */
class AndroidLiveMic(private val context: Context) : LiveAudioIn {
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var active = false
    private var echo: AcousticEchoCanceler? = null
    private var noise: NoiseSuppressor? = null

    @Synchronized
    override fun start(onChunk: (ByteArray) -> Unit): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return "the microphone permission is not granted"
        val rate = LiveProtocol.INPUT_RATE
        val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return "this phone cannot record at 16 kHz"
        val chunk = rate / 10 * 2
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, chunk * 4))
        } catch (e: Exception) {
            return "could not open the microphone: ${e.message ?: e.javaClass.simpleName}"
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return "the microphone is busy or unavailable" }
        try { if (AcousticEchoCanceler.isAvailable()) echo = AcousticEchoCanceler.create(rec.audioSessionId)?.also { it.enabled = true } } catch (_: Exception) {}
        try { if (NoiseSuppressor.isAvailable()) noise = NoiseSuppressor.create(rec.audioSessionId)?.also { it.enabled = true } } catch (_: Exception) {}
        try { rec.startRecording() } catch (e: Exception) { rec.release(); return "the microphone did not start: ${e.message ?: e.javaClass.simpleName}" }
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) { rec.release(); return "the microphone did not start (is another app using it?)" }
        recorder = rec
        active = true
        Thread {
            val buf = ByteArray(chunk)
            while (active) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) onChunk(buf.copyOf(n)) else if (n < 0) break
            }
        }.also { it.name = "live-mic"; it.isDaemon = true }.start()
        return null
    }

    @Synchronized
    override fun stop() {
        active = false
        val rec = recorder
        recorder = null
        try { rec?.stop() } catch (_: Exception) {}
        try { rec?.release() } catch (_: Exception) {}
        try { echo?.release() } catch (_: Exception) {}
        try { noise?.release() } catch (_: Exception) {}
        echo = null
        noise = null
    }
}

/** The real speaker for live mode: 24 kHz mono PCM written by one thread, so the websocket thread never waits on the speaker. */
class AndroidLiveSpeaker : LiveAudioOut {
    private val queue = LinkedBlockingQueue<ByteArray>()
    @Volatile private var track: AudioTrack? = null
    @Volatile private var alive = true

    private fun ensure(): AudioTrack {
        track?.let { return it }
        synchronized(this) {
            track?.let { return it }
            val rate = LiveProtocol.OUTPUT_RATE
            val minBuffer = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(minBuffer, rate / 2 * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            t.play()
            track = t
            Thread {
                while (alive) {
                    val data = try { queue.take() } catch (e: InterruptedException) { break }
                    try { t.write(data, 0, data.size) } catch (_: Exception) {}
                }
            }.also { it.name = "live-speaker"; it.isDaemon = true }.start()
            return t
        }
    }

    override fun play(pcm: ByteArray) {
        ensure()
        queue.put(pcm)
    }

    override fun flush() {
        queue.clear()
        val t = track ?: return
        try { t.pause(); t.flush(); t.play() } catch (_: Exception) {}
    }

    @Synchronized
    override fun stop() {
        alive = false
        queue.clear()
        val t = track
        track = null
        try { t?.pause(); t?.flush() } catch (_: Exception) {}
        try { t?.release() } catch (_: Exception) {}
    }
}

/** True when sound goes to headphones (wired, Bluetooth or USB), so the mic will not hear the model's own voice. */
fun headphonesConnected(context: Context): Boolean {
    val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
    return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
        when (it.type) {
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLE_HEADSET -> true
            else -> false
        }
    }
}

/** The websocket for live mode, over OkHttp. Names are looked up IPv4 first: an any-family lookup stalls for seconds on some phones. */
class OkHttpLiveConnector : LiveConnector {
    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = InetAddress.getAllByName(hostname).sortedBy { it is Inet6Address }
        })
        .build()

    override fun connect(url: String, listener: LiveListener): LiveSocket {
        val ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) = listener.onMessage(text)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = listener.onMessage(bytes.utf8())
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                listener.onClosed("$code: $reason".trim(':', ' '))
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                listener.onClosed((response?.let { "HTTP ${it.code} " } ?: "") + (t.message ?: t.javaClass.simpleName))
            }
        })
        return object : LiveSocket {
            override fun send(text: String): Boolean = ws.send(text)
            override fun close() { ws.close(1000, null) }
        }
    }
}
