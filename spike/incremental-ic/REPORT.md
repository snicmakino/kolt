# Phase B-1b spike report — `kotlin-build-tools-api` incremental compilation

Issue: #104. Throwaway spike. Kotlin 2.3.20.

## Verdict

**It works.** `kotlin-build-tools-api` 2.3.20 drives end-to-end cold and
snapshot-based incremental JVM compilation in-process from an embedder,
on both fixture shapes, with `COMPILATION_SUCCESS` and no silent
fallbacks.

**Recommendation**: proceed to ADR 0019 (B-1c) on approach **B**. The
API surface we depend on is small enough to wrap in a thin adapter;
Phase B-2 can build against it without relying on compiler-internal
types.

## Measured data

Two fixtures, 10 Kotlin files each, hand-authored under `fixtures/`:

- **linear-10**: `Main.kt` (defines `M1` + `main`) plus `F2.kt`..`F10.kt`
  each defining `class M{i}` and `fun work{i}(prev: M{i-1}): M{i}`.
  Adversarial chain from the #103 bench-scaling family.
- **hub-10**: `Util.kt` (singleton object) + `Leaf1.kt`..`Leaf8.kt`
  (independent functions that call `Util.tag`) + `Main.kt`. Edit-closure
  best case.

Edit scenario: touch one source by appending a uniquely-named top-level
function (ABI-neutral addition — does not change any existing
signature). See `src/main/kotlin/spike/ic/Main.kt::touchSourceFile`.

| Fixture | Touched | Cold wall | Incremental wall | Speedup | `lines analyzed` cold → inc | Recompile set |
|---|---|---|---|---|---|---|
| linear-10 | `F5.kt` (mid-graph) | 3914 ms | 565 ms | 6.9× | 63 → 8 | `fixture/F5Kt.class` (1 / 20 class files) |
| hub-10    | `Leaf1.kt`          | 3665 ms | 488 ms | 7.5× | 51 → 6 | `fixture/Leaf1Kt.class` (1 / 10 class files) |

Observation method:

1. Primary: `.class` byte-hash diff between `destinationDirectory`
   snapshots taken after cold and after incremental runs.
2. Supplementary: `BuildOperation.METRICS_COLLECTOR` →
   `BuildMetricsCollector.collectMetric`. The
   `"Total compiler iteration -> Number of lines analyzed"` metric
   tracks how much source the compiler actually processed on each run.

Both signals agreed: exactly one `.class` changed, compiler work
dropped ~8× at the iteration level.

## Open questions

Answers keyed to #104 issue body:

### 1. Session / build ID

**Resolved.** The API exposes `KotlinToolchains.createBuildSession():
BuildSession`, an `AutoCloseable` that scopes a group of operations
sharing caches. `BuildSession.projectId: ProjectId` is the durable
identifier; the runtime `BuildSession` instance is not.

**Who owns what in kolt**: the daemon generates and holds a stable
`ProjectId` per project root (hash of absolute path is sufficient). The
`BuildSession` itself is JVM-local and cheap — open it per
`Message.Compile` request and `close()` when the request completes.
Clients do not need to see either identifier.

### 2. State location

**Resolved.** `snapshotBasedIcConfigurationBuilder(workingDirectory, ...)`
takes a caller-owned `Path`. The impl writes its dependency graph / ABI
snapshots / source-change detector state under that directory.

**Placement for kolt**: daemon-owned, under
`~/.kolt/daemon/ic/<projectId-hash>/`. Per-project, not per-build. Do
not put it under `build/` — we want state to survive `build/` cleans,
and we want the daemon (not the client) to hold invalidation authority.

### 3. Classpath change detection

**Resolved.** Not automatic. The IC API has two routes:

- **Multi-module projects**: compute `ClasspathEntrySnapshot` per
  classpath entry via `JvmPlatformToolchain.classpathSnapshottingOperationBuilder(...)`,
  persist, and pass as `dependenciesSnapshotFiles` into the IC config.
