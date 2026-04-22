package kolt.daemon.server

data class DaemonConfig(
    val idleTimeoutMillis: Long = DEFAULT_IDLE_TIMEOUT_MILLIS,
    val maxCompiles: Int = DEFAULT_MAX_COMPILES,
    val heapWatermarkBytes: Long = DEFAULT_HEAP_WATERMARK_BYTES,
) {
    companion object {
        const val DEFAULT_IDLE_TIMEOUT_MILLIS: Long = 30 * 60 * 1000L
        const val DEFAULT_MAX_COMPILES: Int = 1_000
        const val DEFAULT_HEAP_WATERMARK_BYTES: Long = 1_500L * 1024 * 1024
    }
}
