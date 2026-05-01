package kolt.build.daemon

import com.github.michaelbull.result.get
import com.github.michaelbull.result.getOrElse
import kolt.infra.fileExists
import kolt.infra.readFileAsString
import kolt.infra.readSelfExe
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString

// ADR 0018 §2: the resolver returns the JVM arguments that sit between the
// `java` executable and the daemon's own CLI args — `-cp <cp> <MainClass>`
// in every case. For the libexec layout the classpath comes from the
// `assemble-dist`-emitted argfile; the resolver reads it and substitutes
// the `@KOLT_LIBEXEC@` placeholder with the absolute libexec path so the
// JVM gets a valid classpath whether the user ran install.sh or just
// `tar xzf && bin/kolt` (#336). install.sh's own substitution remains a
// no-op shim under this resolver — the placeholder simply isn't there
// anymore by the time we read it.
sealed interface DaemonJarResolution {
  data class Resolved(val launchArgs: List<String>, val source: Source) : DaemonJarResolution

  data object NotFound : DaemonJarResolution

  enum class Source {
    Env,
    Libexec,
    DevFallback,
  }
}

internal const val DAEMON_JAR_STEM = "kolt-jvm-compiler-daemon"
internal const val DAEMON_MAIN_CLASS = "kolt.daemon.MainKt"
internal const val KOLT_DAEMON_JAR_ENV = "KOLT_DAEMON_JAR"
internal const val LIBEXEC_PLACEHOLDER = "@KOLT_LIBEXEC@"

// Probe order: env override -> libexec argfile -> dev fallback (kolt build output).
fun resolveDaemonJarPure(
  envValue: String?,
  selfExePath: String?,
  fileExists: (String) -> Boolean,
  readManifest: (String) -> List<String>?,
): DaemonJarResolution {
  if (!envValue.isNullOrEmpty()) {
    val launch =
      launchArgsFromThinJar(envValue, readManifest) ?: return DaemonJarResolution.NotFound
    return DaemonJarResolution.Resolved(launch, DaemonJarResolution.Source.Env)
  }

  if (selfExePath == null) return DaemonJarResolution.NotFound

  val binDir = parentDir(selfExePath) ?: return DaemonJarResolution.NotFound
  val prefix = parentDir(binDir) ?: return DaemonJarResolution.NotFound
  val argfile = "$prefix/libexec/classpath/$DAEMON_JAR_STEM.argfile"
  if (fileExists(argfile)) {
    val launch =
      launchArgsFromArgfile(argfile, "$prefix/libexec", readManifest)
        ?: return DaemonJarResolution.NotFound
    return DaemonJarResolution.Resolved(launch, DaemonJarResolution.Source.Libexec)
  }

  var repoRoot: String? = selfExePath
  repeat(5) { repoRoot = repoRoot?.let { parentDir(it) } }
  if (repoRoot != null) {
    val devJar = "$repoRoot/$DAEMON_JAR_STEM/build/$DAEMON_JAR_STEM.jar"
    if (fileExists(devJar)) {
      val launch = launchArgsFromThinJar(devJar, readManifest)
      if (launch != null) {
        return DaemonJarResolution.Resolved(launch, DaemonJarResolution.Source.DevFallback)
      }
    }
  }

  return DaemonJarResolution.NotFound
}

// Read `<libexec>/classpath/<daemon>.argfile` (line-per-token format from
// scripts/assemble-dist.sh `write_argfile`), substitute every
// `@KOLT_LIBEXEC@` with the absolute libexec path, and return the parsed
// launch args. Returns null if the argfile can't be read or yielded no
// tokens. Whether install.sh already ran the same substitution doesn't
// matter — `replace` on a placeholder-free string is a no-op.
private fun launchArgsFromArgfile(
  argfile: String,
  libexecAbs: String,
  readManifest: (String) -> List<String>?,
): List<String>? {
  val lines = readManifest(argfile) ?: return null
  val tokens = lines.map { it.replace(LIBEXEC_PLACEHOLDER, libexecAbs) }.filter { it.isNotEmpty() }
  return tokens.takeIf { it.isNotEmpty() }
}

// Build `-cp <thin>:<deps...> <MainClass>` from a thin jar and its sibling
// `<stem>-runtime.classpath` manifest (kolt build output, ADR 0027 §1: one
// absolute path per line). Returns null if the manifest can't be read.
private fun launchArgsFromThinJar(
  jarPath: String,
  readManifest: (String) -> List<String>?,
): List<String>? {
  val stem = jarPath.removeSuffix(".jar")
  val manifestPath = "$stem-runtime.classpath"
  val deps = readManifest(manifestPath) ?: return null
  val classpath =
    (listOf(jarPath) + deps.mapNotNull { it.trim().takeIf(String::isNotEmpty) }).joinToString(":")
  return listOf("-cp", classpath, DAEMON_MAIN_CLASS)
}

@OptIn(ExperimentalForeignApi::class)
fun resolveDaemonJar(): DaemonJarResolution {
  val env = platform.posix.getenv(KOLT_DAEMON_JAR_ENV)?.toKString()
  val selfExe = readSelfExe().get()
  return resolveDaemonJarPure(
    envValue = env,
    selfExePath = selfExe,
    fileExists = ::fileExists,
    readManifest = ::readManifestLines,
  )
}

internal fun readManifestLines(path: String): List<String>? =
  readFileAsString(path).getOrElse { null }?.lines()

internal fun parentDir(path: String): String? {
  if (path.isEmpty()) return null
  if (path == "/") return null
  val lastSlash = path.lastIndexOf('/')
  if (lastSlash < 0) return null
  if (lastSlash == 0) return "/"
  return path.substring(0, lastSlash)
}
