package kolt.cli

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse
import kolt.build.daemon.KOTLIN_VERSION_FLOOR
import kolt.config.ConfigError
import kolt.config.KOLT_VERSION
import kolt.config.KoltConfig
import kolt.config.KoltPaths
import kolt.config.parseConfig
import kolt.config.resolveKoltPaths
import kolt.infra.absolutise
import kolt.infra.currentWorkingDirectory
import kolt.infra.directorySize
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import platform.posix.uname
import platform.posix.utsname

internal data class HomeBreakdown(
    val cacheBytes: Long,
    val toolchainsBytes: Long,
    val daemonBytes: Long,
    val toolsBytes: Long,
    val cachePath: String,
    val toolchainsPath: String,
    val daemonPath: String,
    val toolsPath: String,
) {
    val totalBytes: Long get() = cacheBytes + toolchainsBytes + daemonBytes + toolsBytes
}

internal data class KotlinInfo(
    val version: String,
    val mode: String,
    val path: String,
    val requestedVersion: String? = null,
    val daemonBaseline: String? = null,
    val subprocessFallbackReason: String? = null,
)

internal data class JdkInfo(
    val version: String?,
    val path: String,
    val source: String? = null,
)

internal data class ProjectInfo(
    val name: String,
    val version: String,
    val kind: String,
    val target: String,
    val manifestPath: String? = null,
    val dependencyCount: Int? = null,
    val testDependencyCount: Int? = null,
    val enabledPlugins: List<String>? = null,
)

internal data class InfoSnapshot(
    val koltVersion: String,
    val koltPath: String?,
    val koltHomeDisplay: String?,
    val koltHomeBytes: Long?,
    val kotlin: KotlinInfo?,
    val jdk: JdkInfo?,
    val host: String,
    val project: ProjectInfo?,
    val koltHomeBreakdown: HomeBreakdown? = null,
    val parseError: String? = null,
)

internal sealed class ProjectLoad {
    data object NotAProject : ProjectLoad()
    data class Loaded(val config: KoltConfig) : ProjectLoad()
    data class ParseFailed(val message: String) : ProjectLoad()
}

private const val UNKNOWN_DISPLAY = "(unknown)"

internal data class InfoOptions(val verbose: Boolean, val json: Boolean)

private const val LABEL_WIDTH = 12
private const val VERBOSE_LABEL_WIDTH = 14
private const val SUB_INDENT = "  "

private fun labeled(label: String, value: String, width: Int = LABEL_WIDTH): String =
    label.padEnd(width) + value

private fun subLabeled(label: String, value: String): String =
    SUB_INDENT + label.padEnd(VERBOSE_LABEL_WIDTH) + value

internal fun formatInfo(snap: InfoSnapshot, verbose: Boolean = false): String =
    if (verbose) formatInfoVerbose(snap) else formatInfoDefault(snap)

private fun formatInfoDefault(snap: InfoSnapshot): String = buildString {
    appendLine(labeled("kolt", "v${snap.koltVersion} (${snap.koltPath ?: UNKNOWN_DISPLAY})"))

    val home = snap.koltHomeDisplay ?: UNKNOWN_DISPLAY
    val homeValue = if (snap.koltHomeBytes != null) {
        "$home (${formatBytes(snap.koltHomeBytes)})"
    } else {
        home
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
        append(projectPlaceholder(snap.parseError))
    }
}.trimEnd('\n')

private fun projectPlaceholder(parseError: String?): String =
    if (parseError != null) {
        "(kolt.toml failed to parse -- see error above)"
    } else {
        "(not in a kolt project -- no kolt.toml in current directory)"
    }

