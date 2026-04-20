package kolt.cli

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kolt.build.FallbackNativeCompilerBackend
import kolt.build.NativeCompileError
import kolt.build.NativeCompileOutcome
import kolt.build.NativeCompilerBackend
import kolt.build.nativedaemon.NATIVE_KOTLIN_VERSION_FLOOR
import kolt.build.nativedaemon.NativeDaemonPreconditionError
import kolt.build.nativedaemon.NativeDaemonSetup
import kolt.config.BuildSection
import kolt.config.KoltConfig
import kolt.config.KoltPaths
import kolt.config.KotlinSection
import kolt.infra.MkdirFailed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ResolveNativeCompilerBackendTest {

    private val paths = KoltPaths(home = "/fake/home")
    private val config = minimalConfig(kotlincVersion = "2.3.20")
    private val subprocess = SentinelNativeBackend("subprocess")
    private val daemonSentinel = SentinelNativeBackend("daemon")

    private val okSetup = NativeDaemonSetup(
        javaBin = "/fake/java",
        daemonJarPath = "/fake/kolt-native-daemon-all.jar",
        konancJar = "/fake/konanc/konan/lib/kotlin-native-compiler-embeddable.jar",
        konanHome = "/fake/konanc",
        daemonDir = "/fake/daemon/dir",
        socketPath = "/fake/daemon/dir/native-daemon.sock",
        logPath = "/fake/daemon/dir/native-daemon.log",
    )

    private val absProject = "/fake/project"

    @Test
    fun useDaemonFalseReturnsSubprocessWithoutProbingAnything() {
        val warnings = mutableListOf<String>()
        val backend = resolveNativeCompilerBackend(
            config = config,
            paths = paths,
            subprocessBackend = subprocess,
            useDaemon = false,
            absProjectPath = absProject,
            preconditionResolver = { _, _, _ -> error("must not probe preconditions when useDaemon=false") },
            daemonDirCreator = { error("must not create daemon dir when useDaemon=false") },
            daemonBackendFactory = { error("must not construct daemon backend when useDaemon=false") },
            warningSink = { warnings.add(it) },
        )

        assertSame(subprocess, backend)
        assertEquals(emptyList(), warnings)
    }

    @Test
    fun kotlinVersionBelowFloorFallsBackWithVersionsInWarning() {
        val warnings = mutableListOf<String>()
        val backend = resolveNativeCompilerBackend(
            config = minimalConfig(kotlincVersion = "2.1.0"),
            paths = paths,
            subprocessBackend = subprocess,
            useDaemon = true,
            absProjectPath = absProject,
            preconditionResolver = { _, requested, _ ->
                Err(
                    NativeDaemonPreconditionError.KotlinVersionBelowFloor(
                        requested = requested,
                        floor = NATIVE_KOTLIN_VERSION_FLOOR,
                    ),
                )
            },
            daemonDirCreator = { error("must not create daemon dir when precondition failed") },
            daemonBackendFactory = { error("must not construct daemon backend when precondition failed") },
            warningSink = { warnings.add(it) },
        )

        assertSame(subprocess, backend)
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("2.3.0"), "warning should cite the floor")
        assertTrue(warnings.single().contains("2.1.0"), "warning should cite the requested version")
    }

    @Test
    fun nativeDaemonJarMissingFallsBack() {
        val warnings = mutableListOf<String>()
        val backend = resolveNativeCompilerBackend(
            config = config,
            paths = paths,
            subprocessBackend = subprocess,
            useDaemon = true,
            absProjectPath = absProject,
            preconditionResolver = { _, _, _ ->
                Err(NativeDaemonPreconditionError.NativeDaemonJarMissing)
            },
            daemonDirCreator = { error("must not create daemon dir when precondition failed") },
            daemonBackendFactory = { error("must not construct daemon backend when precondition failed") },
            warningSink = { warnings.add(it) },
        )

        assertSame(subprocess, backend)
        assertTrue(warnings.single().contains("kolt-native-daemon"))
    }

    @Test
    fun daemonDirCreationFailureWarnsAndFallsBack() {
        val warnings = mutableListOf<String>()
        val backend = resolveNativeCompilerBackend(
            config = config,
            paths = paths,
            subprocessBackend = subprocess,
            useDaemon = true,
            absProjectPath = absProject,
            preconditionResolver = { _, _, _ -> Ok(okSetup) },
            daemonDirCreator = { Err(MkdirFailed(it)) },
            daemonBackendFactory = { error("must not construct daemon backend when mkdir failed") },
            warningSink = { warnings.add(it) },
        )

        assertSame(subprocess, backend)
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("daemon state directory"))
    }

    @Test
    fun happyPathWrapsDaemonPrimaryAndSubprocessFallback() {
        val warnings = mutableListOf<String>()
        var createdFrom: NativeDaemonSetup? = null
        val backend = resolveNativeCompilerBackend(
            config = config,
            paths = paths,
            subprocessBackend = subprocess,
            useDaemon = true,
            absProjectPath = absProject,
            preconditionResolver = { _, kotlincVersion, cwd ->
                assertEquals("2.3.20", kotlincVersion)
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
        val fallback = assertNotNull(backend as? FallbackNativeCompilerBackend)
        assertSame(daemonSentinel, fallback.primary)
        assertSame(subprocess, fallback.fallback)
        assertEquals(okSetup, createdFrom)
    }

    private class SentinelNativeBackend(val tag: String) : NativeCompilerBackend {
        override fun compile(args: List<String>): Result<NativeCompileOutcome, NativeCompileError> =
            Err(NativeCompileError.InternalMisuse("sentinel:$tag"))
    }

    private fun minimalConfig(kotlincVersion: String) = KoltConfig(
        name = "it",
        version = "0.0.0",
        kotlin = KotlinSection(version = kotlincVersion),
        build = BuildSection(
            target = "linuxX64",
            main = "itMainKt",
            sources = emptyList(),
        ),
    )
}
