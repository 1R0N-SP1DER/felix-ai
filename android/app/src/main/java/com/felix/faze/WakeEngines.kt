package com.felix.faze

import ai.picovoice.porcupine.Porcupine
import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer

/** Something that listens to 16 kHz mono audio, [frameSize] samples at a time, and says when the wake word was spoken. */
interface WakeEngine : AutoCloseable {
    val frameSize: Int
    fun process(frame: ShortArray): Boolean
    fun reset() {}
}

/** Offline. A tiny general speech model held to the phrase by a grammar, then checked word by word ([WakeVerifier]). */
class VoskWake(modelDir: File, private val phrase: String) : WakeEngine {
    private val model: Model
    private val recognizer: Recognizer

    init {
        LibVosk.setLogLevel(LogLevel.WARNINGS)
        model = Model(modelDir.absolutePath)
        recognizer = Recognizer(model, 16000f, Wake.strictGrammarJson(phrase))
        recognizer.setWords(true)
    }

    override val frameSize = 1600

    override fun process(frame: ShortArray): Boolean {
        if (!recognizer.acceptWaveForm(frame, frame.size)) return false // still mid-sound: only a finished result counts
        return WakeVerifier.accept(recognizer.result, phrase)
    }

    override fun reset() {
        recognizer.reset()
    }

    override fun close() {
        recognizer.close()
        model.close()
    }
}

/** Picovoice Porcupine: a model trained for the keyword itself, so far fewer false alarms. The AccessKey checks in online. */
class PorcupineWake(context: Context, accessKey: String, keywordFile: File?, builtIn: String, sensitivity: Float) : WakeEngine {
    private val engine: Porcupine

    init {
        val builder = Porcupine.Builder().setAccessKey(accessKey).setSensitivity(sensitivity)
        if (keywordFile != null) builder.setKeywordPath(keywordFile.absolutePath) else builder.setKeyword(Porcupine.BuiltInKeyword.valueOf(builtIn))
        engine = builder.build(context)
    }

    override val frameSize: Int = engine.frameLength

    override fun process(frame: ShortArray): Boolean = engine.process(frame) >= 0

    override fun close() {
        engine.delete()
    }
}

/** The built-in keywords offered when no custom file was imported. */
object PorcupineKeywords {
    val builtIn = listOf("JARVIS", "COMPUTER", "PORCUPINE", "BUMBLEBEE", "PICOVOICE", "TERMINATOR")
}

/** Runs the fixed root commands of [RootCommands] and nothing else. */
class AndroidRootShell : RootShell {
    override fun run(command: String): String? {
        if (!RootCommands.isAllowed(command)) return null
        return try {
            val p = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            if (!p.waitFor(8, TimeUnit.SECONDS)) {
                p.destroy()
                return null
            }
            val out = p.inputStream.bufferedReader().readText().trim()
            if (p.exitValue() == 0) out.ifEmpty { "ok" } else null
        } catch (e: Exception) {
            null
        }
    }
}
