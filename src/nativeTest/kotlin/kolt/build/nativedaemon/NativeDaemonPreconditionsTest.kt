package kolt.build.nativedaemon

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kolt.build.daemon.BootstrapJdkError
import kolt.config.KoltPaths
import kolt.tool.ToolchainError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NativeDaemonPreconditionsTest {

  private val paths = KoltPaths(home = "/home/test")
  private val kotlinVersion = "2.3.20"
  private val project = "/tmp/myproject"

  private fun run(
    kotlincVersion: String = kotlinVersion,
    ensureJavaBin: (KoltPaths) -> Result<String, BootstrapJdkError> = { Ok("/opt/jdk/bin/java") },
    resolveNativeJar: () -> NativeDaemonJarResolution = {
      NativeDaemonJarResolution.Resolved(
        listOf("@/opt/kolt/libexec/classpath/kolt-native-compiler-daemon.argfile"),
        NativeDaemonJarResolution.Source.Libexec,
      )
    },
    konancLayoutExists: (String) -> Boolean = { true },
    absProjectPath: String = project,
  ): Result<NativeDaemonSetup, NativeDaemonPreconditionError> =
    resolveNativeDaemonPreconditions(
      paths = paths,
      kotlincVersion = kotlincVersion,
      absProjectPath = absProjectPath,
      ensureJavaBin = ensureJavaBin,
      resolveNativeDaemonJar = resolveNativeJar,
      fileExists = konancLayoutExists,
    )

  @Test
  fun happyPathReturnsSetupWithDerivedKonancJarAndHome() {
    val setup = assertNotNull(run().get())

    assertEquals("/opt/jdk/bin/java", setup.javaBin)
    assertEquals(
      listOf("@/opt/kolt/libexec/classpath/kolt-native-compiler-daemon.argfile"),
      setup.daemonLaunchArgs,
    )
    // konanHome is the managed toolchain root for the Kotlin version.
    assertEquals("/home/test/.kolt/toolchains/konanc/2.3.20", setup.konanHome)
    // konancJar is the single embeddable under `konan/lib/` — the same
    // path the spike (#166) used.
    assertEquals(
      "/home/test/.kolt/toolchains/konanc/2.3.20/konan/lib/kotlin-native-compiler-embeddable.jar",
      setup.konancJar,
    )
    assertTrue(setup.socketPath.endsWith("/native-compiler-daemon.sock"))
    assertTrue(setup.logPath.endsWith("/native-compiler-daemon.log"))
  }

  @Test
  fun kotlinVersionBelowFloorShortCircuits() {
    // Floor check runs before any disk probe (cheap) — no filesystem
    // fakes need to stub true to satisfy it.
    val err = run(kotlincVersion = "2.2.10").getError()

    val below = assertIs<NativeDaemonPreconditionError.KotlinVersionBelowFloor>(err)
    assertEquals("2.2.10", below.requested)
    assertTrue(below.floor.isNotEmpty())
  }

  @Test
  fun nativeDaemonJarMissingShortCircuits() {
    val err = run(resolveNativeJar = { NativeDaemonJarResolution.NotFound }).getError()
    assertEquals(NativeDaemonPreconditionError.NativeDaemonJarMissing, err)
  }

  @Test
  fun bootstrapJdkFailureSurfaces() {
    val err =
      run(
          ensureJavaBin = {
            Err(BootstrapJdkError(jdkInstallDir = "/x/jdk", cause = ToolchainError("fetch failed")))
          }
        )
        .getError()

    val jdkErr = assertIs<NativeDaemonPreconditionError.BootstrapJdkInstallFailed>(err)
    assertEquals("/x/jdk", jdkErr.jdkInstallDir)
    assertTrue(jdkErr.cause.contains("fetch failed"))
  }

  @Test
  fun konancLayoutMissingSurfacesAsKonancJarMissing() {
    // The konanc jar sits at konan/lib/kotlin-native-compiler-embeddable.jar
    // under the managed toolchain. If the jar is absent (incomplete
    // install, manual tamper), the daemon will not start — fall back.
    val err = run(konancLayoutExists = { false }).getError()

    val missing = assertIs<NativeDaemonPreconditionError.KonancJarMissing>(err)
    assertTrue(missing.path.endsWith("/konan/lib/kotlin-native-compiler-embeddable.jar"))
  }

  @Test
  fun socketPathTooLongSurfaces() {
    // An extreme HOME path forces the projected socket path past the
    // Linux AF_UNIX sun_path capacity. Mirrors the JVM-side check.
    val longHome = "/home/" + "x".repeat(128)
    val err =
      resolveNativeDaemonPreconditions(
          paths = KoltPaths(home = longHome),
          kotlincVersion = kotlinVersion,
          absProjectPath = project,
          ensureJavaBin = { Ok("/opt/jdk/bin/java") },
          resolveNativeDaemonJar = { NativeDaemonJarResolution.NotFound },
          fileExists = { true },
        )
        .getError()

    val tooLong = assertIs<NativeDaemonPreconditionError.SocketPathTooLong>(err)
    assertTrue(tooLong.projectedBytes > tooLong.maxBytes)
  }

  @Test
  fun formatWarningMentionsFallbackForEveryVariant() {
    val errors: List<NativeDaemonPreconditionError> =
      listOf(
        NativeDaemonPreconditionError.BootstrapJdkInstallFailed("/x/jdk", "fetch failed"),
        NativeDaemonPreconditionError.NativeDaemonJarMissing,
        NativeDaemonPreconditionError.KonancJarMissing(
          "/.../kotlin-native-compiler-embeddable.jar"
        ),
        NativeDaemonPreconditionError.KotlinVersionBelowFloor("2.2.0", "2.3.0"),
        NativeDaemonPreconditionError.SocketPathTooLong("/too/long", 120, 108),
      )

    for (err in errors) {
      val msg = formatNativeDaemonPreconditionWarning(err)
      assertTrue(msg.startsWith("warning:"), "expected warning: prefix, got: $msg")
      assertTrue(msg.contains("falling back to subprocess"), "expected fallback mention, got: $msg")
    }
  }
}
