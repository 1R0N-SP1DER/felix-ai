package com.felix.faze

import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/** One root command and what it printed. Starting the model server, finding models and stopping it all go through here. */
object Su {
    fun run(command: String, timeoutMs: Long): String {
        val pb = ProcessBuilder("su", "-c", command)
        pb.redirectErrorStream(true)
        pb.environment().remove("LD_PRELOAD")
        pb.environment().remove("LD_LIBRARY_PATH")
        val p = try { pb.start() } catch (e: Exception) { throw RemoteError("model", "root is not available (offline models need it)") }
        p.outputStream.close()
        val out = ByteArrayOutputStream()
        val reader = Thread { try { p.inputStream.copyTo(out) } catch (_: Exception) {} }.also { it.start() }
        if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) { p.destroyForcibly(); reader.join(500); throw RemoteError("model", "the phone did not answer in time") }
        reader.join(1000)
        return String(out.toByteArray(), Charsets.UTF_8)
    }
}
