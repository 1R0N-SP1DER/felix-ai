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

    override fun tavilyKey(): String = tavily

    override fun weatherLocation(): String = city

    override fun model(): String = modelName.ifBlank { Groq.DEFAULT_MODEL }

    override fun userName(): String = user.ifBlank { "Felix" }

    override fun speak(): Boolean = speakReplies
}
