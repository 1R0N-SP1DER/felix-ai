package com.felix.faze

import java.io.File
import java.io.IOException
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Unzips a downloaded model. Refuses entries that would land outside the target folder (zip slip) or that are huge. */
object ZipSafe {
    const val MAX_TOTAL = 400L * 1024 * 1024

    fun extract(input: InputStream, target: File, onProgress: (Long) -> Unit = {}) {
        val root = target.canonicalFile
        root.mkdirs()
        var total = 0L
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val out = File(root, entry.name).canonicalFile
                if (out.path != root.path && !out.path.startsWith(root.path + File.separator)) throw IOException("unsafe path in archive: ${entry.name}")
                if (entry.isDirectory) {
                    out.mkdirs()
                    continue
                }
                out.parentFile?.mkdirs()
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = zip.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_TOTAL) throw IOException("archive is too large")
                        o.write(buf, 0, n)
                        onProgress(total)
                    }
                }
            }
        }
    }

    /** The model zip holds one folder; the recognizer wants the folder that has the "am" and "conf" parts. */
    fun modelRoot(dir: File): File? {
        if (File(dir, "am").isDirectory && File(dir, "conf").isDirectory) return dir
        return dir.listFiles()?.filter { it.isDirectory }?.firstNotNullOfOrNull { modelRoot(it) }
    }
}

/** Counts the bytes read through it, so a download can show progress while it is being unzipped. */
class CountingInputStream(input: InputStream) : FilterInputStream(input) {
    @Volatile
    var count = 0L
        private set

    override fun read(): Int {
        val b = super.read()
        if (b >= 0) count++
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) count += n
        return n
    }
}
