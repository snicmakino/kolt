package kolt.build.daemon

import com.github.michaelbull.result.getOrElse
import kolt.infra.listJarFiles
import kolt.infra.readSelfExe
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString

// Result of locating the kotlin-build-tools-impl jars the Phase B daemon
// expects via its `--bta-impl-jars` CLI flag. Carries the probed directory
// on the NotFound branch so the warning tells the user the exact path kolt
// looked at, mirroring the shape of `DaemonPreconditionError.CompilerJarsMissing`.
sealed interface BtaImplJarsResolution {
    data class Resolved(val jars: List<String>, val source: Source) : BtaImplJarsResolution
    data class NotFound(val probedDir: String) : BtaImplJarsResolution

    enum class Source { Env, Libexec, DevFallback }
}

// KOLT_BTA_IMPL_JARS_DIR is the explicit override for the -impl jar directory.
// Found => taken verbatim, the path must contain at least one .jar. Parallel
// to KOLT_DAEMON_JAR for the daemon fat jar itself — one env var per resource
// keeps the override contract obvious to users writing scripts.
internal const val KOLT_BTA_IMPL_JARS_DIR_ENV = "KOLT_BTA_IMPL_JARS_DIR"

// Directory name used under both the installed layout and the dev fallback.
// Kept as a shared constant so a future rename (e.g. for a second Kotlin
// version segment) touches exactly one place.
internal const val BTA_IMPL_DIR_NAME = "kolt-bta-impl"

/**
 * Pure resolution entry point for kotlin-build-tools-impl jars. The probe
 * order mirrors [resolveDaemonJarPure]:
 *
 *   1. `[envDirValue]` — early-return override pointed at a directory.
 *      If the env var is set but the directory is empty or unreadable,
 *      return NotFound with the env-supplied path so the user's
 *      mis-setting surfaces as the warning.
 *   2. Installed layout: `<dirname(dirname(selfExe))>/libexec/kolt-bta-impl/`.
 *      Jars end up here when kolt is shipped as a packaged distribution
 *      with a staged copy of `kotlin-build-tools-impl` alongside the
 *      daemon fat jar. The libexec layout is the same one
 *      [resolveDaemonJarPure] uses for `kolt-compiler-daemon-all.jar`.
 *   3. Dev fallback: `<repo>/kolt-compiler-daemon/build/bta-impl-jars/`,
 *      populated by the `stageBtaImplJars` Gradle task. Identical
 *      five-parents-up walk from `build/bin/linuxX64/<variant>/kolt.kexe`
 *      to the repo root as [resolveDaemonJarPure].
 *
 * `NotFound` carries the last probed directory so the caller can surface
 * a warning with an actionable path (either the env override, the libexec
 * layout, or the dev fallback, in that priority order).
 */
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
            // Env override is user-asserted; empty or unreadable is a
            // mis-setting we surface rather than falling through, so the
            // user doesn't get a libexec path in the warning when they
            // clearly set the env var.
            BtaImplJarsResolution.NotFound(envDirValue)
        }
    }

    // Probed directories are collected so the NotFound branch can name the
    // last-probed path without re-deriving it.
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

        // Dev fallback: walk five parents up from the test/debug binary.
        var repoRoot: String? = selfExePath
        repeat(5) { repoRoot = repoRoot?.let { parentDir(it) } }
        if (repoRoot != null) {
            val devDir = "$repoRoot/kolt-compiler-daemon/build/bta-impl-jars"
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
