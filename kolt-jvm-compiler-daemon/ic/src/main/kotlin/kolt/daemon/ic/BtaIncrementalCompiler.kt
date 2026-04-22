@file:OptIn(ExperimentalBuildToolsApi::class)

package kolt.daemon.ic

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse
import org.jetbrains.kotlin.buildtools.api.BuildOperation
import org.jetbrains.kotlin.buildtools.api.CompilationResult
import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.KotlinToolchains
import org.jetbrains.kotlin.buildtools.api.SharedApiClassesClassLoader
import org.jetbrains.kotlin.buildtools.api.SourcesChanges
import org.jetbrains.kotlin.buildtools.api.arguments.JvmCompilerArguments
import org.jetbrains.kotlin.buildtools.api.jvm.JvmPlatformToolchain.Companion.jvm
import org.jetbrains.kotlin.buildtools.api.jvm.operations.JvmCompilationOperation
import org.jetbrains.kotlin.buildtools.api.trackers.BuildMetricsCollector
import java.io.File
import java.net.URLClassLoader
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
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
    // Maps a kolt.toml [kotlin.plugins] alias (e.g. "serialization") to the plugin jar
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
    // ADR 0019 §Negative follow-up (#127): caches ClasspathEntrySnapshot
    // files keyed by (path, mtime, size). Shared across projects under a
    // version-stamped directory so kotlin-stdlib and common deps are
    // snapshotted once per daemon lifetime.
    private val classpathSnapshotCache: ClasspathSnapshotCache,
) : IncrementalCompiler {

    // Advisory `flock` held on `<workingDir>/LOCK` for the daemon
    // process lifetime, keyed by `workingDir`. The IC reaper probes
    // `tryLock` on the same file; a held entry here means "this dir is
    // in use" and the reaper will skip it.
    //
    // Each entry caches both the `FileLock` and the backing
    // `FileChannel`. When `SelfHealingIncrementalCompiler` wipes
    // `workingDir` after a corruption, the LOCK file on disk is also
    // deleted — the cached entry then points at a dead inode and the
    // next compile must close the stale channel and re-acquire the
    // lock against the freshly-created file. A naive "cache by
    // containsKey" short-circuit would leave the daemon holding a dead
    // lock for the rest of its lifetime.
    private class HeldLock(val channel: FileChannel, val lock: FileLock)
    private val heldLocks: MutableMap<Path, HeldLock> = ConcurrentHashMap()

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
        Files.createDirectories(request.workingDir)

        // LOCK must be the first write on workingDir — IcReaper treats
        // LOCK-existence as proof the dir is alive, so any earlier write
        // opens a concurrent-boot wipe window. Contended LOCK continues:
        // reaper keys off existence, not ownership.
        ensureLock(request.workingDir).getOrElse { return Err(it) }

        val btaWorkingDir = request.workingDir.resolve(BTA_SUBDIR)
        val wasEmptyBeforeCompile = isEmptyDir(btaWorkingDir)
        Files.createDirectories(btaWorkingDir)

        // ADR 0019 §Negative follow-up (IC reaper): drop a `project.path`
        // breadcrumb next to BTA state so the reaper can decide whether
        // this projectId's source tree still exists. Idempotent on every
        // compile — overwrite is cheap and resilient to a truncation by
        // the self-heal path. Kept above `btaWorkingDir` because BTA
        // clears its workingDirectory on cold-path startup; a breadcrumb
        // sitting inside BTA state would be swept away on every first
        // compile. Failure to write is logged as a reaper concern and
        // does not fail the compile.
        runCatching {
            Files.writeString(
                request.workingDir.resolve(PROJECT_PATH_BREADCRUMB),
                request.projectRoot.toString(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
            )
        }.onFailure { metrics.record(METRIC_REAPER_BREADCRUMB_FAILED) }

        val builder = toolchain.jvm.jvmCompilationOperationBuilder(request.sources, request.outputDir)

        // ADR 0019 §9 + #148: kolt.toml [kotlin.plugins] → `-Xplugin=<jar>` freeArgs
        // translation happens inside the adapter, not in daemon core. The
        // translated list is pushed through `applyArgumentStrings` rather
        // than the structured `COMPILER_PLUGINS` key: the structured key is
        // BTA 2.3.20 only and rejects even an empty list on 2.3.0 / 2.3.10
        // impls, whereas `-Xplugin=` passthrough is accepted across the whole
        // 2.3.x family (verified by spike/bta-compat-138/REPORT.md §"Plugin-passthrough spike: GREEN").
        //
        // **Ordering is load-bearing.** `applyArgumentStrings` resets every
        // argument it does not mention back to the parser default — observed
        // failure: running it after `MODULE_NAME` was assigned via the
        // structured setter nulls out `moduleName` and BTA fails the compile
        // with `'moduleName' is null!` at `IncrementalJvmCompilerRunnerBase.makeServices`.
        // The structured `set(...)` calls therefore come AFTER the
        // passthrough so their values survive into the final compile.
        // #162: language/api-version args must ride the same applyArgumentStrings
        // batch as plugin args — the reset-to-default behavior documented above
        // would wipe whichever translator fed an earlier call.
        val translatedPluginArgs = PluginTranslator.translate(request.projectRoot, pluginJarResolver)
        val translatedLanguageArgs = LanguageVersionTranslator.translate(request.projectRoot)
        val freeArgs = translatedPluginArgs + translatedLanguageArgs
        if (freeArgs.isNotEmpty()) {
            builder.compilerArguments.applyArgumentStrings(freeArgs)
        }

        if (request.classpath.isNotEmpty()) {
            builder.compilerArguments[JvmCompilerArguments.CLASSPATH] =
                request.classpath.joinToString(File.pathSeparator) { it.toString() }
        }
        builder.compilerArguments[JvmCompilerArguments.MODULE_NAME] = moduleNameFor(request)

        // #127: cached by ClasspathSnapshotCache; see class doc for key/error policy.
        val dependenciesSnapshotFiles = classpathSnapshotCache.getOrComputeSnapshots(request.classpath)

        val shrunkClasspathSnapshot = btaWorkingDir.resolve(SHRUNK_CLASSPATH_SNAPSHOT)
        val icConfig = builder.snapshotBasedIcConfigurationBuilder(
            workingDirectory = btaWorkingDir,
            sourcesChanges = SourcesChanges.ToBeCalculated,
            dependenciesSnapshotFiles = dependenciesSnapshotFiles,
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

    // Err = LOCK file could not be created; caller must abort before any
    // non-LOCK write. Ok = either we hold it or another owner does
    // (reaper's existence probe is load-bearing in both cases).
    private fun ensureLock(workingDir: Path): Result<Unit, IcError.InternalError> {
        val lockPath = workingDir.resolve(LOCK_FILE)
        val cached = heldLocks[workingDir]
        if (cached != null && cached.lock.isValid && Files.isRegularFile(lockPath)) {
            return Ok(Unit)
        }
        if (cached != null) {
            // Stale cache entry: either the lock was released or
            // `SelfHealingIncrementalCompiler` wiped the LOCK file when
            // it cleaned `workingDir`. Close the dead channel and drop
            // the entry so the next block re-acquires cleanly.
            runCatching { cached.channel.close() }
            heldLocks.remove(workingDir)
        }
        val channel = try {
            FileChannel.open(
                lockPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
            )
        } catch (vme: VirtualMachineError) {
            throw vme
        } catch (t: Throwable) {
            // Disk full, EACCES, FS error, or LOCK path collides with a
            // non-file entry (EISDIR → `FileSystemException`). Self-heal's
            // wipe+retry does not help — the same failure recurs — so
            // surface InternalError and let the caller abort.
            metrics.record(METRIC_REAPER_LOCK_FAILED)
            return Err(IcError.InternalError(t))
        }
        val lock: FileLock? = try {
            channel.tryLock()
        } catch (_: OverlappingFileLockException) {
            null
        }
        if (lock == null) {
            channel.close()
            metrics.record(METRIC_REAPER_LOCK_CONFLICT)
            return Ok(Unit)
        }
        heldLocks[workingDir] = HeldLock(channel, lock)
        return Ok(Unit)
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

        internal const val METRIC_REAPER_BREADCRUMB_FAILED: String = "reaper.breadcrumb_failed"
        internal const val METRIC_REAPER_LOCK_FAILED: String = "reaper.lock_failed"
        internal const val METRIC_REAPER_LOCK_CONFLICT: String = "reaper.lock_conflict"

        // Layout constants are mirrored from `IcStateLayout` for call-
        // site terseness. The authoritative definition is there.
        private const val PROJECT_PATH_BREADCRUMB: String = IcStateLayout.BREADCRUMB_FILE
        private const val LOCK_FILE: String = IcStateLayout.LOCK_FILE
        private const val BTA_SUBDIR: String = IcStateLayout.BTA_SUBDIR


        // Loads kotlin-build-tools-impl from [btaImplJars] through a URLClassLoader
        // whose parent is SharedApiClassesClassLoader. This matches the isolation
        // policy used by Gradle's Kotlin plugin and by spike #104. Failure here is
        // load-bearing: if -impl classes cannot be found, BTA cannot initialise, so
        // we surface the classloader failure as an IcError.InternalError in the
        // Result returned to daemon core rather than throwing.
        fun create(
            btaImplJars: List<Path>,
            // Defaults to an empty-result resolver: every plugin alias maps to
            // an empty classpath, which collapses to no `-Xplugin=` freeArg
            // emitted. Production daemon startup overrides this with a
            // resolver that looks up the `pluginJars` map passed through
            // `--plugin-jars`; tests that do not exercise plugin delivery
            // rely on this default.
            pluginJarResolver: (alias: String) -> List<Path> = { _ -> emptyList() },
            metrics: IcMetricsSink = NoopIcMetricsSink,
            // ADR 0019 §Negative follow-up (#127): shared directory for cached
            // ClasspathEntrySnapshot files. When null, a temp directory is used
            // (test convenience — production always passes the real path).
            classpathSnapshotsDir: Path? = null,
        ): Result<BtaIncrementalCompiler, IcError.InternalError> =
            try {
                require(btaImplJars.isNotEmpty()) {
                    "btaImplJars is empty — daemon must receive kotlin-build-tools-impl classpath via --bta-impl-jars"
                }
                val urls = btaImplJars.map { it.toUri().toURL() }.toTypedArray()
                val implLoader = URLClassLoader(urls, SharedApiClassesClassLoader())
                val toolchain = KotlinToolchains.loadImplementation(implLoader)
                val snapshotsDir = classpathSnapshotsDir
                    ?: Files.createTempDirectory("kolt-cp-snapshots-")
                Ok(
                    BtaIncrementalCompiler(
                        toolchain = toolchain,
                        pluginJarResolver = pluginJarResolver,
                        metrics = metrics,
                        classpathSnapshotCache = ClasspathSnapshotCache(toolchain, snapshotsDir, metrics),
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
