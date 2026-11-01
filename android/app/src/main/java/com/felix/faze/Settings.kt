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

    /** "vosk" (offline, nothing leaves the phone) or "porcupine" (Picovoice; needs an AccessKey that checks in online). */
    var wakeEngine: String
        get() = text("wake_engine").ifEmpty { "vosk" }
        set(value) = put("wake_engine", value)

    var picovoiceKey: String
        get() = text("picovoice_key")
        set(value) = put("picovoice_key", value)

    /** Name of one of Porcupine's built-in keywords, used when no custom keyword file was imported. */
    var porcupineKeyword: String
        get() = text("porcupine_keyword").ifEmpty { "JARVIS" }
        set(value) = put("porcupine_keyword", value)

    /** Root only: keep the green microphone dot off while only waiting for the wake word. */
    var hideMicDot: Boolean
        get() = prefs.getBoolean("hide_mic_dot", false)
        set(value) = prefs.edit().putBoolean("hide_mic_dot", value).apply()

    /** Both volume keys held opens the overlay (needs the accessibility service). */
    var chordEnabled: Boolean
        get() = prefs.getBoolean("chord_on", true)
        set(value) = prefs.edit().putBoolean("chord_on", value).apply()

    override fun tavilyKey(): String = tavily

    override fun weatherLocation(): String = city

    override fun model(): String = modelName.ifBlank { Groq.DEFAULT_MODEL }

    override fun userName(): String = user.ifBlank { "Felix" }

    override fun speak(): Boolean = speakReplies
}
