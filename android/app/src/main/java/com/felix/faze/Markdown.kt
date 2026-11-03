package com.felix.faze

/** One run of text inside a line: plain, `code`, **strong** or *emphasis*. */
data class Span(val text: String, val kind: Int = PLAIN) {
    companion object { const val PLAIN = 0; const val CODE = 1; const val STRONG = 2; const val EM = 3 }
}

sealed class Block {
    object Gap : Block()
    data class Heading(val spans: List<Span>) : Block()
    data class Para(val spans: List<Span>) : Block()
    data class Item(val ordered: Boolean, val number: Int, val spans: List<Span>) : Block()
    data class Code(val lang: String, val text: String) : Block()
}

/** The markdown the web page understands (renderMarkdown in core/web/app.js): headings, bullet and numbered lists, fenced code, `code`, **bold**, *italic*. */
object Markdown {
    private val bullet = Regex("^\\s*[*\\-\u2022]\\s+(.*)$")
    private val numbered = Regex("^\\s*\\d+[.)]\\s+(.*)$")
    private val heading = Regex("^#{1,4}\\s+(.*)$")

    fun parse(src: String): List<Block> {
        val out = ArrayList<Block>()
        var inCode = false
        var lang = ""
        val code = ArrayList<String>()
        var listKind = 0 // 0 none, 1 bullets, 2 numbers
        var number = 0
        for (line in src.split('\n')) {
            if (line.trim().startsWith("```")) {
                if (inCode) {
                    out.add(Block.Code(lang, code.joinToString("\n")))
                    code.clear()
                    inCode = false
                } else {
                    listKind = 0
                    inCode = true
                    lang = line.trim().drop(3).trim().split(Regex("\\s+")).first()
                }
                continue
            }
            if (inCode) { code.add(line); continue }
            val li = bullet.matchEntire(line)
            val ol = if (li == null) numbered.matchEntire(line) else null
            if (li != null || ol != null) {
                val kind = if (li != null) 1 else 2
                if (listKind != kind) { listKind = kind; number = 0 }
                number++
                out.add(Block.Item(kind == 2, number, inline((li ?: ol)!!.groupValues[1])))
                continue
            }
            listKind = 0
            val h = heading.matchEntire(line)
            when {
                h != null -> out.add(Block.Heading(inline(h.groupValues[1])))
                line.isBlank() -> out.add(Block.Gap)
                else -> out.add(Block.Para(inline(line)))
            }
        }
        if (inCode) out.add(Block.Code(lang, code.joinToString("\n"))) // a block left open while text is still streaming
        return out
    }

    fun inline(t: String): List<Span> {
        val out = ArrayList<Span>()
        val plain = StringBuilder()
        fun flush() { if (plain.isNotEmpty()) { out.add(Span(plain.toString())); plain.setLength(0) } }
        var i = 0
        while (i < t.length) {
            val c = t[i]
            if (c == '`') {
                val end = t.indexOf('`', i + 1)
                if (end > i + 1) { flush(); out.add(Span(t.substring(i + 1, end), Span.CODE)); i = end + 1; continue }
            } else if (c == '*' && t.startsWith("**", i)) {
                val end = t.indexOf("**", i + 2)
                if (end > i + 2 && !t.substring(i + 2, end).contains('*')) { flush(); out.add(Span(t.substring(i + 2, end), Span.STRONG)); i = end + 2; continue }
            } else if (c == '*' && (i == 0 || t[i - 1].isWhitespace() || t[i - 1] == '(') && i + 1 < t.length && !t[i + 1].isWhitespace() && t[i + 1] != '*') {
                var end = t.indexOf('*', i + 1)
                while (end > 0) {
                    val next = if (end + 1 < t.length) t[end + 1] else ' '
                    if (end > i + 1 && (end + 1 >= t.length || next.isWhitespace() || next in ").,!?")) break
                    end = t.indexOf('*', end + 1)
                }
                if (end > i + 1) { flush(); out.add(Span(t.substring(i + 1, end), Span.EM)); i = end + 1; continue }
            }
            plain.append(c)
            i++
        }
        flush()
        return out
    }
}

/** The line of the live bar (liveLabel in app.js) and which state the app is in. */
object LiveText {
    fun label(state: String, name: String = "FAZE"): String {
        val who = name.uppercase()
        return when (state) {
            "thinking" -> "\u25CC $who is thinking"
            "tool" -> "\u25CC $who is working"
            "writing" -> "\u25CC writing"
            "done" -> "\u2726 Done"
            "stopped" -> "\u25CC Stopped"
            "error" -> "\u2726 Something went wrong"
            else -> "\u2726 $who is ready"
        }
    }

    /** Thinking until something appears; working while a tool step shows and no reply yet; writing once the reply is coming in. */
    fun stateFor(busy: Boolean, hasText: Boolean, hasSteps: Boolean): String = when {
        !busy -> "ready"
        hasText -> "writing"
        hasSteps -> "tool"
        else -> "thinking"
    }
}
