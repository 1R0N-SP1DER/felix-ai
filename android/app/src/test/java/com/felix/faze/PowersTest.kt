package com.felix.faze

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PowersTest {
    private class FakeHost : PowerHost {
        val calls = ArrayList<String>()
        override fun http(url: String, method: String, body: String, contentType: String, timeoutMs: Long): String { calls.add("http $method $url $body $contentType"); return "HTTP-OK" }
        override fun shell(command: String, timeoutMs: Long): String { calls.add("shell $command"); return "SH:$command" }
        override fun root(command: String, timeoutMs: Long): String { calls.add("root $command"); return "ROOT:$command" }
        override fun readFile(path: String): String { calls.add("read $path"); return "FILE" }
        override fun writeFile(path: String, text: String): String { calls.add("write $path $text"); return "written" }
        override fun listDir(path: String): String { calls.add("list $path"); return "a\nb/" }
        override fun screenshot(): String { calls.add("shot"); return "/cache/shot.png" }
        override fun intent(spec: String): String { calls.add("intent $spec"); return "started" }
    }

    private val slept = ArrayList<Long>()
    private fun sb(host: PowerHost? = FakeHost(), fetch: (String) -> String = { "BODY" }) =
        JsSandbox(fetch, maxMs = 2000, power = host, poweredMs = 5000, sleeper = { slept.add(it) })

    private fun err(block: () -> Unit): String = try { block(); fail("should have failed"); "" } catch (e: SandboxError) { e.message!! }

    @Test fun undeclaredPowersDoNotExist() {
        val s = sb()
        for (fn in listOf("shell('ls')", "root('id')", "read_file('/x')", "write_file('/x','y')", "list_dir('/')", "screenshot()", "start_intent('{}')", "http_post('https://a.org','x')", "sleep(1)")) {
            val m = err { s.run("function run(){ return $fn }", "{}", emptyList()) }
            assertTrue("$fn: $m", m.contains("not defined") || m.contains("ReferenceError") || m.contains("failed"))
        }
    }

    @Test fun eachPowerOnlyUnlocksItsOwnFunctions() {
        val host = FakeHost()
        val s = sb(host)
        assertEquals("\"SH:ls\"", s.run("function run(){ return shell('ls') }", "{}", emptyList(), setOf("shell")))
        err { s.run("function run(){ return root('id') }", "{}", emptyList(), setOf("shell")) }
        err { s.run("function run(){ return read_file('/x') }", "{}", emptyList(), setOf("shell")) }
        assertEquals("\"ROOT:id\"", s.run("function run(){ return root('id') }", "{}", emptyList(), setOf("root")))
        assertEquals("\"FILE\"", s.run("function run(){ return read_file('/x') }", "{}", emptyList(), setOf("files")))
        assertEquals("\"written\"", s.run("function run(){ return write_file('/x','hi') }", "{}", emptyList(), setOf("files")))
        assertEquals("\"a\\nb/\"", s.run("function run(){ return list_dir('/') }", "{}", emptyList(), setOf("files")))
        assertEquals("\"/cache/shot.png\"", s.run("function run(){ return screenshot() }", "{}", emptyList(), setOf("screenshot")))
        assertEquals("\"started\"", s.run("function run(){ return start_intent('{\"action\":\"x\"}') }", "{}", emptyList(), setOf("intent")))
        assertEquals(listOf("shell ls", "root id", "read /x", "write /x hi", "list /", "shot", "intent {\"action\":\"x\"}"), host.calls)
    }

    @Test fun networkLiftsTheSiteList() {
        val s = sb()
        val code = "function run(){ return http_get('https://example.org/a') }"
        assertTrue(err { s.run(code, "{}", listOf("api.open-meteo.com")) }.contains("may only reach"))
        assertEquals("\"BODY\"", s.run(code, "{}", emptyList(), setOf("network")))
        assertTrue(err { s.run("function run(){ return http_get('https://127.0.0.1/a') }", "{}", emptyList(), setOf("network")) }.contains("local and numeric"))
        assertTrue(err { s.run("function run(){ return http_get('http://example.org/a') }", "{}", emptyList(), setOf("network")) }.contains("https"))
    }

    @Test fun httpPostNeedsNetworkAndKeepsTheGuard() {
        val host = FakeHost()
        val s = sb(host)
        assertEquals("\"HTTP-OK\"", s.run("function run(){ return http_post('https://example.org/x', '{\"a\":1}') }", "{}", emptyList(), setOf("network")))
        assertEquals("http POST https://example.org/x {\"a\":1} application/json", host.calls.single())
        assertTrue(err { s.run("function run(){ return http_post('https://localhost/x', 'b') }", "{}", emptyList(), setOf("network")) }.contains("local and numeric"))
    }

    @Test fun hostFailuresReachTheToolAsReadableErrors() {
        val bad = object : PowerHost by FakeHost() { override fun shell(command: String, timeoutMs: Long): String = throw RuntimeException("timed out") }
        assertEquals("shell failed: timed out", err { sb(bad).run("function run(){ return shell('sleep 99') }", "{}", emptyList(), setOf("shell")) })
    }

    @Test fun poweredToolsGetALongerTimeLimit() {
        var now = 0L
        val s = JsSandbox({ "" }, maxMs = 100, power = FakeHost(), poweredMs = 5000, clock = { now++ })
        val loop = "function run(){ var i = 0; while (true) { i++ } }"
        assertTrue(err { s.run(loop, "{}", emptyList()) }.contains("too long"))
        assertTrue(err { s.run(loop, "{}", emptyList(), setOf("shell")) }.contains("too long"))
    }

    @Test fun sleepNeedsAnyPowerAndRespectsTheDeadline() {
        slept.clear()
        val s = sb()
        assertEquals("1", s.run("function run(){ sleep(250); return 1 }", "{}", emptyList(), setOf("files")))
        assertEquals(listOf(250L), slept)
        assertTrue(err { s.run("function run(){ sleep(9000); return 1 }", "{}", emptyList(), setOf("files")) }.contains("too long"))
    }

    @Test fun powersAreParsedAndChecked() {
        assertEquals(listOf("shell", "files"), Powers.parse("Shell, files, shell").first)
        assertTrue(Powers.parse("shell, wizard").second!!.contains("unknown power 'wizard'"))
        assertNull(Powers.undeclared("function run(){ return shell('ls') }", setOf("shell")))
        assertTrue(Powers.undeclared("function run(){ return shell('ls') }", emptySet())!!.contains("'shell' is not in powers"))
        assertTrue(Powers.undeclared("function run(){ return sleep(5) }", emptySet())!!.contains("sleep()"))
        assertNull(Powers.undeclared("function run(){ return sleep(5) }", setOf("files")))
        assertNull(Powers.undeclared("function shell(x){return x} function run(){ return shell('a') }", emptySet())) // own helper
        assertNull(Powers.undeclared("function run(a){ return a.shell + 'shell' + myshell(1) }", emptySet()))
    }

    private val reserved = setOf("vibrate", "create_tool")
    private fun store() = ToolStore(File.createTempFile("tools", "dir").let { it.delete(); File(it, "tools.json") })

    private fun make(allowed: () -> Boolean = { true }, host: FakeHost = FakeHost()): Pair<UserTools, FakeHost> =
        UserTools(store(), JsSandbox({ "" }, power = host), { reserved }, allowed) to host

    private fun args(code: String, powers: String, domains: String = "") =
        JSONObject().put("name", "list_files").put("description", "Lists the files in a folder").put("parameters", "path:string").put("code", code).put("domains", domains).put("powers", powers)

    private val listCode = "function run(a){ return list_dir(a.path) }"

    @Test fun builtToolsKeepTheirPowers() {
        val (t, e) = ToolSpec.build("list_files", "Lists the files in a folder", "path:string", listCode, "", reserved, 1L, "files")
        assertNull(e)
        assertEquals(listOf("files"), t!!.powers)
        assertEquals("the code calls list_dir() but 'files' is not in powers", ToolSpec.build("list_files", "Lists the files in a folder", "path:string", listCode, "", reserved, 1L).second)
        assertTrue(ToolSpec.build("list_files", "Lists the files in a folder", "path:string", listCode, "", reserved, 1L, "wizard").second!!.contains("unknown power"))
        // http_get without domains is fine with the network power
        assertNull(ToolSpec.build("fetcher", "Fetches any page it is given", "u:string", "function run(a){ return http_get(a.u) }", "", reserved, 1L, "network").second)
    }

    @Test fun poweredToolsAreSavedRunWithoutAskingAndShownToTheModel() {
        val (u, host) = make()
        assertNull(u.precheck(args(listCode, "files")))
        assertTrue(u.create(args(listCode, "files")).ok)
        assertTrue(u.has("list_files"))
        assertEquals("\"a\\nb/\"", u.run("list_files", JSONObject().put("path", "/sdcard")).text)
        assertEquals(listOf("list /sdcard"), host.calls)
        assertTrue(u.schemas().single().getJSONObject("function").getString("description").contains("(powers: files)"))
    }

    @Test fun poweredToolsAreRefusedWhenTheSwitchIsOff() {
        var on = true
        val (u, host) = make({ on })
        assertTrue(u.create(args(listCode, "files")).ok)
        on = false
        assertEquals(UserTools.POWERS_OFF, u.precheck(args(listCode, "files")))
        assertFalse(u.create(args(listCode, "files").put("name", "another_one")).ok)
        val r = u.run("list_files", JSONObject().put("path", "/"))
        assertFalse(r.ok)
        assertEquals(UserTools.POWERS_OFF, r.text)
        assertTrue(host.calls.isEmpty())
        assertTrue(u.schemas().isEmpty() || u.schemas().none { it.getJSONObject("function").getString("name") == "list_files" })
        on = true
        assertTrue(u.run("list_files", JSONObject().put("path", "/")).ok)
    }

    @Test fun plainToolsStillWorkWhenTheSwitchIsOff() {
        val (u, _) = make({ false })
        val plain = JSONObject().put("name", "adder").put("description", "Adds one and one together").put("parameters", "").put("code", "function run(a){ return 1 + 1 }").put("domains", "")
        assertTrue(u.create(plain).ok)
        assertEquals("2", u.run("adder", JSONObject()).text)
    }

    @Test fun anOldToolsFileWithoutPowersStillLoads() {
        val f = File.createTempFile("tools", "dir").let { it.delete(); File(it, "tools.json") }
        f.parentFile!!.mkdirs()
        f.writeText("""[{"name":"old_tool","description":"An old tool from before","code":"function run(){return 1}","created":5,"params":[],"domains":[]}]""")
        val t = ToolStore(f).get("old_tool")!!
        assertTrue(t.powers.isEmpty())
    }
}
