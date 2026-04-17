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
            preconditionResolver = { _, _, _, _ -> error("must not resolve preconditions when useDaemon=false") },
            daemonDirCreator = { error("must not create daemon dir when useDaemon=false") },
            daemonBackendFactory = { _, _ -> error("must not construct daemon backend when useDaemon=false") },
            warningSink = { warnings.add(it) },
        )

        assertSame(subprocess, backend)
        assertEquals(emptyList(), warnings)
    }

    // #136 stop-gap: bundled-vs-requested Kotlin version mismatch enters the
    // same "precondition error" warning rail as every other daemon-probe
    // failure (ADR 0016 §5). DaemonPreconditionsTest covers that the real
    // resolver surfaces this error without any probing; this test pins that
    // resolveCompilerBackend routes the variant into the warning sink.
    @Test
    fun kotlinVersionMismatchFromResolverFallsBackWithVersionsAndFollowUpInWarning() {
        val warnings = mutableListOf<String>()
        val backend = resolveCompilerBackend(
            config = minimalConfig(kotlincVersion = "2.1.0"),
            paths = paths,
            subprocessBackend = subprocess,
            useDaemon = true,
            absProjectPath = absProject,
            bundledKotlinVersion = "2.3.20",
            preconditionResolver = { _, requested, _, bundled ->
                Err(
                    DaemonPreconditionError.KotlinVersionMismatch(
                        requested = requested,
                        bundled = bundled,
                    ),
                )
            },
            daemonDirCreator = { error("must not create daemon dir after precondition failure") },
            daemonBackendFactory = { _, _ -> error("must not construct daemon backend after precondition failure") },
            warningSink = { warnings.add(it) },
        )

        assertSame(subprocess, backend)
        val warning = warnings.single()
        assertTrue(warning.contains("2.1.0"), "warning should cite the requested version: $warning")
        assertTrue(warning.contains("2.3.20"), "warning should cite the bundled daemon version: $warning")
        assertTrue(
            warning.contains("falling back to subprocess compile"),
            "warning should say the build is falling back: $warning",
        )
        assertTrue(warning.contains("temporary"), "warning should flag the restriction as temporary: $warning")
    }

    @Test
    fun bundledKotlinVersionIsPassedThroughToPreconditionResolver() {
        var seenBundled: String? = null
        val backend = resolveCompilerBackend(
            config = minimalConfig(kotlincVersion = "2.3.20"),
            paths = paths,
            subprocessBackend = subprocess,
            useDaemon = true,
            absProjectPath = absProject,
            bundledKotlinVersion = "2.3.20",
            preconditionResolver = { _, _, _, bundled ->
                seenBundled = bundled
                Ok(okSetup)
            },
            daemonDirCreator = { Ok(Unit) },
            daemonBackendFactory = { _, _ -> daemonSentinel },
            warningSink = { error("happy path must not warn") },
        )

        assertEquals("2.3.20", seenBundled)
        assertNotNull(backend as? FallbackCompilerBackend)
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
            preconditionResolver = { _, _, _, _ ->
                Err(DaemonPreconditionError.DaemonJarMissing)
            },
            daemonDirCreator = { error("must not create daemon dir after precondition failure") },
            daemonBackendFactory = { _, _ -> error("must not construct daemon backend after precondition failure") },
            warningSink = { warnings.add(it) },
        )

        assertSame(subprocess, backend)
        assertEquals(1, warnings.size)
        assertTrue(
            warnings.single().contains("kolt-compiler-daemon jar not found"),
            "unexpected warning: ${warnings.single()}",
        )
    }

    // ADR 0016 §5: daemon is never load-bearing for correctness.
    @Test
    fun bootstrapJdkInstallFailureFallsBackWithCauseInWarning() {
        val warnings = mutableListOf<String>()
        val backend = resolveCompilerBackend(
            config = config,
            paths = paths,
            subprocessBackend = subprocess,
            useDaemon = true,
            absProjectPath = absProject,
            preconditionResolver = { _, _, _, _ ->
                Err(
                    DaemonPreconditionError.BootstrapJdkInstallFailed(
                        jdkInstallDir = "/fake/home/.kolt/toolchains/jdk/21",
                        cause = "network error downloading jdk 21: connection refused",
                    ),
                )
            },
            daemonDirCreator = { error("must not create daemon dir after precondition failure") },
            daemonBackendFactory = { _, _ -> error("must not construct daemon backend after precondition failure") },
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
            preconditionResolver = { _, _, _, _ -> Ok(okSetup) },
            daemonDirCreator = { Err(MkdirFailed(okSetup.daemonDir)) },
            daemonBackendFactory = { _, _ -> error("must not construct daemon backend after mkdir failure") },
            warningSink = { warnings.add(it) },
        )

        assertSame(subprocess, backend)
        assertEquals(listOf(WARNING_DAEMON_DIR_UNWRITABLE), warnings)
    }

    @Test
    fun pluginJarsArgumentIsForwardedToDaemonBackendFactory() {
        val warnings = mutableListOf<String>()
        var capturedPluginJars: Map<String, List<String>>? = null
        val pluginJars = mapOf(
            "serialization" to listOf("/fake/kotlinc/lib/kotlinx-serialization-compiler-plugin.jar"),
        )
        val backend = resolveCompilerBackend(
            config = config,
            paths = paths,
            subprocessBackend = subprocess,
            useDaemon = true,
            absProjectPath = absProject,
            pluginJars = pluginJars,
            preconditionResolver = { _, _, _, _ -> Ok(okSetup) },
            daemonDirCreator = { Ok(Unit) },
            daemonBackendFactory = { _, jars ->
                capturedPluginJars = jars
                daemonSentinel
            },
            warningSink = { warnings.add(it) },
        )

        assertEquals(emptyList(), warnings)
        assertNotNull(backend as? FallbackCompilerBackend)
        assertEquals(pluginJars, capturedPluginJars)
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
            preconditionResolver = { _, kotlincVersion, cwd, _ ->
                assertEquals("2.1.0", kotlincVersion)
                assertEquals(absProject, cwd)
                Ok(okSetup)
            },
            daemonDirCreator = { dir ->
                assertEquals(okSetup.daemonDir, dir)
                Ok(Unit)
            },
            daemonBackendFactory = { setup, _ ->
                createdFrom = setup
                daemonSentinel
            },
            warningSink = { warnings.add(it) },
        )

        assertEquals(emptyList(), warnings)
        val fallback = assertNotNull(backend as? FallbackCompilerBackend)
        assertSame(daemonSentinel, fallback.primary)
        assertSame(subprocess, fallback.fallback)
        assertEquals(okSetup, createdFrom)
    }

    @Test
    fun pluginsFingerprintIsStableForSamePluginMap() {
        val a = pluginsFingerprint(mapOf("serialization" to listOf("/k/lib/ser.jar")))
        val b = pluginsFingerprint(mapOf("serialization" to listOf("/k/lib/ser.jar")))
        assertEquals(a, b)
    }

    @Test
    fun pluginsFingerprintIsOrderInsensitiveOnAliases() {
        val a = pluginsFingerprint(linkedMapOf("a" to listOf("/x"), "b" to listOf("/y")))
        val b = pluginsFingerprint(linkedMapOf("b" to listOf("/y"), "a" to listOf("/x")))
        assertEquals(a, b)
    }

    @Test
    fun pluginsFingerprintChangesWhenAClasspathChanges() {
        val a = pluginsFingerprint(mapOf("serialization" to listOf("/k/lib/ser-2.0.jar")))
        val b = pluginsFingerprint(mapOf("serialization" to listOf("/k/lib/ser-2.1.jar")))
        assertTrue(a != b, "version bump should change fingerprint, both=$a")
    }

    @Test
    fun pluginsFingerprintEmptyMapHasFixedMarker() {
        assertEquals("noplugins", pluginsFingerprint(emptyMap()))
    }

    @Test
    fun applyPluginsFingerprintInsertsBeforeExtension() {
        assertEquals(
            "/fake/daemon/dir/daemon-abcd1234.sock",
            applyPluginsFingerprintToFile("/fake/daemon/dir/daemon.sock", "abcd1234"),
        )
        assertEquals(
            "/fake/daemon/dir/daemon-abcd1234.log",
            applyPluginsFingerprintToFile("/fake/daemon/dir/daemon.log", "abcd1234"),
        )
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
