package com.felix.faze

import android.content.Context
import java.io.File

/** Writes the reason for a crash to a small file before the app dies, so Settings can show it afterwards (and it can be copied from there). */
object CrashLog {
    private fun file(context: Context) = File(context.filesDir, "last-crash.txt")

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try { file(app).writeText(describe(thread.name, e)) } catch (_: Exception) { }
            previous?.uncaughtException(thread, e)
        }
    }

    fun describe(thread: String, e: Throwable): String =
        "In thread $thread: ${e.javaClass.name}: ${e.message}\n" + e.stackTrace.take(14).joinToString("\n") { "  at $it" } +
            (e.cause?.let { "\nCaused by ${it.javaClass.name}: ${it.message}\n" + it.stackTrace.take(8).joinToString("\n") { s -> "  at $s" } } ?: "")

    fun read(context: Context): String = try { file(context).takeIf { it.exists() }?.readText().orEmpty() } catch (_: Exception) { "" }

    fun clear(context: Context) { try { file(context).delete() } catch (_: Exception) { } }
}
