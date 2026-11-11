package com.felix.faze

/** Safe ranges for how the offline model is run: a bad value could make Android kill the server or Termux refuse to start it. */
object OfflineLimits {
    const val DEFAULT_THREADS = 4
    const val DEFAULT_CTX = 2048
    val THREADS = listOf(2, 3, 4, 6, 8)
    val CTX = listOf(1024, 2048, 3072, 4096)

    fun threads(v: Int?): Int = (v ?: DEFAULT_THREADS).coerceIn(1, 8)
    fun ctx(v: Int?): Int = (v ?: DEFAULT_CTX).coerceIn(512, 8192)
}
