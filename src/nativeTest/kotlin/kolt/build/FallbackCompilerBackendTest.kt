package kolt.build

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private class FakeBackend(
    private val reply: Result<CompileOutcome, CompileError>,
) : CompilerBackend {
    var callCount: Int = 0
        private set
    var lastRequest: CompileRequest? = null
        private set

    override fun compile(request: CompileRequest): Result<CompileOutcome, CompileError> {
        callCount++
        lastRequest = request
        return reply
    }
}

private fun request() = CompileRequest(
    workingDir = "/tmp/ws",
    classpath = listOf("a.jar"),
    sources = listOf("Main.kt"),
    outputPath = "build/classes",
    moduleName = "m",
    extraArgs = emptyList(),
)

class FallbackCompilerBackendTest {

    @Test
    fun primaryOkReturnsOutcomeAndDoesNotCallFallback() {
        val outcome = CompileOutcome(stdout = "ok", stderr = "")
        val primary = FakeBackend(Ok(outcome))
        val fallback = FakeBackend(Err(CompileError.NoCommand))
        val observed = mutableListOf<CompileError>()
        val backend = FallbackCompilerBackend(primary, fallback) { observed += it }

        val result = backend.compile(request())

        assertEquals(outcome, result.get())
        assertEquals(1, primary.callCount)
        assertEquals(0, fallback.callCount)
        assertTrue(observed.isEmpty())
    }

    @Test
    fun primaryBackendUnavailableTriggersFallback() {
        val primary = FakeBackend(Err(CompileError.BackendUnavailable.Other("daemon gone")))
        val outcome = CompileOutcome(stdout = "subprocess ok", stderr = "")
        val fallback = FakeBackend(Ok(outcome))
        val observed = mutableListOf<CompileError>()
        val backend = FallbackCompilerBackend(primary, fallback) { observed += it }

        val result = backend.compile(request())

        assertEquals(outcome, result.get())
        assertEquals(1, primary.callCount)
        assertEquals(1, fallback.callCount)
        assertEquals(1, observed.size)
        val reported = assertIs<CompileError.BackendUnavailable.Other>(observed.single())
        assertEquals("daemon gone", reported.detail)
    }

    @Test
    fun everyBackendUnavailableVariantIsEligible() {
        // The exhaustive list makes sure a future variant cannot be
        // added to BackendUnavailable without the author noticing
        // that it needs to be re-classified here.
        val variants: List<CompileError> = listOf(
            CompileError.BackendUnavailable.ForkFailed,
            CompileError.BackendUnavailable.WaitFailed,
            CompileError.BackendUnavailable.SignalKilled,
            CompileError.BackendUnavailable.PopenFailed,
            CompileError.BackendUnavailable.Other("x"),
        )
        for (variant in variants) {
            assertTrue(isFallbackEligible(variant), "expected eligible: $variant")
        }
    }

    @Test
    fun internalMisuseIsEligibleAndReportedForLoudLogging() {
        val err = CompileError.InternalMisuse("socket path too long")
        val primary = FakeBackend(Err(err))
        val fallback = FakeBackend(Ok(CompileOutcome("", "")))
        val observed = mutableListOf<CompileError>()
        val backend = FallbackCompilerBackend(primary, fallback) { observed += it }

        val result = backend.compile(request())

        assertNotNull(result.get())
        assertEquals(1, fallback.callCount)
        // Caller is expected to distinguish this from BackendUnavailable
        // and surface it as an error rather than a warning (ADR 0016 §3).
        assertIs<CompileError.InternalMisuse>(observed.single())
    }

    @Test
    fun compilationFailedIsNotEligibleAndPassesThrough() {
        val err = CompileError.CompilationFailed(exitCode = 1, stdout = "", stderr = "boom")
        val primary = FakeBackend(Err(err))
        val fallback = FakeBackend(Ok(CompileOutcome("", "")))
        val observed = mutableListOf<CompileError>()
        val backend = FallbackCompilerBackend(primary, fallback) { observed += it }

        val result = backend.compile(request())

        assertEquals(err, result.getError())
        assertEquals(1, primary.callCount)
        assertEquals(0, fallback.callCount)
        assertTrue(observed.isEmpty())
    }

    @Test
    fun noCommandIsNotEligibleAndPassesThrough() {
        val primary = FakeBackend(Err(CompileError.NoCommand))
        val fallback = FakeBackend(Ok(CompileOutcome("", "")))
        val observed = mutableListOf<CompileError>()
        val backend = FallbackCompilerBackend(primary, fallback) { observed += it }

        val result = backend.compile(request())

        assertEquals(CompileError.NoCommand, result.getError())
        assertEquals(0, fallback.callCount)
        assertTrue(observed.isEmpty())
    }

    @Test
    fun fallbackErrorIsReturnedWhenBothBackendsFail() {
        val primaryErr = CompileError.BackendUnavailable.ForkFailed
        val fallbackErr = CompileError.CompilationFailed(exitCode = 2, stdout = "", stderr = "nope")
        val primary = FakeBackend(Err(primaryErr))
        val fallback = FakeBackend(Err(fallbackErr))
        val observed = mutableListOf<CompileError>()
        val backend = FallbackCompilerBackend(primary, fallback) { observed += it }

        val result = backend.compile(request())

        assertEquals(fallbackErr, result.getError())
        assertEquals(1, primary.callCount)
        assertEquals(1, fallback.callCount)
        // onFallback still fires once — the observer sees *which*
        // primary error triggered the fallback, regardless of whether
        // the fallback ultimately succeeded.
        assertEquals(primaryErr, observed.single())
    }

    @Test
    fun requestIsForwardedUnchangedToBothBackends() {
        val primary = FakeBackend(Err(CompileError.BackendUnavailable.ForkFailed))
        val fallback = FakeBackend(Ok(CompileOutcome("", "")))
        val backend = FallbackCompilerBackend(primary, fallback)
        val req = request()

        backend.compile(req)

        assertEquals(req, primary.lastRequest)
        assertEquals(req, fallback.lastRequest)
    }

    @Test
    fun onFallbackDefaultsToNoOp() {
        val outcome = CompileOutcome(stdout = "subprocess ok", stderr = "")
        val primary = FakeBackend(Err(CompileError.BackendUnavailable.ForkFailed))
        val fallback = FakeBackend(Ok(outcome))
        val backend = FallbackCompilerBackend(primary, fallback)

        assertEquals(outcome, backend.compile(request()).get())
    }

    @Test
    fun isFallbackEligibleMatrix() {
        assertTrue(isFallbackEligible(CompileError.BackendUnavailable.ForkFailed))
        assertTrue(isFallbackEligible(CompileError.InternalMisuse("x")))
        assertFalse(isFallbackEligible(CompileError.CompilationFailed(1, "", "")))
        assertFalse(isFallbackEligible(CompileError.NoCommand))
    }
}
