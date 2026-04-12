package kolt.infra

import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ProcessTest {

    @Test
    fun executeCommandReturnsOkOnSuccess() {
        val result = executeCommand(listOf("true"))

        assertEquals(0, assertNotNull(result.get()))
    }

    @Test
    fun executeCommandReturnsExitCodeOnNonZero() {
        val result = executeCommand(listOf("false"))

        val error = assertNotNull(result.getError())
        assertIs<ProcessError.NonZeroExit>(error)
        assertEquals(1, error.exitCode)
    }

    @Test
    fun executeCommandPassesArgsWithoutShellExpansion() {
        val result = executeCommand(listOf("echo", "\$HOME"))
        assertEquals(0, assertNotNull(result.get()))
    }

    @Test
    fun executeCommandEmptyArgsReturnsError() {
        val result = executeCommand(emptyList())

        assertNull(result.get())
        assertIs<ProcessError.EmptyArgs>(result.getError())
    }

    @Test
    fun executeAndCaptureReturnsOutput() {
        val result = executeAndCapture("echo hello 2>&1")

        val output = assertNotNull(result.get())
        assertEquals("hello\n", output)
    }

    @Test
    fun executeAndCaptureReturnsErrOnFailure() {
        val result = executeAndCapture("false")

        val error = assertNotNull(result.getError())
        assertIs<ProcessError.NonZeroExit>(error)
        assertEquals(1, error.exitCode)
    }
}
