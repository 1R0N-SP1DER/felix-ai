package com.felix.faze

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.util.concurrent.Executors

/** Lets the push-to-talk button borrow the microphone from the always-on service and hand it back. */
interface ListenControl {
    fun pause()
    fun resume()
}

/**
 * Everything one running app shares: the conversation, the tools behind the approval gate, speech, saved chats. The
 * screen and the listening service both talk to this one object, so a spoken request and a typed one are the same thing.
 * Every property is written on the main thread.
 */
class FazeCore(private val app: Application) : EngineListener {
    val settings = AppSettings(app)
    val messages = mutableStateListOf<ChatMessage>()
    val chats = mutableStateListOf<ChatRecord>()

    var busy by mutableStateOf(false)
        private set
    var status by mutableStateOf("")
        private set
    var timing by mutableStateOf("")
        private set
    var approval by mutableStateOf<ApprovalRequest?>(null)
        private set
    var recording by mutableStateOf(false)
        private set
    var transcribing by mutableStateOf(false)
        private set
    var speaking by mutableStateOf(false)
        private set
    var listening by mutableStateOf(false) // the always-on service is running
    var heard by mutableStateOf(false) // the wake word fired and a request is being captured
    var modelReady by mutableStateOf(ModelStore.ready(app) != null)
        private set
    var modelProgress by mutableStateOf(-1) // -1 not downloading, else 0..99
        private set
    var chatId by mutableStateOf(newId())
        private set

    @Volatile
    var listenControl: ListenControl? = null

    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val transport = UrlTransport()
    private val speaker = Speaker(app)
    private val recorder = Recorder()
    private val stt = Stt(transport, { settings.groqKey })
    private val store = ChatStore(File(app.filesDir, "chats"))
    private val broker = ApprovalBroker({ block -> main.post { block() } }, 60) { approval = it }
    private val engine: ChatEngine
    private var sttMs = 0L

    init {
        val client = RemoteClient(transport, Groq.BASE, { settings.groqKey }, "Groq")
        val tools = GatedTools(Tools(AndroidDevice(app), transport, settings), broker)
        engine = ChatEngine(Agent(client, tools), settings, speaker, { block -> main.post { block() } }, executor, messages, this)
        refreshChats()
        main.post(object : Runnable {
            override fun run() {
                speaking = speaker.isSpeaking()
                main.postDelayed(this, 400)
            }
        })
    }

    private fun newId(): String = "c" + System.currentTimeMillis().toString(36)

    /** True while the wake listener should stay quiet: it would otherwise hear the assistant's own voice. */
    fun occupied(): Boolean = busy || speaking || transcribing || recording

    fun reportStatus(text: String) {
        main.post { status = text }
    }

    fun markHeard(value: Boolean) {
        main.post { heard = value }
    }

    fun markListening(value: Boolean) {
        main.post { listening = value }
    }

    // ---- EngineListener

    override fun onBusy(busy: Boolean) {
        this.busy = busy
        if (!busy) save()
    }

    override fun onStatus(text: String) {
        status = text
    }

    override fun onTiming(llmMs: Long) {
        timing = TimingText.format(sttMs, llmMs)
        sttMs = 0
    }

    // ---- conversation

    fun send(text: String) {
        timing = ""
        engine.send(text)
    }

    fun stop() {
        broker.denyAll()
        engine.stop()
    }

    fun resolveApproval(id: Long, allow: Boolean) = broker.resolve(id, allow)

    private fun save() {
        val copy = messages.toList()
        val id = chatId
        executor.execute {
            store.save(id, copy)
            val list = store.list()
            main.post {
                chats.clear()
                chats.addAll(list)
            }
        }
    }

    fun refreshChats() {
        executor.execute {
            val list = store.list()
            main.post {
                chats.clear()
                chats.addAll(list)
            }
        }
    }

    fun newChat() {
        stop()
        messages.clear()
        chatId = newId()
        timing = ""
        status = ""
    }

    fun openChat(id: String) {
        stop()
        executor.execute {
            val record = store.load(id)
            main.post {
                if (record != null) {
                    messages.clear()
                    messages.addAll(record.messages)
                    chatId = record.id
                    timing = ""
                    status = ""
                }
            }
        }
    }

    fun deleteChat(id: String) {
        executor.execute {
            store.delete(id)
            val list = store.list()
            main.post {
                chats.clear()
                chats.addAll(list)
                if (id == chatId) newChat()
            }
        }
    }

    fun search(query: String): List<ChatRecord> = store.list(query)

    // ---- voice

    fun startRecording() {
        speaker.stop()
        listenControl?.pause()
        if (recorder.start()) {
            recording = true
            status = ""
        } else {
            listenControl?.resume()
            status = "couldn't open the microphone"
        }
    }

    fun finishRecording() {
        if (!recording) return
        recording = false
        val pcm = recorder.stop()
        listenControl?.resume()
        handlePcm(pcm)
    }

    /** One spoken request, as 16 kHz mono PCM: transcribed, then sent like typed text. Safe to call from any thread. */
    fun handlePcm(pcm: ByteArray) {
        main.post {
            if (pcm.size < Recorder.RATE) { // under half a second of audio
                status = "didn't catch anything"
                return@post
            }
            status = "transcribing..."
            transcribing = true
            executor.execute {
                val t0 = System.nanoTime()
                try {
                    val text = stt.transcribe(Wav.fromPcm(pcm, Recorder.RATE))
                    val ms = (System.nanoTime() - t0) / 1_000_000
                    main.post {
                        transcribing = false
                        if (text.isEmpty()) {
                            status = "didn't catch that"
                        } else {
                            status = ""
                            sttMs = ms
                            send(text)
                        }
                    }
                } catch (e: SttError) {
                    main.post {
                        transcribing = false
                        status = e.message ?: "speech recognition failed"
                    }
                } catch (e: Exception) {
                    main.post {
                        transcribing = false
                        status = "speech recognition failed: ${e.message ?: e.javaClass.simpleName}"
                    }
                }
            }
        }
    }

    // ---- wake word model

    fun downloadModel() {
        if (modelProgress >= 0) return
        modelProgress = 0
        Thread {
            try {
                ModelStore.download(app) { p -> main.post { modelProgress = p } }
                main.post {
                    modelProgress = -1
                    modelReady = true
                    status = ""
                }
            } catch (e: Exception) {
                main.post {
                    modelProgress = -1
                    status = "model download failed: ${e.message ?: e.javaClass.simpleName}"
                }
            }
        }.start()
    }
}
