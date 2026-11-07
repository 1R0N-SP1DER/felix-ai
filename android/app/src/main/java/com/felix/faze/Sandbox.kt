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

/** Which web addresses a saved tool may fetch: https only, never local or private addresses, and (unless it has the network power) only the sites it declared. */
object NetGuard {
    private val ipLike = Regex("^[0-9.]+$")

    /** Null if the address is allowed, otherwise the reason it is not. [anyHost] is the network power: every public https site is fine. */
    fun check(url: String, allowed: List<String>, anyHost: Boolean = false): String? {
        if (url.length > 600) return "that address is too long"
        val u = try { URI(url) } catch (e: Exception) { return "that is not a valid address" }
        if (u.scheme != "https") return "only https addresses are allowed"
        if (u.userInfo != null) return "addresses with a user name are not allowed"
        if (u.port != -1 && u.port != 443) return "only the normal https port is allowed"
        val host = (u.host ?: return "that address has no host").lowercase()
        if (host.contains(':') || host.contains('[') || ipLike.matches(host) || host == "localhost" || host.endsWith(".local") || host.endsWith(".internal") || host.endsWith(".localhost"))
            return "local and numeric addresses are not allowed"
        if (!anyHost && allowed.none { host == it || host.endsWith(".$it") }) return "this tool may only reach: ${allowed.joinToString(", ").ifEmpty { "no sites" }}"
        return null
    }
}

/**
 * The powers a tool can declare. The person reads the code and the powers once, when the tool is made; after that it runs without asking.
 * A power's functions only exist inside the sandbox when the tool declared it, so undeclared use fails no matter what the code tries.
 */
object Powers {
    val all = listOf("network", "shell", "root", "files", "screenshot", "intent")

    /** Each extra function and the power it needs ("" = any declared power). */
    val functions: Map<String, String> = linkedMapOf(
        "http_post" to "network", "shell" to "shell", "root" to "root",
        "read_file" to "files", "write_file" to "files", "list_dir" to "files",
        "screenshot" to "screenshot", "start_intent" to "intent", "sleep" to "",
    )

    /** The declared powers, or the reason the list is not valid. */
    fun parse(spec: String): Pair<List<String>?, String?> {
        val list = spec.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
        for (p in list) if (p !in all) return null to "unknown power '$p' (powers: ${all.joinToString(", ")})"
        return list to null
    }

    /** Early feedback for the model: null if the code only calls power functions it declared. (The real gate is that undeclared ones do not exist at run time.) */
    fun undeclared(code: String, declared: Set<String>): String? {
        for ((fn, needed) in functions) {
            if (Regex("function\\s+$fn\\b").containsMatchIn(code)) continue // the code defines its own helper with that name
            if (!Regex("(^|[^A-Za-z0-9_.\\$])$fn\\s*\\(").containsMatchIn(code)) continue
            if (needed.isEmpty()) {
                if (declared.isEmpty()) return "the code calls $fn() but declares no powers; $fn() needs at least one power (${all.joinToString(", ")})"
            } else if (needed !in declared) return "the code calls $fn() but '$needed' is not in powers"
        }
        return null
    }
}

/** What a powered tool can do outside the sandbox. The Android side does the real work; tests hand in a fake. Failures are thrown and reach the tool as a readable error. */
interface PowerHost {
    fun http(url: String, method: String, body: String, contentType: String, timeoutMs: Long): String
    fun shell(command: String, timeoutMs: Long): String
    fun root(command: String, timeoutMs: Long): String
    fun readFile(path: String): String
    fun writeFile(path: String, text: String): String
    fun listDir(path: String): String
    fun screenshot(): String
    fun intent(spec: String): String
}

/**
 * Runs a saved tool's JavaScript in a locked room. There is no way out to Java, files, the phone or other apps: the code gets plain
 * JavaScript plus three things handed in on purpose: http_get(url) (see [NetGuard], at most [maxFetches] times), now(), and log().
 * A tool that declared powers also gets the matching functions from [Powers] (and a longer time limit, [poweredMs]).
 * A time limit stops endless loops, and the result is cut to [maxResult] characters.
 */
