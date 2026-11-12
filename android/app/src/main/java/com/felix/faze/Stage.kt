package com.felix.faze

/** What the model may put on the live stage: a picture, diagram, chart, table, code or a small demo, written as HTML (or SVG inside it). */
interface StageControl {
    /** Shows [html]; returns a line for the model. Throws ToolFailure when it can't be shown. */
    fun show(title: String, html: String): String
    fun clear()
}

object StageHtml {
    const val MAX = 60_000

    /** The page may use inline style, script and data pictures only: nothing can be fetched or sent anywhere. */
    const val CSP = "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; img-src data:; font-src data:; media-src data:"

    private const val META = "<meta http-equiv=\"Content-Security-Policy\" content=\"$CSP\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
    private const val BASE = "<style>html,body{margin:0;min-height:100%;background:#0b0f14;color:#e6edf3;font:16px/1.45 system-ui,sans-serif}body{padding:12px;box-sizing:border-box}svg{max-width:100%;height:auto}pre,code{font-family:ui-monospace,monospace}pre{white-space:pre-wrap;overflow-x:auto}</style>"

    fun problem(html: String): String? = when {
        html.isBlank() -> "html is empty: write the page to show"
        html.length > MAX -> "that page is too long (${html.length} characters; the most is $MAX): make it smaller"
        else -> null
    }

    /** A fragment gets a dark page around it; a complete page keeps its own look. Either way the lock comes first. */
    fun wrap(html: String): String {
        val head = Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(html)
        if (head != null) return html.substring(0, head.range.last + 1) + META + html.substring(head.range.last + 1)
        val root = Regex("<html[^>]*>", RegexOption.IGNORE_CASE).find(html)
        if (root != null) return html.substring(0, root.range.last + 1) + "<head>" + META + "</head>" + html.substring(root.range.last + 1)
        return "<!doctype html><html><head>$META$BASE</head><body>$html</body></html>"
    }
}
