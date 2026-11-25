package com.felix.faze

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneModelsTest {
    private val listing = """
        2600000000|/sdcard/Download/Qwen3.5-4B-Q4_K_M.gguf
        800000000|/data/data/com.termux/files/home/models/LFM2.5-1.2B-Q8_0.gguf
        300000000|/sdcard/Download/mmproj-vision.gguf
        5|/sdcard/Download/we ird;name.gguf
        junk line
    """.trimIndent()

    private fun hub(on: Boolean = true, run: (String, Long) -> String = { _, _ -> "" }) = PhoneModels({ on }, run)

    @Test fun theListKeepsRealModelsAndDropsProjectorsAndUnsafePaths() {
        val rows = PhoneModels.parseList(listing)
        assertEquals(listOf("/data/data/com.termux/files/home/models/LFM2.5-1.2B-Q8_0.gguf", "/sdcard/Download/Qwen3.5-4B-Q4_K_M.gguf"), rows.map { it.second })
        assertEquals(800000000L, rows[0].first)
    }

    @Test fun listingAnswersWithNamesAndSizes() {
        val r = hub(run = { _, _ -> listing }).handle(JSONObject().put("t", "models_list"))!!
        assertEquals("models_list_ok", r.getString("t"))
        assertEquals(2, r.getJSONArray("models").length())
        assertEquals("LFM2.5-1.2B-Q8_0.gguf", r.getJSONArray("models").getJSONObject(0).getString("name"))
    }

    @Test fun offMeansAnErrorAndNoRootCommand() {
        var ran = false
        val r = hub(on = false, run = { _, _ -> ran = true; "" }).handle(JSONObject().put("t", "models_list"))!!
        assertEquals("error", r.getString("t"))
        assertFalse(ran)
    }

    @Test fun otherMessagesAreNotItsBusiness() {
        assertEquals(null, hub().handle(JSONObject().put("t", "clip")))
    }

    @Test fun stagingCopiesOnlyAllowedModelFilesAndReportsTheSize() {
        val cmds = mutableListOf<String>()
        val h = hub(run = { c, _ -> cmds.add(c); if (c.startsWith("stat")) "2600000000\n" else "" })
        val ok = h.handle(JSONObject().put("t", "models_stage").put("path", "/sdcard/Download/Qwen3.5-4B-Q4_K_M.gguf"))!!
        assertEquals("models_stage_ok", ok.getString("t"))
        assertEquals("Models", ok.getString("root"))
        assertEquals("Qwen3.5-4B-Q4_K_M.gguf", ok.getString("path"))
        assertEquals(2600000000L, ok.getLong("size"))
        assertTrue(cmds.first().contains("cp -f '/sdcard/Download/Qwen3.5-4B-Q4_K_M.gguf' '${PhoneModels.STAGE}/Qwen3.5-4B-Q4_K_M.gguf'"))
        for (bad in listOf("/etc/passwd", "/sdcard/Download/../../etc/x.gguf", "/sdcard/Pictures/x.gguf", "/sdcard/Download/a b.gguf", "/sdcard/Download/x.gguf; rm -rf /")) {
            assertEquals("error", h.handle(JSONObject().put("t", "models_stage").put("path", bad))!!.getString("t"))
        }
    }

    @Test fun aFailedCopyIsAnErrorNotACrash() {
        val r = hub(run = { _, _ -> "" }).handle(JSONObject().put("t", "models_stage").put("path", "/sdcard/Download/a.gguf"))!!
        assertEquals("error", r.getString("t"))
    }

    @Test fun unstagingOnlyRemovesPlainStagedNames() {
        val cmds = mutableListOf<String>()
        val h = hub(run = { c, _ -> cmds.add(c); "" })
        assertEquals("models_unstage_ok", h.handle(JSONObject().put("t", "models_unstage").put("name", "a.gguf"))!!.getString("t"))
        assertEquals("rm -f '${PhoneModels.STAGE}/a.gguf'", cmds.single())
        assertEquals("error", h.handle(JSONObject().put("t", "models_unstage").put("name", "../x.gguf"))!!.getString("t"))
        assertEquals(1, cmds.size)
    }

    @Test fun withoutRootItListsAndCopiesFromSharedFolders() {
        val base = java.nio.file.Files.createTempDirectory("faze").toFile()
        val dl = java.io.File(base, "Download").also { it.mkdirs() }
        java.io.File(dl, "Qwen-Q4.gguf").writeBytes(ByteArray(2000) { 1 })
        java.io.File(dl, "mmproj-x.gguf").writeBytes(ByteArray(10))
        java.io.File(dl, "notes.txt").writeText("x")
        val stage = java.io.File(base, "stage")
        val ran = ArrayList<String>()
        val h = PhoneModels({ true }, { c, _ -> ran.add(c); "" }, {}, { false }, listOf(dl.path), stage.path)
        val list = h.handle(JSONObject().put("t", "models_list"))!!.getJSONArray("models")
        assertEquals(1, list.length())
        assertEquals("Qwen-Q4.gguf", list.getJSONObject(0).getString("name"))
        val st = h.handle(JSONObject().put("t", "models_stage").put("path", java.io.File(dl, "Qwen-Q4.gguf").path))!!
        assertEquals("models_stage_ok", st.getString("t"))
        assertEquals(2000L, st.getLong("size"))
        assertTrue(java.io.File(stage, "Qwen-Q4.gguf").exists())
        h.handle(JSONObject().put("t", "models_unstage").put("name", "Qwen-Q4.gguf"))
        assertFalse(java.io.File(stage, "Qwen-Q4.gguf").exists())
        assertTrue("no root command should run", ran.isEmpty())
    }

    @Test fun withoutRootTermuxModelsPointToTheCopyCommand() {
        val h = PhoneModels({ true }, { _, _ -> "" }, {}, { false }, listOf("/sdcard/Download"), "/tmp/x")
        val r = h.handle(JSONObject().put("t", "models_stage").put("path", "/data/data/com.termux/files/home/m.gguf"))!!
        assertEquals("error", r.getString("t"))
        assertTrue(r.getString("message").contains("cp "))
    }
}
