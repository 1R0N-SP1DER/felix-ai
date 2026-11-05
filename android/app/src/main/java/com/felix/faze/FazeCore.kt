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

    // The look (Settings > General). Read by the screens, so a change shows at once.
    var motion by mutableStateOf(settings.motion)
        private set
    var textSize by mutableStateOf(settings.textSize)
        private set
    var fontChoice by mutableStateOf(settings.fontChoice)
        private set
    var greetingOn by mutableStateOf(settings.greeting)
        private set
    var provider by mutableStateOf(settings.provider)
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
    val memory = MemoryStore(File(app.filesDir, "memory.json"))
    private val broker = ApprovalBroker({ block -> main.post { block() } }, 60) { approval = it }
    private val engine: ChatEngine
    private var sttMs = 0L

    init {
        val client = ResilientChat(
            mapOf(
                "groq" to RemoteClient(transport, Groq.BASE, { settings.groqKey }, "Groq"),
                "gemini" to GeminiClient(transport, { settings.geminiKey }, { settings.thinkLevel }),
                "openrouter" to OpenRouterChat(
                    RemoteClient(transport, OpenRouter.BASE, { settings.openrouterKey }, "OpenRouter"),
                    { transport.get("${OpenRouter.BASE}/models", emptyMap(), 15000).body },
                    { settings.openrouterModel = it },
                ),
            ),
            { Fallbacks.plan(settings.provider, listOf("groq", "gemini", "openrouter").filter { settings.hasKey(it) }.toSet()) { settings.modelFor(it) } },
            { text -> main.post { status = text } },
        )
        val tools = GatedTools(Tools(AndroidDevice(app), transport, settings, memory), broker)
        engine = ChatEngine(Agent(client, tools), object : EngineConfig by settings {
            override fun memoryBlock(): String = if (settings.useMemory) memory.promptBlock(settings.user) else ""
        }, speaker, { block -> main.post { block() } }, executor, messages, this)
        speaker.setVoice(settings.voiceRate, settings.voicePitch)
        Palette.setAccent(Accents.byName(settings.accent))
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
        status = ""
        engine.send(text)
    }

    fun stop() {
        broker.denyAll()
        engine.stop()
    }

    fun resolveApproval(id: Long, allow: Boolean) = broker.resolve(id, allow)

    /** Settings > Voice: speed and pitch take effect on the next sentence. */
    fun setVoice(rate: Float, pitch: Float) {
        settings.voiceRate = rate
        settings.voicePitch = pitch
        speaker.setVoice(settings.voiceRate, settings.voicePitch)
    }

    /** The stop button in the top bar: be quiet now (the request, if one is running, carries on). */
    fun stopSpeaking() {
        speaker.stop()
    }

    /** The Listen button under a turn: say that message aloud (markdown and code are skipped, as on the web page). */
    fun speakText(text: String) {
        speaker.stop()
        for (sentence in Speakable.split(Speakable.clean(text), true).first) speaker.speak(sentence)
    }

    fun testVoice() {
        speaker.stop()
        speaker.speak("This is how I sound.")
    }

    fun setAccent(accent: Accent) {
        settings.accent = accent.name
        Palette.setAccent(accent)
    }

    // The model picker: each provider's own list, fetched when asked for and kept until refreshed.
    val modelLists = androidx.compose.runtime.mutableStateMapOf<String, List<ModelOption>>()
    var modelLoading by mutableStateOf("")
        private set
    var modelError by mutableStateOf("")
        private set
    var modelRev by mutableStateOf(0) // bumps when a model is picked, so screens showing it redraw
        private set

    fun loadModels(provider: String, force: Boolean = false) {
        if (modelLoading.isNotEmpty() || (!force && modelLists.containsKey(provider))) return
        modelLoading = provider
        modelError = ""
        Thread {
            try {
                val list = ModelCatalog(transport) { settings.keyFor(it) }.fetch(provider)
                main.post { modelLists[provider] = list; modelLoading = "" }
            } catch (e: RemoteError) {
                main.post { modelError = e.message ?: "couldn't load the list"; modelLoading = "" }
            } catch (e: Exception) {
                main.post { modelError = "couldn't load the list: ${e.javaClass.simpleName}"; modelLoading = "" }
            }
        }.start()
    }

    fun pickModel(provider: String, id: String) {
        settings.setModelFor(provider, id)
        useProvider(provider)
        modelRev++
    }

    fun useProvider(id: String) { settings.provider = id; provider = settings.provider }

    fun useMotion(on: Boolean) { settings.motion = on; motion = on }

    fun useTextSize(size: String) { settings.textSize = size; textSize = size }

    fun useFont(font: String) { settings.fontChoice = font; fontChoice = font }

    fun useGreeting(on: Boolean) { settings.greeting = on; greetingOn = on }

    fun renameChat(id: String, title: String) {
        executor.execute {
            store.rename(id, title)
            val list = store.list()
            main.post {
                chats.clear()
                chats.addAll(list)
            }
        }
    }

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

    /** Stop recording and throw the audio away (the overlay was closed, or the person backed out). */
    fun cancelRecording() {
        if (!recording) return
        recording = false
        recorder.stop()
        listenControl?.resume()
    }

    /** One short line for what the assistant is doing right now, or empty when it is idle. */
    fun liveLabel(): String = when {
        recording -> "Listening… tap Done when you finish"
        heard -> "Listening…"
        transcribing -> "Transcribing…"
        speaking -> "Speaking…"
        busy -> "Thinking…"
        else -> if (listening) "Wake word on" else ""
    }

    fun coreMode(): CoreMode = when {
        recording || heard -> CoreMode.LISTENING
        speaking -> CoreMode.SPEAKING
        busy || transcribing -> CoreMode.THINKING
        else -> CoreMode.IDLE
    }

    /** One spoken request, as 16 kHz mono PCM: transcribed, then sent like typed text. Safe to call from any thread. */
    fun handlePcm(pcm: ByteArray) {
        main.post {
            if (pcm.size < Recorder.RATE) { // under half a second of audio
                status = "didn't catch anything"
                return@post
            }
            if (Pcm.isSilent(pcm)) { // Android hands a background app a microphone that records only zeros
                status = "The microphone gave only silence. Android blocks it in the background: turn on Always listening, or open the app."
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
