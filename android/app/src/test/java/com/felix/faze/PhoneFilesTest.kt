package com.felix.faze

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PhoneFilesTest {
    private lateinit var tmp: File
    private lateinit var docs: File
    private lateinit var secret: File
    private var open = true
    private var all = false

    @Before fun setUp() {
        tmp = Files.createTempDirectory("pf").toFile()
        docs = File(tmp, "Docs").also { it.mkdirs() }
        File(docs, "sub").mkdirs()
        File(docs, "a.txt").writeText("hello")
        File(docs, "sub/b.bin").writeBytes(ByteArray(PhoneFiles.CHUNK + 100) { it.toByte() })
        secret = File(tmp, "secret.txt").also { it.writeText("nope") }
        Files.createSymbolicLink(File(docs, "leak.txt").toPath(), secret.toPath())
    }

    @After fun tearDown() { tmp.deleteRecursively() }

    private fun files() = PhoneFiles({ if (!open) emptyList() else if (all) listOf("All files" to tmp, "Docs" to docs) else listOf("Docs" to docs) })
    private fun msg(t: String, vararg kv: Pair<String, Any>) = JSONObject().put("t", t).also { j -> kv.forEach { j.put(it.first, it.second) } }

    @Test fun ignoresOtherMessages() = assertNull(files().handle(msg("sync_list")))

    @Test fun refusesWhenSharingIsOff() {
        open = false
        assertEquals("error", files().handle(msg("fs_roots"))!!.getString("t"))
    }

    @Test fun listsRoots() {
        val r = files().handle(msg("fs_roots"))!!.getJSONArray("roots")
        assertEquals(1, r.length())
        assertEquals("Docs", r.getJSONObject(0).getString("id"))
    }

    @Test fun listsFoldersFirstAndHidesLinksThatLeave() {
        val e = files().handle(msg("fs_list", "root" to "Docs", "path" to ""))!!.getJSONArray("entries")
        assertEquals(listOf("sub", "a.txt"), (0 until e.length()).map { e.getJSONObject(it).getString("name") })
    }

    @Test fun readsInChunksUntilEof() {
        val f = files()
        val first = f.handle(msg("fs_read", "root" to "Docs", "path" to "sub/b.bin", "offset" to 0, "len" to PhoneFiles.CHUNK))!!
        assertFalse(first.getBoolean("eof"))
        val second = f.handle(msg("fs_read", "root" to "Docs", "path" to "sub/b.bin", "offset" to PhoneFiles.CHUNK, "len" to PhoneFiles.CHUNK))!!
        assertTrue(second.getBoolean("eof"))
        assertEquals(PhoneFiles.CHUNK.toLong() + 100, second.getLong("size"))
        assertEquals(100, java.util.Base64.getDecoder().decode(second.getString("data")).size)
    }

    @Test fun refusesEscapes() {
        val f = files()
        assertEquals("error", f.handle(msg("fs_read", "root" to "Docs", "path" to "../secret.txt"))!!.getString("t"))
        assertEquals("error", f.handle(msg("fs_read", "root" to "Docs", "path" to "leak.txt"))!!.getString("t"))
        assertEquals("error", f.handle(msg("fs_list", "root" to "Nope", "path" to ""))!!.getString("t"))
    }

    @Test fun allFilesRootReachesEverything() {
        all = true
        val r = files().handle(msg("fs_read", "root" to "All files", "path" to "secret.txt"))!!
        assertEquals("nope", String(java.util.Base64.getDecoder().decode(r.getString("data"))))
    }
}