- **Single-module / no external deps**: pass
  `dependenciesSnapshotFiles = emptyList()`. The impl still requires a
  `shrunkClasspathSnapshot: Path` argument and will write a small
  shrunk-snapshot file there (4 bytes in our runs — effectively empty).

The spike used the second route. Kolt's realistic case is the first —
projects have `kotlin-stdlib` + dependency-resolver output on the
classpath, so B-2 will need a classpath-snapshot caching step parallel
to the IC call.

### 4. API stability and pin

**Resolved.** The whole surface we use (`KotlinToolchains`,
`JvmPlatformToolchain`, `JvmCompilationOperation`,
`JvmSnapshotBasedIncrementalCompilationConfiguration`,
`JvmClasspathSnapshottingOperation`, `BuildOperation.METRICS_COLLECTOR`,
`SourcesChanges`) is annotated `@ExperimentalBuildToolsApi`.
`@since 2.3.0` for the toolchains entry, many members `@since 2.3.20`.

Pin for B-2: **`org.jetbrains.kotlin:kotlin-build-tools-api:2.3.20`** +
`kotlin-build-tools-impl:2.3.20`, matching kolt's compiler version.
Loaded via `SharedApiClassesClassLoader` + a `URLClassLoader` pointing
at the impl artifacts (see `spike/ic/Main.kt::loadToolchain`). Must
track this pin to kolt's `kotlinc` version going forward.

**Trap**: in `master`, the old `CompilationService` entry point is
`@Deprecated(level = ERROR)` with the message "Use the new BTA API with
entry points in KotlinToolchain instead". Do not follow stale
documentation or examples that reference `CompilationService` —
`KotlinToolchains` is the only entry point that actually compiles.

### 5. Failure modes

**Partially resolved.** Both spike runs returned
`CompilationResult.COMPILATION_SUCCESS` and no silent-fallback warnings
in stderr. We did not observe `COMPILATION_ERROR` or fallback paths in
this spike.

What the API gives you:

- `CompilationResult` is an enum returned by `executeOperation`. B-2
  needs to branch on `COMPILATION_SUCCESS` vs error values.
- `BuildMetricsCollector` emits hierarchical metric names under
  "Run compilation -> ..."; a fallback (IC → full) would show up as
  counters shifting between IC-specific and full-build metrics.
- `KotlinBuildToolsException` is the escape hatch for truly broken
  invocations — should be caught by the daemon dispatcher and converted
  to a `Message.Error` reply rather than killing the daemon.

**Residual**: validation of actual IC-fallback-to-full behavior (e.g.
when a cache file is corrupted) was not exercised. B-2 should write a
smoke test that points IC at a truncated cache file and confirms the
error / fallback classification.

### 6. Compiler plugin interaction

**Not exercised.** The spike fixtures are plain Kotlin — no
kotlinx.serialization, no kotlin-test, no kapt. `compilerArguments`
exposes `PLUGIN_CLASSPATHS` / `PLUGIN_OPTIONS` equivalents, so the wiring
is there, but we did not load any plugin jar.

Constraint inherited from compile-bench spike #86 still applies: plugin
jars and kotlin-build-tools-impl share a classloader hierarchy, and
plugin classes must be reachable from whatever loader the impl uses to
invoke them. B-2 should prototype kotlinx.serialization early; the
shared-loader pattern from compile-bench (`SharedLoaderCompileDriver`)
is the working template.

### 7. Wire protocol implications

**Resolved.** Minimum sufficient wire:

```
Message.Compile {
    projectRoot: Path
    sources:     List<Path>       // full source set, not a delta
    classpath:   List<Path>       // deps + stdlib
    outputDir:   Path
}
```

No session ID, no build ID, no explicit dirty-file set required on the
wire. The daemon side wraps this in:

- stable `ProjectId` = hash(projectRoot)
- IC `workingDirectory` = `~/.kolt/daemon/ic/<ProjectId>/`
- `SourcesChanges = ToBeCalculated` — the BTA impl diffs against its
  own persisted state and figures out what changed.