class JsSandbox(
    private val fetch: (String) -> String,
    private val maxMs: Long = 4000,
    private val maxResult: Int = 4000,
    private val maxFetches: Int = 5,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val power: PowerHost? = null,
    private val poweredMs: Long = 30_000,
    private val poweredFetches: Int = 30,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
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

    private fun arg(a: Array<Any?>, i: Int): String {
        val v = a.getOrNull(i)
        return if (v == null || v is Undefined) "" else Context.toString(v)
    }

    /** One call of a power function. Only reached for functions the tool declared. */
    private fun powerCall(name: String, a: Array<Any?>, left: () -> Long, countFetch: () -> Unit): Any? {
        if (name == "sleep") {
            val n = Context.toNumber(a.getOrNull(0) ?: 0)
            val ms = (if (n.isNaN()) 0.0 else n).toLong().coerceIn(0L, 10_000L)
            if (ms >= left()) throw SandboxError("the tool ran too long and was stopped")
            sleeper(ms)
            return Undefined.instance
        }
        val host = power ?: throw SandboxError("powers are not available in this build")
        try {
            return when (name) {
                "http_post" -> {
                    val url = arg(a, 0)
                    NetGuard.check(url, emptyList(), anyHost = true)?.let { throw SandboxError(it) }
                    countFetch()
                    host.http(url, "POST", arg(a, 1), arg(a, 2).ifEmpty { "application/json" }, left())
                }
                "shell" -> host.shell(arg(a, 0), left())
                "root" -> host.root(arg(a, 0), left())
                "read_file" -> host.readFile(arg(a, 0))
                "write_file" -> host.writeFile(arg(a, 0), arg(a, 1))
                "list_dir" -> host.listDir(arg(a, 0))
                "screenshot" -> host.screenshot()
                "start_intent" -> host.intent(arg(a, 0))
                else -> throw SandboxError("unknown function $name")
            }
        } catch (e: SandboxError) {
            throw e
        } catch (e: Exception) {
            throw SandboxError("$name failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Runs `function run(args)` from [code] with [argsJson] and returns its result as text. Throws [SandboxError] with a readable reason. */
    fun run(code: String, argsJson: String, allowedHosts: List<String>, powers: Set<String> = emptySet()): String {
        val deadline = clock() + (if (powers.isEmpty()) maxMs else poweredMs)
        val fetchLimit = if (powers.isEmpty()) maxFetches else poweredFetches
        val factory = Timed(deadline, clock)
        var fetches = 0
        fun left(): Long {
            val l = deadline - clock()
            if (l <= 0) throw SandboxError("the tool ran too long and was stopped")
            return l
        }
        fun countFetch() { if (++fetches > fetchLimit) throw SandboxError("too many web requests in one run (limit $fetchLimit)") }
        try {
            return factory.call { cx ->
                val scope = cx.initSafeStandardObjects() // no Packages, no java.*: the code cannot reach Java at all
                cx.setClassShutter { false }
                ScriptableObject.putProperty(scope, "http_get", Host("http_get") { a ->
                    val url = Context.toString(a.getOrNull(0) ?: "")
                    NetGuard.check(url, allowedHosts, anyHost = "network" in powers)?.let { throw SandboxError(it) }
                    countFetch()
                    try { fetch(url) } catch (e: Exception) { throw SandboxError("web request failed: ${e.message ?: e.javaClass.simpleName}") }
                })
                ScriptableObject.putProperty(scope, "now", Host("now") { java.time.Instant.now().toString() })
                ScriptableObject.putProperty(scope, "log", Host("log") { Undefined.instance })
                ScriptableObject.putProperty(scope, "__args", argsJson)
                for ((fname, needed) in Powers.functions) {
                    val granted = if (needed.isEmpty()) powers.isNotEmpty() else needed in powers
                    if (granted) scope.put(fname, scope, Host(fname) { a -> powerCall(fname, a, ::left, ::countFetch) })
                }
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
