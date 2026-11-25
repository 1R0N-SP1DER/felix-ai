package com.felix.faze

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        assertTrue(LocalLlama.SYSTEM.length < 300)
        assertEquals("user", s.getJSONObject(1).getString("role"))
        assertEquals("user", s.getJSONObject(s.length() - 1).getString("role"))
        assertEquals("q6", s.getJSONObject(s.length() - 1).getString("content"))
        assertTrue(s.length() <= LocalLlama.KEEP_MESSAGES + 1)
        for (i in 0 until s.length()) assertFalse(s.getJSONObject(i).getString("role") == "tool")
    }

    @Test fun whatAToolJustDidReachesTheOfflineModelAsPartOfTheLastMessage() {
        val m = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "s"))
            .put(JSONObject().put("role", "user").put("content", "how much juice is left"))
            .put(JSONObject().put("role", "assistant").put("content", JSONObject.NULL).put("tool_calls", JSONArray().put(JSONObject())))
            .put(JSONObject().put("role", "tool").put("content", "82%"))
        val s = LocalLlama.shrink(m)
        assertEquals(2, s.length())
        val last = s.getJSONObject(1)
        assertEquals("user", last.getString("role"))
        assertTrue(last.getString("content"), last.getString("content").startsWith("how much juice is left") && last.getString("content").contains("Just done for you: 82%"))
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

    private fun tool(name: String, desc: String, vararg params: String): JSONObject {
        val props = JSONObject(); for (p in params) props.put(p, JSONObject().put("type", "string").put("description", "d".repeat(200)))
        return JSONObject().put("type", "function").put("function", JSONObject().put("name", name).put("description", desc)
            .put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(params.toList()))))
    }

    @Test fun theOfflineModelGetsOnlyAFewShortenedTools() {
        val all = JSONArray().put(tool("get_weather", "Gets the weather. It is long and has more detail after.", "city")).put(tool("create_tool", "x")).put(tool("tap", "y")).put(tool("get_battery_status", "Battery."))
        val c = LocalLlama.compactTools(all)!!
        val names = (0 until c.length()).map { c.getJSONObject(it).getJSONObject("function").getString("name") }
        assertEquals(listOf("get_current_datetime", "get_battery_status", "get_weather").filter { it in names }, names.sortedBy { LocalLlama.OFFLINE_TOOLS.indexOf(it) })
        assertTrue("tap" !in names && "create_tool" !in names)
        val w = (0 until c.length()).map { c.getJSONObject(it).getJSONObject("function") }.first { it.getString("name") == "get_weather" }
        assertEquals("Gets the weather", w.getString("description"))
        assertTrue(w.getJSONObject("parameters").getJSONObject("properties").getJSONObject("city").getString("description").length <= 60)
        assertNull(LocalLlama.compactTools(JSONArray().put("tool")))
        assertNull(LocalLlama.compactTools(null))
    }

    @Test fun toolsAreOfferedOnlyBeforeOneHasRun() {
        val before = JSONArray().put(JSONObject().put("role", "user").put("content", "battery?"))
        val after = JSONArray(before.toString()).put(JSONObject().put("role", "tool").put("content", "82%"))
        assertTrue(LocalLlama.noToolResultYet(before))
        assertFalse(LocalLlama.noToolResultYet(after))
    }

    @Test fun anInlineLfmToolCallIsReadAsARealCall() {
        val calls = LocalLlama.parseInlineCalls("<|tool_call_start|>[get_weather(city=\"Lagos\"), set_volume(level=7)]<|tool_call_end|>")
        assertEquals(listOf("get_weather", "set_volume"), calls.map { it.name })
        assertEquals("Lagos", JSONObject(calls[0].arguments).getString("city"))
        assertEquals(7, JSONObject(calls[1].arguments).getInt("level"))
        assertTrue(LocalLlama.parseInlineCalls("just talking").isEmpty())
    }

    @Test fun theServerIsStartedWithJinjaSoToolsAreAccepted() {
        assertTrue(LocalLlama.serverCommand("/b/s", lfm, 4, 2048).contains("--jinja"))
    }

    @Test fun offlineChatOffersToolsFirstAndTalksWithoutThemIfTheServerRefuses() {
        val schema = JSONArray().put(tool("get_battery_status", "Battery."))
        val inner = FakeChat(RemoteError("server", "tools need --jinja"), ChatResult("hey", emptyList(), false))
        val out = OfflineChat(inner, FakeServer(), { lfm }, { }).chat("x", JSONArray().put(JSONObject().put("role", "user").put("content", "hi")), schema, { }, { false })
        assertEquals("hey", out.content)
        assertEquals(2, inner.calls.size)
        assertNotNull(inner.calls[0].tools)
        assertNull(inner.calls[1].tools)
    }

    @Test fun offlineChatReadsAnInlineToolCallWhenTheServerDidNotParseIt() {
        val schema = JSONArray().put(tool("get_battery_status", "Battery."))
        val inner = FakeChat(ChatResult("<|tool_call_start|>[get_battery_status()]<|tool_call_end|>", emptyList(), true))
        val out = OfflineChat(inner, FakeServer(), { lfm }, { }).chat("x", JSONArray().put(JSONObject().put("role", "user").put("content", "battery?")), schema, { }, { false })
        assertEquals(listOf("get_battery_status"), out.toolCalls.map { it.name })
        assertEquals("", out.content)
    }

    @Test fun gpuFlagIsOnlyAddedWhenAsked() {
        assertTrue(!LocalLlama.serverCommand("/b/s", lfm, 4, 2048).contains("-ngl"))
        assertTrue(LocalLlama.serverCommand("/b/s", lfm, 4, 2048, gpuLayers = 16).contains("-ngl 16"))
    }

    @Test fun aGpuStartThatDiesIsRetriedOnTheCpu() {
        val ran = ArrayList<String>()
        val notes = ArrayList<String>()
        var up: String? = null
        val c = LocalServerControl(
            { cmd, _ -> ran.add(cmd); if (cmd.contains("nohup") && !cmd.contains("-ngl")) up = "LFM2.5-230M-Q4_K_M.gguf"; if (cmd.startsWith("pgrep")) "" else "" },
            { up }, { "/b/llama-server" }, { 4 }, { 2048 }, {}, 60_000, { 16 }, { notes.add(it) },
        )
        assertEquals(null, c.ensure(lfm) {})
        assertEquals(2, ran.count { it.contains("nohup") })
        assertTrue(ran.first { it.contains("nohup") }.contains("-ngl 16"))
        assertTrue(!ran.last { it.contains("nohup") }.contains("-ngl"))
        assertTrue(notes.any { it.contains("CPU") })
        // and it does not try the GPU again this run
        up = null
        ran.clear()
        c.stop()
        up = null
        c.ensure("/sdcard/Download/other.gguf") {}
        assertTrue(ran.filter { it.contains("nohup") }.none { it.contains("-ngl") })
    }

    @Test fun gpuNoteFindsTheOffloadLine() {
        assertEquals("llm_load_tensors: offloaded 25/25 layers to GPU", LocalLlama.gpuNote("x\nllm_load_tensors: offloaded 25/25 layers to GPU\nready"))
        assertEquals("", LocalLlama.gpuNote("nothing here"))
    }
}
