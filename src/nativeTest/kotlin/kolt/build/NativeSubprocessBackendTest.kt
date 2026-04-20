package kolt.build

import kolt.infra.ProcessError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class NativeSubprocessBackendTest {

    @Test
    fun argvPrependsKonancBin() {
        val argv = nativeSubprocessArgv(
            konancBin = "/opt/konanc",
            args = listOf("-target", "linux_x64", "src/Main.kt", "-p", "library", "-o", "build/m-klib"),
        )

        assertEquals(
            listOf("/opt/konanc", "-target", "linux_x64", "src/Main.kt", "-p", "library", "-o", "build/m-klib"),
            argv,
        )
    }

    @Test
    fun argvAcceptsEmptyArgs() {
        assertEquals(listOf("/opt/konanc"), nativeSubprocessArgv("/opt/konanc", emptyList()))
    }

    @Test
    fun processErrorForkFailedMapsToBackendUnavailable() {
        assertEquals(
            NativeCompileError.BackendUnavailable.ForkFailed,
            mapProcessErrorToNativeCompileError(ProcessError.ForkFailed),
        )
    }

    @Test
    fun processErrorWaitFailedMapsToBackendUnavailable() {
        assertEquals(
            NativeCompileError.BackendUnavailable.WaitFailed,
            mapProcessErrorToNativeCompileError(ProcessError.WaitFailed),
        )
    }

    @Test
    fun processErrorPopenFailedMapsToBackendUnavailable() {
        assertEquals(
            NativeCompileError.BackendUnavailable.PopenFailed,
            mapProcessErrorToNativeCompileError(ProcessError.PopenFailed),
        )
    }

    @Test
    fun processErrorSignalKilledMapsToBackendUnavailable() {
        assertEquals(
            NativeCompileError.BackendUnavailable.SignalKilled,
            mapProcessErrorToNativeCompileError(ProcessError.SignalKilled),
        )
    }

    @Test
    fun processErrorNonZeroExitMapsToCompilationFailed() {
        val err = mapProcessErrorToNativeCompileError(ProcessError.NonZeroExit(exitCode = 1))
        val compilationFailed = assertIs<NativeCompileError.CompilationFailed>(err)
        assertEquals(1, compilationFailed.exitCode)
        // stderr is empty because the subprocess inherits the parent's fd;
        // the caller never captures it. Matches the JVM subprocess backend.
        assertEquals("", compilationFailed.stderr)
    }

    @Test
    fun processErrorEmptyArgsMapsToNoCommand() {
        assertEquals(
            NativeCompileError.NoCommand,
            mapProcessErrorToNativeCompileError(ProcessError.EmptyArgs),
        )
    }
}
