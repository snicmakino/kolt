package kolt.nativedaemon.server

// ADR 0024 §3: native daemon lifecycle defaults. Deliberately tighter than
// the JVM daemon's (30min / 1000 / 1.5GB) because native builds are less
// frequent and stage 2 linking consumes more LLVM-backend heap per call.
data class DaemonConfig(
    val idleTimeoutMillis: Long = DEFAULT_IDLE_TIMEOUT_MILLIS,
    val maxCompiles: Int = DEFAULT_MAX_COMPILES,
    val heapWatermarkBytes: Long = DEFAULT_HEAP_WATERMARK_BYTES,
) {
    companion object {
        const val DEFAULT_IDLE_TIMEOUT_MILLIS: Long = 10 * 60 * 1000L
        const val DEFAULT_MAX_COMPILES: Int = 500
        const val DEFAULT_HEAP_WATERMARK_BYTES: Long = 2L * 1024 * 1024 * 1024
    }
}
