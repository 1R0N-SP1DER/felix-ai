package com.felix.faze

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import org.json.JSONObject

/** Where the computer's sound goes. */
interface AudioSink {
    fun open(rate: Int)
    /** Stereo sound needs 2; the default is the mono way every sink already has. */
    fun open(rate: Int, channels: Int) = open(rate)
    fun write(pcm: ByteArray)
    fun close()
}

class AndroidSink : AudioSink {
    private var track: AudioTrack? = null

    override fun open(rate: Int) = open(rate, 1)

    override fun open(rate: Int, channels: Int) {
        close()
        val mask = if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val min = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(mask).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(min, rate * channels)).setTransferMode(AudioTrack.MODE_STREAM).build()
        t.play()
        track = t
    }

    override fun write(pcm: ByteArray) { track?.write(pcm, 0, pcm.size) }

    override fun close() { try { track?.stop(); track?.release() } catch (_: Exception) { }; track = null }
}

/**
 * Plays what the computer is playing (source "monitor") or what its microphone hears (source "mic") on this phone. The computer hands over the sound
 * in chunks as we ask; each chunk goes straight to the speaker. Stopping tells the computer to stop recording.
 */
class PcAudio(private val ask: (JSONObject) -> JSONObject, private val sink: AudioSink, private val onError: (String) -> Unit, private val sleep: (Long) -> Unit = { Thread.sleep(it) }) {
    @Volatile var running = false
        private set
    private var rate = 0
    private var source = "monitor"

    /** One round: asks for a chunk and plays it. Returns false when it should stop. */
    fun step(): Boolean {
        val r = try { ask(JSONObject().put("t", "pc_audio_get").put("source", source)) } catch (e: Exception) { onError(e.message ?: "the computer could not be reached"); return false }
        if (r.optString("t") != "pc_audio_ok") { onError(r.optString("message", "the computer did not send sound")); return false }
        val data = java.util.Base64.getDecoder().decode(r.optString("data"))
        if (r.optInt("rate", 32000) != rate) { rate = r.optInt("rate", 32000); sink.open(rate) }
        if (data.isNotEmpty()) sink.write(data) else sleep(40)
        return true
    }

    fun start(source: String) {
        if (running) return
        this.source = source
        running = true
        Thread {
            try {
                while (running && step()) { /* the sink blocks when the speaker is full */ }
            } catch (e: Throwable) {
                onError("the PC's sound failed: " + (e.message ?: e.javaClass.simpleName))
            }
            finish()
        }.start()
    }

    private fun finish() {
        if (!running && rate == 0) return
        running = false
        sink.close()
        rate = 0
        try { ask(JSONObject().put("t", "pc_audio_stop").put("source", source)) } catch (_: Exception) { }
    }

    fun stop() { running = false }
}
