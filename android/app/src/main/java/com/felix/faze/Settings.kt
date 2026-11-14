package com.felix.faze

import android.content.Context

/** Keys and preferences, in the app's private storage. (Keystore-backed encryption comes with the settings redesign.) */
class AppSettings(context: Context) : ToolSettings, EngineConfig {
    private val prefs = context.getSharedPreferences("faze", Context.MODE_PRIVATE)

    private fun text(key: String): String = prefs.getString(key, "") ?: ""

    private fun put(key: String, value: String) {
        prefs.edit().putString(key, value.trim()).apply()
    }

    var groqKey: String
        get() = text("groq_key")
        set(value) = put("groq_key", value)

    var imageModel: String
        get() = text("image_model").ifBlank { GeminiImages.DEFAULT_MODEL }
        set(value) = put("image_model", value.trim())

    var tavily: String
        get() = text("tavily_key")
        set(value) = put("tavily_key", value)

    var modelName: String
        get() = text("model")
        set(value) = put("model", value)

    var city: String
        get() = text("city")
        set(value) = put("city", value)

    var user: String
        get() = text("user")
        set(value) = put("user", value)

    var speakReplies: Boolean
        get() = prefs.getBoolean("speak", true)
        set(value) = prefs.edit().putBoolean("speak", value).apply()

    var wakeEnabled: Boolean
        get() = prefs.getBoolean("wake_on", false)
        set(value) = prefs.edit().putBoolean("wake_on", value).apply()

    var wakePhrase: String
        get() = text("wake_phrase")
        set(value) = put("wake_phrase", value)

    /** Whether the saved notes are shown to the model (Settings > Agent > Memory). */
    var useMemory: Boolean
        get() = prefs.getBoolean("use_memory", true)
        set(value) = prefs.edit().putBoolean("use_memory", value).apply()

    /** Whether saved tools may use the powers they declared (network, shell, root, files, screenshot, intent). Settings > Agent > Allow higher-level tools. */
    var allowPowered: Boolean
        get() = prefs.getBoolean("allow_powered", true)
        set(value) = prefs.edit().putBoolean("allow_powered", value).apply()

    /** Fast routes: built-in rules and learned shortcuts pick the tool without the model; Needle is asked too when its server is running. */
    var fastRoutes: Boolean
        get() = prefs.getBoolean("fast_routes", true)
        set(value) = prefs.edit().putBoolean("fast_routes", value).apply()

    var needleOn: Boolean
        get() = prefs.getBoolean("needle_on", true)
        set(value) = prefs.edit().putBoolean("needle_on", value).apply()

    /** Voice chat: whether a short phrase covers a slow answer, and the small fast Groq model that writes it. */
    var voiceFillers: Boolean
        get() = prefs.getBoolean("voice_fillers", true)
        set(value) = prefs.edit().putBoolean("voice_fillers", value).apply()

    var fillerModel: String
        get() = text("filler_model").ifEmpty { ModelFiller.DEFAULT_MODEL }
        set(value) = put("filler_model", value)

    /** The vision model that looks at screenshots (a Groq model that accepts images). */
    var visionModel: String
        get() = text("vision_model").ifEmpty { ScreenEyes.DEFAULT_MODEL }
        set(value) = put("vision_model", value.trim())

    /** The Gemini Live model and the voice it speaks with (Settings > Agent > Live voice). */
    var liveModel: String
        get() = text("live_model").ifEmpty { LiveProtocol.DEFAULT_MODEL }
        set(value) = put("live_model", value)

    var liveVoice: String
        get() = text("live_voice").let { if (it in LiveProtocol.VOICES) it else LiveProtocol.DEFAULT_VOICE }
        set(value) = put("live_voice", value)

    /** Which online model answers: groq, gemini or openrouter. */
    var provider: String
        get() = text("provider").let { if (it == "gemini" || it == "openrouter" || it == "offline" || it == "custom" || it in OpenAiProviders.all) it else "groq" }
        set(value) = put("provider", value)

    var geminiKey: String
        get() = text("gemini_key")
        set(value) = put("gemini_key", value)

    var openrouterKey: String
        get() = text("openrouter_key")
        set(value) = put("openrouter_key", value)

    var geminiModel: String
        get() = text("gemini_model").ifEmpty { GeminiApi.DEFAULT_MODEL }
        set(value) = put("gemini_model", value)

