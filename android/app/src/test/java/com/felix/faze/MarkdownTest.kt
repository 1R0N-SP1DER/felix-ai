package com.felix.faze

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownTest {
    private fun spans(s: String) = Markdown.inline(s)

    @Test fun inlineStyles() {
        assertEquals(listOf(Span("a "), Span("b", Span.STRONG), Span(" c ")), spans("a **b** c "))
        assertEquals(listOf(Span("run "), Span("ls -la", Span.CODE), Span(" now")), spans("run `ls -la` now"))
        assertEquals(listOf(Span("this is "), Span("it", Span.EM), Span(".")), spans("this is *it*."))
        assertEquals(listOf(Span("("), Span("really", Span.EM), Span(") yes")), spans("(*really*) yes"))
    }

    @Test fun unmatchedMarkersStayAsText() {
        assertEquals(listOf(Span("2 * 3 = 6")), spans("2 * 3 = 6"))
        assertEquals(listOf(Span("a **b")), spans("a **b"))
        assertEquals(listOf(Span("`open")), spans("`open"))
        assertEquals(listOf(Span("snake*case*word")), spans("snake*case*word")) // emphasis needs a space or ( before it
    }

    @Test fun blocks() {
        val b = Markdown.parse("# Title\nhello\n\n- one\n- two\n1. first\n2) second\n```kotlin\nval x = 1\n```\nend")
        assertEquals(
            listOf(
                Block.Heading(listOf(Span("Title"))), Block.Para(listOf(Span("hello"))), Block.Gap,
                Block.Item(false, 1, listOf(Span("one"))), Block.Item(false, 2, listOf(Span("two"))),
                Block.Item(true, 1, listOf(Span("first"))), Block.Item(true, 2, listOf(Span("second"))),
                Block.Code("kotlin", "val x = 1"), Block.Para(listOf(Span("end"))),
            ),
            b,
        )
    }

    @Test fun numberingRestartsAfterAnythingElse() {
        val b = Markdown.parse("1. a\ntext\n1. b")
        assertEquals(Block.Item(true, 1, listOf(Span("b"))), b.last())
    }

    @Test fun anOpenCodeBlockWhileStreamingStillShows() {
        assertEquals(listOf(Block.Code("", "let a = 1")), Markdown.parse("```\nlet a = 1"))
    }

    @Test fun headingsNeedASpaceAndStopAtFourHashes() {
        assertEquals(Block.Para(listOf(Span("#nope"))), Markdown.parse("#nope")[0])
        assertEquals(Block.Para(listOf(Span("##### five"))), Markdown.parse("##### five")[0])
        assertEquals(Block.Heading(listOf(Span("four"))), Markdown.parse("#### four")[0])
    }

    @Test fun liveBarWordsAndStates() {
        assertEquals("\u2726 FAZE is ready", LiveText.label("ready"))
        assertEquals("\u25CC FAZE is thinking", LiveText.label("thinking"))
        assertEquals("\u25CC writing", LiveText.label("writing"))
        assertEquals("ready", LiveText.stateFor(false, true, true))
        assertEquals("thinking", LiveText.stateFor(true, false, false))
        assertEquals("tool", LiveText.stateFor(true, false, true))
        assertEquals("writing", LiveText.stateFor(true, true, true))
    }
}
