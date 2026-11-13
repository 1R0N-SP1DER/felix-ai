package com.felix.faze

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Something attached to a chat message: a picture (a photo the person sent, or one the AI made) or a file the person sent.
 * [context] is what the AI is given in its place: the text of a file, or a description of a picture. It is not shown in the chat.
 */
data class Attachment(val name: String, val path: String, val kind: String, val context: String = "") {
    val isImage: Boolean get() = kind == IMAGE

    companion object {
        const val IMAGE = "image"
        const val FILE = "file"

        fun toJson(list: List<Attachment>): JSONArray {
            val a = JSONArray()
            for (x in list) a.put(JSONObject().put("name", x.name).put("path", x.path).put("kind", x.kind).put("context", x.context))
            return a
        }

        fun fromJson(a: JSONArray?): List<Attachment> =
            if (a == null) emptyList() else (0 until a.length()).map {
                val o = a.getJSONObject(it)
                Attachment(o.optString("name"), o.optString("path"), o.optString("kind", FILE), o.optString("context"))
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

/** Runware's image API: one POST with an array of tasks, the picture comes back as base64 in the same response. */
class RunwareImages(
    private val transport: Transport,
    private val key: () -> String,
    private val model: () -> String,
    private val dir: File,
    private val shelf: ImageShelf,
    private val id: () -> String = { java.util.UUID.randomUUID().toString() },
    private val now: () -> Long = { System.currentTimeMillis() },
) : ImageMaker {
    override fun make(prompt: String, shape: String): String {
        val text = prompt.trim()
        if (text.isEmpty()) throw ToolFailure("describe the picture to make")
        if (key().isEmpty()) throw ToolFailure("no Runware key: add it in Settings")
        val (w, h) = size(shape)
        val task = id()
        val body = JSONArray().put(
            JSONObject().put("taskType", "imageInference").put("taskUUID", task).put("positivePrompt", text.take(2000))
                .put("model", model().ifBlank { DEFAULT_MODEL }).put("width", w).put("height", h)
                .put("outputType", "base64Data").put("outputFormat", "JPG").put("numberResults", 1),
        ).toString()
        val res = try {
            transport.post(URL, mapOf("Authorization" to "Bearer ${key()}"), body.toByteArray(), "application/json", 120000)
        } catch (e: Exception) {
            throw ToolFailure("Runware could not be reached: ${e.message ?: e.javaClass.simpleName}")
        }
        val json = try { JSONObject(res.body) } catch (e: Exception) { null }
        if (res.status != 200 || json == null) throw ToolFailure("Runware said ${res.status}: ${problem(json) ?: res.body.take(200)}")
        problem(json)?.let { throw ToolFailure("Runware refused: $it") }
        val data = json.optJSONArray("data")
        var item: JSONObject? = null
        if (data != null) for (i in 0 until data.length()) { val o = data.optJSONObject(i); if (o != null && o.optString("taskUUID") == task) item = o }
        if (item == null && data != null && data.length() > 0) item = data.optJSONObject(0)
        val b64 = item?.optString("imageBase64Data").orEmpty().ifEmpty { item?.optString("imageDataURI").orEmpty().substringAfter("base64,", "") }
        if (b64.isEmpty()) throw ToolFailure("Runware sent no picture back")
        val bytes = try { java.util.Base64.getDecoder().decode(b64.trim()) } catch (e: Exception) { throw ToolFailure("the picture from Runware was damaged") }
        dir.mkdirs()
        val file = File(dir, "img_${now()}.jpg")
        file.writeBytes(bytes)
        shelf.add(Attachment("Generated picture", file.absolutePath, Attachment.IMAGE))
        return "The picture was made and is shown in the chat. Do not describe a link; say something short about it."
    }

    private fun problem(json: JSONObject?): String? {
        val errors = json?.optJSONArray("errors") ?: return null
        if (errors.length() == 0) return null
        val e = errors.optJSONObject(0)
        return e?.optString("message")?.ifEmpty { null } ?: e?.optString("code")?.ifEmpty { null } ?: "unknown error"
    }

    companion object {
        const val URL = "https://api.runware.ai/v1"
        const val DEFAULT_MODEL = "runware:101@1"

        /** Runware wants multiples of 64. */
        fun size(shape: String): Pair<Int, Int> = when (shape.trim().lowercase()) {
            "portrait", "tall" -> 832 to 1216
            "landscape", "wide" -> 1216 to 832
            else -> 1024 to 1024
        }
    }
}
