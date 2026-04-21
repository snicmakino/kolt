package kolt.cli

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse
import kolt.build.daemon.KOTLIN_VERSION_FLOOR
import kolt.config.KOLT_VERSION
import kolt.config.KoltConfig
import kolt.config.parseConfig
import kolt.config.resolveKoltPaths
import kolt.infra.eprintln
import kolt.infra.fileExists
import kolt.infra.formatBytes
import kolt.infra.homeDirectory
import kolt.infra.readFileAsString
import kolt.infra.readSelfExe
import kolt.resolve.compareVersions
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import platform.posix.uname
import platform.posix.utsname

internal data class KotlinInfo(val version: String, val mode: String, val path: String)
internal data class JdkInfo(val version: String, val path: String)
internal data class ProjectInfo(val name: String, val version: String, val kind: String, val target: String)

internal data class InfoSnapshot(
    val koltVersion: String,
    val koltPath: String,
    val koltHomeDisplay: String,
    val koltHomeBytes: Long?,
    val kotlin: KotlinInfo?,
    val jdk: JdkInfo?,
    val host: String,
    val project: ProjectInfo?
)

private const val LABEL_WIDTH = 12

private fun labeled(label: String, value: String): String =
    label.padEnd(LABEL_WIDTH) + value

internal fun formatInfo(snap: InfoSnapshot): String = buildString {
    appendLine(labeled("kolt", "v${snap.koltVersion} (${snap.koltPath})"))

    val homeValue = if (snap.koltHomeBytes != null) {
        "${snap.koltHomeDisplay} (${formatBytes(snap.koltHomeBytes)})"
    } else {
        snap.koltHomeDisplay
    }
    appendLine(labeled("kolt home", homeValue))

    snap.kotlin?.let {
        appendLine(labeled("kotlin", "${it.version} (${it.mode}, ${it.path})"))
    }
    snap.jdk?.let {
        appendLine(labeled("jdk", "${it.version} (${it.path})"))
    }
    appendLine(labeled("host", snap.host))

    if (snap.project != null) {
        appendLine()
        appendLine(labeled("project", "${snap.project.name} v${snap.project.version}"))
        appendLine(labeled("kind", snap.project.kind))
        append(labeled("target", snap.project.target))
    } else {
        appendLine()
        append("(not in a kolt project — no kolt.toml in current directory)")
    }
}.trimEnd('\n')

internal fun abbreviateHomePath(path: String, home: String): String {
    // Without this guard, an empty `home` turns `prefix` into "/", which
    // matches every absolute path and mangles them into bogus "~/..." forms.
    if (home.isEmpty()) return path
    if (path == home) return "~"
    val prefix = "$home/"
    return if (path.startsWith(prefix)) "~/" + path.substring(prefix.length) else path
}

internal fun doInfo(args: List<String>): Result<Unit, Int> {
    if (args.isNotEmpty()) {
        eprintln("usage: kolt info")
        return Err(EXIT_CONFIG_ERROR)
    }
    println(formatInfo(gatherInfo()))
    return Ok(Unit)
}

@OptIn(ExperimentalForeignApi::class)
private fun gatherInfo(): InfoSnapshot {
    val koltPath = readSelfExe().getOrElse { "(unknown)" }
    val home = homeDirectory().getOrElse { null }.orEmpty()
    val paths = resolveKoltPaths().getOrElse { null }

    val koltHomeAbs = paths?.let { "${it.home}/.kolt" }
    val koltHomeDisplay = koltHomeAbs?.let { abbreviateHomePath(it, home) } ?: "(unknown)"

    val project = loadProjectForInfo()

    val kotlinInfo = project?.let { config ->
        val version = config.kotlin.effectiveCompiler
        val mode = if (compareVersions(version, KOTLIN_VERSION_FLOOR) >= 0) {
            "daemon"
        } else {
            "subprocess [<$KOTLIN_VERSION_FLOOR]"
        }
        val rawPath = paths?.kotlincBin(version) ?: ""
        KotlinInfo(version, mode, abbreviateHomePath(rawPath, home))
    }

    val jdkInfo = project?.build?.jdk?.let { version ->
        val rawPath = paths?.javaBin(version) ?: ""
        JdkInfo(version, abbreviateHomePath(rawPath, home))
    }

    val projectInfo = project?.let {
        ProjectInfo(it.name, it.version, it.kind, it.build.target)
    }

    return InfoSnapshot(
        koltVersion = KOLT_VERSION,
        koltPath = koltPath,
        koltHomeDisplay = koltHomeDisplay,
        // Total size is deferred to `kolt info --verbose` (issue #207):
        // walking multi-GB caches makes the default path sluggish.
        koltHomeBytes = null,
        kotlin = kotlinInfo,
        jdk = jdkInfo,
        host = hostString(),
        project = projectInfo
    )
}

private fun loadProjectForInfo(): KoltConfig? {
    if (!fileExists(KOLT_TOML)) return null
    val toml = readFileAsString(KOLT_TOML).getOrElse { return null }
    return parseConfig(toml).getOrElse { null }
}

@OptIn(ExperimentalForeignApi::class)
private fun hostString(): String = memScoped {
    val buf = alloc<utsname>()
    if (uname(buf.ptr) != 0) return@memScoped "unknown"
    val sysname = buf.sysname.toKString().lowercase()
    val machine = buf.machine.toKString()
    "$sysname-$machine"
}
