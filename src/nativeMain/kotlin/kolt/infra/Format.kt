package kolt.infra

import kotlin.time.Duration

fun formatDuration(duration: Duration): String {
    val tenths = duration.inWholeMilliseconds / 100
    val minutes = (tenths / 600).toInt()
    val secondsTenths = tenths - minutes * 600
    val secStr = "${secondsTenths / 10}.${secondsTenths % 10}s"
    return if (minutes > 0) "${minutes}m $secStr" else secStr
}

// Binary units (KB = 1024 B), one decimal, rounded to match `du -h`.
// Drops the decimal for raw bytes.
fun formatBytes(n: Long): String {
    if (n < 1024) return "$n B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = n.toDouble() / 1024.0
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex++
    }
    val tenths = kotlin.math.round(value * 10).toLong()
    return "${tenths / 10}.${tenths % 10} ${units[unitIndex]}"
}
