package kolt.build.daemon

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kolt.config.KoltPaths
import kolt.infra.DownloadError
import kolt.resolve.RepositoryAttempt
import kolt.resolve.RepositoryDownloadFailure
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
  private val fakeLaunchArgs = listOf("-cp", "/fake/libexec/daemon.jar", DAEMON_MAIN_CLASS)
  private val okJar =
    DaemonJarResolution.Resolved(fakeLaunchArgs, DaemonJarResolution.Source.Libexec)
  private val fakeJars = listOf("/fake/home/.kolt/toolchains/kotlinc/$bundledVersion/lib/a.jar")
  private val fakeBtaJars = listOf("/fake/libexec/kolt-bta-impl/kotlin-build-tools-impl.jar")
  private val okBtaImpl =
    BtaImplJarsResolution.Resolved(fakeBtaJars, BtaImplJarsResolution.Source.Libexec)
  private val mustNotFetch: (String, String) -> Nothing = { _, _ ->
    error("must not call fetcher when bundled libexec is in use")
  }

  @Test
  fun resolveReturnsCompleteSetupWhenAllInputsPresent() {
    val result =
      resolveDaemonPreconditions(
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
    assertEquals(fakeLaunchArgs, setup.daemonLaunchArgs)
    assertEquals(fakeJars, setup.compilerJars)
    assertEquals(fakeBtaJars, setup.btaImplJars)
    val expectedHash = projectHashOf(absProject)
    assertEquals("/fake/home/.kolt/daemon/$expectedHash/$bundledVersion", setup.daemonDir)
    assertEquals(
      "/fake/home/.kolt/daemon/$expectedHash/$bundledVersion/jvm-compiler-daemon.sock",
      setup.socketPath,
    )
    assertEquals(
      "/fake/home/.kolt/daemon/$expectedHash/$bundledVersion/jvm-compiler-daemon.log",
      setup.logPath,
    )
  }

  @Test
  fun versionBelowFloorShortCircuitsBeforeAnyProbing() {
    val result =
      resolveDaemonPreconditions(
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

  // #146: Linux `sun_path` tops out at 108 bytes. A deep `$HOME` (CI runner,
  // NFS mount) can push the projected socket path past the cap before
  // any daemon spawn. The precondition check catches this upfront so the
  // fallback rail handles it instead of surfacing as `bind() ENAMETOOLONG`.
  @Test
  fun socketPathAboveSunPathCapShortCircuits() {
    // Synthesise a `$HOME` that, after the full
    // `<home>/.kolt/daemon/<16hex>/<version>/daemon-noplugins.sock`
    // build-up, exceeds 108 bytes. The tail for `2.3.20` is 59 bytes; so any
    // `$HOME` of 50+ chars reliably overflows.
    val longHome = "/home/" + "x".repeat(96)
    val longPaths = KoltPaths(home = longHome)
    val result =
      resolveDaemonPreconditions(
        paths = longPaths,
        kotlincVersion = bundledVersion,
        absProjectPath = absProject,
        bundledKotlinVersion = bundledVersion,
        ensureJavaBin = { error("must not probe JDK when socket path exceeds sun_path") },
        resolveDaemonJar = { error("must not probe daemon jar when socket path exceeds sun_path") },
        listCompilerJars = {
          error("must not list compiler jars when socket path exceeds sun_path")
        },
        resolveBundledBtaImplJars = {
          error("must not probe BTA-impl jars when socket path exceeds sun_path")
        },
        fetchBtaImplJars = mustNotFetch,
      )

    val err = assertIs<DaemonPreconditionError.SocketPathTooLong>(result.getError())
    assertEquals(SUN_PATH_CAPACITY, err.maxBytes)
    assertTrue(err.projectedBytes > SUN_PATH_CAPACITY, "projected should exceed cap: $err")
    assertTrue(
      err.socketPath.startsWith(longHome),
      "socket path should be rooted at home: ${err.socketPath}",
    )
  }

  @Test
  fun socketPathInsideSunPathCapPasses() {
    // `/home/makino` + typical 16-hex hash + `2.3.20` + `daemon-noplugins.sock`
    // ≈ 71 bytes; well under the 108-byte cap.
    val result =
      resolveDaemonPreconditions(
        paths = paths,
        kotlincVersion = bundledVersion,
        absProjectPath = absProject,
        bundledKotlinVersion = bundledVersion,
        ensureJavaBin = { Ok("/fake/java") },
        resolveDaemonJar = { okJar },
        listCompilerJars = { fakeJars },
        resolveBundledBtaImplJars = { okBtaImpl },
        fetchBtaImplJars = mustNotFetch,
      )
    assertNotNull(result.get())
  }

  // Post-#148: `[plugins]` on the floor (2.3.0) is accepted through the
  // fetcher — the daemon routes plugins via `-Xplugin=` passthrough which
  // works across the full 2.3.x family, so there is no version carve-out
  // for plugin-using projects inside the family.
  @Test
  fun pluginsAtFloorIsAcceptedThroughFetcher() {
    var fetchedVersion: String? = null
    val result =
      resolveDaemonPreconditions(
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
  fun versionAtFloorIsAccepted() {
    var fetchedVersion: String? = null
    val result =
      resolveDaemonPreconditions(
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
    val result =
      resolveDaemonPreconditions(
        paths = paths,
        kotlincVersion = "2.3.10",
        absProjectPath = absProject,
        bundledKotlinVersion = bundledVersion,
        ensureJavaBin = { Ok("/fake/java") },
        resolveDaemonJar = { okJar },
        listCompilerJars = { fakeJars },
        resolveBundledBtaImplJars = {
          error("must not call libexec resolver when version != bundled")
        },
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
    val cause =
      BtaImplFetchError.ResolveFailed(
        "2.3.10",
        ResolveError.DownloadFailed(
          "org.jetbrains.kotlin:kotlin-build-tools-impl",
          RepositoryDownloadFailure.AllAttemptsFailed(
            listOf(
              RepositoryAttempt("http://example", DownloadError.HttpFailed("http://example", 503))
            )
          ),
        ),
      )
    val result =
      resolveDaemonPreconditions(
        paths = paths,
        kotlincVersion = "2.3.10",
        absProjectPath = absProject,
        bundledKotlinVersion = bundledVersion,
        ensureJavaBin = { Ok("/fake/java") },
        resolveDaemonJar = { okJar },
        listCompilerJars = { fakeJars },
        resolveBundledBtaImplJars = {
          error("must not call libexec resolver when version != bundled")
        },
        fetchBtaImplJars = { _, _ -> Err(cause) },
      )

    val err = assertIs<DaemonPreconditionError.BtaImplFetchFailed>(result.getError())
    assertEquals("2.3.10", err.version)
    assertEquals(cause, err.cause)
  }

  @Test
  fun bootstrapJdkInstallFailedShortCircuits() {
    val installDir = "/fake/home/.kolt/toolchains/jdk/$BOOTSTRAP_JDK_VERSION"
    val result =
      resolveDaemonPreconditions(
        paths = paths,
        kotlincVersion = bundledVersion,
        absProjectPath = absProject,
        bundledKotlinVersion = bundledVersion,
        ensureJavaBin = {
          Err(
            BootstrapJdkError(
              installDir,
              ToolchainError("network error downloading jdk 21: connection refused"),
            )
          )
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
    val result =
      resolveDaemonPreconditions(
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
    val result =
      resolveDaemonPreconditions(
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
    val result =
      resolveDaemonPreconditions(
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
  fun bundledBtaImplLibexecMissFallsThroughToFetcher() {
    // Without a DevFallback in BtaImplJarResolver (dev binaries have no
    // libexec), the bundled-version path must fetch from Maven Central on
    // a libexec miss — otherwise every daemon run from `./build/kolt.kexe`
    // would degrade to subprocess.
    val fetched = listOf("/cache/impl-bundled.jar")
    var fetchedVersion: String? = null
    val warnings = mutableListOf<String>()
    val result =
      resolveDaemonPreconditions(
        paths = paths,
        kotlincVersion = bundledVersion,
        absProjectPath = absProject,
        bundledKotlinVersion = bundledVersion,
        ensureJavaBin = { Ok("/fake/java") },
        resolveDaemonJar = { okJar },
        listCompilerJars = { fakeJars },
        resolveBundledBtaImplJars = {
          BtaImplJarsResolution.NotFound("/fake/libexec/kolt-bta-impl")
        },
        fetchBtaImplJars = { v, _ ->
          fetchedVersion = v
          Ok(fetched)
        },
        isDirectory = { false },
        warningSink = { warnings += it },
      )

    val setup = assertNotNull(result.get())
    assertEquals(fetched, setup.btaImplJars)
    assertEquals(bundledVersion, fetchedVersion)
    assertEquals(
      emptyList<String>(),
      warnings,
      "dev binary (no libexec sibling) should stay silent on bundled fall-through",
    )
  }

  @Test
  fun bundledBtaImplLibexecMissInInstalledLayoutWarnsBeforeFetching() {
    // When the probed libexec/kolt-bta-impl's parent dir (the install
    // layout's `libexec/`) exists, the miss points at a corrupted install
    // rather than a dev binary. #234: emit a diagnostic so the silent
    // Maven Central fetch does not mask the regression.
    val fetched = listOf("/cache/impl-bundled.jar")
    val warnings = mutableListOf<String>()
    val result =
      resolveDaemonPreconditions(
        paths = paths,
        kotlincVersion = bundledVersion,
        absProjectPath = absProject,
        bundledKotlinVersion = bundledVersion,
        ensureJavaBin = { Ok("/fake/java") },
        resolveDaemonJar = { okJar },
        listCompilerJars = { fakeJars },
        resolveBundledBtaImplJars = {
          BtaImplJarsResolution.NotFound("/fake/libexec/kolt-bta-impl")
        },
        fetchBtaImplJars = { _, _ -> Ok(fetched) },
        isDirectory = { path -> path == "/fake/libexec" },
        warningSink = { warnings += it },
      )

    assertNotNull(result.get())
    assertEquals(1, warnings.size, "expected one install-corruption warning: $warnings")
    val warning = warnings.single()
    assertTrue(
      warning.contains("/fake/libexec/kolt-bta-impl"),
      "warning should name the missing probed dir: $warning",
    )
    assertTrue(
      warning.contains("Reinstall kolt") || warning.contains("Maven Central"),
      "warning should guide the operator toward a fix: $warning",
    )
  }

  @Test
  fun warningWordingCoversAllVariants() {
    assertEquals(
      "warning: could not install bootstrap JDK at /opt/jdks/21 (HTTP 503) — falling back to subprocess compile",
      formatDaemonPreconditionWarning(
        DaemonPreconditionError.BootstrapJdkInstallFailed("/opt/jdks/21", "HTTP 503")
      ),
    )
    assertEquals(
      "warning: kolt-jvm-compiler-daemon jar not found — falling back to subprocess compile",
      formatDaemonPreconditionWarning(DaemonPreconditionError.DaemonJarMissing),
    )
    assertEquals(
      "warning: no compiler jars found in /x/lib — falling back to subprocess compile",
      formatDaemonPreconditionWarning(DaemonPreconditionError.CompilerJarsMissing("/x/lib")),
    )
    val belowFloor =
      formatDaemonPreconditionWarning(
        DaemonPreconditionError.KotlinVersionBelowFloor(requested = "2.2.20", floor = "2.3.0")
      )
    assertTrue(
      belowFloor.contains("2.2.20") &&
        belowFloor.contains("2.3.0") &&
        belowFloor.contains("falling back to subprocess compile") &&
        belowFloor.contains("--no-daemon"),
      "unexpected below-floor warning: $belowFloor",
    )
    val fetchFailed =
      formatDaemonPreconditionWarning(
        DaemonPreconditionError.BtaImplFetchFailed(
          version = "2.3.10",
          cause =
            BtaImplFetchError.ResolveFailed(
              "2.3.10",
              ResolveError.DownloadFailed(
                "org.jetbrains.kotlin:kotlin-build-tools-impl",
                RepositoryDownloadFailure.AllAttemptsFailed(
                  listOf(
                    RepositoryAttempt(
                      "http://example",
                      DownloadError.HttpFailed("http://example", 503),
                    )
                  )
                ),
              ),
            ),
        )
      )
    assertTrue(
      fetchFailed.contains("2.3.10") && fetchFailed.contains("falling back to subprocess compile"),
      "unexpected fetch-failed warning: $fetchFailed",
    )
    val resolvedEmpty =
      formatDaemonPreconditionWarning(
        DaemonPreconditionError.BtaImplFetchFailed(
          version = "2.3.10",
          cause = BtaImplFetchError.ResolvedEmpty("2.3.10"),
        )
      )
    assertTrue(
      resolvedEmpty.contains("2.3.10") &&
        resolvedEmpty.contains("resolver returned no jars") &&
        resolvedEmpty.contains("falling back to subprocess compile"),
      "unexpected resolved-empty warning: $resolvedEmpty",
    )
    val tooLong =
      formatDaemonPreconditionWarning(
        DaemonPreconditionError.SocketPathTooLong(
          socketPath = "/very/long/path/to/jvm-compiler-daemon.sock",
          projectedBytes = 120,
          maxBytes = 108,
        )
      )
    assertTrue(
      tooLong.contains("108") &&
        tooLong.contains("/very/long/path/to/jvm-compiler-daemon.sock") &&
        tooLong.contains("falling back to subprocess compile") &&
        tooLong.contains("--no-daemon"),
      "unexpected socket-path-too-long warning: $tooLong",
    )
  }
}
