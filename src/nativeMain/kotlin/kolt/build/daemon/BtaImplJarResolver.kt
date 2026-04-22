package kolt.build.daemon

import com.github.michaelbull.result.getOrElse
import kolt.infra.listJarFiles
import kolt.infra.readSelfExe
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString

sealed interface BtaImplJarsResolution {
    data class Resolved(val jars: List<String>, val source: Source) : BtaImplJarsResolution
    data class NotFound(val probedDir: String) : BtaImplJarsResolution

    enum class Source { Env, Libexec, DevFallback }
}

internal const val KOLT_BTA_IMPL_JARS_DIR_ENV = "KOLT_BTA_IMPL_JARS_DIR"

internal const val BTA_IMPL_DIR_NAME = "kolt-bta-impl"

// Probe order: env override -> libexec -> dev fallback (same as resolveDaemonJarPure).
fun resolveBtaImplJarsPure(
    envDirValue: String?,
    selfExePath: String?,
    listJarFiles: (String) -> List<String>?,
): BtaImplJarsResolution {
    if (envDirValue != null && envDirValue.isNotEmpty()) {
        val jars = listJarFiles(envDirValue)
        return if (!jars.isNullOrEmpty()) {
            BtaImplJarsResolution.Resolved(jars, BtaImplJarsResolution.Source.Env)
        } else {
            BtaImplJarsResolution.NotFound(envDirValue)
        }
    }

    var lastProbed = "<no selfExe>"

    if (selfExePath != null) {
        val binDir = parentDir(selfExePath)
        val prefix = binDir?.let { parentDir(it) }
        if (prefix != null) {
            val libexec = "$prefix/libexec/$BTA_IMPL_DIR_NAME"
            lastProbed = libexec
            val jars = listJarFiles(libexec)
            if (!jars.isNullOrEmpty()) {
                return BtaImplJarsResolution.Resolved(jars, BtaImplJarsResolution.Source.Libexec)
            }
        }

        var repoRoot: String? = selfExePath
        repeat(5) { repoRoot = repoRoot?.let { parentDir(it) } }
        if (repoRoot != null) {
            val devDir = "$repoRoot/kolt-jvm-compiler-daemon/build/bta-impl-jars"
            lastProbed = devDir
            val jars = listJarFiles(devDir)
            if (!jars.isNullOrEmpty()) {
                return BtaImplJarsResolution.Resolved(jars, BtaImplJarsResolution.Source.DevFallback)
            }
        }
    }

    return BtaImplJarsResolution.NotFound(lastProbed)
}

@OptIn(ExperimentalForeignApi::class)
fun resolveBtaImplJars(): BtaImplJarsResolution {
    val envDir = platform.posix.getenv(KOLT_BTA_IMPL_JARS_DIR_ENV)?.toKString()
    val selfExe = readSelfExe().getOrElse { null }
    return resolveBtaImplJarsPure(
        envDirValue = envDir,
        selfExePath = selfExe,
        listJarFiles = { dir -> listJarFiles(dir).getOrElse { null } },
    )
}
