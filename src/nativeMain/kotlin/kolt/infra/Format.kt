package kolt.infra

import kotlin.time.Duration

fun formatDuration(duration: Duration): String {
    val tenths = duration.inWholeMilliseconds / 100
    val minutes = (tenths / 600).toInt()
    val secondsTenths = tenths - minutes * 600
    val secStr = "${secondsTenths / 10}.${secondsTenths % 10}s"
    return if (minutes > 0) "${minutes}m $secStr" else secStr
}
