package kolt.daemon

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.mapBoth
import kolt.daemon.ic.BtaIncrementalCompiler
import kolt.daemon.ic.SelfHealingIncrementalCompiler
import kolt.daemon.ic.StderrIcMetricsSink
import kolt.daemon.reaper.IcReaper
import kolt.daemon.server.DaemonConfig
import kolt.daemon.server.DaemonServer
import java.io.File
import java.nio.file.Path
import kotlin.system.exitProcess

// Kotlin compiler version pin. ADR 0019 §1 requires this to move in
// lockstep with the `kotlin-build-tools-impl` artifact version in
// `kolt-compiler-daemon/ic/build.gradle.kts`. Both are updated together
// whenever the daemon's kotlinc is bumped. This constant is read by
// `DaemonServer` to stamp the IC state directory
// (`~/.kolt/daemon/ic/<this>/<projectHash>/`, ADR 0019 §5) so that a
// compiler bump invalidates the cache in one move.
internal const val KOLT_DAEMON_KOTLIN_VERSION: String = "2.3.20"

// Root of daemon-owned IC state per ADR 0019 §5. Default resolves under
// `$HOME/.kolt/daemon/ic`; overridable via `--ic-root <path>` for tests
// and dogfood sandboxes so they can use a temp dir instead.
internal fun defaultIcRoot(): Path =
    Path.of(System.getProperty("user.home"), ".kolt", "daemon", "ic")

internal data class CliArgs(
    val socketPath: Path,
    // Retained per post-B-2a review decision: kolt-compiler-embeddable still
    // flows through this flag even though Phase B routes compile traffic through
    // BtaIncrementalCompiler. B-2c chose **not** to reuse it for plugin jars and
    // added a separate `--plugin-jars` flag instead (see `pluginJars` below), so
    // the two channels stay decoupled and a future retirement of `--compiler-jars`
    // does not need to coordinate with the plugin-plumbing code path. Unused
    // paths are ignored by the daemon rather than failing startup.
    // `MainCliArgsTest` pins the flag as required so a future contributor
    // cannot silently remove it from the parser without updating the native-
    // client spawn argv in lock-step.
    val compilerJars: List<File>,
    val btaImplJars: List<File>,
    // Override for the daemon-owned IC state root (ADR 0019 §5). Defaults
    // to `~/.kolt/daemon/ic`. Exposed as a flag primarily so the
    // integration tests can point at a per-run temp dir without touching
    // the developer's real cache.
    val icRoot: Path,
    // ADR 0019 §9 + B-2c: `kolt.toml [plugins]` alias → compiler plugin
    // classpath mapping. Parsed from `--plugin-jars alias=cp[;alias=cp]`
    // where each `cp` is a File.pathSeparator-joined list. Empty map =
    // no plugins requested; the adapter's PluginTranslator still drives
    // the no-op path in that case. The daemon is the sole consumer;
    // resolving these jars is the native client's responsibility.
    val pluginJars: Map<String, List<Path>>,
)

internal sealed interface CliError {
    data class UnknownFlag(val flag: String) : CliError
    data object MissingSocket : CliError
    data object MissingCompilerJars : CliError
    data object EmptyCompilerJars : CliError
    data object MissingBtaImplJars : CliError
    data object EmptyBtaImplJars : CliError
    data class MalformedPluginJars(val entry: String) : CliError
}

fun main(args: Array<String>) {
    val cli = parseArgs(args).mapBoth(
        success = { it },
        failure = { err ->
            System.err.println("kolt-compiler-daemon: ${formatCliError(err)}")
            System.err.println(
                "usage: kolt-compiler-daemon --socket <path> --compiler-jars <classpath> --bta-impl-jars <classpath>",
            )
            exitProcess(64)
        },
    )

    // ADR 0019 §7 observability: a stderr-backed sink so every
    // `ic.success` / `ic.fallback_to_full` / `ic.self_heal` / `ic.bta.*`
    // counter lands on the daemon's stderr stream in a single-line JSON
    // shape (`IcMetricsSink.kt`). `kolt doctor` and Phase B smoke tests
    // parse these lines back out. Wiring one instance and handing it to
    // both the adapter and the self-heal wrapper keeps the
    // "observability via metrics" rule from forking into two sinks.
    val metrics = StderrIcMetricsSink()

    // ADR 0019 §9: translate the CLI-supplied alias→classpath map into the
    // resolver signature BtaIncrementalCompiler expects. Unknown aliases
    // return `emptyList()` so PluginTranslator can surface a plugin-not-
    // found error at compile time rather than failing daemon startup.
    val pluginJarResolver: (String) -> List<Path> = { alias ->
        cli.pluginJars[alias] ?: emptyList()
    }

    val adapter = BtaIncrementalCompiler.create(
        btaImplJars = cli.btaImplJars.map { it.toPath() },
        pluginJarResolver = pluginJarResolver,
        metrics = metrics,
    ).mapBoth(
        success = { it },
        failure = { err ->
            System.err.println(
                "kolt-compiler-daemon: failed to initialise incremental compiler: " +
                    (err.cause.message ?: err.cause.javaClass.name),
            )
            exitProcess(70)
        },
    )

    // ADR 0019 §7: wrap the raw BTA adapter in the self-heal path so
    // `IcError.InternalError` (corrupt cache / BTA-internal failure) is
    // transparently recovered by wiping the per-project IC state and
    // retrying once. Daemon core sees only the post-retry result.
    val compiler = SelfHealingIncrementalCompiler(
        delegate = adapter,
        metrics = metrics,
    )

    // ADR 0019 §Negative follow-up (#125): fire the IC reaper on a
    // detached thread right after the daemon has bound its socket but
    // before it starts serving compiles. Cleanup never blocks the
    // first compile; a reaper failure is logged via `metrics` and
    // swallowed by DaemonServer's `runCatching` around `preServeHook`.
    val server = DaemonServer(
        socketPath = cli.socketPath,
        compiler = compiler,
        icRoot = cli.icRoot,
        kotlinVersion = KOLT_DAEMON_KOTLIN_VERSION,
        config = DaemonConfig(),
        preServeHook = {
            IcReaper.runDetached(cli.icRoot, KOLT_DAEMON_KOTLIN_VERSION, metrics)
        },
    )
    val reason = server.serve().mapBoth(
        success = { it },
        failure = { err ->
            System.err.println("kolt-compiler-daemon: serve failed: $err")
            exitProcess(71)
        },
    )
    System.err.println("kolt-compiler-daemon: exiting (${reason::class.simpleName})")
    exitProcess(0)
}

