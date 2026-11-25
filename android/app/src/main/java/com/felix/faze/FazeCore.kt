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
    private val imageShelf = ImageShelf()

    /** What Faze did, for the activity log in Settings. */
    /** The computers (and later other devices) linked to this phone. The phone is the hub: any number of them can pair. */
    val linkPeers = LinkPeers({ settings.linkPeers }, { settings.linkPeers = it })
    var linkTick by androidx.compose.runtime.mutableIntStateOf(0)
        private set
    var linkCode by mutableStateOf("")
        private set
    val linkRemotes = LinkRemotes({ settings.linkRemotes }, { settings.linkRemotes = it })
    var foundPhones by mutableStateOf<List<LinkClient.Companion.Found>>(emptyList())
    val linkClient: LinkClient = LinkClient(settings.linkId, { android.os.Build.MODEL ?: "Phone" }, linkRemotes, onChange = { main.post { linkTick++ } })
    val link: LinkServer = LinkServer(linkPeers, settings.linkId, { android.os.Build.MODEL ?: "Phone" }, handler = { _, msg -> sync.handle(msg) ?: peerTasks.handle(msg) ?: screenShare.handle(msg) ?: phoneFiles.handle(msg) ?: phoneModels.handle(msg) ?: phoneMedia.handle(msg) ?: phoneInbox.handle(msg) ?: phoneClip.handle(msg) ?: pcFrames.handle(msg) ?: screenStream.handle(msg) }, onChange = { main.post { linkTick++; if (!link.pairingOpen()) linkCode = ""; syncClipWatch() } })

    fun setLink(on: Boolean) {
        settings.linkOn = on
        syncBackground()
        Thread { try { if (on) link.start() else link.stop() } catch (e: Exception) { main.post { status = "Could not open the link: " + (e.message ?: "") } }; main.post { linkTick++ } }.start()
    }

    /** The models the connected computer's Ollama has, for the model sheet. Empty when no computer is connected. */
    var pcModels by mutableStateOf<List<String>>(emptyList())
        private set
    var pcModelsLoading by mutableStateOf(false)
        private set
    var pcModelsError by mutableStateOf("")
        private set

    val pcConnected: Boolean get() { linkTick; return link.connected().isNotEmpty() }

    fun loadPcModels() {
        if (pcModelsLoading || link.connected().isEmpty()) return
        pcModelsLoading = true
        pcModelsError = ""
        Thread {
            try {
                val r = link.ask(link.connected().first(), JSONObject().put("t", "pc_models"), 8000)
                val names = PcModels.parse(r)
                main.post { pcModels = names; pcModelsError = if (names.isEmpty()) r.optString("message", "Ollama on the PC has no models. Is it running?") else ""; pcModelsLoading = false }
            } catch (e: Exception) {
                main.post { pcModelsError = "could not reach the PC: ${e.message ?: ""}"; pcModelsLoading = false }
            }
        }.start()
    }

    /** Use one of the PC's models straight up, until another is chosen. */
    fun usePcModel(name: String) { settings.setModelFor("pc", name); modelRev++; useProvider("pc") }

    fun startPairing() { linkCode = link.openPairing(); linkTick++ }

    fun stopPairing() { link.closePairing(); linkCode = ""; linkTick++ }

    /** Asks the connected computer something; throws IOException when there is none. */
    fun pcAsk(msg: JSONObject, timeoutMs: Long): JSONObject = link.ask(link.connected().firstOrNull() ?: throw java.io.IOException("no computer is connected"), msg, timeoutMs)

    private val remoteWorker = Executors.newSingleThreadExecutor()

    /** The remote-receiver controller for the connected computer (its screen, mouse and keyboard). [onError] is called on a background thread with the computer's own words. */
    fun pcRemote(onError: (String) -> Unit): PcRemote = PcRemote(
        { msg, timeout -> pcAsk(msg, timeout) },
        { r -> remoteWorker.execute(r) },
        { m -> main.post { onError(m) } },
        { m -> link.notify(m) },
    )

    /** Asks the connected computer to open a window showing this phone's screen (it needs Allow screen view and control to be on). */
    fun showPhoneOnPc() {
        val id = link.connected().firstOrNull() ?: run { status = "No computer is connected."; return }
        executor.execute {
            val msg = try {
                val r = link.ask(id, JSONObject().put("t", "mirror_open"), 15_000)
                if (r.optString("t") == "error") r.optString("message", "the computer refused") else if (settings.linkScreen) "Opened on your computer." else "Opened on your computer. Turn on Allow screen view and control in Settings > Advanced > Devices to see the screen."
            } catch (e: Exception) { "The computer could not be reached: ${e.message ?: ""}" }
            main.post { status = msg }
        }
    }

    fun removeDevice(id: String) {
        if (linkRemotes.get(id) != null) linkClient.forget(id) else { linkPeers.remove(id); link.drop(id) }
        linkTick++
    }

    /** Pairs with another phone that is showing a code, then connects to it. Runs off the main thread. */
    fun pairPhone(host: String, code: String) {
        Thread {
            val msg = try {
                val r = linkClient.pair(host.trim().substringBefore(':'), host.trim().substringAfter(':', LinkServer.PORT.toString()).toIntOrNull() ?: LinkServer.PORT, code)
                linkClient.connect(r.id)
                "Paired with " + r.name + "."
            } catch (e: Exception) { e.message ?: "Could not pair." }
            main.post { status = msg; linkTick++ }
        }.start()
    }

    /** Looks for other Faze phones on the network and fills the list the Devices screen shows. */
    fun findPhones() {
        Thread {
            val f = LinkClient.discover(settings.linkId)
            main.post { foundPhones = f; linkTick++; if (f.isEmpty()) status = "No other Faze phones answered. Check Allow devices is on there, or type its address." }
        }.start()
    }

    fun connectPhone(id: String) {
        Thread {
            val msg = try { linkClient.connect(id); null } catch (e: Exception) { e.message ?: "Could not connect." }
            if (msg != null) main.post { status = msg; linkTick++ }
        }.start()
    }

    /** What the Devices screen lists: each paired device, whether it is connected, and how. */
    fun deviceRows(): List<DeviceRow> {
        linkTick
        val on = link.connected()
        val phones = linkClient.connected()
        return (linkPeers.all().map { DeviceRow(it.id, it.name, it.os, it.id in on, link.connectionOf(it.id)) } +
            linkRemotes.all().map { DeviceRow(it.id, it.name, "android", it.id in phones, linkClient.connectionOf(it.id)) }).sortedByDescending { it.connected }
    }

    val activity = ActivityLog(java.io.File(app.filesDir, "activity.log"))
    private val MAX_PENDING = 6
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
    private val deviceSpeaker = Speaker(app)
    private val limits = ProviderLimits()

    /** Auto mode: chooses the models for this request (see [AutoMode]). */
    private fun autoPlan(messages: org.json.JSONArray): List<Attempt> {
        val text = AutoMode.lastUserText(messages)
        val task = AutoMode.kindOf(text, Effort.norm(settings.effortLevel))
        val scanned = modelLists["offline"].orEmpty().map { it.id }
        val keys = listOf("groq", "gemini", "openrouter", "nvidia").filter { settings.hasKey(it) }.toSet()
        val plan = AutoMode.plan(
            task, Online.available(app), keys, { limits.isResting(it) }, { settings.modelFor(it) },
            AutoMode.offlineFor(Task.CASUAL, settings.autoCasualModel, settings.autoDeepModel, scanned, settings.offlineModel),
            AutoMode.offlineFor(task.let { if (it == Task.CASUAL) Task.DEEP else it }, settings.autoCasualModel, settings.autoDeepModel, scanned, settings.offlineModel),
            pc = link.connected().isNotEmpty(),
        )
        val ordered = plan.sortedBy { it.provider == avoid } // after a thumbs down, retry starts with a different model (stable: the rest keep their order)
        activity.add("auto", "${task.name.lowercase()} request: ${ordered.take(3).joinToString(", ") { it.provider }}")
        return ordered
    }

    private val voiceLines = VoiceLines(
        java.io.File(app.filesDir, "voice_lines"),
        GeminiSpeech(transport, { settings.geminiKey }, { settings.ttsModel }, { settings.liveVoice }),
        GoogleTakeover.LINES,
        { "${settings.liveVoice}-${settings.ttsModel}" },
    )

    private val speaker = SmartSpeaker(
        deviceSpeaker,
        AndroidLiveSpeaker { l -> main.post { outLevel = l } },
        GeminiSpeech(transport, { settings.geminiKey }, { settings.ttsModel }, { settings.liveVoice }),
        { settings.voiceEngine },
        { Online.available(app) },
        { settings.geminiKey.isNotEmpty() },
        exec = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "natural-voice").also { it.isDaemon = true } },
        onFail = { why -> activity.add("voice", "Gemini voice failed, using the phone voice for a minute: ${why.take(160)}") },
    )

    /** The phone's own voices, for the picker in Settings. */
    fun deviceVoices(): List<VoiceOption> = deviceSpeaker.voices().let { all -> if (settings.offlineVoicesOnly) all.filter { it.offline } else all }

    fun setDeviceVoice(name: String) {
        settings.deviceVoice = name
        deviceSpeaker.setVoiceName(name)
    }
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
    private val rootCheck = RootCheck({ Su.run("id", 3000).contains("uid=0") })
    private val rootNow: Boolean get() = ControlMode.useRoot(settings.controlMode) { rootCheck.available() }
    private val a11yShot = A11yShot()
    /** A screenshot the way the control mode says: root screencap or the accessibility screenshot. */
    private fun screenJpeg(): ByteArray = if (rootNow) AndroidScreenCapture().jpeg() else a11yShot.jpeg()
    private val hands: Hands = ChoosingHands(AndroidHands(app), NoRootHands(AndroidA11y(app)), { settings.controlMode }) { rootCheck.available() }
    private val screenShare = ScreenShare(
        { screenJpeg() }, hands,
        { app.resources.displayMetrics.let { it.widthPixels to it.heightPixels } },
        { settings.linkScreen }, { LockOverlayActivity.isLocked(app) }, { activity.add("link", it) },
    )
    private val screenStream = ScreenStream(
        { w, h -> RootScreenSource(w, h) },
        { app.resources.displayMetrics.let { it.widthPixels to it.heightPixels } },
        { settings.linkScreen }, { LockOverlayActivity.isLocked(app) },
        { m -> link.notify(m) }, log = { activity.add("link", it) },
        unavailable = { if (rootNow) "" else "Live video needs root on this phone for now. Pictures work without it." },
    )
    /** Copies of offline models staged for a computer to read (see PhoneModels). */
    private val phoneModels = PhoneModels({ settings.linkModels }, { cmd, ms -> try { Su.run(cmd, ms) } catch (_: Exception) { "" } }, { activity.add("link", it) })
    private val phoneInbox = PhoneInbox({ settings.linkReceive }, AndroidInboxStore(app), AndroidInboxNotify(app), { activity.add("link", it) })
    private val phoneMedia = PhoneMedia({ settings.linkMedia }, AndroidMic(app), AndroidCam(app), { activity.add("link", it) }, AndroidSound(app))
    private val phoneFiles = PhoneFiles({
        val top = android.os.Environment.getExternalStorageDirectory()
        val allOk = android.os.Build.VERSION.SDK_INT < 30 || android.os.Environment.isExternalStorageManager()
        val staged = if (settings.linkModels) listOf(PhoneModels.ROOT_NAME to File(top, "Faze/models")) else emptyList()
        staged + when {
            settings.linkFilesAll && allOk -> listOf("All files" to top)
            settings.linkFiles || settings.linkFilesAll -> listOf("DCIM", "Pictures", "Download", "Documents", "Music", "Movies").map { it to File(top, it) }.filter { it.second.isDirectory }
            else -> emptyList()
        }
    }, { activity.add("link", it) })
    /** The connected computer's live screen (see PcFrames). */
    val pcFrames = PcFrames()

    private val clipboard = app.getSystemService(android.content.ClipboardManager::class.java)
    private val phoneClip = PhoneClip({ settings.linkClip && link.connected().isNotEmpty() },
        { text -> main.post { try { clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Faze", text)) } catch (_: Exception) { } } },
        { text -> executor.execute { link.notify(JSONObject().put("t", "clip").put("text", text)) } })

    private val clipWatch = ClipWatch(app.packageName) { m -> main.post { activity.add("link", m) } }

    /** Starts or stops what keeps the link alive in the background: the notification service and, with root, the clipboard watcher. */
    fun syncBackground() {
        val on = settings.linkOn
        try {
            if (on) androidx.core.content.ContextCompat.startForegroundService(app, android.content.Intent(app, LinkService::class.java))
            else app.stopService(android.content.Intent(app, LinkService::class.java))
        } catch (_: Exception) { /* Android only lets this start while the app is in front; the next time Faze opens it will */ }
        syncClipWatch()
    }

    /** The root clipboard watcher runs only while a computer is linked: with none, nothing is watched and the clipboard is never read. */
    fun syncClipWatch() {
        if (settings.linkOn && settings.linkClip && settings.linkClipRoot && link.connected().isNotEmpty()) clipWatch.start() else clipWatch.stop()
    }

    /** Sends the phone's clipboard to the computer. Android only lets an app read it while the app is in front, so this runs on a copy and when Faze opens. */
    fun shareClipboard() {
        if (!settings.linkClip || link.connected().isEmpty()) return // not linked: do not even read it
        try { phoneClip.changed(clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(app)?.toString()) } catch (_: Exception) { }
    }

    private val peerTasks = PeerTasks({ busy || voiceOn || liveOn }, { task -> main.post { newChat(); send("(From your computer) $task") } })
    private val computers = object : Computers {
        override fun available() = link.connected().isNotEmpty()
        override fun ask(task: String): Pair<Boolean, String> = try {
            val r = link.ask(link.connected().first(), JSONObject().put("t", "pc_task").put("task", task), 300_000)
            if (r.optString("t") == "pc_task_ok") true to r.optString("text").ifBlank { "Done." } else false to r.optString("message", "the computer could not do it")
        } catch (e: Exception) {
            false to "the computer could not be reached: ${e.message ?: ""}"
        }
    }
    private val sync = LinkSync(store, memory, changed = { executor.execute { val list = store.list(); main.post { chats.clear(); chats.addAll(list) } } })
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
    @Volatile private var androidListener: AndroidListener? = null
    @Volatile private var nextAloud = false // the reply to a spoken message is read out; the reply to a typed one is not
    @Volatile private var dictateNext = false

    /** Where dictated text goes (the overlay's text box) while it is open. */
    @Volatile var dictateSink: ((String) -> Unit)? = null
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

    /** Tool chains the person approved with a thumbs up; replayed offline with no model at all. */
    val recipes = RecipeBook(File(app.filesDir, "recipes.json"))

    /** The provider that answered last, and the one to try last on the next retry (set by a thumbs down). */
    @Volatile private var answeredBy = ""
    @Volatile private var avoid = ""

    /** True when no online model can answer: the offline model is chosen, or there is no network. Saved recipes only replay then. */
    private fun offlineNow(): Boolean = settings.provider == "offline" || !Online.available(app)
    private val router = FastRoute(learned, NeedleClient(transport), { settings.fastRoutes }, { settings.needleOn }, { PermissionPolicy.tier(it) == Tier.SAFE })

    private val localServer: LocalServer = LocalServerControl(
        run = { cmd, ms -> Su.run(cmd, ms) },
        alive = { try { transport.get("${LocalLlama.BASE}/models", emptyMap(), 1500).let { if (it.status == 200) LocalLlama.parseServed(it.body) else null } } catch (e: Exception) { null } },
        binary = { settings.offlineBinary.ifEmpty { LocalLlama.pickBinary(Su.run(LocalLlama.findBinaryCommand(), 15_000))?.also { settings.offlineBinary = it } } },
        threads = { settings.offlineThreads },
        ctx = { settings.offlineCtx },
        gpu = { settings.offlineGpuLayers },
        notice = { activity.add("model", it) },
    )

    init {
        clipboard.addPrimaryClipChangedListener { shareClipboard() }
        syncBackground()
        if (settings.linkOn) Thread { try { link.start() } catch (e: Exception) { /* the port may be taken */ } }.start()
        val client = ResilientChat(
            mapOf(
                "groq" to RemoteClient(transport, Groq.BASE, { settings.groqKey }, "Groq"),
                "gemini" to GeminiClient(transport, { settings.geminiKey }, { settings.thinkLevel }),
                "offline" to OfflineChat(
                    RemoteClient(transport, LocalLlama.BASE, { "local" }, "Offline", 400, JSONObject().put("chat_template_kwargs", JSONObject().put("enable_thinking", false))),
                    localServer, { settings.offlineModel }, { text -> main.post { status = text } },
                ),
                *OpenAiProviders.all.values.map { info -> info.id to (RemoteClient(transport, info.base, { settings.compatKey(info.id) }, info.label) as ChatApi) }.toTypedArray(),
                "pc" to PcChat({ msg, ms, cancelled -> link.ask(link.connected().first(), msg, ms, cancelled) }),
                "openrouter" to OpenRouterChat(
                    RemoteClient(transport, OpenRouter.BASE, { settings.openrouterKey }, "OpenRouter"),
                    { transport.get("${OpenRouter.BASE}/models", emptyMap(), 15000).body },
                    { settings.openrouterModel = it },
                ),
            ),
            { Fallbacks.plan(settings.provider, listOf("groq", "gemini", "openrouter", "nvidia").filter { settings.hasKey(it) }.toSet()) { settings.modelFor(it) }.let { p -> if (p.size > 1) p.sortedBy { it.provider == avoid } else p } },
            { text -> main.post { status = text } },
            planFor = { messages -> if (settings.provider == "auto") autoPlan(messages) else null },
            onUsed = { p -> answeredBy = p },
            onLimit = { p, e -> limits.note(p, e); activity.add("auto", "${ModelSheet.label(p)} is out of allowance for a while: ${(e.message ?: "").take(100)}") },
        )
        val userTools = UserTools(
            toolStore,
            JsSandbox({ url -> val r = transport.get(url, emptyMap(), 10000); if (r.status != 200) throw RuntimeException("HTTP ${r.status}"); r.body.take(200_000) }, power = AndroidPowers(app)),
            { settings.allowPowered },
            { PermissionPolicy.all().map { it.first }.toSet() },
        )
        rawTools = Tools(AndroidDevice(app), transport, settings, memory, userTools, ScreenEyes(RemoteClient(transport, Groq.BASE, { settings.groqKey }, "Groq", 700), { settings.visionModel }, { screenJpeg() }), hands, scheduler, AndroidSwitches(), liveControl,
            GeminiImages(transport, { settings.geminiKey }, { settings.imageModel }, java.io.File(app.filesDir, "images"), imageShelf),
            WebImages(transport, { settings.tavily }, imageShelf), computers)
        gate = GatedTools(rawTools, broker, extraTier = { name -> if (userTools.has(name)) Tier.SAFE else null }, approvalMode = { settings.approvalMode }, phoneControl = { settings.phoneControl }, locked = { settings.limitWhenLocked && LockOverlayActivity.isLocked(app) })
        gate.logger = { line -> activity.add("tool", line) }
        val tools: ToolRunner = gate
        gatedTools = tools
        // A tool that takes over the screen waits for the assistant to finish speaking, unless the bubble keeps the conversation going outside the app.
        val speakingNow = { speaker.isSpeaking() || (live?.isSpeaking() ?: false) }
        liveTools = SpeechAwareTools(tools, { true }, speakingNow, { bubbleReady() })
        engine = ChatEngine(Agent(client, SpeechAwareTools(tools, { voiceActive }, speakingNow, { bubbleReady() }), router = router, recipes = recipes, offline = { offlineNow() }), object : EngineConfig by settings {
            override fun answeredBy(): String = this@FazeCore.answeredBy
            override fun memoryBlock(): String = if (settings.useMemory && !(settings.limitWhenLocked && LockOverlayActivity.isLocked(app))) memory.promptBlock(settings.user) else ""
            override fun speak(): Boolean = settings.speakReplies || nextAloud
        }, speaker, { block -> main.post { block() } }, executor, messages, this)
        engine.takeImages = { imageShelf.take() }
        deviceSpeaker.setVoice(settings.voiceRate, settings.voicePitch)
        deviceSpeaker.setVoiceName(settings.deviceVoice)
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
        if (value) activity.add("wake", "Wake word heard")
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

    /** [aloud]: the message was spoken, so its reply is read out too (typed ones are not, unless "Speak replies aloud" is on). */
    fun send(text: String, aloud: Boolean = false) {
        avoid = ""
        answeredBy = ""
        nextAloud = aloud
        timing = ""
        status = ""
        engine.send(text)
    }

    private val learner = object : Feedback.Learner {
        override fun learnTemplate(request: String, tool: String, args: JSONObject) = router.learn(request, tool, args)
        override fun saveRecipe(request: String, steps: List<Step>, say: String) = recipes.save(request, steps, say)
        override fun forgetRecipe(request: String) = recipes.remove(request)
        override fun confirmRecipe(request: String) = recipes.hit(RecipeBook.keyOf(request))
        override fun forgetShortcut(id: Int) = learned.remove(id)
        override fun confirmShortcut(id: Int) = learned.hit(id)
    }

    /** A thumbs up or down on a reply. [tap] is 1 or -1; the same tap again takes it back. Runs on the main thread. */
    fun rate(replyId: Long, tap: Int) {
        val i = messages.indexOfFirst { it.id == replyId }
        if (i <= 0) return
        val reply = messages[i]
        val ask = messages[i - 1]
        if (reply.role != "assistant" || ask.role != "user") return
        val out = Feedback.rate(reply, ask.text, tap, learner)
        messages[i] = reply.copy(feedback = out.feedback)
        status = out.note
        save()
    }

    /** Asks the same question again. After a thumbs down the model that gave that answer goes last, so another one answers. */
    fun retry() {
        val last = messages.lastOrNull() ?: return
        if (last.role != "assistant" || busy) return
        avoid = if (last.feedback < 0) last.model else ""
        status = ""
        engine.retry()
    }

    /** What is waiting to go with the next message: pictures and files picked with the + button. */
    val pending = mutableStateListOf<Attachment>()

    private val attachDir get() = java.io.File(app.filesDir, "attachments").also { it.mkdirs() }

    private val reader: AttachmentReader by lazy {
        val eyes = RemoteClient(transport, Groq.BASE, { settings.groqKey }, "Groq", 900)
        AttachmentReader(
            vision = { jpeg, q ->
                if (settings.groqKey.isEmpty()) "A picture was attached but it cannot be looked at: the vision model needs the Groq key."
                else try {
                    eyes.chat(settings.visionModel, AttachmentReader.visionMessages(q, jpeg), null, { _ -> }, { false }).content.trim().ifEmpty { "The vision model gave no description." }
                } catch (e: RemoteError) {
                    "The picture could not be looked at: ${e.message}"
                }
            },
            pdfPages = { f -> AndroidPdf.pages(f, 4) },
        )
    }

    /** A picked photo, file or camera picture: copied into the app (so it stays readable) and queued. Off the main thread. */
    fun attach(uri: android.net.Uri, nameHint: String = "") {
        if (pending.size >= MAX_PENDING) { status = "Up to $MAX_PENDING attachments per message"; return }
        executor.execute {
            val made = try { AndroidAttachments.copyIn(app, uri, attachDir, nameHint) } catch (e: Exception) { null }
            main.post {
                if (made == null) status = "Could not read that file"
                else pending.add(made)
            }
        }
    }

    fun removePending(a: Attachment) { pending.remove(a) }

    /** Sends the typed text together with the queued attachments. The pictures are looked at and the files read first. */
    fun sendWithAttachments(text: String) {
        val list = ArrayList(pending)
        pending.clear()
        if (list.isEmpty()) { send(text); return }
        status = "Reading ${list.size} attachment${if (list.size == 1) "" else "s"}…"
        executor.execute {
            val read = list.mapIndexed { i, a -> if (a.isImage && i >= AttachmentReader.MAX_PICTURES) a.copy(context = "Not looked at: too many pictures in one message.") else reader.read(a, text) }
            main.post { nextAloud = false; timing = ""; status = ""; engine.send(text, read) }
        }
    }

    fun stop() {
        gate.revokeControl()
        broker.denyAll()
        engine.stop()
        transport.abortAll() // end a request that is waiting on the network right now
    }

    fun resolveApproval(id: Long, allow: Boolean) = broker.resolve(id, allow)

    /** Settings > Voice: speed and pitch take effect on the next sentence. */
    fun setVoice(rate: Float, pitch: Float) {
        settings.voiceRate = rate
        settings.voicePitch = pitch
        deviceSpeaker.setVoice(settings.voiceRate, settings.voicePitch)
    }

    /** The stop button in the top bar: be quiet now and end the request if one is running. */
    fun stopSpeaking() {
        if (busy) stop()
        speaker.stop()
    }

    /** The Listen button under a turn: say that message aloud (markdown and code are skipped, as on the web page). */
    fun speakText(text: String) {
        speaker.stop()
        for (sentence in Speakable.split(Speakable.clean(text), true).first) speaker.speak(sentence)
    }

    /** The overlay's opening line: the saved sound when there is one (works offline, costs nothing), else the usual voice. */
    fun speakOpening(line: String) {
        val i = GoogleTakeover.LINES.indexOf(line)
        val pcm = if (i >= 0 && settings.voiceEngine != "device") voiceLines.pcm(i) else null
        if (pcm != null) speaker.playClip(pcm) else speakText(line)
    }

    /** Which opening line to use next: one that is saved as sound when any are, so it can play offline. */
    fun pickOpening(last: Int): Int = GoogleTakeover.pickFrom(if (settings.voiceEngine == "device") emptyList() else voiceLines.saved(), last)

    var linesStatus by mutableStateOf("")
        private set

    fun linesSaved(): Int = voiceLines.saved().size

    /** Settings > Voice: makes the opening lines with the Gemini voice now, once, and keeps them. */
    fun downloadLines() {
        if (linesBusy) return
        linesBusy = true
        linesStatus = "Starting…"
        Thread {
            val (n, why) = voiceLines.download({ false }) { done, total -> main.post { linesStatus = "Saved $done of $total…" } }
            main.post {
                linesBusy = false
                linesStatus = if (why == null) "All $n lines saved. They play offline and use no credits."
                else "Saved $n of ${GoogleTakeover.LINES.size}. Stopped: ${why.take(120)}. Tap again later to continue."
                activity.add("voice", linesStatus)
            }
        }.also { it.isDaemon = true; it.name = "voice-lines" }.start()
    }
    private var linesBusy = false

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
                } else ModelCatalog(transport, { settings.customUrl }) { settings.keyFor(it) }.fetch(provider)
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
        if (id == "auto") loadModels("offline") // so LFM and Qwen can be found by name (does nothing without root)
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
        androidListener?.muted = micMuted
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
        if (!engine.isBusy()) send(text, true) else if (tries > 0) main.postDelayed({ sendWhenFree(text, tries - 1) }, 100)
    }

    /** Starts voice chat. The wake-word listener is paused meanwhile (one microphone). The outcome shows in [voiceState]. */
    fun startVoiceChat() {
        if (voiceLoop != null || androidListener != null) return
        if (settings.groqKey.isEmpty()) { startAndroidListening("No Groq key: using Android's own speech recognition"); return }
        voiceOn = true
        voiceActive = true
        micMuted = false
        voiceState = "Starting…"
        startLiveService()
        liveStarter.execute {
            listenControl?.pause()
            val host = object : VoiceHost {
                override fun transcribe(wav: ByteArray): String = try {
                    stt.transcribe(wav)
                } catch (e: Exception) {
                    main.post { fallBackToAndroid() } // Groq is down or refused: carry on with Android's recogniser
                    throw e
                }
                override fun takeDictation(text: String): Boolean = takeVoiceDictation(text)
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

    /** Android's own recogniser listens (no Groq key, or Groq failed). Everything it hears goes the same way as the Whisper path. */
    private fun startAndroidListening(note: String) {
        voiceOn = true
        voiceActive = true
        micMuted = false
        startLiveService()
        listenControl?.pause()
        val l = AndroidListener(
            app,
            { text -> main.post { if (!takeVoiceDictation(text)) sendWhenFree(text) } },
            { level -> main.post { micLevel = if (micMuted) 0f else level } },
            { why -> main.post { androidListener = null; voiceState = "Voice chat could not start: $why"; status = voiceState; voiceEnded() } },
            { busy || speaker.isSpeaking() },
        )
        androidListener = l
        val failed = l.start()
        if (failed != null) { androidListener = null; voiceState = "Voice chat could not start: $failed"; voiceEnded() } else voiceState = note
    }

    private fun fallBackToAndroid() {
        val l = voiceLoop ?: return
        if (androidListener != null) return
        voiceLoop = null
        liveStarter.execute {
            l.stop()
            main.post { startAndroidListening("Groq's speech recognition failed: using Android's own") }
        }
    }

    /** The mic was pressed while talking: this phrase goes into the text box instead of being sent. */
    private fun takeVoiceDictation(text: String): Boolean {
        val sink = dictateSink
        if (!dictateNext || sink == null) return false
        dictateNext = false
        main.post { sink(text) }
        return true
    }

    /** True while the person is in the middle of a phrase. */
    fun hearingSpeech(): Boolean = (voiceLoop?.hearingSpeech() ?: false) || (androidListener?.hearing ?: false)

    /** What is being said right now is typed into the text box, not sent. */
    fun dictateThis() { dictateNext = true }

    fun stopVoiceChat() {
        val a = androidListener
        if (a != null) { a.stop(); androidListener = null; voiceEnded(); return }
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

    /** The live screen shows the camera full screen while it is shared. */
    fun showCameraPreview(surface: androidx.camera.core.Preview.SurfaceProvider?) {
        (shareSource as? CameraSource)?.showPreview(surface)
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
                            send(text, true)
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

data class DeviceRow(val id: String, val name: String, val os: String, val connected: Boolean, val connection: String)
