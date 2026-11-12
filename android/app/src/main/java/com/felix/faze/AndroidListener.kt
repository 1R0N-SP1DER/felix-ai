package com.felix.faze

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** What to do after the recogniser failed, so the retry rule is plain and tested without a phone. */
object ListenRetry {
    const val ERROR_NETWORK_TIMEOUT = 1
    const val ERROR_NETWORK = 2
    const val ERROR_NO_MATCH = 7
    const val ERROR_BUSY = 8
    const val ERROR_SPEECH_TIMEOUT = 6
    const val ERROR_PERMISSIONS = 9

    /** Quiet or busy outcomes are normal: listen again. A missing permission is not, so stop and say why. */
    fun again(code: Int): Boolean = code != ERROR_PERMISSIONS

    fun delayMs(code: Int): Long = when (code) {
        ERROR_NO_MATCH, ERROR_SPEECH_TIMEOUT -> 150L
        ERROR_BUSY -> 600L
        else -> 1000L
    }

    fun message(code: Int): String? = when (code) {
        ERROR_PERMISSIONS -> "allow the microphone for Faze first"
        ERROR_NETWORK, ERROR_NETWORK_TIMEOUT -> "Android's speech recognition needs a connection on this phone"
        else -> null
    }
}

/**
 * Listens with Android's own speech recogniser: the fallback when Groq's Whisper has no key or is down. It must run on the main
 * thread. It hears one phrase at a time, hands the text over, waits while the assistant is busy or talking (so it does not hear
 * itself), and then listens again.
 */
class AndroidListener(
    private val context: Context,
    private val onText: (String) -> Unit,
    private val onLevel: (Float) -> Unit,
    private val onStopped: (String) -> Unit,
    private val shouldWait: () -> Boolean,
) {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    @Volatile private var running = false
    @Volatile var muted = false
    @Volatile var hearing = false
        private set

    fun isRunning(): Boolean = running

    /** Null when it started, otherwise why not. */
    fun start(): String? {
        if (running) return null
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return "this phone has no speech recognition service"
        running = true
        main.post { begin() }
        return null
    }

    fun stop() {
        running = false
        hearing = false
        main.removeCallbacksAndMessages(null)
        main.post {
            try { recognizer?.cancel(); recognizer?.destroy() } catch (e: Exception) { /* already gone */ }
            recognizer = null
        }
    }

    private fun begin() {
        if (!running) return
        if (muted || shouldWait()) { main.postDelayed({ begin() }, 300); return }
        try {
            val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also { it.setRecognitionListener(listener); recognizer = it }
            r.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                    .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                    .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L),
            )
        } catch (e: Exception) {
            fail("speech recognition could not start: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun again(ms: Long) {
        hearing = false
        onLevel(0f)
        if (running) main.postDelayed({ begin() }, ms)
    }

    private fun fail(why: String) {
        running = false
        hearing = false
        onLevel(0f)
        onStopped(why)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() { hearing = true }
        override fun onRmsChanged(rmsdB: Float) { if (!muted) onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)) }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { hearing = false; onLevel(0f) }
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onError(error: Int) {
            if (!ListenRetry.again(error)) { fail(ListenRetry.message(error) ?: "speech recognition stopped"); return }
            again(ListenRetry.delayMs(error))
        }

        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()
            if (text.length >= 2 && !muted) onText(text)
            again(200)
        }
    }
}
