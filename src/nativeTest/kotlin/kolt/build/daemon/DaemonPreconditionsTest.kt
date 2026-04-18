package kolt.build.daemon

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kolt.config.KoltPaths
import kolt.infra.DownloadError
import kolt.resolve.ResolveError
import kolt.tool.ToolchainError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DaemonPreconditionsTest {

    private val paths = KoltPaths(home = "/fake/home")
    private val bundledVersion = "2.3.20"
    private val absProject = "/fake/project"
    private val okJar = DaemonJarResolution.Resolved("/fake/libexec/daemon.jar", DaemonJarResolution.Source.Libexec)
    private val fakeJars = listOf("/fake/home/.kolt/toolchains/kotlinc/$bundledVersion/lib/a.jar")
    private val fakeBtaJars = listOf("/fake/libexec/kolt-bta-impl/kotlin-build-tools-impl.jar")
    private val okBtaImpl = BtaImplJarsResolution.Resolved(fakeBtaJars, BtaImplJarsResolution.Source.Libexec)
    private val mustNotFetch: (String, String) -> Nothing = { _, _ ->
        error("must not call fetcher when bundled libexec is in use")
    }

    @Test
    fun resolveReturnsCompleteSetupWhenAllInputsPresent() {
        val result = resolveDaemonPreconditions(
            paths = paths,
            kotlincVersion = bundledVersion,
            absProjectPath = absProject,
            bundledKotlinVersion = bundledVersion,
            ensureJavaBin = { Ok("/fake/home/.kolt/toolchains/jdk/21/bin/java") },
            resolveDaemonJar = { okJar },
            listCompilerJars = { fakeJars },
            resolveBundledBtaImplJars = { okBtaImpl },
            fetchBtaImplJars = mustNotFetch,
        )

        val setup = assertNotNull(result.get())
        assertEquals("/fake/home/.kolt/toolchains/jdk/21/bin/java", setup.javaBin)
        assertEquals("/fake/libexec/daemon.jar", setup.daemonJarPath)
        assertEquals(fakeJars, setup.compilerJars)
        assertEquals(fakeBtaJars, setup.btaImplJars)
        val expectedHash = projectHashOf(absProject)
        assertEquals("/fake/home/.kolt/daemon/$expectedHash/$bundledVersion", setup.daemonDir)
        assertEquals("/fake/home/.kolt/daemon/$expectedHash/$bundledVersion/daemon.sock", setup.socketPath)
        assertEquals("/fake/home/.kolt/daemon/$expectedHash/$bundledVersion/daemon.log", setup.logPath)
    }

    @Test
    fun versionBelowFloorShortCircuitsBeforeAnyProbing() {
        val result = resolveDaemonPreconditions(
            paths = paths,
            kotlincVersion = "2.2.20",
            absProjectPath = absProject,
            bundledKotlinVersion = bundledVersion,
            ensureJavaBin = { error("must not probe JDK below floor") },
            resolveDaemonJar = { error("must not probe daemon jar below floor") },
            listCompilerJars = { error("must not list compiler jars below floor") },
            resolveBundledBtaImplJars = { error("must not probe BTA-impl jars below floor") },
            fetchBtaImplJars = mustNotFetch,
        )

        val err = assertIs<DaemonPreconditionError.KotlinVersionBelowFloor>(result.getError())
        assertEquals("2.2.20", err.requested)
        assertEquals(KOTLIN_VERSION_FLOOR, err.floor)
    }

    @Test
    fun pluginsRequestedBelow2_3_20ShortCircuitsBeforeAnyProbing() {
        val result = resolveDaemonPreconditions(
            paths = paths,
            kotlincVersion = "2.3.10",
            absProjectPath = absProject,
            bundledKotlinVersion = bundledVersion,
            pluginsRequested = true,
            ensureJavaBin = { error("must not probe JDK when plugins gate fails") },
            resolveDaemonJar = { error("must not probe daemon jar when plugins gate fails") },
            listCompilerJars = { error("must not list compiler jars when plugins gate fails") },
            resolveBundledBtaImplJars = { error("must not probe BTA-impl jars when plugins gate fails") },
            fetchBtaImplJars = mustNotFetch,
        )

        val err = assertIs<DaemonPreconditionError.PluginsRequireMinKotlinVersion>(result.getError())
        assertEquals("2.3.10", err.requested)
        assertEquals(KOTLIN_VERSION_FOR_PLUGINS, err.minVersion)
    }

    @Test
    fun pluginsRequestedAtMinVersionIsAccepted() {
        val result = resolveDaemonPreconditions(
            paths = paths,
            kotlincVersion = KOTLIN_VERSION_FOR_PLUGINS,
            absProjectPath = absProject,
            bundledKotlinVersion = KOTLIN_VERSION_FOR_PLUGINS,
            pluginsRequested = true,
            ensureJavaBin = { Ok("/fake/java") },
            resolveDaemonJar = { okJar },
            listCompilerJars = { fakeJars },
            resolveBundledBtaImplJars = { okBtaImpl },
            fetchBtaImplJars = mustNotFetch,
        )
        assertNotNull(result.get())
    }

    @Test
    fun pluginsNotRequestedBelow2_3_20IsAcceptedThroughFetcher() {
        var fetchedVersion: String? = null
        val result = resolveDaemonPreconditions(
            paths = paths,
            kotlincVersion = "2.3.10",
            absProjectPath = absProject,
            bundledKotlinVersion = bundledVersion,
            pluginsRequested = false,
            ensureJavaBin = { Ok("/fake/java") },
            resolveDaemonJar = { okJar },
            listCompilerJars = { fakeJars },
            resolveBundledBtaImplJars = { error("must not use libexec for non-bundled version") },
            fetchBtaImplJars = { v, _ ->
                fetchedVersion = v
                Ok(listOf("/cache/impl-$v.jar"))
            },
        )
        assertNotNull(result.get())
        assertEquals("2.3.10", fetchedVersion)
    }

    @Test
    fun versionAtFloorIsAccepted() {
        var fetchedVersion: String? = null
        val result = resolveDaemonPreconditions(
            paths = paths,
            kotlincVersion = KOTLIN_VERSION_FLOOR,
            absProjectPath = absProject,
            bundledKotlinVersion = bundledVersion,
            ensureJavaBin = { Ok("/fake/java") },
            resolveDaemonJar = { okJar },
            listCompilerJars = { fakeJars },
            resolveBundledBtaImplJars = { error("must not use libexec for non-bundled version") },
            fetchBtaImplJars = { v, _ ->
                fetchedVersion = v
                Ok(listOf("/cache/impl-$v.jar"))
            },
        )

        assertNotNull(result.get())
        assertEquals(KOTLIN_VERSION_FLOOR, fetchedVersion)
    }

    @Test
    fun nonBundledVersionGoesThroughFetcher() {
        val fetched = listOf("/cache/impl-2.3.10.jar", "/cache/api-2.3.10.jar")
        val result = resolveDaemonPreconditions(
            paths = paths,
            kotlincVersion = "2.3.10",
            absProjectPath = absProject,
            bundledKotlinVersion = bundledVersion,
            ensureJavaBin = { Ok("/fake/java") },
            resolveDaemonJar = { okJar },
            listCompilerJars = { fakeJars },
            resolveBundledBtaImplJars = { error("must not call libexec resolver when version != bundled") },
            fetchBtaImplJars = { v, base ->
                assertEquals("2.3.10", v)
                assertEquals(paths.cacheBase, base)
                Ok(fetched)
            },
        )

        val setup = assertNotNull(result.get())
        assertEquals(fetched, setup.btaImplJars)
    }

    @Test
    fun fetcherFailureMapsToBtaImplFetchFailed() {
        val cause = BtaImplFetchError.ResolveFailed(
            "2.3.10",
            ResolveError.DownloadFailed(
                "org.jetbrains.kotlin:kotlin-build-tools-impl",
                DownloadError.HttpFailed("http://example", 503),
            ),
        )
        val result = resolveDaemonPreconditions(
            paths = paths,
            kotlincVersion = "2.3.10",
            absProjectPath = absProject,
            bundledKotlinVersion = bundledVersion,
            ensureJavaBin = { Ok("/fake/java") },
            resolveDaemonJar = { okJar },
            listCompilerJars = { fakeJars },
            resolveBundledBtaImplJars = { error("must not call libexec resolver when version != bundled") },
            fetchBtaImplJars = { _, _ -> Err(cause) },
        )

        val err = assertIs<DaemonPreconditionError.BtaImplFetchFailed>(result.getError())
        assertEquals("2.3.10", err.version)
        assertEquals(cause, err.cause)
    }

    @Test
    fun bootstrapJdkInstallFailedShortCircuits() {
        val installDir = "/fake/home/.kolt/toolchains/jdk/$BOOTSTRAP_JDK_VERSION"
        val result = resolveDaemonPreconditions(
            paths = paths,
            kotlincVersion = bundledVersion,
            absProjectPath = absProject,
            bundledKotlinVersion = bundledVersion,
            ensureJavaBin = {
                Err(BootstrapJdkError(installDir, ToolchainError("network error downloading jdk 21: connection refused")))
            },
            resolveDaemonJar = { error("must not run after BootstrapJdkInstallFailed") },
            listCompilerJars = { error("must not run after BootstrapJdkInstallFailed") },
            resolveBundledBtaImplJars = { error("must not run after BootstrapJdkInstallFailed") },
            fetchBtaImplJars = mustNotFetch,
        )

        assertNull(result.get())
        val err = assertIs<DaemonPreconditionError.BootstrapJdkInstallFailed>(result.getError())
        assertEquals(installDir, err.jdkInstallDir)
        assertEquals("network error downloading jdk 21: connection refused", err.cause)
    }

    @Test
    fun daemonJarMissingShortCircuits() {
        val result = resolveDaemonPreconditions(
            paths = paths,
            kotlincVersion = bundledVersion,
            absProjectPath = absProject,
            bundledKotlinVersion = bundledVersion,
            ensureJavaBin = { Ok("/fake/java") },
            resolveDaemonJar = { DaemonJarResolution.NotFound },
            listCompilerJars = { error("must not run after DaemonJarMissing") },
            resolveBundledBtaImplJars = { error("must not run after DaemonJarMissing") },
            fetchBtaImplJars = mustNotFetch,
        )

        assertEquals(DaemonPreconditionError.DaemonJarMissing, result.getError())
    }

    @Test
    fun compilerJarsMissingWhenDirAbsent() {
        val result = resolveDaemonPreconditions(
            paths = paths,
            kotlincVersion = bundledVersion,
            absProjectPath = absProject,
            bundledKotlinVersion = bundledVersion,
            ensureJavaBin = { Ok("/fake/java") },
            resolveDaemonJar = { okJar },
            listCompilerJars = { null },
            resolveBundledBtaImplJars = { error("must not run after CompilerJarsMissing") },
            fetchBtaImplJars = mustNotFetch,
        )

        val err = assertIs<DaemonPreconditionError.CompilerJarsMissing>(result.getError())
        assertEquals("/fake/home/.kolt/toolchains/kotlinc/$bundledVersion/lib", err.kotlincLibDir)
    }

    @Test
    fun compilerJarsMissingWhenDirEmpty() {
        val result = resolveDaemonPreconditions(
            paths = paths,
            kotlincVersion = bundledVersion,
            absProjectPath = absProject,
            bundledKotlinVersion = bundledVersion,
            ensureJavaBin = { Ok("/fake/java") },
            resolveDaemonJar = { okJar },
            listCompilerJars = { emptyList() },
            resolveBundledBtaImplJars = { error("must not run after CompilerJarsMissing") },
            fetchBtaImplJars = mustNotFetch,
        )

        assertIs<DaemonPreconditionError.CompilerJarsMissing>(result.getError())
    }

    @Test
    fun bundledBtaImplJarsMissingShortCircuitsAfterCompilerJarsPass() {
        val result = resolveDaemonPreconditions(
            paths = paths,
            kotlincVersion = bundledVersion,
            absProjectPath = absProject,
            bundledKotlinVersion = bundledVersion,
            ensureJavaBin = { Ok("/fake/java") },
            resolveDaemonJar = { okJar },
            listCompilerJars = { fakeJars },
            resolveBundledBtaImplJars = { BtaImplJarsResolution.NotFound("/fake/libexec/kolt-bta-impl") },
            fetchBtaImplJars = mustNotFetch,
        )

        val err = assertIs<DaemonPreconditionError.BtaImplJarsMissing>(result.getError())
        assertEquals("/fake/libexec/kolt-bta-impl", err.probedDir)
    }

    @Test
    fun warningWordingCoversAllVariants() {
        assertEquals(
            "warning: could not install bootstrap JDK at /opt/jdks/21 (HTTP 503) — falling back to subprocess compile",
            formatDaemonPreconditionWarning(
                DaemonPreconditionError.BootstrapJdkInstallFailed("/opt/jdks/21", "HTTP 503"),
            ),
        )
        assertEquals(
            "warning: kolt-compiler-daemon jar not found — falling back to subprocess compile",
            formatDaemonPreconditionWarning(DaemonPreconditionError.DaemonJarMissing),
        )
        assertEquals(
            "warning: no compiler jars found in /x/lib — falling back to subprocess compile",
            formatDaemonPreconditionWarning(DaemonPreconditionError.CompilerJarsMissing("/x/lib")),
        )
        assertEquals(
            "warning: kotlin-build-tools-impl jars not found in /x/libexec/kolt-bta-impl — falling back to subprocess compile",
            formatDaemonPreconditionWarning(
                DaemonPreconditionError.BtaImplJarsMissing("/x/libexec/kolt-bta-impl"),
            ),
        )
        val belowFloor = formatDaemonPreconditionWarning(
            DaemonPreconditionError.KotlinVersionBelowFloor(requested = "2.2.20", floor = "2.3.0"),
        )
        assertTrue(
            belowFloor.contains("2.2.20") &&
                belowFloor.contains("2.3.0") &&
                belowFloor.contains("falling back to subprocess compile") &&
                belowFloor.contains("--no-daemon"),
            "unexpected below-floor warning: $belowFloor",
        )
        val fetchFailed = formatDaemonPreconditionWarning(
            DaemonPreconditionError.BtaImplFetchFailed(
                version = "2.3.10",
                cause = BtaImplFetchError.ResolveFailed(
                    "2.3.10",
                    ResolveError.DownloadFailed(
                        "org.jetbrains.kotlin:kotlin-build-tools-impl",
                        DownloadError.HttpFailed("http://example", 503),
                    ),
                ),
            ),
        )
        assertTrue(
            fetchFailed.contains("2.3.10") &&
                fetchFailed.contains("falling back to subprocess compile"),
            "unexpected fetch-failed warning: $fetchFailed",
        )
        val resolvedEmpty = formatDaemonPreconditionWarning(
            DaemonPreconditionError.BtaImplFetchFailed(
                version = "2.3.10",
                cause = BtaImplFetchError.ResolvedEmpty("2.3.10"),
            ),
        )
        assertTrue(
            resolvedEmpty.contains("2.3.10") &&
                resolvedEmpty.contains("resolver returned no jars") &&
                resolvedEmpty.contains("falling back to subprocess compile"),
            "unexpected resolved-empty warning: $resolvedEmpty",
        )
        val pluginsGate = formatDaemonPreconditionWarning(
            DaemonPreconditionError.PluginsRequireMinKotlinVersion(
                requested = "2.3.10",
                minVersion = "2.3.20",
            ),
        )
        assertTrue(
            pluginsGate.contains("2.3.10") &&
                pluginsGate.contains("2.3.20") &&
                pluginsGate.contains("compiler plugins") &&
                pluginsGate.contains("falling back to subprocess compile") &&
                pluginsGate.contains("--no-daemon"),
            "unexpected plugins-gate warning: $pluginsGate",
        )
    }
}
