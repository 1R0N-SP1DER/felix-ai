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
/** One voice the phone can speak with. [offline] voices work without internet. */
data class VoiceOption(val name: String, val label: String, val offline: Boolean)

class Speaker(context: android.content.Context) : SpeechOut, DeviceSpeech {
    private var voiceName = ""
    private var tts: TextToSpeech? = null
    private var ready = false
    private val waiting = ArrayList<String>()
    private var rate = 1.0f
    private var pitch = 1.0f

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            synchronized(this) {
                if (status == TextToSpeech.SUCCESS) {
                    tts?.setLanguage(Locale.getDefault())
                    applyVoice()
                    tts?.setSpeechRate(rate)
                    tts?.setPitch(pitch)
                    ready = true
                    for (s in waiting) sayNow(s)
                }
                waiting.clear()
            }
        }
    }

    private fun sayNow(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "faze-" + System.nanoTime())
    }

    override fun say(text: String) = speak(text)

    private fun applyVoice() {
        try {
            val v = if (voiceName.isEmpty()) null else tts?.voices?.firstOrNull { it.name == voiceName }
            if (v != null) tts?.voice = v
        } catch (e: Exception) {
            // a voice that is gone falls back to the default one
        }
    }

    /** The voice picked in Settings ("" = the phone's default). */
    fun setVoiceName(name: String) {
        synchronized(this) {
            voiceName = name
            if (ready) applyVoice()
        }
    }

    /** The voices installed on this phone for the current language first, offline ones before online ones. Empty until the engine has started. */
    fun voices(): List<VoiceOption> = synchronized(this) {
        if (!ready) return emptyList()
        val lang = Locale.getDefault().language
        try {
            (tts?.voices ?: emptySet()).filter { it.locale.language == lang }
                .sortedWith(compareBy({ it.isNetworkConnectionRequired }, { -it.quality }, { it.name }))
                .map { VoiceOption(it.name, it.locale.displayName + "  " + it.name.substringAfterLast('-').take(24), !it.isNetworkConnectionRequired) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun speak(sentence: String) {
        val text = Speakable.clean(sentence)
        if (text.isEmpty()) return
        synchronized(this) {
            if (ready) sayNow(text) else waiting.add(text)
        }
    }

    override fun stop() {
        synchronized(this) {
            waiting.clear()
            tts?.stop()
        }
    }

    /** Speed and pitch, 0.6 to 1.6 as on the web settings page. Applies to what is said next. */
    fun setVoice(newRate: Float, newPitch: Float) {
        synchronized(this) {
            rate = newRate
            pitch = newPitch
            if (ready) {
                tts?.setSpeechRate(rate)
                tts?.setPitch(pitch)
            }
        }
    }

    override fun isSpeaking(): Boolean = synchronized(this) { waiting.isNotEmpty() || tts?.isSpeaking == true }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
