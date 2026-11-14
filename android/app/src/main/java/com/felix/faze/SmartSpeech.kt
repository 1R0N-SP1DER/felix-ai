package com.felix.faze

import java.util.concurrent.Executor
import org.json.JSONArray
import org.json.JSONObject

/** The phone's own text to speech: works offline. */
interface DeviceSpeech {
    fun say(text: String)
    fun stop()
    fun isSpeaking(): Boolean
}

/** Turns text into 24 kHz mono 16-bit PCM with a natural voice (online). Throws when it cannot. */
fun interface Synth {
    fun synth(text: String): ByteArray
}

/** Pulls the raw sound out of a WAV file (or passes raw PCM through). */
object WavReader {
    fun pcm(bytes: ByteArray): ByteArray {
        if (bytes.size < 12 || String(bytes, 0, 4, Charsets.ISO_8859_1) != "RIFF") return bytes
        var i = 12
        while (i + 8 <= bytes.size) {
            val id = String(bytes, i, 4, Charsets.ISO_8859_1)
            val size = (bytes[i + 4].toInt() and 0xff) or ((bytes[i + 5].toInt() and 0xff) shl 8) or ((bytes[i + 6].toInt() and 0xff) shl 16) or ((bytes[i + 7].toInt() and 0xff) shl 24)
            if (id == "data") return bytes.copyOfRange(i + 8, if (size < 0 || i + 8 + size > bytes.size) bytes.size else i + 8 + size)
            i += 8 + size + (size and 1)
        }
        return ByteArray(0)
    }
}

/** Google's Gemini speech models: the same family of voices as live mode, for spoken replies. One request per chunk of text. */
class GeminiSpeech(
    private val transport: Transport,
    private val key: () -> String,
    private val model: () -> String,
    private val voice: () -> String,
) : Synth {
    override fun synth(text: String): ByteArray {
        if (key().isEmpty()) throw ToolFailure("no Gemini key")
        val body = JSONObject()
            .put("model", model().ifBlank { DEFAULT_MODEL })
            .put("input", JSONArray().put(JSONObject().put("type", "user_input").put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))))
            .put("response_format", JSONObject().put("type", "audio"))
            .put("generation_config", JSONObject().put("speech_config", JSONArray().put(JSONObject().put("voice", voice().ifBlank { "Kore" }))))
            .toString()
        val r = transport.post(URL, mapOf("x-goog-api-key" to key()), body.toByteArray(Charsets.UTF_8), "application/json", 30000)
        if (r.status != 200) throw ToolFailure("Gemini voice answered ${r.status}")
        val b64 = audio(r.body) ?: throw ToolFailure("Gemini voice sent no sound")
        val pcm = WavReader.pcm(java.util.Base64.getDecoder().decode(b64))
        if (pcm.isEmpty()) throw ToolFailure("Gemini voice sent empty sound")
        return pcm
    }

    /** The base64 audio of the last model_output step. */
    fun audio(json: String): String? {
        val steps = try { JSONObject(json).optJSONArray("steps") } catch (e: Exception) { null } ?: return null
        var found: String? = null
        for (i in 0 until steps.length()) {
            val s = steps.optJSONObject(i) ?: continue
            if (s.optString("type") != "model_output") continue
            val content = s.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val c = content.optJSONObject(j) ?: continue
                if (c.optString("type") == "audio" && c.optString("data").isNotEmpty()) found = c.getString("data")
            }
        }
        return found
    }

    companion object {
        const val URL = "https://generativelanguage.googleapis.com/v1beta/interactions"
        const val DEFAULT_MODEL = "gemini-3.8-flash-tts"
    }
}

/**
 * Speaks replies with the natural Gemini voice when that is wanted and possible (Auto: when online and there is a key), and with the phone's
 * own voice otherwise, so there is always a voice offline. Sentences that arrive while one is being made are joined into one request, which
 * keeps requests (and delay) down. A failed request falls back to the phone's voice for that text and the natural voice rests for a minute.
 */
class SmartSpeaker(
    private val device: DeviceSpeech,
    private val out: LiveAudioOut,
    private val synth: Synth?,
    private val mode: () -> String,
    private val online: () -> Boolean,
    private val hasKey: () -> Boolean,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val exec: Executor,
    private val onVoiceChange: () -> Unit = {},
    private val onFail: (String) -> Unit = {},
) : SpeechOut {
    private val lock = Object()
    private val pending = ArrayList<String>()
    private var working = false
    private var generation = 0
    @Volatile private var restUntil = 0L

    /** True when the next sentence will use the natural voice. */
    fun natural(): Boolean {
        if (synth == null || !hasKey() || now() < restUntil) return false
        return when (mode()) {
            "gemini" -> true
            "device" -> false
            else -> online()
        }
    }

    override fun speak(sentence: String) {
        val text = Speakable.clean(sentence)
        if (text.isEmpty()) return
        if (!natural()) { device.say(text); return }
        synchronized(lock) {
            pending.add(text)
            if (working) return
            working = true
        }
        exec.execute { work() }
    }

    private fun work() {
        while (true) {
            val chunk: String
            val gen: Int
            synchronized(lock) {
                if (pending.isEmpty()) { working = false; return }
                chunk = pending.joinToString(" ")
                pending.clear()
                gen = generation
            }
            try {
                val pcm = synth!!.synth(chunk)
                if (synchronized(lock) { gen == generation }) out.play(pcm)
            } catch (e: Exception) {
                restUntil = now() + 60_000L
                onFail(e.message ?: e.javaClass.simpleName)
                if (synchronized(lock) { gen == generation }) device.say(chunk)
            }
        }
    }

    override fun stop() {
        synchronized(lock) { generation++; pending.clear() }
        out.flush()
        device.stop()
    }

    /** Plays ready-made sound (a saved line), cutting off anything being said. */
    fun playClip(pcm: ByteArray) {
        stop()
        out.play(pcm)
    }

    fun isSpeaking(): Boolean = synchronized(lock) { pending.isNotEmpty() || working } || out.isBusy() || device.isSpeaking()
}

/** Whether the phone has a working internet connection right now. */
object Online {
    fun available(context: android.content.Context): Boolean {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