**Optional optimization for later**: if a future kolt client (e.g. a
file watcher) already knows which files changed, the wire can add
`modifiedSources: List<Path>` + `removedSources: List<Path>`, and the
daemon selects `SourcesChanges.Known(...)`. This is not required for
correctness and should not block B-2 — keep the minimum wire and add
`Known` as a follow-up.

**Classpath snapshots cross-wire**: if B-2 computes classpath snapshots
on the daemon side per request, no protocol change is needed. If snapshot
computation is expensive enough to want client-side caching, that is a
separate protocol discussion (out of scope for B-1c).

### 8. Fixture shape sensitivity

**Answered empirically, with a caveat.**

The #103 takeaway predicted "linear chain is adversarial because
mid-graph touch cascades through n/2 downstream files". This spike's
results were narrower than that prediction for the specific edit we
tested:

- For **ABI-neutral** edits (adding a new, unreferenced top-level
  declaration to a mid-chain file), IC on the linear-10 fixture
  recompiled exactly **1** file. Downstream files were not touched,
  because IC's ABI snapshot correctly determined that `class M5` and
  `fun work5`'s public shape did not change.
- The hub-10 fixture recompiled the same **1** file for the leaf edit,
  as expected.

**Caveat (important, because it's the scenario #103 was worried
about)**: the spike did NOT test an **ABI-affecting** edit, e.g.
changing `fun work5(prev: M4): M5` to add a parameter, or changing `M5`'s
public API. Such an edit would force the downstream chain
(F6..F10) to re-link, and the linear-chain shape would genuinely matter
there. We elected not to block the spike on hand-editing matched
call sites for a third scenario.

