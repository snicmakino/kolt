package keel

import kotlin.test.Test
import kotlin.test.assertEquals

class ProcessErrorFormatTest {

    @Test
    fun formatNonZeroExitWithContext() {
        val msg = formatProcessError(ProcessError.NonZeroExit(1), "compilation")
        assertEquals("error: compilation failed with exit code 1", msg)
    }

    @Test
    fun formatEmptyArgs() {
        val msg = formatProcessError(ProcessError.EmptyArgs, "compilation")
        assertEquals("error: no command to execute", msg)
    }

    @Test
    fun formatForkFailed() {
        val msg = formatProcessError(ProcessError.ForkFailed, "compiler")
        assertEquals("error: failed to start compiler process", msg)
    }

    @Test
    fun formatWaitFailed() {
        val msg = formatProcessError(ProcessError.WaitFailed, "compiler")
        assertEquals("error: failed waiting for compiler process", msg)
    }

    @Test
    fun formatSignalKilled() {
        val msg = formatProcessError(ProcessError.SignalKilled, "compiler")
        assertEquals("error: compiler process was killed", msg)
    }

    @Test
    fun formatPopenFailed() {
        val msg = formatProcessError(ProcessError.PopenFailed, "compiler")
        assertEquals("error: failed to start compiler process", msg)
    }
}
