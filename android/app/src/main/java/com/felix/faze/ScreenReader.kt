package com.felix.faze

import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

/**
 * Turns what the phone says is on the screen (the XML that `uiautomator dump` writes) into a short list the model can read:
 * which app is in front, then each thing worth knowing about, in reading order, with the point to tap for it.
 * Plain logic, no phone needed, so it is tested in CI.
 */
object ScreenReader {
    const val MAX_ITEMS = 120
    const val MAX_CHARS = 6000
    private val boundsRe = Regex("\\[(-?\\d+),(-?\\d+)]\\[(-?\\d+),(-?\\d+)]")

    private class Item(val kind: String, val label: String, val id: String, val flags: List<String>, val cx: Int, val cy: Int)

    /** The XML does not look like a dump (the phone answered with an error, or nothing). */
    fun isDump(xml: String): Boolean = xml.contains("<hierarchy")

    /** The dump as text for the model. [selfPackage] is this app: if it is the one in front, the answer says so. */
    fun describe(xml: String, selfPackage: String = "com.felix.faze"): String {
        val start = xml.indexOf("<?xml").takeIf { it >= 0 } ?: xml.indexOf("<hierarchy").takeIf { it >= 0 } ?: throw ToolFailure("the phone did not return a screen dump")
        val end = xml.lastIndexOf("</hierarchy>").let { if (it < 0) throw ToolFailure("the screen dump was cut off") else it + "</hierarchy>".length }
        val doc = try {
            val f = DocumentBuilderFactory.newInstance()
            f.isNamespaceAware = false
            try { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } catch (_: Exception) {}
            f.newDocumentBuilder().parse(InputSource(StringReader(xml.substring(start, end))))
        } catch (e: Exception) {
            throw ToolFailure("could not read the screen dump")
        }
        val items = ArrayList<Item>()
        val packages = LinkedHashMap<String, Int>()
        var width = 0
        var height = 0
        fun walk(e: Element) {
            val pkg = e.getAttribute("package")
            val b = boundsRe.matchEntire(e.getAttribute("bounds"))
            if (e.tagName == "node" && b != null) {
                val (l, t, r, bt) = b.destructured.toList().map { it.toInt() }.let { listOf(it[0], it[1], it[2], it[3]) }
                width = maxOf(width, r)
                height = maxOf(height, bt)
                if (pkg.isNotEmpty() && !pkg.startsWith("com.android.systemui")) packages[pkg] = (packages[pkg] ?: 0) + 1
                val text = e.getAttribute("text").trim()
                val desc = e.getAttribute("content-desc").trim()
                val clickable = e.getAttribute("clickable") == "true"
                val editable = e.getAttribute("class").endsWith("EditText")
                val scrollable = e.getAttribute("scrollable") == "true"
                val label = text.ifEmpty { desc }
                val onScreen = r > l && bt > t && e.getAttribute("visible-to-user") != "false"
                if (onScreen && (label.isNotEmpty() || editable || (clickable && e.getAttribute("resource-id").isNotEmpty()) || scrollable)) {
                    val flags = ArrayList<String>()
                    if (clickable) flags.add("tap")
                    if (editable) flags.add("text field")
                    if (scrollable) flags.add("scrolls")
                    if (e.getAttribute("checkable") == "true") flags.add(if (e.getAttribute("checked") == "true") "on" else "off")
                    if (e.getAttribute("enabled") == "false") flags.add("disabled")
                    if (e.getAttribute("focused") == "true") flags.add("focused")
                    items.add(Item(e.getAttribute("class").substringAfterLast('.'), label.replace(Regex("\\s+"), " ").take(120), e.getAttribute("resource-id").substringAfter(":id/"), flags, (l + r) / 2, (t + bt) / 2))
                }
            }
            val kids = e.childNodes
            for (i in 0 until kids.length) (kids.item(i) as? Element)?.let { walk(it) }
        }
        walk(doc.documentElement)
        val front = packages.maxByOrNull { it.value }?.key ?: ""
        val out = StringBuilder()
        out.append("Screen ").append(width).append("x").append(height)
        if (front.isNotEmpty()) out.append(", app in front: ").append(front)
        out.append('\n')
        if (front == selfPackage) out.append("This is Faze itself. If you meant another app, open it first (open_app) and read again.\n")
        if (items.isEmpty()) {
            out.append("Nothing readable is on the screen (it may be blocked, locked, or a picture only).")
            return out.toString()
        }
        var shown = 0
        for (it in items) {
            if (shown >= MAX_ITEMS || out.length > MAX_CHARS) break
            out.append(shown + 1).append(". ").append(it.kind)
            if (it.label.isNotEmpty()) out.append(" \"").append(it.label).append('"')
            if (it.id.isNotEmpty() && it.label.isEmpty()) out.append(" #").append(it.id)
            if (it.flags.isNotEmpty()) out.append(" (").append(it.flags.joinToString(", ")).append(')')
            out.append(" at ").append(it.cx).append(',').append(it.cy).append('\n')
            shown++
        }
        if (shown < items.size) out.append("... ").append(items.size - shown).append(" more not shown")
        return out.toString().trimEnd()
    }
}
