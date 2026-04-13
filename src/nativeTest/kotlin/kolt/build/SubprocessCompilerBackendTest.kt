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

// Cross-check sweep: the new backend argv must match Builder.buildCommand() byte-for-byte
// across the realistic combinations of inputs doBuild() will produce. This is the S1 oracle
// that protects the refactor from silently changing the kotlinc invocation. Removed after
// S5 when Builder.buildCommand() itself is deleted (see Builder.kt).
class SubprocessCompilerBackendLegacyCrossCheckTest {

    private fun requestFromLegacy(
        config: kolt.config.KoltConfig,
        classpath: String?,
        pluginArgs: List<String>,
    ): CompileRequest = CompileRequest(
        workingDir = "",
        classpath = if (classpath.isNullOrEmpty()) emptyList()
            else classpath.split(":").filter { it.isNotEmpty() },
        sources = config.sources,
        outputPath = CLASSES_DIR,
        moduleName = config.name,
        extraArgs = buildList {
            add("-jvm-target")
            add(config.jvmTarget)
            addAll(pluginArgs)
        },
    )

    private fun assertArgvMatchesLegacy(
        config: kolt.config.KoltConfig,
        classpath: String?,
        pluginArgs: List<String>,
    ) {
        val legacy = buildCommand(config, classpath = classpath, pluginArgs = pluginArgs, kotlincPath = "/managed/kotlinc")
        val argv = subprocessArgv("/managed/kotlinc", requestFromLegacy(config, classpath, pluginArgs))
        assertEquals(legacy.args, argv)
    }

    @Test
    fun matchesLegacyWithClasspathAndPluginArgs() {
        assertArgvMatchesLegacy(
            config = kolt.testConfig(jvmTarget = "17"),
            classpath = "/cache/a.jar:/cache/b.jar",
            pluginArgs = listOf("-Xplugin=foo.jar"),
        )
    }

    @Test
    fun matchesLegacyWithoutClasspath() {
        assertArgvMatchesLegacy(
            config = kolt.testConfig(jvmTarget = "17"),
            classpath = null,
            pluginArgs = listOf("-Xplugin=foo.jar"),
        )
    }

    @Test
    fun matchesLegacyWithoutPluginArgs() {
        assertArgvMatchesLegacy(
            config = kolt.testConfig(jvmTarget = "17"),
            classpath = "/cache/a.jar",
            pluginArgs = emptyList(),
        )
    }

    @Test
    fun matchesLegacyWithJvmTarget21() {
        assertArgvMatchesLegacy(
            config = kolt.testConfig(jvmTarget = "21"),
            classpath = "/cache/a.jar:/cache/b.jar",
            pluginArgs = listOf("-Xplugin=foo.jar", "-Xplugin=bar.jar"),
        )
    }

    @Test
    fun matchesLegacyWithMultipleSources() {
        assertArgvMatchesLegacy(
            config = kolt.testConfig(jvmTarget = "17", sources = listOf("src", "generated")),
            classpath = "/cache/a.jar",
            pluginArgs = emptyList(),
        )
    }

    @Test
    fun matchesLegacyWithMinimalInputs() {
        assertArgvMatchesLegacy(
            config = kolt.testConfig(jvmTarget = "17"),
            classpath = null,
            pluginArgs = emptyList(),
        )
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

// Wording parity: every CompileError case produced by SubprocessCompilerBackend
// must format to the same user-facing string that formatProcessError produced
// pre-refactor. Legacy wording is reproduced here verbatim so a regression is
// caught at test time rather than in user-visible output.
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
        // Free-form variant reserved for DaemonCompilerBackend (socket closed, protocol error etc.).
        // No legacy wording to match — this is daemon-only and formats with a descriptive prefix.
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
        // /bin/true ignores all args and exits 0. Exercises the success path of
        // executeCommand → CompileOutcome mapping without needing a real kotlinc.
        val backend = SubprocessCompilerBackend(kotlincBin = "/bin/true")
        val result = backend.compile(trivialRequest)
        val outcome = assertNotNull(result.get())
        // TODO(#14 S5+): once DaemonCompilerBackend returns populated stdout/stderr,
        // this contract will diverge. Subprocess backend intentionally returns ""
        // because kotlinc inherits our stdout/stderr directly.
        assertEquals("", outcome.stdout)
        assertEquals("", outcome.stderr)
    }

    @Test
    fun compileReturnsCompilationFailedWhenBinaryExitsNonZero() {
        // /bin/false ignores all args and exits 1. This is how a real kotlinc
        // compile error surfaces at the process layer.
        val backend = SubprocessCompilerBackend(kotlincBin = "/bin/false")
        val result = backend.compile(trivialRequest)
        val error = assertNotNull(result.getError())
        val failure = assertIs<CompileError.CompilationFailed>(error)
        assertEquals(1, failure.exitCode)
    }
}
