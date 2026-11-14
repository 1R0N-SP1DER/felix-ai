package com.felix.faze

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Something attached to a chat message: a picture (a photo the person sent, or one the AI made) or a file the person sent.
 * [context] is what the AI is given in its place: the text of a file, or a description of a picture. It is not shown in the chat.
 */
data class Attachment(val name: String, val path: String, val kind: String, val context: String = "", val url: String = "") {
    val isImage: Boolean get() = kind == IMAGE

    /** A picture from the web that is shown from its address and is not kept on the phone unless it is downloaded. */
    val isRemote: Boolean get() = url.isNotEmpty()

    companion object {
        const val IMAGE = "image"
        const val FILE = "file"

        fun toJson(list: List<Attachment>): JSONArray {
            val a = JSONArray()
            for (x in list) a.put(JSONObject().put("name", x.name).put("path", x.path).put("kind", x.kind).put("context", x.context).put("url", x.url))
            return a
        }

        fun fromJson(a: JSONArray?): List<Attachment> =
            if (a == null) emptyList() else (0 until a.length()).map {
                val o = a.getJSONObject(it)
                Attachment(o.optString("name"), o.optString("path"), o.optString("kind", FILE), o.optString("context"), o.optString("url"))
            }

        /** What the AI reads for a message: the words, then each attachment's context under its name. */
        fun forModel(text: String, list: List<Attachment>): String {
            val extra = list.filter { it.context.isNotBlank() }.joinToString("\n\n") { "[Attached ${it.kind}: ${it.name}]\n${it.context.trim()}" }
            return if (extra.isEmpty()) text else if (text.isBlank()) extra else "$text\n\n$extra"
        }
    }
}

/** Pictures the AI made during a turn, waiting to be attached to its reply. */
class ImageShelf {
    private val items = ArrayList<Attachment>()

    @Synchronized fun add(a: Attachment) { items.add(a) }

    @Synchronized fun take(): List<Attachment> { val out = ArrayList(items); items.clear(); return out }
}

/** Makes a picture from a description. Throws ToolFailure with a readable reason. */
interface ImageMaker {
    /** Returns what the AI is told; the picture itself goes onto the shelf. */
    fun make(prompt: String, shape: String): String
}

/** Gemini's picture model ("Nano Banana"): one generateContent call, the picture comes back as base64 in the reply. Uses the Gemini key. */
class GeminiImages(
    private val transport: Transport,
    private val key: () -> String,
    private val model: () -> String,
    private val dir: File,
    private val shelf: ImageShelf,
    private val now: () -> Long = { System.currentTimeMillis() },
) : ImageMaker {
    override fun make(prompt: String, shape: String): String {
        val text = prompt.trim()
        if (text.isEmpty()) throw ToolFailure("describe the picture to make")
        if (key().isEmpty()) throw ToolFailure("no Gemini key: add it in Settings")
        val name = model().ifBlank { DEFAULT_MODEL }
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", text.take(2000))))))
            .put("generationConfig", JSONObject()
                .put("responseModalities", JSONArray().put("IMAGE"))
                .put("imageConfig", JSONObject().put("aspectRatio", ratio(shape))))
            .toString()
        val res = try {
            transport.post("$BASE/$name:generateContent", mapOf("x-goog-api-key" to key()), body.toByteArray(), "application/json", 120000)
        } catch (e: Exception) {
            throw ToolFailure("Gemini could not be reached: ${e.message ?: e.javaClass.simpleName}")
        }
        val json = try { JSONObject(res.body) } catch (e: Exception) { null }
        if (res.status != 200 || json == null) throw ToolFailure("Gemini said ${res.status}: ${problem(json) ?: res.body.take(200)}")
        val b64 = findPicture(json)
        if (b64.isEmpty()) {
            val why = json.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
            throw ToolFailure(if (why.isNotEmpty()) "Gemini would not draw that ($why)" else "Gemini sent no picture back")
        }
        val bytes = try { java.util.Base64.getDecoder().decode(b64.trim()) } catch (e: Exception) { throw ToolFailure("the picture from Gemini was damaged") }
        dir.mkdirs()
        val file = File(dir, "img_${now()}.png")
        file.writeBytes(bytes)
        shelf.add(Attachment("Generated picture", file.absolutePath, Attachment.IMAGE))
        return "The picture was made and is shown in the chat. Do not describe a link; say something short about it."
    }

    private fun findPicture(json: JSONObject): String {
        val cands = json.optJSONArray("candidates") ?: return ""
        for (i in 0 until cands.length()) {
            val parts = cands.optJSONObject(i)?.optJSONObject("content")?.optJSONArray("parts") ?: continue
            for (j in 0 until parts.length()) {
                val p = parts.optJSONObject(j) ?: continue
                val inline = p.optJSONObject("inlineData") ?: p.optJSONObject("inline_data") ?: continue
                val data = inline.optString("data")
                if (data.isNotEmpty()) return data
            }
        }
        return ""
    }

    private fun problem(json: JSONObject?): String? =
        json?.optJSONObject("error")?.optString("message")?.ifEmpty { null }

    companion object {
        const val BASE = "https://generativelanguage.googleapis.com/v1beta/models"
        const val DEFAULT_MODEL = "gemini-2.5-flash-image"

        fun ratio(shape: String): String = when (shape.trim().lowercase()) {
            "portrait", "tall" -> "2:3"
            "landscape", "wide" -> "3:2"
            else -> "1:1"
        }
    }
}
