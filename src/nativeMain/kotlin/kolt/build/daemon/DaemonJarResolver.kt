package kolt.build.daemon

import com.github.michaelbull.result.get
import kolt.infra.fileExists
import kolt.infra.readSelfExe
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString

sealed interface DaemonJarResolution {
    data class Resolved(val path: String, val source: Source) : DaemonJarResolution
    data object NotFound : DaemonJarResolution

    enum class Source { Env, Libexec, DevFallback }
}

internal const val DAEMON_JAR_FILENAME = "kolt-compiler-daemon-all.jar"
internal const val KOLT_DAEMON_JAR_ENV = "KOLT_DAEMON_JAR"

// Probe order: env override -> libexec -> dev fallback.
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

internal fun parentDir(path: String): String? {
    if (path.isEmpty()) return null
    if (path == "/") return null
    val lastSlash = path.lastIndexOf('/')
    if (lastSlash < 0) return null
    if (lastSlash == 0) return "/"
    return path.substring(0, lastSlash)
}
