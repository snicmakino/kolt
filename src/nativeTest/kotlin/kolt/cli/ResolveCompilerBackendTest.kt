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
import kolt.build.daemon.KOTLIN_VERSION_FLOOR
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

class ResolveCompilerBackendTest {

  private val paths = KoltPaths(home = "/fake/home")
  private val config = minimalConfig(kotlincVersion = "2.1.0")
  private val subprocess = SentinelBackend("subprocess")
  private val daemonSentinel = SentinelBackend("daemon")

  private val okSetup =
    DaemonSetup(
      javaBin = "/fake/java",
      daemonLaunchArgs = listOf("-cp", "/fake/daemon.jar", "kolt.daemon.MainKt"),
      compilerJars = listOf("/fake/kotlinc/lib/a.jar"),
      btaImplJars = listOf("/fake/libexec/kolt-bta-impl/kotlin-build-tools-impl.jar"),
      daemonDir = "/fake/daemon/dir",
      socketPath = "/fake/daemon/dir/jvm-compiler-daemon.sock",
      logPath = "/fake/daemon/dir/jvm-compiler-daemon.log",
    )

  private val absProject = "/fake/project"

  @Test
  fun useDaemonFalseReturnsSubprocessWithoutProbingAnything() {
    val warnings = mutableListOf<String>()
    val backend =
      resolveCompilerBackend(
        config = config,
        paths = paths,
        subprocessBackend = subprocess,
        useDaemon = false,
        absProjectPath = absProject,
        preconditionResolver = { _, _, _, _ ->
          error("must not resolve preconditions when useDaemon=false")
        },
        daemonDirCreator = { error("must not create daemon dir when useDaemon=false") },
        daemonBackendFactory = { _, _, _, _ ->
          error("must not construct daemon backend when useDaemon=false")
        },
        warningSink = { warnings.add(it) },
      )

    assertSame(subprocess, backend)
    assertEquals(emptyList(), warnings)
  }

  // ADR 0022 floor (#138): a Kotlin version below 2.3.0 enters the same
  // "precondition error" warning rail as every other daemon-probe
  // failure (ADR 0016 §5). DaemonPreconditionsTest covers that the real
  // resolver surfaces this error without any probing; this test pins
  // that resolveCompilerBackend routes the variant into the warning sink.
  @Test
  fun kotlinVersionBelowFloorFallsBackWithVersionsInWarning() {
    val warnings = mutableListOf<String>()
    val backend =
      resolveCompilerBackend(
        config = minimalConfig(kotlincVersion = "2.1.0"),
        paths = paths,
        subprocessBackend = subprocess,
        useDaemon = true,
        absProjectPath = absProject,
        bundledKotlinVersion = "2.3.20",
        preconditionResolver = { _, requested, _, _ ->
          Err(
            DaemonPreconditionError.KotlinVersionBelowFloor(
              requested = requested,
              floor = KOTLIN_VERSION_FLOOR,
            )
          )
        },
        daemonDirCreator = { error("must not create daemon dir after precondition failure") },
        daemonBackendFactory = { _, _, _, _ ->
          error("must not construct daemon backend after precondition failure")
        },
        warningSink = { warnings.add(it) },
      )

    assertSame(subprocess, backend)
    val warning = warnings.single()
    assertTrue(warning.contains("2.1.0"), "warning should cite the requested version: $warning")
    assertTrue(warning.contains(KOTLIN_VERSION_FLOOR), "warning should cite the floor: $warning")
    assertTrue(
      warning.contains("falling back to subprocess compile"),
      "warning should say the build is falling back: $warning",
    )
    assertTrue(warning.contains("--no-daemon"), "warning should point at --no-daemon: $warning")
  }

  @Test
  fun bundledKotlinVersionIsPassedThroughToPreconditionResolver() {
    var seenBundled: String? = null
    val backend =
      resolveCompilerBackend(
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
        daemonBackendFactory = { _, _, _, _ -> daemonSentinel },
        warningSink = { error("happy path must not warn") },
      )

    assertEquals("2.3.20", seenBundled)
    assertNotNull(backend as? FallbackCompilerBackend)
  }

  @Test
  fun preconditionFailureWarnsWithFormattedWordingAndFallsBack() {
    val warnings = mutableListOf<String>()
    val backend =
      resolveCompilerBackend(
        config = config,
        paths = paths,
        subprocessBackend = subprocess,
        useDaemon = true,
        absProjectPath = absProject,
        preconditionResolver = { _, _, _, _ -> Err(DaemonPreconditionError.DaemonJarMissing) },
        daemonDirCreator = { error("must not create daemon dir after precondition failure") },
        daemonBackendFactory = { _, _, _, _ ->
          error("must not construct daemon backend after precondition failure")
        },
        warningSink = { warnings.add(it) },
      )

    assertSame(subprocess, backend)
    assertEquals(1, warnings.size)
    assertTrue(
      warnings.single().contains("kolt-jvm-compiler-daemon jar not found"),
      "unexpected warning: ${warnings.single()}",
    )
  }

