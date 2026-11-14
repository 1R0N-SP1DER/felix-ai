package com.felix.faze

import java.io.File

/**
 * The opening lines as saved sound: made once with the Gemini voice, kept on the phone, played back with no network and no usage.
 * The file name carries the voice, the model and the wording, so changing any of them makes the old files unused (and they are cleared
 * the next time the lines are downloaded).
 */
class VoiceLines(
    private val dir: File,
    private val synth: Synth?,
    private val lines: List<String>,
    private val tag: () -> String,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {
    private fun file(i: Int): File = File(dir, "${tag().filter { it.isLetterOrDigit() || it == '-' || it == '.' }}_${i}_${lines[i].hashCode().toUInt()}.pcm")

    fun has(i: Int): Boolean = i in lines.indices && file(i).let { it.isFile && it.length() > 0 }

    /** Indexes of the lines that are saved for the current voice. */
    fun saved(): List<Int> = lines.indices.filter { has(it) }

    fun pcm(i: Int): ByteArray? = if (has(i)) file(i).readBytes() else null

    /** Deletes saved sound that belongs to another voice, model or wording. */
    private fun clearOthers() {
        val keep = lines.indices.map { file(it).name }.toSet()
        dir.listFiles()?.forEach { if (it.name !in keep) it.delete() }
    }

    /**
     * Makes every line that is not saved yet, one request each. A failed request is tried once more after a pause; a second failure
     * stops (the rest can be fetched later: saved lines are kept). Returns how many are saved now and, if it stopped early, why.
     */
    fun download(cancelled: () -> Boolean = { false }, progress: (Int, Int) -> Unit = { _, _ -> }): Pair<Int, String?> {
        val maker = synth ?: return saved().size to "no Gemini key: add it in Settings"
        dir.mkdirs()
        clearOthers()
        for (i in lines.indices) {
            if (cancelled()) return saved().size to "stopped"
            if (has(i)) { progress(saved().size, lines.size); continue }
            var pcm: ByteArray? = null
            var why = ""
            for (attempt in 0..1) {
                try { pcm = maker.synth(lines[i]); break } catch (e: Exception) { why = e.message ?: e.javaClass.simpleName; if (attempt == 0) sleep(6000) }
            }
            if (pcm == null || pcm.isEmpty()) return saved().size to why.ifEmpty { "no sound came back" }
            val f = file(i)
            val part = File(dir, f.name + ".part")
            part.writeBytes(pcm)
            part.renameTo(f)
            progress(saved().size, lines.size)
        }
        return saved().size to null
    }
}