    var openrouterModel: String
        get() = text("openrouter_model") // blank: a free model is picked automatically
        set(value) = put("openrouter_model", value)

    /** The custom provider: any service that speaks the OpenAI chat format. */
    var customName: String
        get() = text("custom_name").ifBlank { "Custom" }
        set(value) = put("custom_name", value.trim())

    var customUrl: String
        get() = text("custom_url")
        set(value) = put("custom_url", value.trim())

    var customKey: String
        get() = text("custom_key")
        set(value) = put("custom_key", value.trim())

    var customModel: String
        get() = text("custom_model")
        set(value) = put("custom_model", value.trim())

    /** Offline: the model file picked, where llama-server was found, and how it is run. */
    var offlineModel: String
        get() = text("offline_model")
        set(value) = put("offline_model", value)

    var offlineBinary: String
        get() = text("offline_binary")
        set(value) = put("offline_binary", value)

    var offlineThreads: Int
        get() = OfflineLimits.threads(text("offline_threads").toIntOrNull())
        set(value) = put("offline_threads", OfflineLimits.threads(value).toString())

    /** Key and model of NVIDIA, stored per provider id. */
    fun compatKey(id: String): String = text("${id}_key")
    fun setCompatKey(id: String, value: String) = put("${id}_key", value.trim())
    fun compatModel(id: String): String = text("${id}_model").ifBlank { OpenAiProviders.all[id]?.defaultModel.orEmpty() }
    fun setCompatModel(id: String, value: String) = put("${id}_model", value.trim())

    var offlineCtx: Int
        get() = OfflineLimits.ctx(text("offline_ctx").toIntOrNull())
        set(value) = put("offline_ctx", OfflineLimits.ctx(value).toString())

    fun modelFor(provider: String): String = when (provider) {
        "offline" -> offlineModel
        "gemini" -> geminiModel
        "openrouter" -> openrouterModel
        "custom" -> customModel
        in OpenAiProviders.all -> compatModel(provider)
        else -> modelName.ifBlank { Groq.DEFAULT_MODEL }
    }

    /** Chooses the model of one provider (Groq, Gemini or OpenRouter) from the picker. */
    fun setModelFor(provider: String, id: String) {
        when (provider) {
            "offline" -> offlineModel = id
            "gemini" -> geminiModel = id
            "openrouter" -> openrouterModel = id
            "custom" -> customModel = id
            in OpenAiProviders.all -> setCompatModel(provider, id)
            else -> modelName = id
        }
    }

    fun keyFor(provider: String): String = when (provider) {
        "offline" -> ""
        "gemini" -> geminiKey
        "openrouter" -> openrouterKey
        "custom" -> customKey
        in OpenAiProviders.all -> compatKey(provider)
        else -> groqKey
    }

    fun hasKey(provider: String): Boolean = when (provider) {
        "offline" -> true // no key: it runs on the phone
        "gemini" -> geminiKey.isNotEmpty()
        "openrouter" -> openrouterKey.isNotEmpty()
        "custom" -> CustomEndpoint.base(customUrl) != null // the key is optional; an address is what it needs
        in OpenAiProviders.all -> compatKey(provider).isNotEmpty()
        else -> groqKey.isNotEmpty()
    }

    /** The look, as on the web settings page: accent colour name, text size s/m/l, font system/serif/mono, motion and greeting switches. */
    var accent: String
        get() = text("accent").ifEmpty { "cyan" }
        set(value) = put("accent", value)

    var textSize: String
        get() = text("text_size").ifEmpty { "m" }
        set(value) = put("text_size", value)

    var fontChoice: String
        get() = text("font_choice").ifEmpty { "system" }
        set(value) = put("font_choice", value)

    var effortLevel: String
        get() = Effort.norm(text("effort_level"))
        set(value) = put("effort_level", Effort.norm(value))

    var lengthStep: Int
        get() = text("length_step").toIntOrNull()?.let { ResponseLength.norm(it) } ?: ResponseLength.DEFAULT
        set(value) = put("length_step", ResponseLength.norm(value).toString())

    override fun effort(): String = effortLevel
    override fun lengthSetting(): Int = lengthStep

    /** The thinking level picked in the model sheet: auto, off, low, medium, high or max. Kept for the models that use it. */
    var thinkLevel: String
        get() = text("think_level").ifEmpty { "auto" }
        set(value) = put("think_level", value)

