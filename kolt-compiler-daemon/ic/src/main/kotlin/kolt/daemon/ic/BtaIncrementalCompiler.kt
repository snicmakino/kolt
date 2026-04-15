@file:OptIn(ExperimentalBuildToolsApi::class)

package kolt.daemon.ic

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import org.jetbrains.kotlin.buildtools.api.BuildOperation
import org.jetbrains.kotlin.buildtools.api.CompilationResult
import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.KotlinLogger
import org.jetbrains.kotlin.buildtools.api.KotlinToolchains
import org.jetbrains.kotlin.buildtools.api.SharedApiClassesClassLoader
import org.jetbrains.kotlin.buildtools.api.SourcesChanges
import org.jetbrains.kotlin.buildtools.api.arguments.CommonCompilerArguments
import org.jetbrains.kotlin.buildtools.api.arguments.JvmCompilerArguments
import org.jetbrains.kotlin.buildtools.api.jvm.JvmPlatformToolchain.Companion.jvm
import org.jetbrains.kotlin.buildtools.api.jvm.operations.JvmCompilationOperation
import org.jetbrains.kotlin.buildtools.api.trackers.BuildMetricsCollector
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

// Adapter over kotlin-build-tools-api 2.3.20 per ADR 0019 §3. B-2a drives a
// **full recompile** through BTA (no SourcesChanges / workingDirectory state,
// no IC configuration attached to the JvmCompilationOperation). The goal of
// this class in B-2a is to prove classloader topology + compile path work end
// to end; IC enablement + self-heal land in B-2b.
//
// Classloader topology (issue #112 acceptance criterion 2 relies on this):
//
//   daemon-core classloader
//     └─ SharedApiClassesClassLoader  (serves org.jetbrains.kotlin.buildtools.api.*
//         └─ URLClassLoader            only — no -impl classes reachable upward)
//             └─ kotlin-build-tools-impl-*.jar [+ transitive]
//
// The entire BTA call sequence is fenced by a single `try { ... } catch (Throwable)`
// at the adapter boundary so that a thrown `KotlinBuildToolsException` (or any other
// failure mode on the experimental surface) becomes an `IcError.InternalError`
// instead of propagating into daemon core. Per ADR 0019 §7, `InternalError` is the
// signal B-2b's self-heal path keys off — it must cover both (a) thrown exceptions
// and (b) non-COMPILATION_ERROR `CompilationResult` values (`COMPILER_INTERNAL_ERROR`,
// `COMPILATION_OOM_ERROR`), because those are compiler-infrastructure failures, not
// user-code type errors. Only `COMPILATION_ERROR` represents "the user's source
// does not type-check" and is the one variant that must reach daemon core as
// `CompilationFailed`.
class BtaIncrementalCompiler private constructor(
    private val toolchain: KotlinToolchains,
    // Maps a kolt.toml plugin alias (e.g. "serialization") to the plugin jar
    // classpath on disk. Injected at construction time so daemon startup owns
    // the policy (walk a plugin-jars directory, read from --plugin-jars CLI,
    // etc.) and :ic stays a pure translator. B-2a defaults this to an empty-
    // result resolver — the translation path is still exercised end to end,
    // and real plugin jar delivery is wired in daemon core later (B-2b/B-2c).
    private val pluginJarResolver: (alias: String) -> List<Path>,
    // ADR 0019 §7 observability: adapter-level counters are recorded here.
    // Defaults to no-op so tests that only care about the Result<,> shape
    // stay terse; Main wires `StderrIcMetricsSink` in production so
    // dogfood runs leave a parsable structured-metric trail.
    private val metrics: IcMetricsSink,
) : IncrementalCompiler {

    override fun compile(request: IcRequest): Result<IcResponse, IcError> =
        try {
            executeCompile(request)
        } catch (vme: VirtualMachineError) {
            // ADR 0019 §7: JVM-fatal errors are **not** absorbed. Routing an
            // `OutOfMemoryError` through `IcError.InternalError` would fire
            // `SelfHealingIncrementalCompiler`'s wipe+retry path, which
            // allocates more objects and reproduces the OOM in a loop. Let
            // the VME propagate past daemon core so `FallbackCompilerBackend`
            // (ADR 0016 §5) can take over — that is the escape hatch for
            // "the daemon JVM itself is broken".
            throw vme
        } catch (t: Throwable) {
            Err(IcError.InternalError(t))
        }

    private fun executeCompile(request: IcRequest): Result<IcResponse, IcError> {
        // ADR 0019 §5: the adapter is the sole writer under `workingDir`.
        // Lazy mkdir (not eager on daemon startup) because the daemon does
        // not learn which project is compiling until the first request
        // arrives. `createDirectories` is a no-op if the tree already
        // exists, which is the common case for every non-initial request.
        val wasEmptyBeforeCompile = isEmptyDir(request.workingDir)
        Files.createDirectories(request.workingDir)

        val builder = toolchain.jvm.jvmCompilationOperationBuilder(request.sources, request.outputDir)
        if (request.classpath.isNotEmpty()) {
            builder.compilerArguments[JvmCompilerArguments.CLASSPATH] =
                request.classpath.joinToString(File.pathSeparator) { it.toString() }
        }
        builder.compilerArguments[JvmCompilerArguments.MODULE_NAME] = moduleNameFor(request)

        // ADR 0019 §9: kolt.toml [plugins] → List<CompilerPlugin> translation
        // happens inside the adapter, not in daemon core. An empty list here is
        // a "no plugins requested" signal and is the common case; emitting it
        // unconditionally keeps the wiring obvious to a reader.
        val translatedPlugins = PluginTranslator.translate(request.projectRoot, pluginJarResolver)
        builder.compilerArguments[CommonCompilerArguments.COMPILER_PLUGINS] = translatedPlugins

        // ADR 0019 §5 + §Negative: attach the snapshot-based IC configuration
        // so BTA runs in incremental mode. `SourcesChanges.ToBeCalculated`
        // asks BTA to diff against its own persisted state under `workingDir`
        // — the wire protocol does not carry a dirty-file delta (§4), and
        // `kolt watch` (#15) is the future place where `SourcesChanges.Known`
        // would show up. `dependenciesSnapshotFiles` is empty for now: per-
        // entry `ClasspathEntrySnapshot` computation is filed as a post-B-2
        // follow-up, because the shipping first-cut handles the source-only
        // iterative-edit case (the bimodal IC win the spike measured), and
        // classpath changes go through kolt's existing BuildCache gate
        // before they ever reach the daemon.
        //
        // `shrunkClasspathSnapshot` is still a required argument to
        // `snapshotBasedIcConfigurationBuilder`, so we pass a daemon-owned
        // path under `workingDir`. With empty `dependenciesSnapshotFiles`
        // there is nothing for BTA to shrink, and the impl in 2.3.20 may
        // never create the file — the spike observed a 4-byte empty
        // snapshot when deps were empty. The file name is promoted to the
        // `SHRUNK_CLASSPATH_SNAPSHOT` constant so the post-B-2 reaper (and
        // any future debugging session that wonders why this file
        // occasionally does not exist) can recognise it.
        val shrunkClasspathSnapshot = request.workingDir.resolve(SHRUNK_CLASSPATH_SNAPSHOT)
        val icConfig = builder.snapshotBasedIcConfigurationBuilder(
            workingDirectory = request.workingDir,
            sourcesChanges = SourcesChanges.ToBeCalculated,
            dependenciesSnapshotFiles = emptyList(),
            shrunkClasspathSnapshot = shrunkClasspathSnapshot,
        ).build()
        builder[JvmCompilationOperation.INCREMENTAL_COMPILATION] = icConfig

        // ADR 0019 §7 "observability via metrics": attach a BTA-level
        // `BuildMetricsCollector` that forwards every internal counter to
        // the adapter's `IcMetricsSink` with an `ic.bta.` prefix. A future
        // `kolt doctor` or smoke test can then see the raw BTA numbers
        // (compiled file counts, lines analysed, etc.) without daemon
        // core knowing the metric names.
        builder[BuildOperation.METRICS_COLLECTOR] = object : BuildMetricsCollector {
            override fun collectMetric(
                name: String,
                type: BuildMetricsCollector.ValueType,
                value: Long,
            ) {
                metrics.record("ic.bta.${sanitiseMetricName(name)}", value)
            }
        }

        // ADR 0019 §7: diagnostic capture via `KotlinLogger`. BTA does not
        // return compile diagnostics in the `CompilationResult` value; the
        // only path to per-source-position messages is to inject a logger
        // and collect what BTA writes through it during the compile. We
        // keep the captured messages on a local collector and, on
        // `COMPILATION_ERROR`, fold the `error`-level lines into
        // `IcError.CompilationFailed.messages` so daemon core (and the
        // native client) can surface real compile errors to the user.
        // Info / warn / debug lines are forwarded to the metrics sink as
        // coarse `ic.log.<level>` counters so they stay observable without
        // crowding the user-facing diagnostics stream.
        val diagnostics = CapturingKotlinLogger(metrics)

        // ADR 0019 §6: per-request `BuildSession` open/close. The session
        // is JVM-local and AutoCloseable; caching it across requests was
        // explicitly considered and rejected (B-2b sync note) because the
        // daemon serialises compile traffic and the payoff is not worth
        // the classloader-leak / state-mixing debugging surface.
        //
        // NB: `builder.build()` is a pure configuration snapshot and
        // lives outside the session scope — same pattern the spike #104
        // reference uses. `executeOperation` is the only BTA call that
        // needs the session to be open, and it is the only one inside
        // the `use { }` block.
        val policy = toolchain.createInProcessExecutionPolicy()
        val start = TimeSource.Monotonic.markNow()
        val op = builder.build()
        val compilationResult = toolchain.createBuildSession().use { session ->
            session.executeOperation(op, policy, diagnostics)
        }
        val wall = start.elapsedNow().toLong(DurationUnit.MILLISECONDS)

        return when (compilationResult) {
            CompilationResult.COMPILATION_SUCCESS -> {
                metrics.record(METRIC_SUCCESS)
                metrics.record(METRIC_WALL_MS, wall)
                // ADR 0019 §7: `ic.fallback_to_full` is a metrics-only
                // signal (no stderr spam, unlike self-heal). We emit it
                // whenever this compile started from an empty working
                // dir — cold first run or post-self-heal retry — because
                // those are exactly the cases where BTA has no prior
                // state to diff against and does a full recompile. A
                // finer heuristic based on BTA's internal "compiled
                // file count" metric would be more accurate but ties
                // the adapter to a specific BTA metric name; the
                // empty-dir heuristic is cheap and version-stable.
                if (wasEmptyBeforeCompile) {
                    metrics.record(METRIC_FALLBACK_TO_FULL)
                }
                Ok(
                    IcResponse(
                        wallMillis = wall,
                        compiledFileCount = null,
                    ),
                )
            }
            // User source does not type-check. The captured logger
            // `error` lines are the actual kotlinc diagnostics; we fall
            // back to a synthetic "kotlinc reported ..." line only when
            // BTA reports COMPILATION_ERROR without emitting any error
            // log entry (shouldn't happen in practice, but we must not
            // surface an empty `messages` list — daemon core prints it
            // verbatim to the user's stderr). Self-heal must NOT fire on
            // this path: a wipe+retry would just reproduce the same
            // type error.
            CompilationResult.COMPILATION_ERROR -> {
                val captured = diagnostics.errorMessages()
                Err(
                    IcError.CompilationFailed(
                        messages = captured.ifEmpty {
                            listOf("kotlinc reported $compilationResult")
                        },
                    ),
                )
            }
            // Compiler-infrastructure failures, not user-code failures. Routed as
            // `InternalError` so B-2b's `wipe workingDir + full recompile retry`
            // path (ADR 0019 §7) can fire: OOM often clears on retry with a fresh
            // heap state, and `COMPILER_INTERNAL_ERROR` is indistinguishable from
            // a thrown `KotlinBuildToolsException` in terms of the right recovery.
            CompilationResult.COMPILATION_OOM_ERROR,
            CompilationResult.COMPILER_INTERNAL_ERROR,
            -> Err(
                IcError.InternalError(
                    RuntimeException("kotlinc reported $compilationResult"),
                ),
            )
        }
    }

    private fun moduleNameFor(request: IcRequest): String =
        // projectId is a stable caller-derived hash per ADR 0019 §3; it is
        // already URL/FS-safe enough to serve as a kotlinc module name.
        "kolt-${request.projectId}"

    // Captures BTA's compile-time log output. `error`-level lines are
    // retained for folding into `IcError.CompilationFailed.messages`;
    // `warn` / `info` / `debug` / `lifecycle` lines are forwarded to
    // the metrics sink as coarse counters so log volume is observable
    // without polluting the user's compile-error stream. Throwables
    // attached to error/warn calls are unwrapped and appended to the
    // captured line so a user chasing a BTA-internal stack trace still
    // sees the message.
    private class CapturingKotlinLogger(
        private val metrics: IcMetricsSink,
    ) : KotlinLogger {
        private val errors: MutableList<String> = mutableListOf()

        override val isDebugEnabled: Boolean get() = false

        override fun error(msg: String, throwable: Throwable?) {
            errors.add(if (throwable == null) msg else "$msg: ${throwable.message ?: throwable.javaClass.name}")
            metrics.record(METRIC_LOG_ERROR)
        }

        override fun warn(msg: String, throwable: Throwable?) {
            metrics.record(METRIC_LOG_WARN)
        }

        override fun info(msg: String) {
            metrics.record(METRIC_LOG_INFO)
        }

        override fun debug(msg: String) {
            // Not recorded: debug is too noisy to count per line and
            // `isDebugEnabled = false` keeps BTA from even producing it.
        }

        override fun lifecycle(msg: String) {
            metrics.record(METRIC_LOG_LIFECYCLE)
        }

        fun errorMessages(): List<String> = errors.toList()
    }

    private fun isEmptyDir(workingDir: Path): Boolean {
        if (!Files.exists(workingDir)) return true
        if (!Files.isDirectory(workingDir)) return false
        Files.newDirectoryStream(workingDir).use { stream ->
            return !stream.iterator().hasNext()
        }
    }

    private fun sanitiseMetricName(raw: String): String {
        // BTA metric names include spaces, `:`, `->`, and other
        // separators that break the stderr sink's single-line JSON
        // wire. Replace anything that would need escaping with `_`
        // so the wire stays simple. Letters, digits, `_`, `.`, `-`
        // pass through untouched.
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            sb.append(if (ch.isLetterOrDigit() || ch == '_' || ch == '.' || ch == '-') ch else '_')
        }
        return sb.toString()
    }

    companion object {
        // File name under `workingDir` where BTA persists the shrunk
        // classpath snapshot. Promoted to a constant so a reader can
        // grep both for the directory layout and for any future reaper
        // that needs to recognise it.
        internal const val SHRUNK_CLASSPATH_SNAPSHOT: String = "shrunk-classpath-snapshot.bin"

        internal const val METRIC_SUCCESS: String = "ic.success"
        internal const val METRIC_WALL_MS: String = "ic.wall_ms"
        internal const val METRIC_FALLBACK_TO_FULL: String = "ic.fallback_to_full"
        internal const val METRIC_LOG_ERROR: String = "ic.log.error"
        internal const val METRIC_LOG_WARN: String = "ic.log.warn"
        internal const val METRIC_LOG_INFO: String = "ic.log.info"
        internal const val METRIC_LOG_LIFECYCLE: String = "ic.log.lifecycle"


        // Loads kotlin-build-tools-impl from [btaImplJars] through a URLClassLoader
        // whose parent is SharedApiClassesClassLoader. This matches the isolation
        // policy used by Gradle's Kotlin plugin and by spike #104. Failure here is
        // load-bearing: if -impl classes cannot be found, BTA cannot initialise, so
        // we surface the classloader failure as an IcError.InternalError in the
        // Result returned to daemon core rather than throwing.
        fun create(
            btaImplJars: List<Path>,
            // Defaults to an empty-result resolver: every plugin alias maps to
            // an empty classpath. Production daemon startup overrides this with
            // a resolver that walks a known plugin-jars directory. B-2a's
            // acceptance criterion 4 requires the translation path to be
            // exercised, not for plugins to actually compile — so even this
            // default still attaches `COMPILER_PLUGINS` when kolt.toml lists
            // enabled entries.
            pluginJarResolver: (alias: String) -> List<Path> = { _ -> emptyList() },
            metrics: IcMetricsSink = NoopIcMetricsSink,
        ): Result<BtaIncrementalCompiler, IcError.InternalError> =
            try {
                require(btaImplJars.isNotEmpty()) {
                    "btaImplJars is empty — daemon must receive kotlin-build-tools-impl classpath via --bta-impl-jars"
                }
                val urls = btaImplJars.map { it.toUri().toURL() }.toTypedArray()
                val implLoader = URLClassLoader(urls, SharedApiClassesClassLoader())
                Ok(
                    BtaIncrementalCompiler(
                        toolchain = KotlinToolchains.loadImplementation(implLoader),
                        pluginJarResolver = pluginJarResolver,
                        metrics = metrics,
                    ),
                )
            } catch (vme: VirtualMachineError) {
                // Same rationale as `compile` above: a VME during classloader
                // construction (e.g. OOM loading the impl jars) is a daemon-
                // JVM-broken signal, not a recoverable `InternalError`. Let
                // it propagate to the daemon `main` which exits to
                // `FallbackCompilerBackend`.
                throw vme
            } catch (t: Throwable) {
                Err(IcError.InternalError(t))
            }
    }
}
