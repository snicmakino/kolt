package kolt.build

import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kolt.infra.ProcessError
import kolt.infra.fileExists
import kolt.infra.removeDirectoryRecursive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SubprocessCompilerBackendArgvTest {

    private fun request(
        sources: List<String> = listOf("src"),
        classpath: List<String> = emptyList(),
        outputPath: String = "build/classes",
        moduleName: String = "my-app",
        extraArgs: List<String> = listOf("-jvm-target", "17"),
    ) = CompileRequest(
        workingDir = "",
        classpath = classpath,
        sources = sources,
        outputPath = outputPath,
        moduleName = moduleName,
        extraArgs = extraArgs,
    )

    @Test
    fun argvStartsWithKotlincBin() {
        val argv = subprocessArgv("/home/user/.kolt/toolchains/kotlinc/2.1.0/bin/kotlinc", request())
        assertEquals("/home/user/.kolt/toolchains/kotlinc/2.1.0/bin/kotlinc", argv.first())
    }

    @Test
    fun argvOmitsClasspathFlagWhenEmpty() {
        val argv = subprocessArgv("kotlinc", request(classpath = emptyList()))
        assertTrue("-cp" !in argv)
    }

    @Test
    fun argvIncludesClasspathJoinedByColon() {
        val argv = subprocessArgv(
            "kotlinc",
            request(classpath = listOf("/cache/a.jar", "/cache/b.jar")),
        )
        val cpIdx = argv.indexOf("-cp")
        assertTrue(cpIdx >= 0)
        assertEquals("/cache/a.jar:/cache/b.jar", argv[cpIdx + 1])
    }

    @Test
    fun argvEndsWithDFlagAndOutputPath() {
        val argv = subprocessArgv("kotlinc", request(outputPath = "build/classes"))
        val dIdx = argv.indexOf("-d")
        assertTrue(dIdx >= 0)
        assertEquals("build/classes", argv[dIdx + 1])
        assertEquals(argv.size - 2, dIdx)
    }

    @Test
    fun argvIncludesSourcesInOrder() {
        val argv = subprocessArgv(
            "kotlinc",
            request(sources = listOf("src", "generated")),
        )
        assertTrue(argv.containsAll(listOf("src", "generated")))
        assertTrue(argv.indexOf("src") < argv.indexOf("generated"))
    }

    @Test
    fun argvIncludesExtraArgs() {
        val argv = subprocessArgv(
            "kotlinc",
            request(extraArgs = listOf("-jvm-target", "21", "-Xplugin=foo.jar")),
        )
        assertTrue(argv.containsAll(listOf("-jvm-target", "21", "-Xplugin=foo.jar")))
    }
}

class SubprocessCompilerBackendMapProcessErrorTest {

    @Test
    fun nonZeroExitMapsToCompilationFailedWithExitCode() {
        val mapped = mapProcessErrorToCompileError(ProcessError.NonZeroExit(42))
        val failure = assertIs<CompileError.CompilationFailed>(mapped)
        assertEquals(42, failure.exitCode)
    }

    @Test
    fun forkFailedMapsToForkFailedVariant() {
        val mapped = mapProcessErrorToCompileError(ProcessError.ForkFailed)
        assertEquals(CompileError.BackendUnavailable.ForkFailed, mapped)
    }

    @Test
    fun waitFailedMapsToWaitFailedVariant() {
        val mapped = mapProcessErrorToCompileError(ProcessError.WaitFailed)
        assertEquals(CompileError.BackendUnavailable.WaitFailed, mapped)
    }

    @Test
    fun signalKilledMapsToSignalKilledVariant() {
        val mapped = mapProcessErrorToCompileError(ProcessError.SignalKilled)
        assertEquals(CompileError.BackendUnavailable.SignalKilled, mapped)
    }

    @Test
    fun popenFailedMapsToPopenFailedVariant() {
        val mapped = mapProcessErrorToCompileError(ProcessError.PopenFailed)
        assertEquals(CompileError.BackendUnavailable.PopenFailed, mapped)
    }

    @Test
    fun emptyArgsMapsToNoCommand() {
        val mapped = mapProcessErrorToCompileError(ProcessError.EmptyArgs)
        assertEquals(CompileError.NoCommand, mapped)
    }
}

// Pins user-facing wording against the pre-refactor formatProcessError strings.
class FormatCompileErrorWordingTest {

    @Test
    fun compilationFailedMatchesLegacyNonZeroExitWording() {
        val message = formatCompileError(CompileError.CompilationFailed(1, "", ""), "compilation")
        assertEquals("error: compilation failed with exit code 1", message)
    }

    @Test
    fun forkFailedMatchesLegacyWording() {
        val message = formatCompileError(CompileError.BackendUnavailable.ForkFailed, "compilation")
        assertEquals("error: failed to start compilation process", message)
    }

    @Test
    fun popenFailedMatchesLegacyWording() {
        val message = formatCompileError(CompileError.BackendUnavailable.PopenFailed, "compilation")
        assertEquals("error: failed to start compilation process", message)
    }

    @Test
    fun waitFailedMatchesLegacyWording() {
        val message = formatCompileError(CompileError.BackendUnavailable.WaitFailed, "compilation")
        assertEquals("error: failed waiting for compilation process", message)
    }

    @Test
    fun signalKilledMatchesLegacyWording() {
        val message = formatCompileError(CompileError.BackendUnavailable.SignalKilled, "compilation")
        assertEquals("error: compilation process was killed", message)
    }

    @Test
    fun noCommandMatchesLegacyWording() {
        val message = formatCompileError(CompileError.NoCommand, "compilation")
        assertEquals("error: no command to execute", message)
    }

    @Test
    fun backendUnavailableOtherIncludesDetail() {
        val message = formatCompileError(
            CompileError.BackendUnavailable.Other("daemon socket closed"),
            "compilation",
        )
        assertEquals("error: compilation backend unavailable: daemon socket closed", message)
    }

    @Test
    fun internalMisuseIncludesDetail() {
        val message = formatCompileError(CompileError.InternalMisuse("unexpected state"), "compilation")
        assertEquals("error: compilation internal bug: unexpected state", message)
    }
}

class SubprocessCompilerBackendIntegrationTest {

    private val scratchDir = "build/tmp/kolt_subprocess_backend_test_out"

    private val trivialRequest = CompileRequest(
        workingDir = "",
        classpath = emptyList(),
        sources = emptyList(),
        outputPath = scratchDir,
        moduleName = "test",
        extraArgs = emptyList(),
    )

    @AfterTest
    fun cleanup() {
        if (fileExists(scratchDir)) {
            removeDirectoryRecursive(scratchDir)
        }
    }

    @Test
    fun compileReturnsOkWhenBinaryExitsZero() {
        val backend = SubprocessCompilerBackend(kotlincBin = "/bin/true")
        val result = backend.compile(trivialRequest)
        val outcome = assertNotNull(result.get())
        // Subprocess backend returns "" because kotlinc inherits our stdout/stderr directly.
        assertEquals("", outcome.stdout)
        assertEquals("", outcome.stderr)
    }

    @Test
    fun compileReturnsCompilationFailedWhenBinaryExitsNonZero() {
        val backend = SubprocessCompilerBackend(kotlincBin = "/bin/false")
        val result = backend.compile(trivialRequest)
        val error = assertNotNull(result.getError())
        val failure = assertIs<CompileError.CompilationFailed>(error)
        assertEquals(1, failure.exitCode)
    }
}
