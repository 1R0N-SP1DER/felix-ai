package com.felix.faze

import java.net.URI
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.RhinoException
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import org.mozilla.javascript.WrappedException

class SandboxError(message: String) : RuntimeException(message) // a RuntimeException: Rhino can only pass those through its interpreter

/** Which web addresses a saved tool may fetch: https only, only the sites it declared, never local or private addresses. */
object NetGuard {
    private val ipLike = Regex("^[0-9.]+$")

    /** Null if the address is allowed, otherwise the reason it is not. */
    fun check(url: String, allowed: List<String>): String? {
        if (url.length > 600) return "that address is too long"
        val u = try { URI(url) } catch (e: Exception) { return "that is not a valid address" }
        if (u.scheme != "https") return "only https addresses are allowed"
        if (u.userInfo != null) return "addresses with a user name are not allowed"
        if (u.port != -1 && u.port != 443) return "only the normal https port is allowed"
        val host = (u.host ?: return "that address has no host").lowercase()
        if (host.contains(':') || host.contains('[') || ipLike.matches(host) || host == "localhost" || host.endsWith(".local") || host.endsWith(".internal") || host.endsWith(".localhost"))
            return "local and numeric addresses are not allowed"
        if (allowed.none { host == it || host.endsWith(".$it") }) return "this tool may only reach: ${allowed.joinToString(", ").ifEmpty { "no sites" }}"
        return null
    }
}

/**
 * Runs a saved tool's JavaScript in a locked room. There is no way out to Java, files, the phone or other apps: the code gets plain
 * JavaScript plus three things handed in on purpose: http_get(url) (see [NetGuard], at most [maxFetches] times), now(), and log().
 * A time limit stops endless loops, and the result is cut to [maxResult] characters.
 */
class JsSandbox(
    private val fetch: (String) -> String,
    private val maxMs: Long = 4000,
    private val maxResult: Int = 4000,
    private val maxFetches: Int = 5,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private class Timed(val deadline: Long, val clock: () -> Long) : ContextFactory() {
        override fun makeContext(): Context = super.makeContext().apply {
            optimizationLevel = -1 // interpreted: works on Android, where classes can't be generated
            instructionObserverThreshold = 5000
            languageVersion = Context.VERSION_ES6
        }

        override fun observeInstructionCount(cx: Context, instructionCount: Int) {
            if (clock() > deadline) throw SandboxError("the tool ran too long and was stopped")
        }
    }

    private class Host(name: String, val impl: (Array<Any?>) -> Any?) : BaseFunction() {
        init { defineProperty("name", name, DONTENUM) }
        override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? = impl(args)
    }

    /** Runs `function run(args)` from [code] with [argsJson] and returns its result as text. Throws [SandboxError] with a readable reason. */
    fun run(code: String, argsJson: String, allowedHosts: List<String>): String {
        val factory = Timed(clock() + maxMs, clock)
        var fetches = 0
        try {
            return factory.call { cx ->
                val scope = cx.initSafeStandardObjects() // no Packages, no java.*: the code cannot reach Java at all
                cx.setClassShutter { false }
                ScriptableObject.putProperty(scope, "http_get", Host("http_get") { a ->
                    val url = Context.toString(a.getOrNull(0) ?: "")
                    NetGuard.check(url, allowedHosts)?.let { throw SandboxError(it) }
                    if (++fetches > maxFetches) throw SandboxError("too many web requests in one run (limit $maxFetches)")
                    try { fetch(url) } catch (e: Exception) { throw SandboxError("web request failed: ${e.message ?: e.javaClass.simpleName}") }
                })
                ScriptableObject.putProperty(scope, "now", Host("now") { java.time.Instant.now().toString() })
                ScriptableObject.putProperty(scope, "log", Host("log") { Undefined.instance })
                ScriptableObject.putProperty(scope, "__args", argsJson)
                val out = cx.evaluateString(scope, "$code\n;(typeof run === 'function') ? JSON.stringify(run(JSON.parse(__args))) : '__no_run__'", "tool", 1, null)
                val text = when (out) {
                    null, is Undefined -> ""
                    else -> Context.toString(out)
                }
                if (text == "__no_run__") throw SandboxError("the code must define function run(args)")
                text.take(maxResult)
            } as String
        } catch (e: SandboxError) {
            throw e
        } catch (e: RhinoException) {
            // an error raised by one of our own functions arrives wrapped; hand back the original reason
            (e as? WrappedException)?.wrappedException?.let { if (it is SandboxError) throw it }
            val cause = generateSequence<Throwable>(e) { it.cause }.firstOrNull { it is SandboxError }
            if (cause != null) throw cause as SandboxError
            throw SandboxError("the tool failed: " + (e.details() ?: e.message ?: "error").take(200))
        } catch (e: Exception) {
            val cause = generateSequence<Throwable>(e) { it.cause }.firstOrNull { it is SandboxError }
            if (cause != null) throw cause as SandboxError
            throw SandboxError("the tool failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Only checks that the code compiles; nothing runs. Null if it is fine. */
    fun syntaxProblem(code: String): String? {
        val factory = Timed(clock() + maxMs, clock)
        return try {
            factory.call { cx -> cx.compileString(code, "tool", 1, null); null }
        } catch (e: RhinoException) {
            "syntax error: " + (e.details() ?: "invalid code").take(160) + " (line ${e.lineNumber()})"
        }
    }
}
