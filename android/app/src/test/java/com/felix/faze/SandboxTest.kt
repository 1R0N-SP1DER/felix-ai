package com.felix.faze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SandboxTest {
    private fun sb(fetch: (String) -> String = { "BODY" }, maxMs: Long = 2000) = JsSandbox(fetch, maxMs)
    private fun err(block: () -> Unit): String = try { block(); fail("should have failed"); "" } catch (e: SandboxError) { e.message!! }

    @Test fun runsPlainJavaScriptAndReturnsJson() {
        val out = sb().run("function run(a) { return {sum: a.x + a.y, list: [1,2,3].map(n => n * 2)}; }", """{"x":2,"y":3}""", emptyList())
        assertEquals("""{"sum":5,"list":[2,4,6]}""", out)
    }

    @Test fun stringsComeBackQuoted() {
        assertEquals("\"hi Lagos\"", sb().run("function run(a){ return 'hi ' + a.city }", """{"city":"Lagos"}""", emptyList()))
    }

    @Test fun javaIsNotReachable() {
        for (code in listOf(
            "function run(){ return java.lang.System.getProperty('user.dir') }",
            "function run(){ return Packages.java.io.File('/').list() }",
            "function run(){ return new java.io.File('/') }",
            "function run(){ return importPackage(java.io) }",
            "function run(){ return this.getClass() }",
            "function run(){ return (function(){}).constructor.constructor('return java')() }",
        )) {
            val m = err { sb().run(code, "{}", emptyList()) }
            assertTrue(code + " -> " + m, m.startsWith("the tool failed"))
        }
    }

    @Test fun anEndlessLoopIsStopped() {
        val start = System.currentTimeMillis()
        assertTrue(err { sb(maxMs = 300).run("function run(){ while(true){} }", "{}", emptyList()) }.contains("ran too long"))
        assertTrue(System.currentTimeMillis() - start < 3000)
    }

    @Test fun aMissingRunFunctionAndBrokenCodeAreClearErrors() {
        assertTrue(err { sb().run("var x = 1;", "{}", emptyList()) }.contains("must define function run"))
        assertTrue(err { sb().run("function run( {", "{}", emptyList()) }.startsWith("the tool failed"))
        assertTrue(err { sb().run("function run(){ throw new Error('nope') }", "{}", emptyList()) }.contains("nope"))
    }

    @Test fun syntaxIsCheckedWithoutRunningAnything() {
        assertNull(sb().syntaxProblem("function run(a){ return 1 }"))
        assertNotNull(sb().syntaxProblem("function run(a){ return }}"))
        assertNull(sb().syntaxProblem("while(true){}")) // compiled, never run
    }

    @Test fun webRequestsGoThroughTheGuard() {
        val seen = ArrayList<String>()
        val s = sb({ seen.add(it); """{"t":21}""" })
        val code = "function run(a){ return JSON.parse(http_get('https://api.open-meteo.com/v1/forecast?x=1')).t }"
        assertEquals("21", s.run(code, "{}", listOf("open-meteo.com")))
        assertEquals(listOf("https://api.open-meteo.com/v1/forecast?x=1"), seen)
        assertTrue(err { s.run(code, "{}", listOf("example.org")) }.contains("may only reach: example.org"))
        assertTrue(err { s.run("function run(){ return http_get('http://open-meteo.com/') }", "{}", listOf("open-meteo.com")) }.contains("only https"))
        assertEquals(1, seen.size) // refused calls never reached the network
    }

    @Test fun tooManyWebRequestsInOneRunAreStopped() {
        val code = "function run(){ for (var i=0;i<10;i++) http_get('https://a.example.org/' + i); return 'x' }"
        assertTrue(err { sb().run(code, "{}", listOf("example.org")) }.contains("too many web requests"))
    }

    @Test fun aFailingFetchBecomesAReadableError() {
        val s = sb({ throw RuntimeException("HTTP 500") })
        assertTrue(err { s.run("function run(){ return http_get('https://a.example.org/') }", "{}", listOf("example.org")) }.contains("HTTP 500"))
    }

    @Test fun theResultIsCutShort() {
        val out = JsSandbox({ "" }, 2000, 50).run("function run(){ return 'x'.repeat(500) }", "{}", emptyList())
        assertEquals(50, out.length)
    }

    @Test fun nowAndLogExist() {
        assertTrue(sb().run("function run(){ log('x'); return now() }", "{}", emptyList()).contains("T"))
    }

    @Test fun theNetGuardRefusesLocalAndOddAddresses() {
        val ok = listOf("example.org")
        assertNull(NetGuard.check("https://example.org/a?b=1", ok))
        assertNull(NetGuard.check("https://api.example.org/a", ok))
        for (u in listOf("http://example.org/", "https://localhost/", "https://127.0.0.1/", "https://192.168.1.5/", "https://[::1]/", "https://example.org:8080/",
            "https://user:pw@example.org/", "https://evilexample.org/", "https://example.org.evil.com/", "ftp://example.org/", "https://printer.local/", "not a url")) {
            assertNotNull(u, NetGuard.check(u, ok))
        }
    }
}