**Implication for B-2**: the `recoverable budget ≲ 0.40s` ceiling from
#103 was computed assuming full-downstream recompile. IC appears to be
substantially smarter than that on ABI-neutral edits (which, in typical
iterative development, are the common case: "add a log line", "tweak a
literal"). The realistic win distribution will be **bimodal**:
- ABI-neutral edit on any file: ~1-file recompile, win is large.
- ABI-affecting edit at the top of a long chain: worst case, win is the
  #103 ceiling or thereabouts.

**Recommended B-2 validation scenarios** (not to be done in the spike,
but in the Phase B-2 test suite):

1. ABI-neutral body edit (literal change in `work5`'s body).
2. ABI-affecting signature change in `work5` with matching edits in
   `F6.kt`'s call site, to observe the cascade depth.
3. Classpath-entry ABI change (a `stdlib` upgrade or similar) to
   exercise the `classpathSnapshottingOperation` path.

## Recommended adapter-layer shape for Phase B-2

Goal: isolate `@ExperimentalBuildToolsApi` surface from the daemon
core so that a 2.3.x → 2.4 bump of Kotlin cannot break the daemon
request handler directly.

Proposed interface (daemon-core-facing, experimental-API-free):

```kotlin
// daemon/src/.../ic/IncrementalCompiler.kt
interface IncrementalCompiler {
    fun compile(request: IcRequest): Result<IcResponse, IcError>
}

data class IcRequest(
    val projectId: String,            // stable, caller-derived
    val sources: List<Path>,          // full source set
    val classpath: List<Path>,        // full classpath incl. stdlib
    val outputDir: Path,
    val workingDir: Path,             // per-project IC state dir
)

data class IcResponse(
    val wallMillis: Long,
    val compiledFileCount: Int?,      // may be null if metrics not available
    val status: Status,
)

enum class Status { SUCCESS, ERROR }

sealed interface IcError {
    data class CompilationFailed(val messages: List<String>) : IcError
    data class InternalError(val cause: Throwable) : IcError
}
```

Concrete implementation (`BtaIncrementalCompiler`) lives in a separate
module that is the ONLY module depending on
`kotlin-build-tools-api`/`-impl`. All experimental annotations,
`SharedApiClassesClassLoader`, `JvmCompilerArguments`, `SourcesChanges`
handling, and metrics collection are internal to that module. Daemon
core sees only `IncrementalCompiler`.

Pattern mirrors compile-bench spike #86's `SharedLoaderCompileDriver`
separation — daemon-facing interface plus a driver that owns the
classloader hierarchy.

## Wire-protocol deltas the B-1c ADR must cover

Based on this spike, ADR 0019 needs to specify:

1. **`Message.Compile` stays simple**: projectRoot, sources (full set),
   classpath, outputDir. No session ID, no dirty-file set. (See O.Q. 7.)
2. **Daemon-owned IC state dir convention**:
   `~/.kolt/daemon/ic/<sha256(projectRoot)>/`.
   Decision for the ADR: is this daemon-global, or per-JVM-version?
   (Kotlin version bumps probably require wiping this — ADR should say
   so explicitly, and the daemon should version-stamp the dir.)
3. **Classpath snapshot lifecycle**: compute on daemon per request
   (simple, O(classpath) cost) vs. cache keyed by classpath-entry
   mtime+size+path (fast for repeated builds). Spike did not exercise
   the multi-module case, so the ADR should pick **per-request
   computation** as the B-2 initial implementation and mark caching as
   a follow-up.
4. **Failure classification**: `COMPILATION_SUCCESS` vs
   `CompilationResult.COMPILATION_ERROR` vs thrown
   `KotlinBuildToolsException`. The daemon dispatcher must not
   propagate uncaught exceptions — wrap at the adapter boundary.
5. **Plugin-classloader policy**: defer to the pattern in compile-bench
   `SharedLoaderCompileDriver`. The ADR should reference that file and
   explicitly note that B-2 must prototype kotlinx.serialization before
   declaring B-2 complete (residual from O.Q. 6).
6. **Regression guardrail from #103**: the BuildCache coarse-grained
   noop fast-path (0.00–0.01s) must remain. IC is only invoked when the
   fast path says "changes detected". ADR must state this
   non-negotiable.

## Residual risks / followups not answered by this spike

- **Plugin / compiler-arguments plumbing**: `IcRequest` in the adapter
  sketch above intentionally omits a `compilerArguments` / plugin-list
  field. The spike fixtures did not need plugins, so the shape of this
  field is not yet forced by data. ADR 0019 must decide whether
  plugin settings (plugin classpaths, plugin options, compiler flags
  like `-jvm-target`) are (a) resolved inside the adapter by reading
  `kolt.toml [plugins]` and translated to `JvmCompilerArguments` there,
  or (b) passed through from daemon core on every `IcRequest`. Option
  (a) keeps daemon core free of BTA-shaped types; option (b) keeps the
  adapter pure and makes plugin resolution the daemon's job. Decide in
  the ADR; either works, the tradeoff is where the translation layer
  lives.
- ABI-affecting cascade depth: see O.Q. 8 caveat. Covered by B-2
  validation suite, not by ADR.
- IC fallback-to-full observability: see O.Q. 5 residual. Covered by
  B-2 smoke tests.
- Compiler plugin loading: see O.Q. 6. Covered by B-2 prototype.
- `SourcesChanges.Known(...)` optimization path: covered by a
  follow-up issue post-B-2 if a file-watcher client ever materializes.
- IC performance at larger fixture sizes (jvm-100, jvm-250): B-1a's
  bench-scaling harness already exists and can be pointed at a B-2
  implementation; not a blocker for the ADR.

## What the spike cost

Rough: 1 working session (much less than the 1-day budget in the issue
body, largely because context7 + direct `gh api` browsing of
`jetbrains/kotlin` at the `v2.3.20` tag let O.Q. 1/2/3/7 get answered
from source before any code ran). The 1-hour early-exit gate for
step 4 (driving the API) was not triggered: the first `executeOperation`
call produced `COMPILATION_SUCCESS` on the first compile and correct
`lines analyzed` deltas on the first incremental call after a stdlib
classpath and touch-semantics fix.
