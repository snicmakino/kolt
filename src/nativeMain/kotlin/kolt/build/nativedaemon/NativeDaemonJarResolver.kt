package kolt.build.nativedaemon

import com.github.michaelbull.result.get
import com.github.michaelbull.result.getOrElse
import kolt.infra.fileExists
import kolt.infra.readFileAsString
import kolt.infra.readSelfExe
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString

// Parallel to DaemonJarResolver for the JVM daemon. ADR 0018 §2: the resolver
// returns the JVM args to splice between `java` and the daemon's own CLI
// args — `-cp <cp> <MainClass>` in every case. The libexec branch reads
// the `assemble-dist`-emitted argfile and substitutes `@KOLT_LIBEXEC@`
// with the absolute libexec path so the JVM gets a valid classpath
// whether the user ran install.sh or just `tar xzf && bin/kolt` (#336).
// Filename and env var differ from the JVM daemon so a dev machine with
// both staged doesn't cross-wire them.
sealed interface NativeDaemonJarResolution {
  data class Resolved(val launchArgs: List<String>, val source: Source) : NativeDaemonJarResolution

  data object NotFound : NativeDaemonJarResolution

  enum class Source {
    Env,
    Libexec,
    DevFallback,
  }
}

internal const val NATIVE_DAEMON_JAR_STEM = "kolt-native-compiler-daemon"
internal const val NATIVE_DAEMON_MAIN_CLASS = "kolt.nativedaemon.MainKt"
internal const val KOLT_NATIVE_DAEMON_JAR_ENV = "KOLT_NATIVE_DAEMON_JAR"
internal const val LIBEXEC_PLACEHOLDER = "@KOLT_LIBEXEC@"

fun resolveNativeDaemonJarPure(
  envValue: String?,
  selfExePath: String?,
  fileExists: (String) -> Boolean,
  readManifest: (String) -> List<String>?,
): NativeDaemonJarResolution {
  if (!envValue.isNullOrEmpty()) {
    val launch =
      launchArgsFromThinJar(envValue, readManifest) ?: return NativeDaemonJarResolution.NotFound
    return NativeDaemonJarResolution.Resolved(launch, NativeDaemonJarResolution.Source.Env)
  }

  if (selfExePath == null) return NativeDaemonJarResolution.NotFound

  val binDir = parentDir(selfExePath) ?: return NativeDaemonJarResolution.NotFound
  val prefix = parentDir(binDir) ?: return NativeDaemonJarResolution.NotFound
  val argfile = "$prefix/libexec/classpath/$NATIVE_DAEMON_JAR_STEM.argfile"
  if (fileExists(argfile)) {
    val launch =
      launchArgsFromArgfile(argfile, "$prefix/libexec", readManifest)
        ?: return NativeDaemonJarResolution.NotFound
    return NativeDaemonJarResolution.Resolved(launch, NativeDaemonJarResolution.Source.Libexec)
  }

  var repoRoot: String? = selfExePath
  repeat(5) { repoRoot = repoRoot?.let { parentDir(it) } }
  if (repoRoot != null) {
    val devJar = "$repoRoot/$NATIVE_DAEMON_JAR_STEM/build/$NATIVE_DAEMON_JAR_STEM.jar"
    if (fileExists(devJar)) {
      val launch = launchArgsFromThinJar(devJar, readManifest)
      if (launch != null) {
        return NativeDaemonJarResolution.Resolved(
          launch,
          NativeDaemonJarResolution.Source.DevFallback,
        )
      }
    }
  }

  return NativeDaemonJarResolution.NotFound
}

// See `kolt.build.daemon.launchArgsFromArgfile` for the contract; this
// is the native-daemon mirror, kept private to match the parentDir /
// readManifestLines duplication pattern in this file.
private fun launchArgsFromArgfile(
  argfile: String,
  libexecAbs: String,
  readManifest: (String) -> List<String>?,
): List<String>? {
  val lines = readManifest(argfile) ?: return null
  val tokens = lines.map { it.replace(LIBEXEC_PLACEHOLDER, libexecAbs) }.filter { it.isNotEmpty() }
  return tokens.takeIf { it.isNotEmpty() }
}

private fun launchArgsFromThinJar(
  jarPath: String,
  readManifest: (String) -> List<String>?,
): List<String>? {
  val stem = jarPath.removeSuffix(".jar")
  val manifestPath = "$stem-runtime.classpath"
  val deps = readManifest(manifestPath) ?: return null
  val classpath =
    (listOf(jarPath) + deps.mapNotNull { it.trim().takeIf(String::isNotEmpty) }).joinToString(":")
  return listOf("-cp", classpath, NATIVE_DAEMON_MAIN_CLASS)
}

@OptIn(ExperimentalForeignApi::class)
fun resolveNativeDaemonJar(): NativeDaemonJarResolution {
  val env = platform.posix.getenv(KOLT_NATIVE_DAEMON_JAR_ENV)?.toKString()
  val selfExe = readSelfExe().get()
  return resolveNativeDaemonJarPure(
    envValue = env,
    selfExePath = selfExe,
    fileExists = ::fileExists,
    readManifest = ::readManifestLines,
  )
}

private fun readManifestLines(path: String): List<String>? =
  readFileAsString(path).getOrElse { null }?.lines()

// Duplicated from kolt.build.daemon.parentDir — kept private to avoid a
// cross-package `internal` import chain. If a third resolver ever needs
// the same helper, hoist it into `kolt.infra`.
private fun parentDir(path: String): String? {
  if (path.isEmpty()) return null
  if (path == "/") return null
  val lastSlash = path.lastIndexOf('/')
  if (lastSlash < 0) return null
  if (lastSlash == 0) return "/"
  return path.substring(0, lastSlash)
}
