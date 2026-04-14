package kolt.cli

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kolt.build.CompileError
import kolt.build.CompileOutcome
import kolt.build.CompileRequest
import kolt.build.CompilerBackend
import kolt.build.FallbackCompilerBackend
import kolt.build.daemon.DaemonPreconditionError
import kolt.build.daemon.DaemonSetup
import kolt.config.KoltConfig
import kolt.config.KoltPaths
import kolt.infra.MkdirFailed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Drives every branch of [resolveCompilerBackend] with fake seams.
 * The helper itself contains no I/O, but every production path it
 * touches (precondition resolution, daemon state directory creation,
 * daemon backend construction, warning sink) is exposed as a lambda
 * so the test can observe warning wording and backend identity
 * directly without standing up a real daemon.
 */
class ResolveCompilerBackendTest {

    private val paths = KoltPaths(home = "/fake/home")
    private val config = minimalConfig(kotlincVersion = "2.1.0")
    private val subprocess = SentinelBackend("subprocess")
    private val daemonSentinel = SentinelBackend("daemon")

    private val okSetup = DaemonSetup(
        javaBin = "/fake/java",
        daemonJarPath = "/fake/daemon.jar",
        compilerJars = listOf("/fake/kotlinc/lib/a.jar"),
        btaImplJars = listOf("/fake/libexec/kolt-bta-impl/kotlin-build-tools-impl.jar"),
        daemonDir = "/fake/daemon/dir",
        socketPath = "/fake/daemon/dir/daemon.sock",
        logPath = "/fake/daemon/dir/daemon.log",
    )

    private val absProject = "/fake/project"

    @Test
    fun useDaemonFalseReturnsSubprocessWithoutProbingAnything() {
        val warnings = mutableListOf<String>()
        val backend = resolveCompilerBackend(
            config = config,
            paths = paths,
            subprocessBackend = subprocess,
            useDaemon = false,
            absProjectPath = absProject,
            preconditionResolver = { _, _, _ -> error("must not resolve preconditions when useDaemon=false") },
            daemonDirCreator = { error("must not create daemon dir when useDaemon=false") },
            daemonBackendFactory = { error("must not construct daemon backend when useDaemon=false") },
            warningSink = { warnings.add(it) },
        )

        assertSame(subprocess, backend)
        assertEquals(emptyList(), warnings)
    }

    @Test
    fun preconditionFailureWarnsWithFormattedWordingAndFallsBack() {
        val warnings = mutableListOf<String>()
        val backend = resolveCompilerBackend(
            config = config,
            paths = paths,
            subprocessBackend = subprocess,
            useDaemon = true,
            absProjectPath = absProject,
            preconditionResolver = { _, _, _ ->
                Err(DaemonPreconditionError.DaemonJarMissing)
            },
            daemonDirCreator = { error("must not create daemon dir after precondition failure") },
            daemonBackendFactory = { error("must not construct daemon backend after precondition failure") },
            warningSink = { warnings.add(it) },
        )

        assertSame(subprocess, backend)
        assertEquals(1, warnings.size)
        assertTrue(
            warnings.single().contains("kolt-compiler-daemon jar not found"),
            "unexpected warning: ${warnings.single()}",
        )
    }

    @Test
    fun bootstrapJdkInstallFailureFallsBackWithCauseInWarning() {
        // Binds ADR 0016 §5 "daemon is never load-bearing for
        // correctness" for the auto-install path: a download failure
        // during first-run bootstrap JDK provisioning must produce a
        // one-line warning naming the cause and degrade to the
        // subprocess backend, never propagate as a build failure.
        val warnings = mutableListOf<String>()
        val backend = resolveCompilerBackend(
            config = config,
            paths = paths,
            subprocessBackend = subprocess,
            useDaemon = true,
            absProjectPath = absProject,
            preconditionResolver = { _, _, _ ->
                Err(
                    DaemonPreconditionError.BootstrapJdkInstallFailed(
                        jdkInstallDir = "/fake/home/.kolt/toolchains/jdk/21",
                        cause = "network error downloading jdk 21: connection refused",
                    ),
                )
            },
            daemonDirCreator = { error("must not create daemon dir after precondition failure") },
            daemonBackendFactory = { error("must not construct daemon backend after precondition failure") },
            warningSink = { warnings.add(it) },
        )

        assertSame(subprocess, backend)
        assertEquals(1, warnings.size)
        val warning = warnings.single()
        assertTrue(
            warning.contains("could not install bootstrap JDK at /fake/home/.kolt/toolchains/jdk/21"),
            "warning should name the probed install dir: $warning",
        )
        assertTrue(
            warning.contains("network error downloading jdk 21: connection refused"),
            "warning should carry the underlying cause: $warning",
        )
        assertTrue(
            warning.contains("falling back to subprocess compile"),
            "warning should say the build is falling back: $warning",
        )
    }

    @Test
    fun daemonDirCreationFailureWarnsAndFallsBack() {
        val warnings = mutableListOf<String>()
        val backend = resolveCompilerBackend(
            config = config,
            paths = paths,
            subprocessBackend = subprocess,
            useDaemon = true,
            absProjectPath = absProject,
            preconditionResolver = { _, _, _ -> Ok(okSetup) },
            daemonDirCreator = { Err(MkdirFailed(okSetup.daemonDir)) },
            daemonBackendFactory = { error("must not construct daemon backend after mkdir failure") },
            warningSink = { warnings.add(it) },
        )

        assertSame(subprocess, backend)
        assertEquals(listOf(WARNING_DAEMON_DIR_UNWRITABLE), warnings)
    }

    @Test
    fun happyPathWrapsDaemonPrimaryAndSubprocessFallback() {
        val warnings = mutableListOf<String>()
        var createdFrom: DaemonSetup? = null
        val backend = resolveCompilerBackend(
            config = config,
            paths = paths,
            subprocessBackend = subprocess,
            useDaemon = true,
            absProjectPath = absProject,
            preconditionResolver = { _, kotlincVersion, cwd ->
                assertEquals("2.1.0", kotlincVersion)
                assertEquals(absProject, cwd)
                Ok(okSetup)
            },
            daemonDirCreator = { dir ->
                assertEquals(okSetup.daemonDir, dir)
                Ok(Unit)
            },
            daemonBackendFactory = { setup ->
                createdFrom = setup
                daemonSentinel
            },
            warningSink = { warnings.add(it) },
        )

        assertEquals(emptyList(), warnings)
        val fallback = assertNotNull(backend as? FallbackCompilerBackend)
        // Tightness: asserting `as FallbackCompilerBackend` alone
        // would still pass if the factory were called but its
        // return value thrown away and primary wired to something
        // else. The identity checks below pin the wiring.
        assertSame(daemonSentinel, fallback.primary)
        assertSame(subprocess, fallback.fallback)
        assertEquals(okSetup, createdFrom)
    }

    private class SentinelBackend(val tag: String) : CompilerBackend {
        override fun compile(request: CompileRequest): Result<CompileOutcome, CompileError> =
            Err(CompileError.InternalMisuse("sentinel:$tag"))
    }

    private fun minimalConfig(kotlincVersion: String) = KoltConfig(
        name = "it",
        version = "0.0.0",
        kotlin = kotlincVersion,
        target = "jvm",
        main = "itMainKt",
        sources = emptyList(),
    )
}
