package com.felix.faze

/** What the model may do to the live conversation it is part of. */
interface LiveControl {
    /** True while live mode or voice chat is running. */
    fun active(): Boolean

    /** Closes the conversation once the goodbye has been spoken. */
    fun endAfterSpeech()
}

/** Waits for the goodbye: first for it to start (the reply may not have begun), then for it to finish, then ends. Never waits for ever. */
object EndAfterSpeech {
    fun run(speaking: () -> Boolean, sleep: (Long) -> Unit, end: () -> Unit, startWaitMs: Long = 3000, maxSpeechMs: Long = 12_000) {
        var waited = 0L
        while (!speaking() && waited < startWaitMs) { sleep(100); waited += 100 }
        waited = 0L
        while (speaking() && waited < maxSpeechMs) { sleep(100); waited += 100 }
        sleep(300)
        end()
    }
}
