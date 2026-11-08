package com.felix.faze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ScreenReaderTest {
    private fun node(attrs: String, kids: String = "") = "<node $attrs>$kids</node>"

    private fun dump(inner: String) = "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation=\"0\">$inner</hierarchy>"

    private val chat = dump(
        node(
            "class=\"android.widget.FrameLayout\" package=\"com.whatsapp\" text=\"\" content-desc=\"\" clickable=\"false\" bounds=\"[0,0][1080,2400]\"",
            node("class=\"android.widget.TextView\" package=\"com.whatsapp\" text=\"Mum\" content-desc=\"\" clickable=\"false\" bounds=\"[100,100][500,200]\"") +
                node("class=\"android.widget.ImageButton\" package=\"com.whatsapp\" text=\"\" content-desc=\"Voice call\" clickable=\"true\" bounds=\"[900,100][1000,200]\"") +
                node("class=\"android.widget.EditText\" package=\"com.whatsapp\" text=\"\" resource-id=\"com.whatsapp:id/entry\" content-desc=\"\" clickable=\"true\" bounds=\"[100,2200][800,2300]\"") +
                node("class=\"android.widget.CheckBox\" package=\"com.whatsapp\" text=\"Mute\" checkable=\"true\" checked=\"true\" clickable=\"true\" bounds=\"[0,300][200,400]\"") +
                node("class=\"android.view.View\" package=\"com.whatsapp\" text=\"\" content-desc=\"\" clickable=\"false\" bounds=\"[0,500][1080,600]\""),
        ),
    )

    @Test fun listsTheAppAndWhatIsOnScreenWithTapPoints() {
        val t = ScreenReader.describe(chat)
        val lines = t.lines()
        assertEquals("Screen 1080x2400, app in front: com.whatsapp", lines[0])
        assertTrue(t, lines.contains("1. TextView \"Mum\" at 300,150"))
        assertTrue(t, lines.contains("2. ImageButton \"Voice call\" (tap) at 950,150"))
        assertTrue(t, lines.contains("3. EditText #entry (tap, text field) at 450,2250"))
        assertTrue(t, lines.contains("4. CheckBox \"Mute\" (tap, on) at 100,350"))
        assertFalse(t, t.contains("Faze itself"))
    }

    @Test fun emptyContainersAreLeftOut() {
        assertEquals(4, ScreenReader.describe(chat).lines().count { Regex("^\\d+\\. ").containsMatchIn(it) })
    }

    @Test fun saysSoWhenFazeItselfIsInFront() {
        val own = dump(node("class=\"android.widget.TextView\" package=\"com.felix.faze\" text=\"Hello\" bounds=\"[0,0][100,100]\""))
        val t = ScreenReader.describe(own, "com.felix.faze")
        assertTrue(t, t.contains("This is Faze itself"))
        assertTrue(t, t.contains("open_app"))
    }

    @Test fun anEmptyScreenIsExplained() {
        val t = ScreenReader.describe(dump(node("class=\"android.widget.FrameLayout\" package=\"com.bank\" bounds=\"[0,0][1080,2400]\"")))
        assertTrue(t, t.contains("Nothing readable"))
        assertTrue(t, t.contains("com.bank"))
    }

    @Test fun statusBarIsNotTheAppInFront() {
        val x = dump(
            node("class=\"android.widget.TextView\" package=\"com.android.systemui\" text=\"12:00\" bounds=\"[0,0][100,50]\"") +
                node("class=\"android.widget.TextView\" package=\"com.spotify\" text=\"Liked Songs\" bounds=\"[0,100][500,200]\"") +
                node("class=\"android.widget.TextView\" package=\"com.spotify\" text=\"Play\" bounds=\"[0,300][500,400]\""),
        )
        assertTrue(ScreenReader.describe(x).contains("app in front: com.spotify"))
    }

    @Test fun longScreensAreCutAndCounted() {
        val many = dump((1..300).joinToString("") { node("class=\"android.widget.TextView\" package=\"a.b\" text=\"Item $it\" bounds=\"[0,${it * 10}][100,${it * 10 + 9}]\"") })
        val t = ScreenReader.describe(many)
        assertTrue(t.length < 8000)
        assertTrue(t, t.contains("more not shown"))
    }

    @Test fun badInputFailsInWords() {
        assertFalse(ScreenReader.isDump("ERROR: could not get idle state"))
        assertTrue(ScreenReader.isDump(chat))
        for (bad in listOf("", "<?xml version='1.0'?><hierarchy rotation=\"0\"><node"))
            try { ScreenReader.describe(bad); fail("should have failed: $bad") } catch (e: ToolFailure) { assertTrue(e.message!!.isNotEmpty()) }
    }
}
