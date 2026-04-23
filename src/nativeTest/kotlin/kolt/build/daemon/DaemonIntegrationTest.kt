package kolt.build.daemon

import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import com.github.michaelbull.result.getOrElse
import kolt.build.CompileRequest
import kolt.infra.ensureDirectoryRecursive
import kolt.infra.fileExists
import kolt.infra.listJarFiles
import kolt.infra.removeDirectoryRecursive
import kolt.infra.writeFileAsString
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv
import platform.posix.getpid

// Opt-in via KOLT_DAEMON_IT=1. Requires JAVA_HOME and KOLT_IT_COMPILER_JARS_DIR.
@OptIn(ExperimentalForeignApi::class)
class DaemonIntegrationTest {

  @Test
  fun realDaemonCompilesTrivialSourceTwice() {
    if (!integrationTestsEnabled()) return
    val env = requireEnv()

    val projectDir = "/tmp/kolt_daemon_it_${getpid()}"
    val stateDir = "$projectDir/state"
    val outputDir = "$projectDir/out"
    val srcFile = "$projectDir/Hello.kt"
    try {
      ensureDirectoryRecursive(stateDir)
      ensureDirectoryRecursive(outputDir)
      writeFileAsString(srcFile, "fun hello(): Int = 42\n")

      val backend =
        DaemonCompilerBackend(
          javaBin = env.javaBin,
          daemonLaunchArgs = env.daemonLaunchArgs,
          compilerJars = env.compilerJars,
          btaImplJars = env.btaImplJars,
          socketPath = "$stateDir/daemon.sock",
          logPath = "$stateDir/daemon.log",
        )

      val request =
        CompileRequest(
          workingDir = projectDir,
          classpath = emptyList(),
          sources = listOf(srcFile),
          outputPath = outputDir,
          moduleName = "kolt_daemon_it",
        )

      val cold = backend.compile(request)
      assertNotNull(
        cold.get(),
        "cold compile failed: ${cold.getError()} (daemon log: $stateDir/daemon.log)",
      )

      val warm = backend.compile(request)
      assertNotNull(
        warm.get(),
        "warm compile failed: ${warm.getError()} (daemon log: $stateDir/daemon.log)",
      )
    } finally {
      removeDirectoryRecursive(projectDir)
    }
  }

  private data class ItEnv(
    val javaBin: String,
    val daemonLaunchArgs: List<String>,
    val compilerJars: List<String>,
    val btaImplJars: List<String>,
  )

  private fun requireEnv(): ItEnv {
    val javaHome = getenv("JAVA_HOME")?.toKString()
    if (javaHome.isNullOrEmpty()) {
      error("KOLT_DAEMON_IT=1 but JAVA_HOME is not set")
    }
    val javaBin = "$javaHome/bin/java"
    if (!fileExists(javaBin)) {
      error("KOLT_DAEMON_IT=1 but $javaBin does not exist")
    }

    val launchArgs =
      when (val r = resolveDaemonJar()) {
        is DaemonJarResolution.Resolved -> r.launchArgs
        DaemonJarResolution.NotFound ->
          error(
            "KOLT_DAEMON_IT=1 but kolt-jvm-compiler-daemon launch args could not be resolved — " +
              "run a `kolt build` in kolt-jvm-compiler-daemon/ first or set $KOLT_DAEMON_JAR_ENV"
          )
      }

    val libDir = getenv("KOLT_IT_COMPILER_JARS_DIR")?.toKString()
    if (libDir.isNullOrEmpty()) {
      error("KOLT_DAEMON_IT=1 but KOLT_IT_COMPILER_JARS_DIR is not set")
    }
    val jars =
      listJarFiles(libDir).getOrElse { fsErr ->
        error("KOLT_DAEMON_IT=1 but $libDir cannot be opened: $fsErr")
      }
    if (jars.isEmpty()) {
      error("KOLT_DAEMON_IT=1 but $libDir contains no .jar files")
    }

    val btaJars =
      when (val res = resolveBtaImplJars()) {
        is BtaImplJarsResolution.Resolved -> res.jars
        is BtaImplJarsResolution.NotFound ->
          error(
            "KOLT_DAEMON_IT=1 but kotlin-build-tools-impl jars not found at ${res.probedDir} — " +
              "set $KOLT_BTA_IMPL_JARS_DIR_ENV to a directory containing the -impl closure"
          )
      }

    return ItEnv(javaBin, launchArgs, jars, btaJars)
  }

  private fun integrationTestsEnabled(): Boolean = getenv("KOLT_DAEMON_IT")?.toKString() == "1"
}
