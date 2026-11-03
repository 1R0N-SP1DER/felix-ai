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
    var chordEnabled: Boolean
        get() = prefs.getBoolean("chord_on", true)
        set(value) = prefs.edit().putBoolean("chord_on", value).apply()

    override fun tavilyKey(): String = tavily

    override fun weatherLocation(): String = city

    override fun model(): String = modelName.ifBlank { Groq.DEFAULT_MODEL }

    override fun userName(): String = user.ifBlank { "Felix" }

    override fun speak(): Boolean = speakReplies
}
