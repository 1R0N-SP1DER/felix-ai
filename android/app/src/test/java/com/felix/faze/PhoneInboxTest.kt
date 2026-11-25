package com.felix.faze

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class PhoneInboxTest {
    private class Store : InboxStore {
        val files = mutableMapOf<String, ByteArray>()
        val aborted = mutableListOf<String>()
        override fun open(name: String): InboxTransfer {
            val buf = java.io.ByteArrayOutputStream()
            return object : InboxTransfer {
                override fun write(bytes: ByteArray) = buf.write(bytes)
                override fun finish(): String { files[name] = buf.toByteArray(); return "Downloads/Faze/$name" }
                override fun abort() { aborted.add(name) }
            }
        }
    }

    private class Note : InboxNotify {
        val seen = mutableListOf<String>()
        override fun file(name: String, where: String) { seen.add("file:$name:$where") }
        override fun text(text: String) { seen.add("text:$text") }
        override fun link(url: String) { seen.add("link:$url") }
    }

    private fun piece(name: String, size: Int, offset: Int, bytes: ByteArray, eof: Boolean) = JSONObject().put("t", "phone_put").put("kind", "file").put("name", name).put("size", size)
        .put("offset", offset).put("eof", eof).put("data", Base64.getEncoder().encodeToString(bytes))

    @Test fun aFileInPiecesIsSavedAndAnnounced() {
        val s = Store(); val n = Note()
        val inbox = PhoneInbox({ true }, s, n)
        assertEquals("phone_put_ok", inbox.handle(piece("a.txt", 5, 0, byteArrayOf(1, 2, 3), false))!!.getString("t"))
        assertEquals("phone_put_ok", inbox.handle(piece("a.txt", 5, 3, byteArrayOf(4, 5), true))!!.getString("t"))
        assertEquals(listOf<Byte>(1, 2, 3, 4, 5), s.files["a.txt"]!!.toList())
        assertEquals(listOf("file:a.txt:Downloads/Faze/a.txt"), n.seen)
    }

    @Test fun anEmptyFileWorks() {
        val s = Store()
        val inbox = PhoneInbox({ true }, s, Note())
        assertEquals("phone_put_ok", inbox.handle(piece("e.txt", 0, 0, ByteArray(0), true))!!.getString("t"))
        assertTrue(s.files.containsKey("e.txt"))
    }

    @Test fun piecesOutOfOrderAreRefusedAndCleanedUp() {
        val s = Store()
        val inbox = PhoneInbox({ true }, s, Note())
        inbox.handle(piece("a.bin", 6, 0, byteArrayOf(1, 2), false))
        val r = inbox.handle(piece("a.bin", 6, 4, byteArrayOf(5, 6), true))!!
        assertEquals("error", r.getString("t"))
        assertEquals(listOf("a.bin"), s.aborted)
        assertTrue(s.files.isEmpty())
    }

    @Test fun tooBigOrTooLongOrBadOnesAreRefused() {
        val inbox = PhoneInbox({ true }, Store(), Note())
        assertEquals("error", inbox.handle(piece("a", Int.MAX_VALUE, 0, byteArrayOf(1), false).put("size", PhoneInbox.MAX_SIZE + 1))!!.getString("t"))
        assertEquals("error", inbox.handle(piece("a", 1, 0, byteArrayOf(1, 2), true))!!.getString("t"))
        assertEquals("error", inbox.handle(JSONObject().put("t", "phone_put").put("kind", "link").put("text", "javascript:alert(1)"))!!.getString("t"))
        assertEquals("error", inbox.handle(JSONObject().put("t", "phone_put").put("kind", "text").put("text", ""))!!.getString("t"))
    }

    @Test fun textAndLinksGoToTheNotification() {
        val n = Note()
        val inbox = PhoneInbox({ true }, Store(), n)
        inbox.handle(JSONObject().put("t", "phone_put").put("kind", "text").put("text", "hello"))
        inbox.handle(JSONObject().put("t", "phone_put").put("kind", "link").put("text", "https://example.com"))
        assertEquals(listOf("text:hello", "link:https://example.com"), n.seen)
    }

    @Test fun offMeansRefusedAndOtherMessagesAreNotItsBusiness() {
        val inbox = PhoneInbox({ false }, Store(), Note())
        assertEquals("error", inbox.handle(JSONObject().put("t", "phone_put").put("kind", "text").put("text", "x"))!!.getString("t"))
        assertNull(inbox.handle(JSONObject().put("t", "hello")))
    }

    @Test fun namesAreMadeSafe() {
        assertEquals("passwd", PhoneInbox.safeName("../../etc/passwd"))
        assertEquals("evil.sh", PhoneInbox.safeName("..\\evil.sh"))
        assertEquals("file", PhoneInbox.safeName("   "))
        assertEquals("hidden", PhoneInbox.safeName(".hidden"))
        assertEquals(120, PhoneInbox.safeName("a".repeat(500)).length)
    }
}
