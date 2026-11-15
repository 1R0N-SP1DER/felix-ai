package com.felix.faze

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class PcFilesTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun idsRoundTrip() {
        assertEquals(Triple("pc1", "Docs", "a/b.txt"), PcFiles.parse(PcFiles.id("pc1", "Docs", "a/b.txt")))
        assertEquals(Triple("pc1", "", ""), PcFiles.parse(PcFiles.id("pc1")))
        assertNull(PcFiles.parse("nonsense"))
        assertNull(PcFiles.parse("|x|y"))
    }

    @Test fun aComputerListsItsSharedFoldersThenTheirFiles() {
        val asked = ArrayList<JSONObject>()
        val f = PcFiles { _, m ->
            asked.add(m)
            when (m.getString("t")) {
                "fs_roots" -> JSONObject("{\"t\":\"fs_roots_ok\",\"roots\":[{\"id\":\"Docs\",\"name\":\"Docs\"}]}")
                else -> JSONObject().put("t", "fs_list_ok").put("entries", JSONArray().put(JSONObject().put("name", "sub").put("dir", true).put("size", 0).put("mtime", 1)).put(JSONObject().put("name", "a.txt").put("dir", false).put("size", 5).put("mtime", 2)))
            }
        }
        assertEquals(listOf(PcEntry("pc1|Docs|", "Docs", true, 0, 0)), f.children("pc1||"))
        val kids = f.children("pc1|Docs|x")
        assertEquals(listOf("pc1|Docs|x/sub", "pc1|Docs|x/a.txt"), kids.map { it.docId })
        assertEquals(listOf(true, false), kids.map { it.dir })
        assertEquals("x", asked.last().getString("path"))
    }

    @Test fun anErrorFromTheComputerBecomesAnIoProblem() {
        var failed = false
        try { PcFiles { _, _ -> JSONObject().put("t", "error").put("message", "that path leaves the shared folder") }.children("pc1|Docs|..") } catch (e: IOException) { failed = e.message!!.contains("leaves") }
        assertTrue(failed)
    }

    @Test fun downloadsInChunksUntilTheEnd() {
        val data = ByteArray(1500) { it.toByte() }
        val chunk = 600
        val f = PcFiles { _, m ->
            val off = m.getInt("offset")
            val part = data.copyOfRange(off, minOf(data.size, off + chunk))
            JSONObject().put("t", "fs_read_ok").put("data", java.util.Base64.getEncoder().encodeToString(part)).put("size", data.size).put("eof", off + part.size >= data.size)
        }
        val out = tmp.newFile()
        f.download("pc1|Docs|a.bin", out)
        assertTrue(data.contentEquals(out.readBytes()))
    }

    @Test fun aHugeFileIsRefused() {
        var failed = false
        try { PcFiles { _, _ -> JSONObject().put("t", "fs_read_ok").put("data", "").put("size", PcFiles.MAX_DOWNLOAD + 1).put("eof", false) }.download("pc1|Docs|big", tmp.newFile()) } catch (e: IOException) { failed = e.message!!.contains("too big") }
        assertTrue(failed)
    }
}
