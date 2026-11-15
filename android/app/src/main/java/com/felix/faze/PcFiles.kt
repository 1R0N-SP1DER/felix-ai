package com.felix.faze

import org.json.JSONObject
import java.io.File
import java.io.IOException

data class PcEntry(val docId: String, val name: String, val dir: Boolean, val size: Long, val mtime: Long)

/**
 * A paired computer's shared folders as files. Document ids look like "computerId|folder|path/inside". The computer only shows folders its
 * owner listed, read only, and only those inside them. [ask] sends one question to a computer and returns its answer.
 */
class PcFiles(private val ask: (String, JSONObject) -> JSONObject) {
    companion object {
        const val MAX_DOWNLOAD = 200L * 1024 * 1024
        fun id(pc: String, folder: String = "", path: String = "") = "$pc|$folder|$path"
        fun parse(doc: String): Triple<String, String, String>? = doc.split("|", limit = 3).let { if (it.size == 3 && it[0].isNotEmpty()) Triple(it[0], it[1], it[2]) else null }
    }

    private fun checked(r: JSONObject, want: String): JSONObject {
        if (r.optString("t") == "error") throw IOException(r.optString("message", "the computer refused"))
        if (r.optString("t") != want) throw IOException("unexpected answer from the computer")
        return r
    }

    /** What is inside a document: a computer's shared folders, or the files of a folder. */
    fun children(doc: String): List<PcEntry> {
        val (pc, folder, path) = parse(doc) ?: throw IOException("bad document")
        if (folder.isEmpty()) {
            val roots = checked(ask(pc, JSONObject().put("t", "fs_roots")), "fs_roots_ok").getJSONArray("roots")
            return (0 until roots.length()).map { roots.getJSONObject(it).let { r -> PcEntry(id(pc, r.getString("id")), r.getString("name"), true, 0, 0) } }
        }
        val list = checked(ask(pc, JSONObject().put("t", "fs_list").put("root", folder).put("path", path)), "fs_list_ok").getJSONArray("entries")
        return (0 until list.length()).map {
            val e = list.getJSONObject(it)
            val name = e.getString("name")
            PcEntry(id(pc, folder, if (path.isEmpty()) name else "$path/$name"), name, e.getBoolean("dir"), e.optLong("size"), e.optLong("mtime"))
        }
    }

    /** Fetches a whole file into [dest], a chunk at a time. Fails rather than fill the phone with something huge. */
    fun download(doc: String, dest: File) {
        val (pc, folder, path) = parse(doc) ?: throw IOException("bad document")
        var offset = 0L
        dest.outputStream().use { out ->
            while (true) {
                val r = checked(ask(pc, JSONObject().put("t", "fs_read").put("root", folder).put("path", path).put("offset", offset).put("len", 524288)), "fs_read_ok")
                if (r.optLong("size") > MAX_DOWNLOAD) throw IOException("that file is too big to open from the computer (limit 200 MB)")
                val bytes = android.util.Base64.decode(r.getString("data"), android.util.Base64.DEFAULT)
                out.write(bytes)
                offset += bytes.size
                if (r.getBoolean("eof")) return
                if (bytes.isEmpty()) throw IOException("the computer sent nothing more")
            }
        }
    }
}
