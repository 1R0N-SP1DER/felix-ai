package com.felix.faze

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Lets a connected computer take copies of the offline models on this phone (the .gguf files LocalLlama finds), so it can run them with Ollama.
 * The files can sit where only root reaches (Termux's home), so listing and staging use root: `models_list` lists them, `models_stage` copies one
 * into a public folder (STAGE) that the computer then reads through the normal file messages, and `models_unstage` removes the copy again.
 * Only files LocalLlama would list are allowed, and only plain-character paths ever reach a root command line.
 */
class PhoneModels(
    private val enabled: () -> Boolean,
    private val root: (String, Long) -> String,
    private val log: (String) -> Unit = {},
    /** Whether root commands are used. Without root only the shared folders Faze itself can read (Download, models) are listed and copied. */
    private val rootOn: () -> Boolean = { true },
    private val plainDirs: List<String> = listOf("/sdcard/Download", "/sdcard/models"),
    private val stageDir: String = STAGE,
) {
    companion object {
        val TYPES = setOf("models_list", "models_stage", "models_unstage")
        const val STAGE = "/sdcard/Faze/models"
        const val ROOT_NAME = "Models"
        private val plainName = Regex("[A-Za-z0-9_.+-]+\\.gguf")

        fun listCommand() = LocalLlama.modelDirs.joinToString(" ", "find ", " -maxdepth 4 -name '*.gguf' -type f -exec stat -c '%s|%n' {} + 2>/dev/null")

        /** Lines like `2600000000|/sdcard/Download/Qwen3.5-4B-Q4_K_M.gguf`, as (size, path), without projector files and anything unsafe. */
        fun parseList(out: String): List<Pair<Long, String>> = out.lines().mapNotNull { line ->
            val bar = line.indexOf('|')
            if (bar <= 0) return@mapNotNull null
            val size = line.substring(0, bar).trim().toLongOrNull() ?: return@mapNotNull null
            val path = line.substring(bar + 1).trim()
            if (!path.endsWith(".gguf") || !LocalLlama.isSafePath(path) || File(path).name.contains("mmproj", ignoreCase = true)) null else size to path
        }.distinctBy { it.second }.sortedBy { it.second.lowercase() }

        fun allowedSource(path: String) = LocalLlama.isSafePath(path) && path.endsWith(".gguf") && LocalLlama.modelDirs.any { path.startsWith("$it/") }

        fun stagedName(name: String) = plainName.matches(name)
    }

    fun handle(msg: JSONObject): JSONObject? {
        val t = msg.optString("t")
        if (t !in TYPES) return null
        if (!enabled()) return err("Model sharing is off on the phone (Settings > Devices > Let computers copy my offline models).")
        return when (t) {
            "models_list" -> {
                val rows = if (rootOn()) parseList(root(listCommand(), 20000)) else plainList()
                JSONObject().put("t", "models_list_ok").put("models", JSONArray(rows.map { JSONObject().put("path", it.second).put("name", File(it.second).name).put("size", it.first) }))
            }
            "models_stage" -> stage(msg.optString("path"))
            else -> {
                val name = msg.optString("name")
                if (!stagedName(name)) return err("that is not a staged model")
                if (rootOn()) root("rm -f '$STAGE/$name'", 10000) else File(stageDir, name).delete()
                JSONObject().put("t", "models_unstage_ok")
            }
        }
    }

    /** Models Faze can reach on its own (no root): .gguf files in the shared folders, with the same safety rules as the root listing. */
    private fun plainList(): List<Pair<Long, String>> = plainDirs.flatMap { d ->
        try { File(d).walkTopDown().maxDepth(4).filter { it.isFile && it.name.endsWith(".gguf") }.map { it.length() to it.path }.toList() } catch (e: Exception) { emptyList() }
    }.filter { (_, p) -> LocalLlama.isSafePath(p) && !File(p).name.contains("mmproj", ignoreCase = true) }.distinctBy { it.second }.sortedBy { it.second.lowercase() }

    private fun stagePlain(path: String): JSONObject {
        if (!LocalLlama.isSafePath(path) || !path.endsWith(".gguf") || plainDirs.none { path.startsWith("$it/") }) {
            return err("without root Faze can only share models in Download or models. Copy it there from Termux with: cp ~/yourmodel.gguf ~/storage/downloads/")
        }
        val src = File(path)
        val name = src.name
        if (!stagedName(name)) return err("that file name has characters the copy cannot handle")
        log("A computer copies the model $name")
        return try {
            val dir = File(stageDir).also { it.mkdirs() }
            src.copyTo(File(dir, name), overwrite = true)
            JSONObject().put("t", "models_stage_ok").put("root", ROOT_NAME).put("path", name).put("size", File(dir, name).length())
        } catch (e: Exception) {
            err("the phone could not copy that model: ${e.message ?: "permission denied"}. Turn on Access to all files for Faze in Settings, and check there is free space.")
        }
    }

    private fun stage(path: String): JSONObject {
        if (!rootOn()) return stagePlain(path)
        if (!allowedSource(path)) return err("that model file is not allowed")
        val name = File(path).name
        if (!stagedName(name)) return err("that file name has characters the copy cannot handle")
        log("A computer copies the model $name")
        root("mkdir -p '$STAGE' && cp -f '$path' '$STAGE/$name' && chmod 644 '$STAGE/$name'", 15 * 60 * 1000L)
        val size = root("stat -c %s '$STAGE/$name' 2>/dev/null", 10000).trim().toLongOrNull() ?: return err("the phone could not copy that model (not enough free space?)")
        return JSONObject().put("t", "models_stage_ok").put("root", ROOT_NAME).put("path", name).put("size", size)
    }

    private fun err(m: String) = JSONObject().put("t", "error").put("message", m)
}
