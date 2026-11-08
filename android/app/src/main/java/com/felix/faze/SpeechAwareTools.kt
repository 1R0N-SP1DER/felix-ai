package com.felix.faze

import org.json.JSONArray
import org.json.JSONObject

/**
 * While a spoken conversation is going, a tool that takes over the screen (opening an app or a page) must not cut the assistant off
 * mid-sentence. With the floating bubble up, the conversation carries on outside the app, so the tool runs at once; without it, the
 * tool waits until the speech has finished (at most [maxWaitMs]).
 */
class SpeechAwareTools(
    private val inner: ToolRunner,
    /** Whether a spoken conversation is going at all. */
    private val active: () -> Boolean,
    private val speaking: () -> Boolean,
    /** Whether the conversation can carry on outside the app (the foreground service and the bubble are available). */
    private val bubbleUp: () -> Boolean,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val maxWaitMs: Long = 20_000,
) : ToolRunner {
    override fun schema(): JSONArray = inner.schema()

    override fun precheck(name: String, args: JSONObject): String? = inner.precheck(name, args)

    override fun execute(name: String, args: JSONObject): ToolResult {
        if (name in FOREGROUND_TOOLS && active() && !bubbleUp()) {
            var waited = 0L
            while (speaking() && waited < maxWaitMs) {
                sleep(POLL_MS)
                waited += POLL_MS
            }
            if (waited > 0) sleep(SETTLE_MS) // the last word has finished
        }
        return inner.execute(name, args)
    }

    companion object {
        /** Tools that put another app or page in front of this one. */
        val FOREGROUND_TOOLS = setOf("open_app", "open_url")
        private const val POLL_MS = 100L
        private const val SETTLE_MS = 300L
    }
}
