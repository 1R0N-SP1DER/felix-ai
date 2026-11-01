package com.felix.faze

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.speech.tts.TextToSpeech
import java.io.ByteArrayOutputStream
import java.util.Locale

/** Records the microphone as 16 kHz mono PCM until [stop]. The caller checks the RECORD_AUDIO permission first. */
class Recorder {
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private val pcm = ByteArrayOutputStream()

    @Volatile
    private var running = false

    fun start(): Boolean {
        if (running) return true
        return try {
            val size = maxOf(AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), 4096)
            val r = AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
            if (r.state != AudioRecord.STATE_INITIALIZED) {
                r.release()
                return false
            }
            pcm.reset()
            record = r
            running = true
            r.startRecording()
            thread = Thread {
                val buffer = ByteArray(size)
                while (running && pcm.size() < MAX_BYTES) {
                    val n = r.read(buffer, 0, buffer.size)
                    if (n > 0) synchronized(pcm) { pcm.write(buffer, 0, n) }
                    else if (n < 0) break
                }
            }.also { it.start() }
            true
        } catch (e: Exception) {
            running = false
            record?.release()
            record = null
            false
        }
    }

    fun stop(): ByteArray {
        running = false
        try {
            thread?.join(1000)
        } catch (e: InterruptedException) {
            // carry on with what was recorded
        }
        try {
            record?.stop()
        } catch (e: Exception) {
            // already stopped
        }
        record?.release()
        record = null
        thread = null
        return synchronized(pcm) { pcm.toByteArray() }
    }

    companion object {
        const val RATE = 16000
        const val MAX_BYTES = RATE * 2 * 60
    }
}

/** Speaks sentences as they arrive, queued one after another by Android's own text to speech. */
class Speaker(context: android.content.Context) : SpeechOut {
    private var tts: TextToSpeech? = null
    private var ready = false
    private val waiting = ArrayList<String>()

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            synchronized(this) {
                if (status == TextToSpeech.SUCCESS) {
                    tts?.setLanguage(Locale.getDefault())
                    ready = true
                    for (s in waiting) say(s)
                }
                waiting.clear()
            }
        }
    }

    private fun say(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "faze-" + System.nanoTime())
    }

    override fun speak(sentence: String) {
        val text = Speakable.clean(sentence)
        if (text.isEmpty()) return
        synchronized(this) {
            if (ready) say(text) else waiting.add(text)
        }
    }

    override fun stop() {
        synchronized(this) {
            waiting.clear()
            tts?.stop()
        }
    }

    fun isSpeaking(): Boolean = synchronized(this) { waiting.isNotEmpty() || tts?.isSpeaking == true }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
