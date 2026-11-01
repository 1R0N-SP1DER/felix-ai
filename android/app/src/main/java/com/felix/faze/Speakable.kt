package com.felix.faze

/** Turns a reply that is still being written into sentences that can be spoken as soon as each one is finished. */
object Speakable {
    const val MAX_CHARS = 1500
    private val CODE = Regex("```.*?```", RegexOption.DOT_MATCHES_ALL)
    private val INLINE = Regex("`([^`]*)`")
    private val LINK = Regex("\\[([^\\]]*)]\\([^)]*\\)")
    private val URL = Regex("https?://\\S+")
    private val MARKUP = Regex("[*_#>~|]+")
    private val SPACES = Regex("\\s+")
    private val END = Regex("[.!?\\u2026][\"')\\]]*\\s+|\\n+")
    private val BLANKS = Regex("[ \\t]+")

    /** Reply text without markdown, code blocks and links, so the phone does not read out symbols. */
    fun clean(text: String): String {
        var t = CODE.replace(text, " ")
        t = INLINE.replace(t) { it.groupValues[1] }
        t = LINK.replace(t) { it.groupValues[1] }
        t = URL.replace(t, " link ")
        t = MARKUP.replace(t, " ")
        return SPACES.replace(t, " ").trim().take(MAX_CHARS)
    }

    /**
     * Finished sentences in text that is still arriving: (sentences, rest). Code blocks are said once as "Code omitted",
     * fragments under 20 characters wait for the next sentence, and a sentence end needs a space after it, so "3.5" is not a break.
     */
    fun split(buffer: String, final: Boolean = false): Pair<List<String>, String> {
        var text = CODE.replace(buffer, " Code omitted. ")
        var hold = ""
        val open = text.indexOf("```")
        if (open >= 0) {
            hold = text.substring(open)
            text = text.substring(0, open)
        }
        val sentences = ArrayList<String>()
        var start = 0
        for (m in END.findAll(text)) {
            val piece = BLANKS.replace(text.substring(start, m.range.last + 1), " ").trim()
            if (piece.length >= 20 || (m.value.contains("\n") && piece.isNotEmpty())) {
                sentences.add(piece)
                start = m.range.last + 1
            }
        }
        if (final) {
            val tail = BLANKS.replace(text.substring(start), " ").trim()
            if (tail.isNotEmpty()) sentences.add(tail)
            return Pair(sentences, "")
        }
        return Pair(sentences, text.substring(start) + hold)
    }
}

/** Feed pieces as they stream in; get back the sentences that just became complete. */
class SentenceStream {
    private var text = ""
    private var done = 0

    fun reset() {
        text = ""
        done = 0
    }

    fun feed(piece: String): List<String> {
        text += piece
        val found = Speakable.split(text).first
        val fresh = found.drop(done)
        done = found.size
        return fresh
    }

    fun finish(): List<String> {
        val found = Speakable.split(text, final = true).first
        val fresh = found.drop(done)
        done = found.size
        return fresh
    }
}