private fun formatInfoVerbose(snap: InfoSnapshot): String = buildString {
    appendLine(labeled("kolt", "v${snap.koltVersion} (${snap.koltPath ?: UNKNOWN_DISPLAY})", VERBOSE_LABEL_WIDTH))
    appendLine()

    val home = snap.koltHomeDisplay ?: UNKNOWN_DISPLAY
    val homeValue = if (snap.koltHomeBytes != null) {
        "$home (${formatBytes(snap.koltHomeBytes)})"
    } else {
        home
    }
    appendLine(labeled("kolt home", homeValue, VERBOSE_LABEL_WIDTH))
    snap.koltHomeBreakdown?.let { b ->
        appendLine(subLabeled("cache", "${b.cachePath} (${formatBytes(b.cacheBytes)})"))
        appendLine(subLabeled("toolchains", "${b.toolchainsPath} (${formatBytes(b.toolchainsBytes)})"))
        appendLine(subLabeled("daemon", "${b.daemonPath} (${formatBytes(b.daemonBytes)})"))
        appendLine(subLabeled("tools", "${b.toolsPath} (${formatBytes(b.toolsBytes)})"))
    }

    snap.kotlin?.let { k ->
        appendLine()
        appendLine(labeled("kotlin", "${k.version} (${k.mode})", VERBOSE_LABEL_WIDTH))
        k.requestedVersion?.let { appendLine(subLabeled("requested", it)) }
        appendLine(subLabeled("resolved", k.version))
        appendLine(subLabeled("compiler", k.path))
        k.daemonBaseline?.let { appendLine(subLabeled("daemon base", it)) }
        k.subprocessFallbackReason?.let { appendLine(subLabeled("fallback", it)) }
    }

    snap.jdk?.let { j ->
        appendLine()
        val versionPart = j.version.orEmpty()
        val sourcePart = j.source?.let { "($it)" } ?: ""
        val headerValue = listOf(versionPart, sourcePart).filter { it.isNotEmpty() }.joinToString(" ")
        appendLine(labeled("jdk", headerValue, VERBOSE_LABEL_WIDTH))
        appendLine(subLabeled("path", j.path))
        j.source?.let { appendLine(subLabeled("source", it)) }
    }

    appendLine()
    appendLine(labeled("host", snap.host, VERBOSE_LABEL_WIDTH))

    if (snap.project != null) {
        appendLine()
        appendLine(labeled("project", "${snap.project.name} v${snap.project.version}", VERBOSE_LABEL_WIDTH))
        snap.project.manifestPath?.let { appendLine(subLabeled("manifest", it)) }
        appendLine(subLabeled("kind", snap.project.kind))
        appendLine(subLabeled("target", snap.project.target))
        snap.project.dependencyCount?.let { appendLine(subLabeled("dependencies", it.toString())) }
        snap.project.testDependencyCount?.let { appendLine(subLabeled("test deps", it.toString())) }
        snap.project.enabledPlugins
            ?.takeIf { it.isNotEmpty() }
            ?.let { appendLine(subLabeled("plugins", it.joinToString(", "))) }
    } else {
        appendLine()
        append(projectPlaceholder(snap.parseError))
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

internal fun parseInfoArgs(args: List<String>): Result<InfoOptions, String> {
    var verbose = false
    var json = false
    val usage = "usage: kolt info [--verbose] [--format=json]"
    for (arg in args) {
        when {
            arg == "--verbose" -> verbose = true
            arg == "--format=json" -> json = true
            arg.startsWith("--format=") -> return Err(
                "error: unsupported --format value '${arg.removePrefix("--format=")}' (only 'json' is supported)\n$usage"
            )
            else -> return Err("error: unknown flag '$arg'\n$usage")
        }
    }
    return Ok(InfoOptions(verbose = verbose, json = json))
}

internal fun doInfo(args: List<String>): Result<Unit, Int> {
    val opts = parseInfoArgs(args).getOrElse { msg ->
        eprintln(msg)
        return Err(EXIT_CONFIG_ERROR)
    }
    // `--verbose --format=json` is equivalent to `--format=json` — json always
    // carries the full field set.
    val snap = gatherInfo(verbose = opts.verbose || opts.json)
    snap.parseError?.let { eprintln("error: $it") }
    if (opts.json) {
        println(formatInfoJson(snap))
    } else {
        println(formatInfo(snap, verbose = opts.verbose))
    }
    return if (snap.parseError != null) Err(EXIT_CONFIG_ERROR) else Ok(Unit)
}

@OptIn(ExperimentalForeignApi::class)
private fun gatherInfo(verbose: Boolean): InfoSnapshot {
    val koltPath = readSelfExe().getOrElse { null }
    val home = homeDirectory().getOrElse { null }.orEmpty()
    val paths = resolveKoltPaths().getOrElse { null }

    val koltHomeAbs = paths?.let { "${it.home}/.kolt" }
    val koltHomeDisplay = koltHomeAbs?.let { abbreviateHomePath(it, home) }

    val breakdown = if (verbose && paths != null) walkHomeBreakdown(paths, home) else null
    val koltHomeBytes = breakdown?.totalBytes

    val load = loadProjectForInfo()
    val project = (load as? ProjectLoad.Loaded)?.config
    val parseError = (load as? ProjectLoad.ParseFailed)?.message

    val kotlinInfo = project?.let { config ->
        val version = config.kotlin.effectiveCompiler
        val onDaemon = compareVersions(version, KOTLIN_VERSION_FLOOR) >= 0
        val mode = if (onDaemon) "daemon" else "subprocess [<$KOTLIN_VERSION_FLOOR]"
        val rawPath = paths?.kotlincBin(version) ?: ""
        KotlinInfo(
            version = version,
            mode = mode,
            path = abbreviateHomePath(rawPath, home),
            requestedVersion = if (verbose) config.kotlin.version else null,
            daemonBaseline = if (verbose) KOTLIN_VERSION_FLOOR else null,
            subprocessFallbackReason = if (verbose && !onDaemon) {
                "compiler $version is below daemon baseline $KOTLIN_VERSION_FLOOR"
            } else null,
        )
    }

    // JDK only surfaces for JVM builds. Native targets don't invoke `java`,
    // so reporting a "system" JDK there would mislead users — the field has
    // no bearing on the build.
    val jdkInfo = project?.takeIf { it.build.target == "jvm" }?.let { config ->
        val managedVersion = config.build.jdk
        if (managedVersion != null) {
            val rawPath = paths?.javaBin(managedVersion) ?: ""
            JdkInfo(
                version = managedVersion,
                path = abbreviateHomePath(rawPath, home),
                source = if (verbose) "managed" else null,
            )
        } else if (verbose) {
            // The build uses `java` from PATH (Runner.kt:18); kolt does not
            // consult $JAVA_HOME. Report "system" so the disclosure matches
            // behavior rather than speculating about an env var kolt ignores.
            JdkInfo(version = null, path = "java", source = "system")
        } else null
    }

    val projectInfo = project?.let {
        ProjectInfo(
            name = it.name,
            version = it.version,
            kind = it.kind,
            target = it.build.target,
            manifestPath = if (verbose) absoluteManifestPath() else null,
            dependencyCount = if (verbose) it.dependencies.size else null,
            testDependencyCount = if (verbose) it.testDependencies.size else null,
            enabledPlugins = if (verbose) {
                it.kotlin.plugins.filterValues { enabled -> enabled }.keys.sorted()
            } else null,
        )
    }

    return InfoSnapshot(
        koltVersion = KOLT_VERSION,
        koltPath = koltPath,
        koltHomeDisplay = koltHomeDisplay,
        // Default mode skips the walk because multi-GB caches make the path
        // sluggish. Verbose opts in via `walkHomeBreakdown` and shares the
        // subdir totals with the home-level total (no double walk).
        koltHomeBytes = koltHomeBytes,
        kotlin = kotlinInfo,
        jdk = jdkInfo,
        host = hostString(),
        project = projectInfo,
        koltHomeBreakdown = breakdown,
        parseError = parseError,
    )
}

private fun walkHomeBreakdown(paths: KoltPaths, home: String): HomeBreakdown =
    HomeBreakdown(
        cacheBytes = directorySize(paths.cacheBase),
        toolchainsBytes = directorySize(paths.toolchainsDir),
        daemonBytes = directorySize(paths.daemonBaseDir),
        toolsBytes = directorySize(paths.toolsDir),
        cachePath = abbreviateHomePath(paths.cacheBase, home),
        toolchainsPath = abbreviateHomePath(paths.toolchainsDir, home),
        daemonPath = abbreviateHomePath(paths.daemonBaseDir, home),
        toolsPath = abbreviateHomePath(paths.toolsDir, home),
    )

private fun absoluteManifestPath(): String {
    val cwd = currentWorkingDirectory() ?: return KOLT_TOML
    return absolutise(KOLT_TOML, cwd)
}

internal fun loadProjectForInfo(): ProjectLoad {
    if (!fileExists(KOLT_TOML)) return ProjectLoad.NotAProject
    val toml = readFileAsString(KOLT_TOML).getOrElse { err ->
        return ProjectLoad.ParseFailed("could not read ${err.path}")
    }
    val config = parseConfig(toml).getOrElse { err ->
        val message = when (err) { is ConfigError.ParseFailed -> err.message }
        return ProjectLoad.ParseFailed(message)
    }
    return ProjectLoad.Loaded(config)
}

@OptIn(ExperimentalForeignApi::class)
private fun hostString(): String = memScoped {
    val buf = alloc<utsname>()
    if (uname(buf.ptr) != 0) return@memScoped "unknown"
    val sysname = buf.sysname.toKString().lowercase()
    val machine = buf.machine.toKString()
    "$sysname-$machine"
}

// `kolt info --format=json` schema. Consumed by editors / IDEs / CI, so the
// field set is additive-only: rename = breaking change, removal = breaking
// change. `explicitNulls = false` keeps unavailable fields out of the
// payload; new fields must default to null/empty to stay backward-compatible
// with older consumers.
@Serializable
private data class InfoJson(
    val kolt: InfoKoltJson,
    val host: String,
    val kotlin: InfoKotlinJson? = null,
    val jdk: InfoJdkJson? = null,
    val project: InfoProjectJson? = null,
    val parseError: String? = null,
)

@Serializable
private data class InfoKoltJson(
    val version: String,
    val path: String? = null,
    val home: String? = null,
    val homeBytes: Long? = null,
    val cacheBytes: Long? = null,
    val toolchainsBytes: Long? = null,
    val daemonBytes: Long? = null,
    val toolsBytes: Long? = null,
)

@Serializable
private data class InfoKotlinJson(
    val requestedVersion: String,
    val resolvedVersion: String,
    val mode: String,
    val compilerPath: String,
    val daemonBaseline: String,
    val subprocessFallbackReason: String? = null,
)

@Serializable
private data class InfoJdkJson(
    val version: String? = null,
    val path: String,
    val source: String,
)

@Serializable
private data class InfoProjectJson(
    val name: String,
    val version: String,
    val kind: String,
    val target: String,
    val manifestPath: String,
    val dependencyCount: Int,
    val testDependencyCount: Int,
    val enabledPlugins: List<String>,
)

private val infoJson = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
    encodeDefaults = false
    explicitNulls = false
}

internal fun formatInfoJson(snap: InfoSnapshot): String {
    val kotlin = snap.kotlin?.let { k ->
        InfoKotlinJson(
            requestedVersion = k.requestedVersion ?: k.version,
            resolvedVersion = k.version,
            mode = k.mode,
            compilerPath = k.path,
            daemonBaseline = k.daemonBaseline ?: KOTLIN_VERSION_FLOOR,
            subprocessFallbackReason = k.subprocessFallbackReason,
        )
    }
    val jdk = snap.jdk?.let { j ->
        InfoJdkJson(
            version = j.version,
            path = j.path,
            source = j.source ?: "managed",
        )
    }
    val project = snap.project?.let { p ->
        InfoProjectJson(
            name = p.name,
            version = p.version,
            kind = p.kind,
            target = p.target,
            manifestPath = p.manifestPath ?: KOLT_TOML,
            dependencyCount = p.dependencyCount ?: 0,
            testDependencyCount = p.testDependencyCount ?: 0,
            enabledPlugins = p.enabledPlugins.orEmpty(),
        )
    }
    val dto = InfoJson(
        kolt = InfoKoltJson(
            version = snap.koltVersion,
            path = snap.koltPath,
            home = snap.koltHomeDisplay,
            homeBytes = snap.koltHomeBytes,
            cacheBytes = snap.koltHomeBreakdown?.cacheBytes,
            toolchainsBytes = snap.koltHomeBreakdown?.toolchainsBytes,
            daemonBytes = snap.koltHomeBreakdown?.daemonBytes,
            toolsBytes = snap.koltHomeBreakdown?.toolsBytes,
        ),
        host = snap.host,
        kotlin = kotlin,
        jdk = jdk,
        project = project,
        parseError = snap.parseError,
    )
    return infoJson.encodeToString(InfoJson.serializer(), dto)
}
