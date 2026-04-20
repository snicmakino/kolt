package kolt.build

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class FallbackNativeCompilerBackendTest {

    @Test
    fun primarySuccessDoesNotInvokeFallback() {
        val primary = FakeBackend(reply = Ok(NativeCompileOutcome(stderr = "")))
        val fallback = FakeBackend(reply = Err(NativeCompileError.NoCommand))

        val fb = FallbackNativeCompilerBackend(primary = primary, fallback = fallback)
        val result = fb.compile(listOf("-target", "linux_x64"))

        assertEquals(NativeCompileOutcome(stderr = ""), result.get())
        assertEquals(1, primary.callCount)
        assertEquals(0, fallback.callCount, "fallback must not run when primary succeeded")
    }

    @Test
    fun primaryBackendUnavailableRetriesOnFallback() {
        val primary = FakeBackend(
            reply = Err(NativeCompileError.BackendUnavailable.Other("daemon unreachable")),
        )
        val fallback = FakeBackend(reply = Ok(NativeCompileOutcome(stderr = "warn: foo")))

        val fb = FallbackNativeCompilerBackend(primary = primary, fallback = fallback)
        val result = fb.compile(listOf("-p", "library"))

        assertEquals(NativeCompileOutcome(stderr = "warn: foo"), result.get())
        assertEquals(1, primary.callCount)
        assertEquals(1, fallback.callCount)
    }

    @Test
    fun primaryCompilationFailedDoesNotFallback() {
        val primary = FakeBackend(
            reply = Err(
                NativeCompileError.CompilationFailed(
                    exitCode = 1,
                    stderr = "error: unresolved reference: foo",
                ),
            ),
        )
        val fallback = FakeBackend(reply = Ok(NativeCompileOutcome(stderr = "")))

        val fb = FallbackNativeCompilerBackend(primary = primary, fallback = fallback)
        val err = fb.compile(emptyList()).getError()

        val compilationFailed = assertIs<NativeCompileError.CompilationFailed>(err)
        assertEquals(1, compilationFailed.exitCode)
        assertEquals(0, fallback.callCount, "real compile errors pass through untouched")
    }

    @Test
    fun primaryNoCommandDoesNotFallback() {
        val primary = FakeBackend(reply = Err(NativeCompileError.NoCommand))
        val fallback = FakeBackend(reply = Ok(NativeCompileOutcome(stderr = "")))

        val fb = FallbackNativeCompilerBackend(primary = primary, fallback = fallback)
        val err = fb.compile(emptyList()).getError()

        assertEquals(NativeCompileError.NoCommand, err)
        assertEquals(0, fallback.callCount)
    }

    @Test
    fun primaryInternalMisuseFallsBack() {
        val primary = FakeBackend(
            reply = Err(NativeCompileError.InternalMisuse("socket path too long")),
        )
        val fallback = FakeBackend(reply = Ok(NativeCompileOutcome(stderr = "")))

        val fb = FallbackNativeCompilerBackend(primary = primary, fallback = fallback)
        val result = fb.compile(emptyList())

        assertEquals(NativeCompileOutcome(stderr = ""), result.get())
        assertEquals(1, fallback.callCount)
    }

    @Test
    fun bothBackendsFailReturnsFallbackError() {
        val primary = FakeBackend(
            reply = Err(NativeCompileError.BackendUnavailable.ForkFailed),
        )
        val fallback = FakeBackend(
            reply = Err(NativeCompileError.CompilationFailed(exitCode = 1, stderr = "")),
        )

        val fb = FallbackNativeCompilerBackend(primary = primary, fallback = fallback)
        val err = fb.compile(emptyList()).getError()

        val compilationFailed = assertIs<NativeCompileError.CompilationFailed>(err)
        assertEquals(1, compilationFailed.exitCode)
    }

    @Test
    fun argsAreForwardedUnchangedToBothBackends() {
        val primary = FakeBackend(reply = Err(NativeCompileError.BackendUnavailable.ForkFailed))
        val fallback = FakeBackend(reply = Ok(NativeCompileOutcome(stderr = "")))
        val args = listOf("-target", "linux_x64", "src/Main.kt", "-p", "library")

        FallbackNativeCompilerBackend(primary = primary, fallback = fallback).compile(args)

        assertEquals(args, primary.lastArgs)
        assertEquals(args, fallback.lastArgs)
    }

    @Test
    fun onFallbackFiresWithPrimaryError() {
        val primaryError: NativeCompileError =
            NativeCompileError.BackendUnavailable.Other("spawn failed: daemon jar missing")
        val primary = FakeBackend(reply = Err(primaryError))
        val fallback = FakeBackend(reply = Ok(NativeCompileOutcome(stderr = "")))

        val observed = mutableListOf<NativeCompileError>()
        FallbackNativeCompilerBackend(
            primary = primary,
            fallback = fallback,
            onFallback = { observed += it },
        ).compile(emptyList())

        assertEquals(listOf(primaryError), observed)
    }

    @Test
    fun onFallbackDoesNotFireOnPrimarySuccess() {
        val primary = FakeBackend(reply = Ok(NativeCompileOutcome(stderr = "")))
        val fallback = FakeBackend(reply = Ok(NativeCompileOutcome(stderr = "")))

        var observedError: NativeCompileError? = null
        FallbackNativeCompilerBackend(
            primary = primary,
            fallback = fallback,
            onFallback = { observedError = it },
        ).compile(emptyList())

        assertNull(observedError)
    }

    private class FakeBackend(
        private val reply: Result<NativeCompileOutcome, NativeCompileError>,
    ) : NativeCompilerBackend {
        var callCount: Int = 0
            private set
        var lastArgs: List<String>? = null
            private set

        override fun compile(args: List<String>): Result<NativeCompileOutcome, NativeCompileError> {
            callCount++
            lastArgs = args
            return reply
        }
    }
}
