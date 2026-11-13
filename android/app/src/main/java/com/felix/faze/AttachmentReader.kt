package com.felix.faze

import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * Turns an attachment into words the AI can use: a file is read as text here on the phone; a picture (or the pages of a PDF) goes to the
 * vision model, which answers with what it sees. Picture work is injected so this is tested without a phone or a network.
 */
class AttachmentReader(
    private val vision: (jpeg: ByteArray, question: String) -> String,
    private val pdfPages: (File) -> List<ByteArray> = { emptyList() },
    private val readBytes: (File) -> ByteArray = { it.readBytes() },
) {
    /** Returns the attachment with its context filled in. Never throws: a failure becomes the context, so the AI can say what went wrong. */
    fun read(a: Attachment, question: String): Attachment {
        if (a.context.isNotEmpty()) return a
        val file = File(a.path)
        val text = try {
            when {
                a.isImage -> describe(readBytes(file), question)
                file.extension.lowercase() == "pdf" -> pdf(file, question)
                else -> fileText(a.name, readBytes(file))
            }
        } catch (e: Exception) {
            "This attachment could not be read: ${e.message ?: e.javaClass.simpleName}"
        }
        return a.copy(context = text)
    }

    private fun describe(jpeg: ByteArray, question: String): String {
        val q = question.trim().ifEmpty { "Describe this picture in detail, and read out any text in it." }
        return vision(jpeg, "The user attached this picture to their message: \"$q\"\nDescribe what is in it in detail, read out any text exactly, and note anything relevant to their message.")
    }

    private fun pdf(file: File, question: String): String {
        val pages = pdfPages(file)
        if (pages.isEmpty()) return "This PDF could not be opened."
        return pages.withIndex().joinToString("\n\n") { (i, jpeg) -> "Page ${i + 1}:\n" + describe(jpeg, question) }
    }

    companion object {
        const val MAX_CHARS = 24_000
        const val MAX_PICTURES = 4

        private val TEXT_EXT = setOf("txt", "md", "csv", "tsv", "json", "xml", "html", "htm", "log", "yaml", "yml", "ini", "conf", "kt", "java", "py", "js", "ts", "c", "h", "cpp", "go", "rs", "sh", "sql", "css", "tex", "srt", "toml", "gradle", "properties")
        private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")

        fun isImageName(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in IMAGE_EXT

        /** True if the bytes look like text (no NUL bytes near the start), whatever the file is called. */
        fun looksLikeText(bytes: ByteArray): Boolean = bytes.take(4096).none { it == 0.toByte() }

        fun fileText(name: String, bytes: ByteArray): String {
            val ext = name.substringAfterLast('.', "").lowercase()
            val raw = when {
                ext == "docx" -> docx(bytes)
                ext in TEXT_EXT || looksLikeText(bytes) -> String(bytes, Charsets.UTF_8)
                else -> return "Faze cannot read this kind of file ($name) yet."
            }
            val clean = raw.trim()
            if (clean.isEmpty()) return "The file is empty or has no readable text."
            return if (clean.length <= MAX_CHARS) clean else clean.take(MAX_CHARS) + "\n[…the file continues; only the first $MAX_CHARS characters were read]"
        }

        /** The words of a Word file: the text inside word/document.xml, paragraph by paragraph. */
        fun docx(bytes: ByteArray): String {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.name == "word/document.xml") {
                        val xml = zip.readBytes().toString(Charsets.UTF_8)
                        return xml.replace("</w:p>", "\n").replace(Regex("<w:tab/>"), "\t").replace(Regex("<[^>]+>"), "")
                            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
                    }
                }
            }
            throw ToolFailure("that Word file has no document text")
        }

        /** The request to the vision model: one picture and the instruction, in the usual chat format. */
        fun visionMessages(instruction: String, jpeg: ByteArray): JSONArray {
            val url = "data:image/jpeg;base64," + java.util.Base64.getEncoder().encodeToString(jpeg)
            val user = JSONArray()
                .put(JSONObject().put("type", "text").put("text", instruction))
                .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", url)))
            return JSONArray()
                .put(JSONObject().put("role", "system").put("content", "You are the eyes of an assistant. You are shown a picture the user sent. Describe it accurately and in detail, read any text in it exactly, and do not guess what you cannot see."))
                .put(JSONObject().put("role", "user").put("content", user))
        }
    }
}
