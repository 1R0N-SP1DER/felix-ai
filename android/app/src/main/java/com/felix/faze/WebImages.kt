package com.felix.faze

import java.io.File
import org.json.JSONObject

/** Finds pictures on the web (Tavily's image results) and puts them in the chat. */
interface ImageFinder {
    /** Returns what the AI is told; the pictures themselves go onto the shelf. */
    fun find(query: String, count: Int): String
}

object ImageUrl {
    /** Only plain https pictures from a public-looking host: no logins in the address, no localhost, no private addresses. */
    fun allowed(url: String): Boolean {
        val u = try { java.net.URI(url) } catch (e: Exception) { return false }
        if (u.scheme != "https" || u.userInfo != null) return false
        val host = (u.host ?: return false).lowercase()
        if (host == "localhost" || host.endsWith(".local") || host.endsWith(".internal")) return false
        if (Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$").matches(host)) {
            val p = host.split('.').map { it.toInt() }
            if (p[0] == 10 || p[0] == 127 || p[0] == 0 || (p[0] == 169 && p[1] == 254) || (p[0] == 192 && p[1] == 168) || (p[0] == 172 && p[1] in 16..31)) return false
        }
        if (host.contains(':')) return false
        return true
    }
}

class WebImages(
    private val transport: Transport,
    private val key: () -> String,
    private val dir: File,
    private val shelf: ImageShelf,
    /** Checks the bytes really are a picture and returns them in a safe, smaller form (null when they are not). */
    private val normalize: (ByteArray) -> ByteArray?,
    private val now: () -> Long = { System.currentTimeMillis() },
) : ImageFinder {
    override fun find(query: String, count: Int): String {
        val q = query.trim()
        if (q.isEmpty()) throw ToolFailure("what should I look for?")
        if (key().isBlank()) throw ToolFailure("finding pictures needs the Tavily key (add it in Settings)")
        val body = JSONObject().put("query", q).put("max_results", 3).put("include_images", true).put("include_image_descriptions", true).put("search_depth", "basic")
        val r = try {
            transport.post(SEARCH, mapOf("Authorization" to "Bearer ${key().trim()}"), body.toString().toByteArray(Charsets.UTF_8), "application/json", 20000)
        } catch (e: Exception) {
            throw ToolFailure("Tavily could not be reached: ${e.message ?: e.javaClass.simpleName}")
        }
        if (r.status != 200) throw ToolFailure("Tavily answered ${r.status}")
        val data = try { JSONObject(r.body) } catch (e: Exception) { throw ToolFailure("Tavily sent something unreadable") }
        val found = data.optJSONArray("images") ?: throw ToolFailure("no pictures found for '$q'")
        val wanted = count.coerceIn(1, MAX)
        dir.mkdirs()
        val shown = ArrayList<String>()
        for (i in 0 until found.length()) {
            if (shown.size >= wanted) break
            val item = found.opt(i)
            val url = if (item is JSONObject) item.optString("url") else item?.toString().orEmpty()
            val note = if (item is JSONObject) item.optString("description").replace(Regex("\\s+"), " ").trim().take(120) else ""
            if (!ImageUrl.allowed(url)) continue
            val bytes = try { transport.getBytes(url, mapOf("Accept" to "image/*"), 15000, MAX_BYTES) } catch (e: Exception) { null } ?: continue
            val safe = normalize(bytes) ?: continue
            val file = File(dir, "web_${now()}_${shown.size}.jpg")
            file.writeBytes(safe)
            shelf.add(Attachment(note.ifEmpty { "Picture of $q" }, file.absolutePath, Attachment.IMAGE))
            shown.add(note.ifEmpty { "a picture" })
        }
        if (shown.isEmpty()) throw ToolFailure("found no pictures that could be shown for '$q'")
        return "Showed ${shown.size} picture${if (shown.size == 1) "" else "s"} in the chat for '$q': ${shown.joinToString("; ")}. Do not paste links; say something short about what the pictures show."
    }

    companion object {
        const val SEARCH = "https://api.tavily.com/search"
        const val MAX = 4
        const val MAX_BYTES = 6 * 1024 * 1024
    }
}
