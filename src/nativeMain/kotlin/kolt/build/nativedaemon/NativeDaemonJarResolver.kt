package kolt.build.nativedaemon

import com.github.michaelbull.result.get
import kolt.infra.fileExists
import kolt.infra.readSelfExe
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString

// Parallel to DaemonJarResolver for the JVM daemon. Structure is identical
// (env -> libexec -> dev-fallback probe order) but the filename and env var
// differ so a dev machine with both daemons staged doesn't cross-wire them.
sealed interface NativeDaemonJarResolution {
    data class Resolved(val path: String, val source: Source) : NativeDaemonJarResolution
    data object NotFound : NativeDaemonJarResolution

    enum class Source { Env, Libexec, DevFallback }
}

internal const val NATIVE_DAEMON_JAR_FILENAME = "kolt-native-daemon-all.jar"
internal const val KOLT_NATIVE_DAEMON_JAR_ENV = "KOLT_NATIVE_DAEMON_JAR"

fun resolveNativeDaemonJarPure(
    envValue: String?,
    selfExePath: String?,
    fileExists: (String) -> Boolean,
): NativeDaemonJarResolution {
    if (envValue != null && envValue.isNotEmpty()) {
        return NativeDaemonJarResolution.Resolved(envValue, NativeDaemonJarResolution.Source.Env)
    }

    if (selfExePath == null) return NativeDaemonJarResolution.NotFound

    val binDir = parentDir(selfExePath) ?: return NativeDaemonJarResolution.NotFound
    val prefix = parentDir(binDir) ?: return NativeDaemonJarResolution.NotFound
    val libexec = "$prefix/libexec/$NATIVE_DAEMON_JAR_FILENAME"
    if (fileExists(libexec)) {
        return NativeDaemonJarResolution.Resolved(libexec, NativeDaemonJarResolution.Source.Libexec)
    }

    var repoRoot: String? = selfExePath
    repeat(5) { repoRoot = repoRoot?.let { parentDir(it) } }
    if (repoRoot != null) {
        val devJar = "$repoRoot/kolt-native-daemon/build/libs/$NATIVE_DAEMON_JAR_FILENAME"
        if (fileExists(devJar)) {
            return NativeDaemonJarResolution.Resolved(devJar, NativeDaemonJarResolution.Source.DevFallback)
        }
    }

    return NativeDaemonJarResolution.NotFound
}

@OptIn(ExperimentalForeignApi::class)
fun resolveNativeDaemonJar(): NativeDaemonJarResolution {
    val env = platform.posix.getenv(KOLT_NATIVE_DAEMON_JAR_ENV)?.toKString()
    val selfExe = readSelfExe().get()
    return resolveNativeDaemonJarPure(
        envValue = env,
        selfExePath = selfExe,
        fileExists = ::fileExists,
    )
}

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