internal fun parseArgs(args: Array<String>): Result<CliArgs, CliError> {
    var socketPath: String? = null
    var compilerJars: String? = null
    var btaImplJars: String? = null
    var icRoot: String? = null
    var pluginJarsRaw: String? = null
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--socket" -> { socketPath = args.getOrNull(i + 1); i += 2 }
            "--compiler-jars" -> { compilerJars = args.getOrNull(i + 1); i += 2 }
            "--bta-impl-jars" -> { btaImplJars = args.getOrNull(i + 1); i += 2 }
            "--ic-root" -> { icRoot = args.getOrNull(i + 1); i += 2 }
            "--plugin-jars" -> { pluginJarsRaw = args.getOrNull(i + 1); i += 2 }
            else -> return Err(CliError.UnknownFlag(args[i]))
        }
    }
    if (socketPath == null) return Err(CliError.MissingSocket)
    if (compilerJars == null) return Err(CliError.MissingCompilerJars)
    if (btaImplJars == null) return Err(CliError.MissingBtaImplJars)
    val cjars = compilerJars.split(File.pathSeparator).filter { it.isNotBlank() }.map { File(it) }
    if (cjars.isEmpty()) return Err(CliError.EmptyCompilerJars)
    val bjars = btaImplJars.split(File.pathSeparator).filter { it.isNotBlank() }.map { File(it) }
    if (bjars.isEmpty()) return Err(CliError.EmptyBtaImplJars)
    val icRootPath = icRoot?.let(Path::of) ?: defaultIcRoot()
    val pluginJars = parsePluginJars(pluginJarsRaw).mapBoth(
        success = { it },
        failure = { return Err(it) },
    )
    return Ok(CliArgs(Path.of(socketPath), cjars, bjars, icRootPath, pluginJars))
}

// Parses `alias=cp[;alias=cp]` where each `cp` is a File.pathSeparator-joined
// list of plugin jar paths. `null` or empty input collapses to an empty map.
// Each entry must contain exactly one `=` and a non-empty alias + classpath;
// otherwise `MalformedPluginJars` carries the offending raw entry back.
internal fun parsePluginJars(raw: String?): Result<Map<String, List<Path>>, CliError> {
    if (raw.isNullOrBlank()) return Ok(emptyMap())
    val out = linkedMapOf<String, List<Path>>()
    for (entry in raw.split(';')) {
        if (entry.isBlank()) continue
        val eq = entry.indexOf('=')
        if (eq <= 0 || eq == entry.length - 1) return Err(CliError.MalformedPluginJars(entry))
        val alias = entry.substring(0, eq).trim()
        val cp = entry.substring(eq + 1)
            .split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .map(Path::of)
        if (alias.isEmpty() || cp.isEmpty()) return Err(CliError.MalformedPluginJars(entry))
        out[alias] = cp
    }
    return Ok(out)
}

private fun formatCliError(err: CliError): String = when (err) {
    is CliError.UnknownFlag -> "unknown flag: ${err.flag}"
    CliError.MissingSocket -> "--socket is required"
    CliError.MissingCompilerJars -> "--compiler-jars is required"
    CliError.EmptyCompilerJars -> "--compiler-jars resolved to zero paths"
    CliError.MissingBtaImplJars -> "--bta-impl-jars is required"
    CliError.EmptyBtaImplJars -> "--bta-impl-jars resolved to zero paths"
    is CliError.MalformedPluginJars ->
        "--plugin-jars entry is malformed (expected `alias=cp[;alias=cp]`): ${err.entry}"
}
