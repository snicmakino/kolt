package kolt.daemon.ic

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getError
import com.github.michaelbull.result.getOrElse
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Contract tests for the daemon-core-facing interface defined in ADR 0019 §3.
// These tests intentionally do not exercise any BTA type; they verify only the
// shape of IncrementalCompiler / IcRequest / IcResponse / IcError so that a
// future refactor cannot accidentally reshape the adapter boundary without
// forcing daemon-core call sites to change.
class IncrementalCompilerContractTest {

    private fun sampleRequest(): IcRequest = IcRequest(
        projectId = "abc123",
        projectRoot = Path.of("/tmp/fixture"),
        sources = listOf(Path.of("/tmp/fixture/Main.kt")),
        classpath = listOf(Path.of("/tmp/kotlin-stdlib.jar")),
        outputDir = Path.of("/tmp/fixture/out"),
        workingDir = Path.of("/tmp/fixture/ic"),
    )

    @Test
    fun `success path returns Ok IcResponse`() {
        val compiler = object : IncrementalCompiler {
            override fun compile(request: IcRequest): Result<IcResponse, IcError> =
                Ok(IcResponse(wallMillis = 42, compiledFileCount = 1))
        }

        val response = compiler.compile(sampleRequest()).getOrElse {
            error("expected Ok, got Err($it)")
        }

        assertEquals(42L, response.wallMillis)
        assertEquals(1, response.compiledFileCount)
    }

    @Test
    fun `compiledFileCount is nullable when metrics unavailable`() {
        val response = IcResponse(wallMillis = 1, compiledFileCount = null)
        assertNull(response.compiledFileCount)
    }

    @Test
    fun `CompilationFailed carries diagnostic messages`() {
        val compiler = object : IncrementalCompiler {
            override fun compile(request: IcRequest): Result<IcResponse, IcError> =
                Err(IcError.CompilationFailed(listOf("Main.kt:1: error: unresolved reference")))
        }

        val err = compiler.compile(sampleRequest()).getError()
            ?: error("expected Err")
        val compilationFailed = assertIs<IcError.CompilationFailed>(err)
        assertEquals(1, compilationFailed.messages.size)
        assertTrue(compilationFailed.messages.single().contains("unresolved reference"))
    }

    @Test
    fun `InternalError carries original cause`() {
        val cause = IllegalStateException("bta boom")
        val compiler = object : IncrementalCompiler {
            override fun compile(request: IcRequest): Result<IcResponse, IcError> =
                Err(IcError.InternalError(cause))
        }

        val err = compiler.compile(sampleRequest()).getError()
            ?: error("expected Err")
        val internal = assertIs<IcError.InternalError>(err)
        assertEquals(cause, internal.cause)
    }
}
