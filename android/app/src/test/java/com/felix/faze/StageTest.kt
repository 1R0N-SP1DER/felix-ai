package com.felix.faze

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StageTest {
    private class FakeStage : StageControl {
        var title = ""; var html = ""; var cleared = 0
        override fun show(title: String, html: String): String {
            StageHtml.problem(html)?.let { throw ToolFailure(it) }
            this.title = title; this.html = html
            return "Showing it."
        }
        override fun clear() { cleared++ }
    }

    @Test fun aFragmentGetsADarkPageAndTheLock() {
        val w = StageHtml.wrap("<svg viewBox='0 0 10 10'><circle r='4'/></svg>")
        assertTrue(w.startsWith("<!doctype html>"))
        assertTrue(w.contains("Content-Security-Policy"))
        assertTrue(w.contains("<circle r='4'/>"))
        assertTrue(w.indexOf("Content-Security-Policy") < w.indexOf("<svg"))
    }

    @Test fun aCompletePageKeepsItsOwnLookButTheLockComesFirst() {
        val w = StageHtml.wrap("<!doctype html><html><head><title>x</title><script>1</script></head><body>hi</body></html>")
        assertTrue(w.indexOf("Content-Security-Policy") < w.indexOf("<script>"))
        assertTrue(w.contains("<title>x</title>"))
        val noHead = StageHtml.wrap("<html><body><script>1</script></body></html>")
        assertTrue(noHead.indexOf("Content-Security-Policy") < noHead.indexOf("<script>"))
    }

    @Test fun theLockAllowsNothingToBeFetched() {
        val csp = StageHtml.CSP
        assertTrue(csp.startsWith("default-src 'none'"))
        assertFalse(csp.contains("http"))
        assertFalse(csp.contains("connect-src"))
        assertTrue(csp.contains("img-src data:"))
    }

    @Test fun emptyOrHugePagesAreRefusedWithAReason() {
        assertTrue(StageHtml.problem("  ")!!.contains("empty"))
        assertTrue(StageHtml.problem("x".repeat(StageHtml.MAX + 1))!!.contains("too long"))
        assertEquals(null, StageHtml.problem("<p>ok</p>"))
    }

    private fun tools(stage: StageControl?) = Tools(FakeDevice(), FakeTransport(), FakeSettings(), stage = stage)

    @Test fun theToolsShowAndClear() {
        val s = FakeStage()
        val t = tools(s)
        val r = t.execute("show_visual", JSONObject().put("title", "Flow").put("html", "<b>hi</b>"))
        assertTrue(r.text, r.ok)
        assertEquals("Flow", s.title)
        assertEquals("<b>hi</b>", s.html)
        assertTrue(t.execute("clear_visual", JSONObject()).ok)
        assertEquals(1, s.cleared)
    }

    @Test fun aBadPageGivesTheModelAReasonToFix() {
        val r = tools(FakeStage()).execute("show_visual", JSONObject().put("title", "x").put("html", ""))
        assertFalse(r.ok)
        assertTrue(r.text.contains("empty"))
    }

    @Test fun theToolsOnlyExistWithAStage() {
        val names = { t: Tools -> (0 until t.schema().length()).map { t.schema().getJSONObject(it).getJSONObject("function").getString("name") } }
        assertTrue(names(tools(FakeStage())).containsAll(listOf("show_visual", "clear_visual")))
        assertFalse(names(tools(null)).contains("show_visual"))
    }

    @Test fun drawingNeedsNoApprovalBecauseItCanOnlyDraw() {
        assertEquals(Tier.SAFE, PermissionPolicy.tier("show_visual"))
        assertEquals(Tier.SAFE, PermissionPolicy.tier("clear_visual"))
    }

    @Test fun theLiveModelIsToldAboutTheStage() {
        assertTrue(LiveSession.instruction("Felix", "", listOf("show_visual")).contains("show_visual"))
    }
}
