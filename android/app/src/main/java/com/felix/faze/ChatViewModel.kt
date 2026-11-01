package com.felix.faze

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import java.util.concurrent.Executors

/** Holds the conversation for the screen. The logic lives in [ChatEngine]; this only wires it to Android. */
class ChatViewModel(app: Application) : AndroidViewModel(app), EngineListener {
    val settings = AppSettings(app)
    val messages = mutableStateListOf<ChatMessage>()

    var busy by mutableStateOf(false)
        private set
    var recording by mutableStateOf(false)
        private set
    var status by mutableStateOf("")
        private set

    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val transport = UrlTransport()
    private val speaker = Speaker(app)
    private val recorder = Recorder()
    private val stt = Stt(transport, { settings.groqKey })
    private val engine: ChatEngine

    init {
        val client = RemoteClient(transport, Groq.BASE, { settings.groqKey }, "Groq")
        val agent = Agent(client, Tools(AndroidDevice(app), transport, settings))
        engine = ChatEngine(agent, settings, speaker, { block -> main.post { block() } }, executor, messages, this)
    }

    override fun onBusy(busy: Boolean) {
        this.busy = busy
    }

    override fun onStatus(text: String) {
        status = text
    }

    fun send(text: String) = engine.send(text)

    fun stop() = engine.stop()

    fun startRecording() {
        speaker.stop()
        if (recorder.start()) {
            recording = true
            status = ""
        } else {
            status = "couldn't open the microphone"
        }
    }

    fun finishRecording() {
        if (!recording) return
        recording = false
        val pcm = recorder.stop()
        if (pcm.size < Recorder.RATE) { // under half a second of audio
            status = "didn't catch anything"
            return
        }
        status = "transcribing..."
        executor.execute {
            try {
                val text = stt.transcribe(Wav.fromPcm(pcm, Recorder.RATE))
                main.post {
                    if (text.isEmpty()) {
                        status = "didn't catch that"
                    } else {
                        status = ""
                        engine.send(text)
                    }
                }
            } catch (e: SttError) {
                main.post { status = e.message ?: "speech recognition failed" }
            } catch (e: Exception) {
                main.post { status = "speech recognition failed: ${e.message ?: e.javaClass.simpleName}" }
            }
        }
    }

    override fun onCleared() {
        if (recording) recorder.stop()
        speaker.shutdown()
        executor.shutdown()
    }
}
