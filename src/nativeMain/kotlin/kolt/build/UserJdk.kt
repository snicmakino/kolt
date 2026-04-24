package kolt.build

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse
import kolt.config.KoltConfig
import kolt.config.KoltPaths
import kolt.infra.executeAndCapture
import kolt.infra.fileExists

internal data class UserJdkHome(val version: String, val home: String)

internal sealed interface UserJdkError {
  data class ManagedMissing(val version: String, val expectedPath: String) : UserJdkError

  data object SystemProbeFailed : UserJdkError
}

// Resolves the IDE-facing JDK surfaced through workspace.json. Independent of
// the bootstrap JDK (ADR 0017), which only runs the daemon and is not what
// user code should be read against.
//
// Contract:
//  - `[build] jdk` set to a non-blank value: return the managed install when
//    `bin/java` is present; otherwise `ManagedMissing`. Probing `bin/java`
//    (not just the directory) catches interrupted installs and manual prunes
//    that would otherwise feed kotlin-lsp a home with no class roots. Never
//    silently fall back to a system JDK — that would contradict the pin.
//  - `[build] jdk` unset or blank: probe `java` on PATH and return its
//    `java.home`, tagging the version with `jvmTarget` for the SDK label. If
//    probing fails, return `SystemProbeFailed`.
internal fun resolveUserJdkHome(
  config: KoltConfig,
  paths: KoltPaths,
  exists: (String) -> Boolean = ::fileExists,
  probe: () -> String? = ::probeSystemJavaHome,
): Result<UserJdkHome, UserJdkError> {
  val managed = config.build.jdk
  if (!managed.isNullOrBlank()) {
    val home = paths.jdkPath(managed)
    return if (exists(paths.javaBin(managed))) Ok(UserJdkHome(managed, home))
    else Err(UserJdkError.ManagedMissing(managed, home))
  }
  val systemHome = probe() ?: return Err(UserJdkError.SystemProbeFailed)
  return Ok(UserJdkHome(config.build.jvmTarget, systemHome))
}

// `-XshowSettings:properties` prints to stderr; redirect so popen's stdout-
// only capture sees it. Returns null on any failure (java absent, non-zero
// exit, or the property missing from output).
internal fun probeSystemJavaHome(): String? {
  val output =
    executeAndCapture("java -XshowSettings:properties -version 2>&1").getOrElse {
      return null
    }
  return parseJavaHomeFromProperties(output)
}

private val JAVA_HOME_LINE = Regex("""^\s*java\.home\s*=\s*(.+?)\s*$""")

internal fun parseJavaHomeFromProperties(output: String): String? {
  for (line in output.lineSequence()) {
    val match = JAVA_HOME_LINE.matchEntire(line) ?: continue
    val value = match.groupValues[1].trim()
    if (value.isNotEmpty()) return value
  }
  return null
}
