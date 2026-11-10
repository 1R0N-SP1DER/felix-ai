package com.felix.faze

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LocalLlamaTest {
    private val home = "/data/data/com.termux/files/home"
    private val lfm = "$home/models/LFM2.5-230M-Q4_K_M.gguf"
    private val qwen = "$home/models/qwen-0.8b-q4.gguf"

    @Test fun pathsAreOnlyPlainCharacters() {
        assertTrue(LocalLlama.isSafePath(lfm))
        for (bad in listOf("models/a.gguf", "/a b.gguf", "/a;rm -rf /.gguf", "/a/../b.gguf", "/a\$(x).gguf", "/a'b.gguf", "", "/a`b`.gguf"))
            assertFalse(bad, LocalLlama.isSafePath(bad))
    }

    @Test fun scanOutputBecomesAPickerListAndSkipsJunk() {
        val out = "$qwen\n$lfm\n$home/models/vision-mmproj-f16.gguf\n/sdcard/Download/odd name.gguf\n$lfm\nnot a path\n$home/readme.txt\n"
        val list = LocalLlama.parseModels(out)
        assertEquals(listOf(lfm, qwen), list.map { it.id })
        assertEquals("LFM2.5-230M-Q4_K_M", list[0].label)
        assertEquals("models", list[0].note)
        assertTrue(LocalLlama.parseModels("").isEmpty())
    }

    @Test fun theBinaryInUsrBinIsPreferred() {
        val out = "$home/llama.cpp/build/bin/llama-server\n${LocalLlama.TERMUX}/usr/bin/llama-server\n"
        assertEquals("${LocalLlama.TERMUX}/usr/bin/llama-server", LocalLlama.pickBinary(out))
        assertEquals("$home/llama.cpp/build/bin/llama-server", LocalLlama.pickBinary("$home/llama.cpp/build/bin/llama-server"))
        assertNull(LocalLlama.pickBinary("nothing"))
    }

    @Test fun theStartCommandIsSmallAndOnlyListensOnThisPhone() {
        val c = LocalLlama.serverCommand("/b/llama-server", lfm, 4, 2048)
        assertTrue(c, c.contains("-m $lfm") && c.contains("-c 2048") && c.contains("-t 4") && c.contains("-np 1"))
        assertTrue(c, c.contains("--host 127.0.0.1") && c.contains("--port 8088") && c.endsWith("&"))
        assertTrue(LocalLlama.serverCommand("/b/s", lfm, 99, 999_999).let { it.contains("-t 8") && it.contains("-c 8192") })
        assertTrue(LocalLlama.serverCommand("/b/s", lfm, 0, 1).let { it.contains("-t 1") && it.contains("-c 512") })
    }

    @Test fun servedModelIsReadFromTheModelList() {
        assertEquals("a.gguf", LocalLlama.parseServed("""{"data":[{"id":"a.gguf"}]}"""))
        assertNull(LocalLlama.parseServed("""{"data":[]}"""))
        assertNull(LocalLlama.parseServed("not json"))
    }

    @Test fun theOfflinePromptIsShortAndHasNoToolTraffic() {
        val m = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "x".repeat(5000)))
            .put(JSONObject().put("role", "assistant").put("content", "old"))
        for (i in 1..5) {
            m.put(JSONObject().put("role", "user").put("content", "q$i"))
            m.put(JSONObject().put("role", "assistant").put("content", "a$i"))
        }
        m.put(JSONObject().put("role", "assistant").put("content", "").put("tool_calls", JSONArray().put(JSONObject())))
        m.put(JSONObject().put("role", "tool").put("content", "result"))
        m.put(JSONObject().put("role", "user").put("content", "q6"))
        val s = LocalLlama.shrink(m)
        assertEquals("system", s.getJSONObject(0).getString("role"))
        assertEquals(LocalLlama.SYSTEM, s.getJSONObject(0).getString("content"))
        assertTrue(LocalLlama.SYSTEM.length < 250)
        assertEquals("user", s.getJSONObject(1).getString("role"))
        assertEquals("user", s.getJSONObject(s.length() - 1).getString("role"))
        assertEquals("q6", s.getJSONObject(s.length() - 1).getString("content"))
        assertTrue(s.length() <= LocalLlama.KEEP_MESSAGES + 1)
        for (i in 0 until s.length()) assertFalse(s.getJSONObject(i).getString("role") == "tool")
    }

    // ---- starting the server

    private class Rig(var served: String? = null, var bin: String? = "/b/llama-server", val comesUp: Boolean = true) {
        val ran = ArrayList<String>()
        var polls = 0
        val sleeps = ArrayList<Long>()
        val control = LocalServerControl(
            run = { cmd, _ -> ran.add(cmd); if (cmd.startsWith("tail")) "error: out of memory\nkilled" else ""
            },
            alive = { polls++; if (ran.any { it.contains("nohup") }) (if (comesUp && polls > 2) served else null) else served },
            binary = { bin }, threads = { 4 }, ctx = { 2048 }, sleep = { sleeps.add(it) }, startTimeoutMs = 3000,
        )
    }

    @Test fun aServerAlreadyRunningTheModelIsLeftAlone() {
        val r = Rig(served = "LFM2.5-230M-Q4_K_M.gguf")
        assertNull(r.control.ensure(lfm) { })
        assertTrue(r.ran.isEmpty())
    }

    @Test fun aDownServerIsStartedAndWaitedFor() {
        val waits = ArrayList<String>()
        // not running yet: alive is null until the start command has been issued and a few polls have passed
        val rig = Rig(served = null)
        val c = LocalServerControl({ cmd, _ -> rig.ran.add(cmd); "" }, { rig.polls++; if (rig.ran.isNotEmpty() && rig.polls > 3) "LFM2.5-230M-Q4_K_M.gguf" else null }, { "/b/llama-server" }, { 4 }, { 2048 }, { rig.sleeps.add(it) }, 5000)
        assertNull(c.ensure(lfm) { waits.add(it) })
        assertEquals(1, rig.ran.size)
        assertTrue(rig.ran[0].contains("-m $lfm"))
        assertEquals(listOf("Loading LFM2.5-230M-Q4_K_M…"), waits)
        assertTrue(rig.sleeps.isNotEmpty())
    }

    @Test fun aDifferentModelStopsTheOldServerFirst() {
        val rig = Rig()
        var up: String? = "qwen-0.8b-q4.gguf"
        val ran = ArrayList<String>()
        val c = LocalServerControl({ cmd, _ -> ran.add(cmd); if (cmd.startsWith("pkill")) up = null; if (cmd.contains("nohup")) up = "LFM2.5-230M-Q4_K_M.gguf"; "" }, { up }, { "/b/llama-server" }, { 4 }, { 2048 }, { rig.sleeps.add(it) }, 5000)
        assertNull(c.ensure(lfm) { })
        assertTrue(ran[0].startsWith("pkill"))
        assertTrue(ran[1].contains("nohup"))
    }

    @Test fun problemsComeBackInWords() {
        assertEquals("that model file is not allowed", Rig().control.ensure("/a;b.gguf") { })
        assertEquals("that model file is not allowed", Rig().control.ensure("/data/x.bin") { })
        assertTrue(Rig(bin = null).control.ensure(lfm) { }!!.contains("llama-server was not found"))
        val dead = Rig(comesUp = false).control.ensure(lfm) { }!!
        assertTrue(dead, dead.contains("did not start in time") && dead.contains("out of memory"))
    }

    @Test fun stoppingTakesTheMemoryBack() {
        val r = Rig(served = "x")
        r.control.stop()
        assertEquals(listOf("pkill -f 'port 8088'"), r.ran)
    }

    // ---- the chat

    private class FakeServer(val error: String? = null) : LocalServer {
        val ensured = ArrayList<String>()
        override fun ensure(model: String, onWait: (String) -> Unit): String? { ensured.add(model); return error }
        override fun stop() {}
    }

    @Test fun offlineChatStartsTheModelThenSendsAShortPromptWithoutTools() {
        val inner = FakeChat(ChatResult("hello", emptyList(), false))
        val server = FakeServer()
        val chat = OfflineChat(inner, server, { lfm }, { })
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", "huge")).put(JSONObject().put("role", "user").put("content", "hi"))
        assertEquals("hello", chat.chat("ignored", msgs, JSONArray().put("tool"), { }, { false }).content)
        assertEquals(listOf(lfm), server.ensured)
        val call = inner.calls.single()
        assertEquals("local", call.model)
        assertNull(call.tools)
        assertEquals(LocalLlama.SYSTEM, call.messages.getJSONObject(0).getString("content"))
    }

    @Test fun offlineChatFailsInWordsAndNeverCallsTheModelThen() {
        val inner = FakeChat()
        for ((chat, part) in listOf(
            OfflineChat(inner, FakeServer(), { "" }, { }) to "pick an offline model",
            OfflineChat(inner, FakeServer("the model did not start in time"), { lfm }, { }) to "did not start in time",
        )) {
            try { chat.chat("x", JSONArray(), null, { }, { false }); fail(part) } catch (e: RemoteError) { assertTrue(e.message, e.message!!.contains(part)) }
        }
        assertTrue(inner.calls.isEmpty())
    }
}
