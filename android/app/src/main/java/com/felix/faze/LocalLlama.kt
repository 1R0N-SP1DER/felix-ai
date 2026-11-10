package com.felix.faze

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Starts and stops the local model server. Tests hand in a fake. */
interface LocalServer {
    /** Null when a server with [model] is answering, otherwise why not. [onWait] says what is happening during a slow load. */
    fun ensure(model: String, onWait: (String) -> Unit): String?

    fun stop()
}

/**
 * Offline models: llama.cpp's llama-server, built in Termux, run as a separate process (started with root) and spoken to over
 * 127.0.0.1 in the usual chat format. Its model files are found by scanning, so a new .gguf in a known folder shows up in the picker.
 * Plain logic, tested without a phone.
 */
object LocalLlama {
    const val PORT = 8088
    const val BASE = "http://127.0.0.1:8088/v1"
    const val TERMUX = "/data/data/com.termux/files"
    const val LOG = "/data/local/tmp/faze_llama.log"
    const val KEEP_MESSAGES = 6

    /** Small on purpose: a slow phone reads every word of the prompt before it answers. */
    const val SYSTEM = "You are FAZE, a private voice assistant running offline on the user's phone. Answer briefly and plainly, in a few sentences. You cannot use tools or look things up right now."

    val modelDirs = listOf("$TERMUX/home", "/sdcard/Download", "/sdcard/models")

    private val safePath = Regex("/[A-Za-z0-9_./+-]+")

    /** Paths go into a root command line, so only plain characters are allowed. */
    fun isSafePath(p: String) = safePath.matches(p) && !p.contains("..")

    fun scanCommand() = "find ${modelDirs.joinToString(" ")} -maxdepth 4 -name '*.gguf' -type f 2>/dev/null"

    fun findBinaryCommand() = "find $TERMUX -maxdepth 6 -name llama-server -type f 2>/dev/null"

    fun label(path: String): String = File(path).name.removeSuffix(".gguf")

    fun parseModels(output: String): List<ModelOption> =
        output.lines().map { it.trim() }
            .filter { it.endsWith(".gguf") && isSafePath(it) && !File(it).name.contains("mmproj", ignoreCase = true) }
            .distinct().sortedBy { label(it).lowercase() }
            .map { ModelOption(it, label(it), File(it).parentFile?.name.orEmpty()) }

    /** The llama-server to run: the one in Termux's usr/bin if there is one, otherwise the first found. */
    fun pickBinary(output: String): String? {
        val found = output.lines().map { it.trim() }.filter { isSafePath(it) && it.endsWith("llama-server") }
        return found.firstOrNull { it.startsWith("$TERMUX/usr/bin/") } ?: found.firstOrNull()
    }

    /** Starts the server in the background. One slot and a small context keep the memory down. */
    fun serverCommand(bin: String, model: String, threads: Int, ctx: Int): String =
        "LD_LIBRARY_PATH=$TERMUX/usr/lib nohup $bin -m $model -c ${ctx.coerceIn(512, 8192)} -t ${threads.coerceIn(1, 8)} -np 1 --host 127.0.0.1 --port $PORT >$LOG 2>&1 &"

    fun stopCommand() = "pkill -f 'port $PORT'"

    /** The id of the model the server reports, or null if the answer is not a model list. */
    fun parseServed(json: String): String? = try {
        JSONObject(json).optJSONArray("data")?.optJSONObject(0)?.optString("id")?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        null
    }

    /** What the offline model is sent: the short system prompt, then only the last few things said (no tool traffic). */
    fun shrink(messages: JSONArray): JSONArray {
        val kept = ArrayList<JSONObject>()
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            val role = m.optString("role")
            val content = m.optString("content", "")
            if ((role == "user" || role == "assistant") && content.isNotBlank() && !m.has("tool_calls")) kept.add(JSONObject().put("role", role).put("content", content))
        }
        var tail = kept.takeLast(KEEP_MESSAGES)
        while (tail.isNotEmpty() && tail.first().getString("role") != "user") tail = tail.drop(1)
        // what a tool just did (after the last thing the user said) goes into that message, so the model can say it was done
        val lastUser = (0 until messages.length()).lastOrNull { messages.optJSONObject(it)?.optString("role") == "user" }
        if (lastUser != null && tail.isNotEmpty()) {
            val results = (lastUser + 1 until messages.length()).mapNotNull { messages.optJSONObject(it) }
                .filter { it.optString("role") == "tool" }.map { it.optString("content").take(400) }
            if (results.isNotEmpty()) {
                val last = tail.last()
                if (last.getString("role") == "user") tail = tail.dropLast(1) + JSONObject().put("role", "user").put("content", last.getString("content") + "\n\n(Just done for you: " + results.joinToString("; ") + ". Say so in one short sentence.)")
            }
        }
        val out = JSONArray().put(JSONObject().put("role", "system").put("content", SYSTEM))
        for (m in tail) out.put(m)
        return out
    }
}

/** Starts the server for a model when it is not already the one running, and waits until it answers. */
class LocalServerControl(
    private val run: (String, Long) -> String,
    private val alive: () -> String?,
    private val binary: () -> String?,
    private val threads: () -> Int,
    private val ctx: () -> Int,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val startTimeoutMs: Long = 120_000,
) : LocalServer {
    @Volatile private var started: String? = null

    override fun ensure(model: String, onWait: (String) -> Unit): String? {
        if (!LocalLlama.isSafePath(model) || !model.endsWith(".gguf")) return "that model file is not allowed"
        val up = alive()
        if (up != null) {
            val name = File(model).name
            if (started == model || (started == null && up.contains(name))) { started = model; return null }
            stop()
        }
        val bin = binary() ?: return "llama-server was not found in Termux (build llama.cpp there first)"
        onWait("Loading ${LocalLlama.label(model)}…")
        try {
            run(LocalLlama.serverCommand(bin, model, threads(), ctx()), 8000)
        } catch (e: Exception) {
            return "could not start the model server: ${e.message ?: e.javaClass.simpleName} (is root granted?)"
        }
        started = model
        var waited = 0L
        while (waited < startTimeoutMs) {
            sleep(500)
            waited += 500
            if (alive() != null) return null
        }
        started = null
        val tail = try { run("tail -n 4 ${LocalLlama.LOG}", 4000).trim().replace('\n', ' ').take(200) } catch (e: Exception) { "" }
        return "the model did not start in time" + if (tail.isNotEmpty()) " ($tail)" else " (it may have been killed for memory)"
    }

    override fun stop() {
        started = null
        try { run(LocalLlama.stopCommand(), 4000) } catch (_: Exception) {}
        sleep(800)
    }
}

/** The offline answerer: makes sure the model is running, then talks to it with a small prompt and no tools. */
class OfflineChat(
    private val inner: ChatApi,
    private val server: LocalServer,
    private val model: () -> String,
    private val notice: (String) -> Unit,
) : ChatApi {
    override fun chat(model: String, messages: JSONArray, tools: JSONArray?, onToken: (String) -> Unit, cancelled: () -> Boolean): ChatResult {
        val path = this.model()
        if (path.isEmpty()) throw RemoteError("model", "pick an offline model first (model picker > Offline)")
        server.ensure(path, notice)?.let { throw RemoteError("model", it) }
        if (cancelled()) throw Cancelled()
        return inner.chat("local", LocalLlama.shrink(messages), null, onToken, cancelled)
    }
}
