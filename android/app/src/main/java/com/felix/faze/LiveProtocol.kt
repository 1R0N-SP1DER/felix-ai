package com.felix.faze

import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/** One thing the Gemini Live server told us. */
sealed class LiveEvent {
    class Audio(val pcm: ByteArray) : LiveEvent()
    class Text(val text: String) : LiveEvent()
    class HeardText(val text: String) : LiveEvent()
    class SaidText(val text: String) : LiveEvent()
    object Interrupted : LiveEvent()
    object TurnComplete : LiveEvent()
    class ToolCalls(val calls: List<LiveCall>) : LiveEvent()
    class GoAway(val timeLeft: String) : LiveEvent()
    class Closed(val why: String) : LiveEvent()
}

data class LiveCall(val id: String, val name: String, val args: JSONObject)

data class LiveReply(val id: String, val name: String, val ok: Boolean, val text: String)

/**
 * The messages of the Gemini Live API (a bidirectional audio conversation over a websocket), as plain data: building what we send
 * and reading what comes back. A port of core/live_client.py, which found out by trial how the server wants its setup.
 * Nothing here touches the network, so it is tested without a phone.
 */
object LiveProtocol {
    const val URL = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key="
    const val INPUT_RATE = 16000
    const val OUTPUT_RATE = 24000
    /** The one that works on a free key (3.1 Live and 3.8 Live both answered "quota exceeded" on the owner's key). */
    const val DEFAULT_MODEL = "gemini-2.5-flash-native-audio-preview-12-2025"

    /** Model id and the short label for the picker. */
    val MODELS = listOf(
        DEFAULT_MODEL to "2.5 Audio",
        "gemini-3.1-flash-live-preview" to "3.1 Live",
        "gemini-3.8-live" to "3.8 Live",
    )
    const val DEFAULT_VOICE = "Kore"
    val VOICES = listOf("Kore", "Puck", "Charon", "Aoede", "Fenrir", "Zephyr")

    /** Setup is tried in this order: the docs show the output settings in two places, so the server may accept only one of them. */
    val SHAPES: List<Pair<String, Boolean>> = listOf("generation" to true, "flat" to true, "generation" to false, "flat" to false)

    /** The server refused a field by name or does not support something we asked for, so another shape is worth a try. */
    private val retryable = Regex("unknown name|invalid json|invalid argument|cannot find field|unsupported|not supported|tools?\\b", RegexOption.IGNORE_CASE)

    fun isRetryable(reason: String): Boolean = retryable.containsMatchIn(reason)

    /** Keys never appear in an error line or a log. */
    fun scrub(text: String): String = text.replace(Regex("key=[^&\\s'\"]+"), "key=...")

    /** The first message. shape "generation": output settings inside generationConfig; "flat": on the setup itself. */
    fun setup(model: String, instruction: String, declarations: JSONArray, search: Boolean, voice: String, shape: String): JSONObject {
        val speech = JSONObject().put("voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice)))
        val setup = JSONObject()
            .put("model", if (model.startsWith("models/")) model else "models/$model")
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", instruction))))
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())
        if (shape == "generation") {
            setup.put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("AUDIO")).put("speechConfig", speech))
        } else {
            setup.put("responseModalities", JSONArray().put("AUDIO")).put("speechConfig", speech)
        }
        val tools = JSONArray()
        if (search) tools.put(JSONObject().put("googleSearch", JSONObject()))
        if (declarations.length() > 0) tools.put(JSONObject().put("functionDeclarations", declarations))
        if (tools.length() > 0) setup.put("tools", tools)
        return JSONObject().put("setup", setup)
    }

    fun audio(pcm: ByteArray): String = JSONObject().put(
        "realtimeInput",
        JSONObject().put("audio", JSONObject().put("data", Base64.getEncoder().encodeToString(pcm)).put("mimeType", "audio/pcm;rate=$INPUT_RATE")),
    ).toString()

    fun text(text: String): String = JSONObject().put("realtimeInput", JSONObject().put("text", text)).toString()

    fun audioEnd(): String = JSONObject().put("realtimeInput", JSONObject().put("audioStreamEnd", true)).toString()

    fun toolResponses(replies: List<LiveReply>): String {
        val list = JSONArray()
        for (r in replies) {
            list.put(JSONObject().put("id", r.id).put("name", r.name).put("response", JSONObject().put(if (r.ok) "result" else "error", r.text)))
        }
        return JSONObject().put("toolResponse", JSONObject().put("functionResponses", list)).toString()
    }

    /** One server message as a list of events. */
    fun parse(msg: JSONObject): List<LiveEvent> {
        val events = ArrayList<LiveEvent>()
        val content = msg.optJSONObject("serverContent")
        if (content != null) {
            val parts = content.optJSONObject("modelTurn")?.optJSONArray("parts")
            for (i in 0 until (parts?.length() ?: 0)) {
                val part = parts!!.getJSONObject(i)
                val data = part.optJSONObject("inlineData")?.optString("data", "").orEmpty()
                if (data.isNotEmpty()) events.add(LiveEvent.Audio(Base64.getDecoder().decode(data)))
                else if (part.optString("text", "").isNotEmpty()) events.add(LiveEvent.Text(part.getString("text")))
            }
            content.optJSONObject("inputTranscription")?.optString("text", "")?.takeIf { it.isNotEmpty() }?.let { events.add(LiveEvent.HeardText(it)) }
            content.optJSONObject("outputTranscription")?.optString("text", "")?.takeIf { it.isNotEmpty() }?.let { events.add(LiveEvent.SaidText(it)) }
            if (content.optBoolean("interrupted", false)) events.add(LiveEvent.Interrupted)
            if (content.optBoolean("turnComplete", false)) events.add(LiveEvent.TurnComplete)
        }
        val calls = msg.optJSONObject("toolCall")?.optJSONArray("functionCalls")
        if (calls != null && calls.length() > 0) {
            events.add(LiveEvent.ToolCalls((0 until calls.length()).map {
                val c = calls.getJSONObject(it)
                LiveCall(c.optString("id", ""), c.optString("name", ""), c.optJSONObject("args") ?: JSONObject())
            }))
        }
        if (msg.has("goAway")) events.add(LiveEvent.GoAway(msg.optJSONObject("goAway")?.optString("timeLeft", "").orEmpty()))
        return events
    }
}