    var motion: Boolean
        get() = prefs.getBoolean("motion", true)
        set(value) = prefs.edit().putBoolean("motion", value).apply()

    var greeting: Boolean
        get() = prefs.getBoolean("greeting", true)
        set(value) = prefs.edit().putBoolean("greeting", value).apply()

    var voiceRate: Float
        get() = prefs.getFloat("voice_rate", 1.0f)
        set(value) = prefs.edit().putFloat("voice_rate", value.coerceIn(0.6f, 1.6f)).apply()

    var voicePitch: Float
        get() = prefs.getFloat("voice_pitch", 1.0f)
        set(value) = prefs.edit().putFloat("voice_pitch", value.coerceIn(0.6f, 1.6f)).apply()

    /** Root only: keep the green microphone dot off while only waiting for the wake word. */
    var hideMicDot: Boolean
        get() = prefs.getBoolean("hide_mic_dot", false)
        set(value) = prefs.edit().putBoolean("hide_mic_dot", value).apply()

    /** Both volume keys held opens the overlay (needs the accessibility service). */
    /** When Google's assistant pops up (Hey Google, power-button hold, corner swipe), Faze takes over. */
    var googleTakeover: Boolean
        get() = prefs.getBoolean("google_takeover", true)
        set(value) = prefs.edit().putBoolean("google_takeover", value).apply()

    /** Faze says a short fun line when it takes over (otherwise the line is only shown). */
    var googleQuips: Boolean
        get() = prefs.getBoolean("google_quips", true)
        set(value) = prefs.edit().putBoolean("google_quips", value).apply()

    /** Extra window-name words to take over on, for phones where Google's assistant window has another name. */
    var googleWords: String
        get() = text("google_words")
        set(value) = put("google_words", value.trim())

    /** The last Google window the service saw, so the right words can be found without a computer. */
    var lastGoogleWindow: String
        get() = text("google_last_window")
        set(value) = put("google_last_window", value)

    /** How long the overlay's listening bar waits for speech before it goes back to "Message FAZE". */
    var overlayIdleSeconds: Int
        get() = text("overlay_idle").toIntOrNull()?.coerceIn(3, 60) ?: 6
        set(value) = put("overlay_idle", value.coerceIn(3, 60).toString())

    /** auto: the natural Gemini voice when online, the phone's voice otherwise; device: always the phone's; gemini: natural when it can be. */
    var voiceEngine: String
        get() = text("voice_engine").let { if (it == "device" || it == "gemini") it else "auto" }
        set(value) = put("voice_engine", value)

    /** The phone voice picked in Settings ("" = the phone's default). */
    var deviceVoice: String
        get() = text("device_voice")
        set(value) = put("device_voice", value)

    /** Only offer (and fall back to) voices that work without internet. */
    var offlineVoicesOnly: Boolean
        get() = prefs.getBoolean("offline_voices_only", false)
        set(value) = prefs.edit().putBoolean("offline_voices_only", value).apply()

    var ttsModel: String
        get() = text("tts_model").ifBlank { GeminiSpeech.DEFAULT_MODEL }
        set(value) = put("tts_model", value.trim())

    var chordEnabled: Boolean
        get() = prefs.getBoolean("chord_on", true)
        set(value) = prefs.edit().putBoolean("chord_on", value).apply()

    /** Settings > Approval mode: "ask" or "yolo". */
    var approvalMode: String
        get() = PermissionPolicy.approvalMode(text("approval_mode"))
        set(value) = put("approval_mode", PermissionPolicy.approvalMode(value))

    /** Settings > Phone control: "off", "ask" or "on". */
    var phoneControl: String
        get() = PermissionPolicy.phoneMode(text("phone_control"))
        set(value) = put("phone_control", PermissionPolicy.phoneMode(value))

    /** The volume-key overlay also starts a voice chat (needs the microphone allowed and a Groq key). */
    var chordVoice: Boolean
        get() = prefs.getBoolean("chord_voice", true)
        set(value) = prefs.edit().putBoolean("chord_voice", value).apply()

    override fun tavilyKey(): String = tavily

    override fun weatherLocation(): String = city

    override fun model(): String = modelFor(provider)

    override fun userName(): String = user.ifBlank { "Felix" }

    override fun speak(): Boolean = speakReplies
}
