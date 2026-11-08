package com.felix.faze

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * The real phone powers behind [PowerHost]. Only a saved tool whose code and powers the person read and approved gets here,
 * and only for the powers it declared (see [Powers] and [JsSandbox]). The commands are whatever the approved code says.
 */
class AndroidPowers(private val context: Context) : PowerHost {
    private val maxText = 20_000

    /** Runs a program and returns its exit code and output. Output is read on a helper thread so a stuck process can be stopped on time. */
    private fun exec(argv: List<String>, timeoutMs: Long, mergeErrors: Boolean): Pair<Int, ByteArray> {
        val pb = ProcessBuilder(argv)
        if (mergeErrors) pb.redirectErrorStream(true) else pb.redirectError(File("/dev/null"))
        pb.environment().remove("LD_PRELOAD")
        pb.environment().remove("LD_LIBRARY_PATH")
        val p = pb.start()
        p.outputStream.close()
        val out = ByteArrayOutputStream()
        val reader = Thread { try { p.inputStream.copyTo(out) } catch (_: Exception) {} }.also { it.start() }
        if (!p.waitFor(timeoutMs.coerceAtLeast(1), TimeUnit.MILLISECONDS)) {
            p.destroyForcibly()
            reader.join(500)
            throw RuntimeException("timed out")
        }
        reader.join(1000)
        return p.exitValue() to out.toByteArray()
    }

    /** The one place in this file where the root shell is started. */
    private fun su(command: String, timeoutMs: Long, mergeErrors: Boolean) = exec(listOf("su", "-c", command), timeoutMs, mergeErrors)

    private fun text(r: Pair<Int, ByteArray>): String =
        String(r.second, Charsets.UTF_8).take(maxText) + (if (r.first != 0) "\n[exit ${r.first}]" else "")

    override fun shell(command: String, timeoutMs: Long): String = text(exec(listOf("sh", "-c", command), timeoutMs, true))

    override fun root(command: String, timeoutMs: Long): String = text(su(command, timeoutMs, true))

    override fun http(url: String, method: String, body: String, contentType: String, timeoutMs: Long): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 10_000
            c.readTimeout = timeoutMs.coerceIn(1_000L, 30_000L).toInt()
            c.setRequestProperty("User-Agent", "Faze")
            if (method == "POST") {
                c.doOutput = true
                c.setRequestProperty("Content-Type", contentType)
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code >= 400) c.errorStream else c.inputStream
            val reply = stream?.bufferedReader()?.use { it.readText() }.orEmpty().take(200_000)
            if (code >= 400) throw RuntimeException("HTTP $code: ${reply.take(200)}")
            return reply
        } finally {
            c.disconnect()
        }
    }

    override fun readFile(path: String): String {
        val f = File(path)
        if (!f.isFile) throw RuntimeException("no such file: $path")
        return f.bufferedReader().use { r ->
            val buf = CharArray(100_000)
            val n = r.read(buf)
            if (n <= 0) "" else String(buf, 0, n)
        }
    }

    override fun writeFile(path: String, text: String): String {
        val f = File(path)
        f.parentFile?.mkdirs()
        f.writeText(text)
        return "wrote ${text.length} characters to $path"
    }

    override fun listDir(path: String): String {
        val items = File(path).listFiles() ?: throw RuntimeException("cannot list that folder: $path")
        return items.sortedBy { it.name }.take(500).joinToString("\n") { if (it.isDirectory) it.name + "/" else it.name }
    }

    override fun screenshot(): String {
        val (_, bytes) = su("screencap -p", 15_000, false)
        if (bytes.size < 100) throw RuntimeException("no image came back (is root granted?)")
        val dir = File(context.cacheDir, "shots").also { it.mkdirs() }
        val f = File(dir, "shot_${System.currentTimeMillis()}.png")
        f.writeBytes(bytes)
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(10)?.forEach { it.delete() } // keep only the latest few
        return f.absolutePath
    }

    override fun intent(spec: String): String {
        val o = JSONObject(spec)
        val i = Intent(o.optString("action").ifEmpty { Intent.ACTION_VIEW })
        val data = o.optString("data")
        val type = o.optString("type")
        if (data.isNotEmpty() && type.isNotEmpty()) i.setDataAndType(Uri.parse(data), type)
        else if (data.isNotEmpty()) i.data = Uri.parse(data)
        else if (type.isNotEmpty()) i.type = type
        val pkg = o.optString("package")
        val cls = o.optString("class")
        if (pkg.isNotEmpty() && cls.isNotEmpty()) i.setClassName(pkg, cls) else if (pkg.isNotEmpty()) i.setPackage(pkg)
        o.optJSONObject("extras")?.let { ex ->
            ex.keys().forEach { k ->
                when (val v = ex.get(k)) {
                    is Boolean -> i.putExtra(k, v)
                    is Int -> i.putExtra(k, v)
                    is Long -> i.putExtra(k, v)
                    is Double -> i.putExtra(k, v)
                    else -> i.putExtra(k, v.toString())
                }
            }
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
        return "started"
    }
}