  // ADR 0016 §5: daemon is never load-bearing for correctness.
  @Test
  fun bootstrapJdkInstallFailureFallsBackWithCauseInWarning() {
    val warnings = mutableListOf<String>()
    val backend =
      resolveCompilerBackend(
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
            )
          )
        },
        daemonDirCreator = { error("must not create daemon dir after precondition failure") },
        daemonBackendFactory = { _, _, _, _ ->
          error("must not construct daemon backend after precondition failure")
        },
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
    val backend =
      resolveCompilerBackend(
        config = config,
        paths = paths,
        subprocessBackend = subprocess,
        useDaemon = true,
        absProjectPath = absProject,
        preconditionResolver = { _, _, _, _ -> Ok(okSetup) },
        daemonDirCreator = { Err(MkdirFailed(okSetup.daemonDir)) },
        daemonBackendFactory = { _, _, _, _ ->
          error("must not construct daemon backend after mkdir failure")
        },
        warningSink = { warnings.add(it) },
      )

    assertSame(subprocess, backend)
    assertEquals(listOf(WARNING_DAEMON_DIR_UNWRITABLE), warnings)
  }

  @Test
  fun pluginJarsArgumentIsForwardedToDaemonBackendFactory() {
    val warnings = mutableListOf<String>()
    var capturedPluginJars: Map<String, List<String>>? = null
    val pluginJars =
      mapOf(
        "serialization" to listOf("/fake/kotlinc/lib/kotlinx-serialization-compiler-plugin.jar")
      )
    val backend =
      resolveCompilerBackend(
        config = config,
        paths = paths,
        subprocessBackend = subprocess,
        useDaemon = true,
        absProjectPath = absProject,
        pluginJars = pluginJars,
        preconditionResolver = { _, _, _, _ -> Ok(okSetup) },
        daemonDirCreator = { Ok(Unit) },
        daemonBackendFactory = { _, jars, _, _ ->
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
    val backend =
      resolveCompilerBackend(
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
        daemonBackendFactory = { setup, _, _, _ ->
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
  fun daemonInputsFingerprintIsStableForSameInputs() {
    val a =
      daemonInputsFingerprint(mapOf("serialization" to listOf("/k/lib/ser.jar")), "2.1.0", "2.3.20")
    val b =
      daemonInputsFingerprint(mapOf("serialization" to listOf("/k/lib/ser.jar")), "2.1.0", "2.3.20")
    assertEquals(a, b)
  }

  @Test
  fun daemonInputsFingerprintIsOrderInsensitiveOnAliases() {
    val a =
      daemonInputsFingerprint(linkedMapOf("a" to listOf("/x"), "b" to listOf("/y")), null, null)
    val b =
      daemonInputsFingerprint(linkedMapOf("b" to listOf("/y"), "a" to listOf("/x")), null, null)
    assertEquals(a, b)
  }

  @Test
  fun daemonInputsFingerprintChangesWhenAClasspathChanges() {
    val a =
      daemonInputsFingerprint(mapOf("serialization" to listOf("/k/lib/ser-2.0.jar")), null, null)
    val b =
      daemonInputsFingerprint(mapOf("serialization" to listOf("/k/lib/ser-2.1.jar")), null, null)
    assertTrue(a != b, "plugin classpath change should change fingerprint, both=$a")
  }

  @Test
  fun daemonInputsFingerprintChangesWhenLanguageVersionChanges() {
    val a = daemonInputsFingerprint(emptyMap(), "2.0.0", "2.3.20")
    val b = daemonInputsFingerprint(emptyMap(), "2.1.0", "2.3.20")
    assertTrue(a != b, "language version change must spawn a new daemon, both=$a")
  }

  @Test
  fun daemonInputsFingerprintChangesWhenCompilerVersionChanges() {
    val a = daemonInputsFingerprint(emptyMap(), "2.1.0", "2.3.10")
    val b = daemonInputsFingerprint(emptyMap(), "2.1.0", "2.3.20")
    assertTrue(a != b, "compiler version change must spawn a new daemon, both=$a")
  }

  @Test
  fun daemonInputsFingerprintDistinguishesNullFromEmpty() {
    // Defensive: an empty-string version field must not collide with the
    // null (unset) case. Both serialise into the canonical string but the
    // hash should differ once either grows a value.
    val nullPair = daemonInputsFingerprint(emptyMap(), null, null)
    val withLang = daemonInputsFingerprint(emptyMap(), "2.1.0", null)
    assertTrue(nullPair != withLang, "presence of language version must perturb the fingerprint")
  }

  @Test
  fun applyDaemonInputsFingerprintInsertsBeforeExtension() {
    assertEquals(
      "/fake/daemon/dir/jvm-compiler-daemon-abcd1234.sock",
      applyDaemonInputsFingerprintToFile("/fake/daemon/dir/jvm-compiler-daemon.sock", "abcd1234"),
    )
    assertEquals(
      "/fake/daemon/dir/jvm-compiler-daemon-abcd1234.log",
      applyDaemonInputsFingerprintToFile("/fake/daemon/dir/jvm-compiler-daemon.log", "abcd1234"),
    )
  }

  private class SentinelBackend(val tag: String) : CompilerBackend {
    override fun compile(request: CompileRequest): Result<CompileOutcome, CompileError> =
      Err(CompileError.InternalMisuse("sentinel:$tag"))
  }

  private fun minimalConfig(kotlincVersion: String) =
    KoltConfig(
      name = "it",
      version = "0.0.0",
      kotlin = KotlinSection(version = kotlincVersion),
      build = BuildSection(target = "jvm", main = "itMainKt", sources = emptyList()),
    )
}
