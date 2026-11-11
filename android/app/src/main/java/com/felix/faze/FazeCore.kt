package com.felix.faze

import android.app.Application
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.Executors
import org.json.JSONObject

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
    private lateinit var rawTools: Tools
    val scheduler = AndroidScheduler(app, ScheduleStore(File(app.filesDir, "schedule.json")))
    private val scheduleRunner = ScheduleRunner(app, this, scheduler)
    fun runDueTasks(done: () -> Unit = {}) = main.post { scheduleRunner.runDue(done) }

    /** A switch the person approved when they scheduled it. Only the allowed ones, and not through the chat or the model. */
    fun runApprovedAction(tool: String, enabled: Boolean): ToolResult {
        if (tool !in ScheduledActions.ALLOWED) return ToolResult(false, "not allowed")
        return rawTools.execute(tool, org.json.JSONObject().put("enabled", enabled))
    }
    fun rearmSchedule() = scheduler.rearm()
    private val recorder = Recorder()
    private val stt = Stt(transport, { settings.groqKey })
    private val store = ChatStore(File(app.filesDir, "chats"))
    val memory = MemoryStore(File(app.filesDir, "memory.json"))
    val toolStore = ToolStore(File(app.filesDir, "tools.json"))
    private val broker = ApprovalBroker({ block -> main.post { block() } }, 60) { approval = it }
    private val engine: ChatEngine
    private lateinit var gatedTools: ToolRunner
    private lateinit var gate: GatedTools
    private lateinit var liveTools: ToolRunner

    /** The listening bar and the bubble: the mic level while listening, the output level while it speaks (0..1). */
    var micLevel by mutableStateOf(0f)
    var outLevel by mutableStateOf(0f)
    var micMuted by mutableStateOf(false)

    /** Whether the app is on screen. When it is not and a conversation is going, the floating bubble shows. */
    @Volatile var appVisible = true
    @Volatile private var serviceUp = false

    /** Lets the model end live mode or voice chat itself, after its goodbye has been spoken. */
    private val liveControl = object : LiveControl {
        override fun active(): Boolean = liveOn || voiceOn
        override fun endAfterSpeech() {
            Thread {
                EndAfterSpeech.run({ speaker.isSpeaking() || (live?.isSpeaking() ?: false) }, { Thread.sleep(it) }, { main.post { closeLiveScreen() } })
            }.start()
        }
    }

    /** True while the half-screen overlay (the volume-key chord) is open: it stands in for the bubble. */
    @Volatile var overlayUp = false

    /** Voice chat (you talk, Whisper listens, the chat answers aloud): whether it is on, and its one-line state for Settings. */
    var voiceOn by mutableStateOf(false)
    var voiceState by mutableStateOf("")
    @Volatile private var voiceLoop: VoiceLoop? = null
    @Volatile private var voiceActive = false // replies are spoken while voice chat is on, whatever "Speak replies aloud" says
    private val voiceWorker = Executors.newSingleThreadExecutor()
    private val fillerWorker = Executors.newCachedThreadPool()

    /** Live mode (a spoken conversation with Gemini Live): whether it is on, and its one-line state for Settings. */
    var liveOn by mutableStateOf(false)
    var liveState by mutableStateOf("")
    @Volatile private var live: LiveSession? = null

    /** What is being shown to the model in live mode: nothing, the camera, or the screen. */
    var shareMode by mutableStateOf(ShareMode.NONE)
    private var shareSource: VideoSource? = null
    private val liveStarter = Executors.newSingleThreadExecutor()
    private val liveWorker = Executors.newSingleThreadExecutor() // tool calls: one may wait up to a minute for an approval
    private var sttMs = 0L

    val learned = LearnedRoutes(File(app.filesDir, "learned_routes.json"))
    private val router = FastRoute(learned, NeedleClient(transport), { settings.fastRoutes }, { settings.needleOn }, { PermissionPolicy.tier(it) == Tier.SAFE })

    private val localServer: LocalServer = LocalServerControl(
        run = { cmd, ms -> Su.run(cmd, ms) },
        alive = { try { transport.get("${LocalLlama.BASE}/models", emptyMap(), 1500).let { if (it.status == 200) LocalLlama.parseServed(it.body) else null } } catch (e: Exception) { null } },
        binary = { settings.offlineBinary.ifEmpty { LocalLlama.pickBinary(Su.run(LocalLlama.findBinaryCommand(), 15_000))?.also { settings.offlineBinary = it } } },
        threads = { settings.offlineThreads },
        ctx = { settings.offlineCtx },
    )

    init {
        val client = ResilientChat(
            mapOf(
                "groq" to RemoteClient(transport, Groq.BASE, { settings.groqKey }, "Groq"),
                "gemini" to GeminiClient(transport, { settings.geminiKey }, { settings.thinkLevel }),
                "offline" to OfflineChat(
                    RemoteClient(transport, LocalLlama.BASE, { "local" }, "Offline", 400, JSONObject().put("chat_template_kwargs", JSONObject().put("enable_thinking", false))),
                    localServer, { settings.offlineModel }, { text -> main.post { status = text } },
                ),
                "openrouter" to OpenRouterChat(
                    RemoteClient(transport, OpenRouter.BASE, { settings.openrouterKey }, "OpenRouter"),
                    { transport.get("${OpenRouter.BASE}/models", emptyMap(), 15000).body },
                    { settings.openrouterModel = it },
                ),
            ),
            { Fallbacks.plan(settings.provider, listOf("groq", "gemini", "openrouter").filter { settings.hasKey(it) }.toSet()) { settings.modelFor(it) } },
            { text -> main.post { status = text } },
        )
        val userTools = UserTools(
            toolStore,
            JsSandbox({ url -> val r = transport.get(url, emptyMap(), 10000); if (r.status != 200) throw RuntimeException("HTTP ${r.status}"); r.body.take(200_000) }, power = AndroidPowers(app)),
            { settings.allowPowered },
            { PermissionPolicy.all().map { it.first }.toSet() },
        )
        rawTools = Tools(AndroidDevice(app), transport, settings, memory, userTools, ScreenEyes(RemoteClient(transport, Groq.BASE, { settings.groqKey }, "Groq", 700), { settings.visionModel }, { AndroidScreenCapture().jpeg() }), AndroidHands(app), scheduler, AndroidSwitches(), liveControl)
        gate = GatedTools(rawTools, broker, extraTier = { name -> if (userTools.has(name)) Tier.SAFE else null }, approvalMode = { settings.approvalMode }, phoneControl = { settings.phoneControl })
        val tools: ToolRunner = gate
        gatedTools = tools
        // A tool that takes over the screen waits for the assistant to finish speaking, unless the bubble keeps the conversation going outside the app.
        val speakingNow = { speaker.isSpeaking() || (live?.isSpeaking() ?: false) }
        liveTools = SpeechAwareTools(tools, { true }, speakingNow, { bubbleReady() })
        engine = ChatEngine(Agent(client, SpeechAwareTools(tools, { voiceActive }, speakingNow, { bubbleReady() }), router = router), object : EngineConfig by settings {
            override fun memoryBlock(): String = if (settings.useMemory) memory.promptBlock(settings.user) else ""
            override fun speak(): Boolean = settings.speakReplies || voiceActive
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
        gate.revokeControl()
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
                val list = if (provider == "offline") {
                    LocalLlama.parseModels(Su.run(LocalLlama.scanCommand(), 20_000)).ifEmpty {
                        throw RemoteError("empty", "No .gguf models found in Termux's home, Download or models folder (offline models need root)")
                    }
                } else ModelCatalog(transport) { settings.keyFor(it) }.fetch(provider)
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

    fun useProvider(id: String) {
        val was = provider
        settings.provider = id
        provider = settings.provider
        if (was == "offline" && provider != "offline") Thread { localServer.stop() }.start() // give the memory back
    }

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

    /** The conversation can carry on outside the app: the service is up and the bubble may be drawn. */
    private fun bubbleReady(): Boolean = overlayUp || (serviceUp && android.provider.Settings.canDrawOverlays(app))

    /** A microphone foreground service keeps the conversation alive when the app is left. It has to be started while the app is on screen. */
    private fun startLiveService() {
        try {
            ContextCompat.startForegroundService(app, Intent(app, LiveService::class.java))
            serviceUp = true
        } catch (e: Exception) {
            serviceUp = false
            status = "It can't keep going outside the app: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun stopLiveServiceIfIdle() {
        if (!liveOn && !voiceOn && serviceUp) {
            app.stopService(Intent(app, LiveService::class.java))
            serviceUp = false
        }
    }

    /** Mute: the mic stays open (the bar shows it) but nothing is heard. */
    fun toggleMute() {
        micMuted = !micMuted
        live?.muted = micMuted
        voiceLoop?.muted = micMuted
        if (micMuted) micLevel = 0f
    }

    /** The close button of the live screen: hangs up whichever conversation is going. */
    fun closeLiveScreen() {
        stopLive()
        stopVoiceChat()
    }

    private fun voiceEnded() {
        voiceOn = false
        voiceActive = false
        voiceLoop = null
        micLevel = 0f
        outLevel = 0f
        listenControl?.resume()
        stopLiveServiceIfIdle()
    }

    /** What the person said goes to the chat as soon as it is free (a reply that was just talked over may still be winding down). */
    private fun sendWhenFree(text: String, tries: Int = 30) {
        if (!engine.isBusy()) send(text) else if (tries > 0) main.postDelayed({ sendWhenFree(text, tries - 1) }, 100)
    }

    /** Starts voice chat. The wake-word listener is paused meanwhile (one microphone). The outcome shows in [voiceState]. */
    fun startVoiceChat() {
        if (voiceLoop != null) return
        if (settings.groqKey.isEmpty()) { voiceState = "Voice chat needs a Groq key (Whisper listens)"; return }
        voiceOn = true
        voiceActive = true
        micMuted = false
        voiceState = "Starting…"
        startLiveService()
        liveStarter.execute {
            listenControl?.pause()
            val host = object : VoiceHost {
                override fun transcribe(wav: ByteArray): String = stt.transcribe(wav)
                override fun ask(text: String) { main.post { sendWhenFree(text) } }
                override fun interrupt() { main.post { engine.stop() } }
                override fun engineBusy(): Boolean = busy
                override fun speaking(): Boolean = speaker.isSpeaking()
                override fun replyStarted(): Boolean = messages.lastOrNull()?.let { it.role == "assistant" && it.text.isNotEmpty() } ?: false
                override fun say(phrase: String) { speaker.speak(phrase) }
                override fun status(text: String) {
                    main.post {
                        voiceState = text
                        if (text.startsWith("Voice chat could not") || text.startsWith("Could not transcribe")) this@FazeCore.status = text
                    }
                }
                override fun bargeIn(): Boolean = headphonesConnected(app)
            }
            val fast = ModelFiller(RemoteClient(transport, Groq.BASE, { settings.groqKey }, "Groq"), settings.fillerModel, { settings.groqKey.isNotEmpty() }, fillerWorker)
            val loop = VoiceLoop(host, voiceWorker, fast, fillerWorker, fillersOn = { settings.voiceFillers })
            voiceLoop = loop
            val failed = loop.start(AndroidLiveMic(app) { l -> main.post { micLevel = if (micMuted) 0f else l } })
            if (failed != null) main.post { voiceState = "Voice chat could not start: $failed"; voiceEnded() }
        }
    }

    fun stopVoiceChat() {
        val l = voiceLoop ?: return
        liveStarter.execute { l.stop(); main.post { voiceEnded() } }
    }

    private fun liveEnded() {
        stopShare()
        liveOn = false
        live = null
        micLevel = 0f
        outLevel = 0f
        listenControl?.resume()
        stopLiveServiceIfIdle()
    }

    /** Shows the camera or the screen to the model (live mode only). [done] gets null when it started, otherwise why not (on the main thread). Sharing again switches source. */
    fun startShare(mode: String, done: (String?) -> Unit) {
        val session = live
        if (!liveOn || session == null || !session.isRunning()) { done("Camera and screen share work in live mode (Gemini). Start it with the waveform button."); return }
        stopShare()
        val gate = FrameGate()
        val source: VideoSource = when (mode) {
            ShareMode.CAMERA -> CameraSource(app)
            ShareMode.SCREEN -> ScreenSource()
            else -> { done("unknown source"); return }
        }
        shareSource = source
        source.start({ jpeg ->
            if (gate.tryBegin()) try { session.sendFrame(jpeg) } finally { gate.done() }
        }, { failed ->
            main.post {
                if (failed == null && shareSource === source && liveOn) shareMode = mode
                else { if (shareSource === source) { source.stop(); shareSource = null } }
                done(failed)
            }
        })
    }

    fun stopShare() {
        shareSource?.stop()
        shareSource = null
        shareMode = ShareMode.NONE
    }

    /** Starts live mode. The wake-word listener is paused meanwhile (one microphone). The outcome shows in [liveState]. */
    fun startLive() {
        if (live != null) return
        if (settings.geminiKey.isEmpty()) { liveState = "Live mode needs a Gemini key"; return }
        liveOn = true
        micMuted = false
        liveState = "Connecting…"
        startLiveService()
        liveStarter.execute {
            listenControl?.pause()
            val schema = liveTools.schema()
            val names = (0 until schema.length()).map { schema.getJSONObject(it).getJSONObject("function").getString("name") }
            val session = LiveSession(
                OkHttpLiveConnector(), settings.geminiKey, settings.liveModel, settings.liveVoice, true,
                LiveSession.instruction(settings.user, if (settings.useMemory) memory.promptBlock(settings.user) else "", names),
                liveTools, AndroidLiveMic(app) { l -> main.post { micLevel = if (micMuted) 0f else l } }, AndroidLiveSpeaker { l -> main.post { outLevel = l } }, liveWorker,
                { text ->
                    main.post {
                        liveState = text
                        if (text.startsWith("Live mode could not") || text.startsWith("Live mode ended")) status = text
                    }
                }, { _, _ -> },
                { main.post { liveEnded() } },
                { !headphonesConnected(app) },
            )
            live = session
            if (session.start() != null) main.post { liveEnded() }
        }
    }

    fun stopLive() {
        val s = live ?: return
        liveStarter.execute { s.stop() }
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
