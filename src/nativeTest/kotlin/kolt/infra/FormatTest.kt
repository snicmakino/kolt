package kolt.infra

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.minutes

class FormatTest {
    @Test
    fun formatSubSecond() {
        assertEquals("0.5s", formatDuration(500.milliseconds))
    }

    @Test
    fun formatSeconds() {
        assertEquals("2.3s", formatDuration(2300.milliseconds))
    }

    @Test
    fun formatMinutesAndSeconds() {
        assertEquals("1m 5.0s", formatDuration(1.minutes + 5.seconds))
    }

    @Test
    fun formatRoundsToOneTenth() {
        assertEquals("1.2s", formatDuration(1234.milliseconds))
    }

    @Test
    fun formatZeroDuration() {
        assertEquals("0.0s", formatDuration(0.milliseconds))
    }
}
