package com.felix.faze

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Lets a paired computer browse and read this phone's files, read only. Same messages the computer uses for its own shared folders
 * (fs_roots, fs_list, fs_read). [roots] says which folders are open right now: nothing, a few public folders, or everything,
 * depending on the switches in Settings > Advanced > Devices. Every path is resolved (links included) and must stay inside its root.
 */
class PhoneFiles(private val roots: () -> List<Pair<String, File>>, private val log: (String) -> Unit = {}) {
    companion object {
        val TYPES = setOf("fs_roots", "fs_list", "fs_read")
        const val CHUNK = 512 * 1024
        const val MAX_ENTRIES = 2000
    }

    fun handle(msg: JSONObject): JSONObject? {
        val t = msg.optString("t")
        if (t !in TYPES) return null
        val open = roots()
        if (open.isEmpty()) return error("File sharing is off on the phone. Turn it on in Settings > Advanced > Devices.")
        return try {
            when (t) {
                "fs_roots" -> JSONObject().put("t", "fs_roots_ok").put("roots", JSONArray(open.map { JSONObject().put("id", it.first).put("name", it.first) }))
                "fs_list" -> JSONObject().put("t", "fs_list_ok").put("entries", list(open, msg.optString("root"), msg.optString("path")))
                else -> read(open, msg)
            }
        } catch (e: IOException) {
            error(e.message ?: "that did not work")
        }
    }

    private fun base(open: List<Pair<String, File>>, root: String): File = (open.firstOrNull { it.first == root } ?: throw IOException("that folder is not shared")).second.canonicalFile

    private fun inside(base: File, f: File): Boolean = f == base || f.path.startsWith(base.path.trimEnd('/') + "/")

    private fun resolve(open: List<Pair<String, File>>, root: String, rel: String): File {
        val b = base(open, root)
        val parts = rel.split("/").filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." || it.contains('\u0000') }) throw IOException("that path is not allowed")
        val full = File(b, parts.joinToString("/")).canonicalFile
        if (!inside(b, full)) throw IOException("that path leaves the shared folder")
        return full
    }

    private fun list(open: List<Pair<String, File>>, root: String, rel: String): JSONArray {
        val dir = resolve(open, root, rel)
        if (!dir.isDirectory) throw IOException("not a folder")
        val b = base(open, root)
        val names = dir.list() ?: throw IOException("the phone would not list that folder (is Access to all files allowed?)")
        val out = JSONArray()
        for (name in names.sortedWith(compareBy({ !File(dir, it).isDirectory }, { it.lowercase() })).take(MAX_ENTRIES)) {
            val real = try { File(dir, name).canonicalFile } catch (e: IOException) { continue }
            if (!inside(b, real) || !real.exists()) continue
            out.put(JSONObject().put("name", name).put("dir", real.isDirectory).put("size", if (real.isDirectory) 0L else real.length()).put("mtime", real.lastModified()))
        }
        return out
    }

    private fun read(open: List<Pair<String, File>>, msg: JSONObject): JSONObject {
        val f = resolve(open, msg.optString("root"), msg.optString("path"))
        if (!f.isFile) throw IOException("not a file")
        val offset = msg.optLong("offset", 0).coerceAtLeast(0)
        val len = msg.optInt("len", CHUNK).coerceIn(1, CHUNK)
        if (offset == 0L) log("A computer reads ${msg.optString("root")}/${msg.optString("path")}")
        val size = f.length()
        val buf = ByteArray(len)
        val n = try { java.io.RandomAccessFile(f, "r").use { it.seek(offset); it.read(buf).coerceAtLeast(0) } } catch (e: java.io.IOException) { throw IOException(e.message ?: "could not read the file") }
        return JSONObject().put("t", "fs_read_ok").put("data", java.util.Base64.getEncoder().encodeToString(buf.copyOf(n))).put("size", size).put("eof", offset + n >= size)
    }

    private fun error(m: String) = JSONObject().put("t", "error").put("message", m)
}
