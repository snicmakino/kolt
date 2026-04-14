package kolt.build.daemon

import com.github.michaelbull.result.get
import kolt.infra.fileExists
import kolt.infra.readSelfExe
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString

// Result of resolving where the kolt-compiler-daemon fat jar lives on
// disk. The [source] tag is preserved so a future `kolt doctor` command
// (or a verbose log line) can tell a user which fallback actually fired
// without re-running the resolution.
sealed interface DaemonJarResolution {
    data class Resolved(val path: String, val source: Source) : DaemonJarResolution
    data object NotFound : DaemonJarResolution

    enum class Source { Env, Libexec, DevFallback }
}

// Fixed filename of the Shadow-built fat jar produced by
// kolt-compiler-daemon/build.gradle.kts (see S2, commit ad4199f).
internal const val DAEMON_JAR_FILENAME = "kolt-compiler-daemon-all.jar"

// KOLT_DAEMON_JAR is the explicit override documented in the
// DaemonCompilerBackend contract. Found => taken verbatim, other
// candidates are not inspected and the path is not existence-checked —
// that matches the intent of an override (user takes responsibility).
internal const val KOLT_DAEMON_JAR_ENV = "KOLT_DAEMON_JAR"

/**
 * Pure resolution entry point, split out so the fallback chain can be
 * exercised with fake inputs — the real I/O wrapper [resolveDaemonJar]
 * below fills the holes from `getenv` + [readSelfExe] + [fileExists].
 *
 * Priority:
 *   1. [envValue] — early-return override, no existence check.
 *   2. Installed layout: `<dirname(dirname(selfExe))>/libexec/kolt-compiler-daemon-all.jar`.
 *      A `kolt` binary at `<prefix>/bin/kolt` and a jar at
 *      `<prefix>/libexec/kolt-compiler-daemon-all.jar` is the layout we
 *      will ship when packaging gets cut (intentionally left unbuilt in
 *      S2 — see memory/project_phase_a_daemon.md).
 *   3. Dev fallback: the jar produced by `./gradlew :kolt-compiler-daemon:shadowJar`
 *      at `<repo>/kolt-compiler-daemon/build/libs/kolt-compiler-daemon-all.jar`.
 *      The native test binary lives at
 *      `<repo>/build/bin/linuxX64/debugTest/test.kexe`
 *      and the dev binary at
 *      `<repo>/build/bin/linuxX64/debugExecutable/kolt.kexe`,
 *      so the jar is five levels up from the binary file and then
 *      `kolt-compiler-daemon/build/libs/...`. This keeps dev usable
 *      without any installed layout at all.
 */
fun resolveDaemonJarPure(
    envValue: String?,
    selfExePath: String?,
    fileExists: (String) -> Boolean,
): DaemonJarResolution {
    if (envValue != null && envValue.isNotEmpty()) {
        return DaemonJarResolution.Resolved(envValue, DaemonJarResolution.Source.Env)
    }

    if (selfExePath == null) return DaemonJarResolution.NotFound

    val binDir = parentDir(selfExePath) ?: return DaemonJarResolution.NotFound
    val prefix = parentDir(binDir) ?: return DaemonJarResolution.NotFound
    val libexec = "$prefix/libexec/$DAEMON_JAR_FILENAME"
    if (fileExists(libexec)) {
        return DaemonJarResolution.Resolved(libexec, DaemonJarResolution.Source.Libexec)
    }

    // From <repo>/build/bin/linuxX64/<variant>/kolt.kexe the repo root
    // is five parents up. Any other binary layout simply won't match
    // and falls through to NotFound.
    var repoRoot: String? = selfExePath
    repeat(5) { repoRoot = repoRoot?.let { parentDir(it) } }
    if (repoRoot != null) {
        val devJar = "$repoRoot/kolt-compiler-daemon/build/libs/$DAEMON_JAR_FILENAME"
        if (fileExists(devJar)) {
            return DaemonJarResolution.Resolved(devJar, DaemonJarResolution.Source.DevFallback)
        }
    }

    return DaemonJarResolution.NotFound
}

// Impure entry point: wires [resolveDaemonJarPure] up to getenv + the
// real /proc/self/exe + on-disk file existence. DaemonCompilerBackend
// calls this at compile() time, not at construction, so a user who
// installs a daemon jar between builds gets picked up on the next run
// without restarting kolt.
@OptIn(ExperimentalForeignApi::class)
fun resolveDaemonJar(): DaemonJarResolution {
    val env = platform.posix.getenv(KOLT_DAEMON_JAR_ENV)?.toKString()
    val selfExe = readSelfExe().get()
    return resolveDaemonJarPure(
        envValue = env,
        selfExePath = selfExe,
        fileExists = ::fileExists,
    )
}

// Extract the parent directory component of a POSIX-style path. Returns
// null if the path has no separator (e.g. "kolt"), or is exactly "/".
internal fun parentDir(path: String): String? {
    if (path.isEmpty()) return null
    if (path == "/") return null
    val lastSlash = path.lastIndexOf('/')
    if (lastSlash < 0) return null
    if (lastSlash == 0) return "/"
    return path.substring(0, lastSlash)
}
