# ADR 0019: Incremental JVM compilation via kotlin-build-tools-api

## Status

Implemented (2026-04-16). All follow-up items are resolved or explicitly
deferred to their respective scopes — see §Follow-ups below.

§1's single-version pin on `kotlin-build-tools-api` 2.3.20 is superseded
by ADR 0022 (2026-04-18), which generalises the pin to a per-Kotlin-version
scheme with a soft floor at 2.3.0. Every other section of this ADR
(adapter layer, wire protocol, IC state layout, failure classification,
BuildCache interaction, plugin plumbing, rollout) stands unchanged.

Previously Accepted (2026-04-15). Depends on #103 (Phase B-1a
bench-scaling ceiling, merged as c722dcc) and #104 (Phase B-1b
`kotlin-build-tools-api` spike, merged as f652eb1). This ADR is the
deliverable of #105 (Phase B-1c) and covers the design of Phase B-2;
B-2a (#112, adapter skeleton through the full-recompile path) merged as
c321615 and B-2b (#113) promoted this ADR from Proposed to Accepted by
enabling the incremental configuration, state layout, and self-heal
described below.

The parent issue #105 titles the work "kotlin-build-tools-**impl**", but
the spike confirmed that the callable entry point is the
`kotlin-build-tools-api` artifact (with `kotlin-build-tools-impl` as its
runtime pair loaded under a dedicated classloader). This ADR uses the
accurate name.

## Context

Phase A (ADR 0016) turned `kolt build` into a warm-daemon compile path.
The #96 scaling benchmark showed the daemon-warm wall time is
well-modelled as `~0.70 s` fixed cost plus `~12 ms` per file, and that
the `warm < 1 s` target survives only below ~15–20 files. Above that the
per-file slope dominates. Phase B's job is to cut the slope, which
means compiling only what changed.

The Phase B-1a bench (#103) measured how much room there is for a
smarter-than-full-recompile strategy, and the Phase B-1b spike (#104)
drove `kotlin-build-tools-api` end-to-end against two fixture shapes
(linear-10, hub-10) with ABI-neutral edits. Headline spike results:

| Fixture | Cold | Incremental | Speedup | Recompile set |
|---|---|---|---|---|
| linear-10 (touch F5) | 3914 ms | 565 ms | 6.9× | 1 / 20 class files |
| hub-10    (touch Leaf1) | 3665 ms | 488 ms | 7.5× | 1 / 10 class files |

The spike verdict was "it works": `COMPILATION_SUCCESS`, no silent
fallbacks, one `.class` changed, compiler-level `lines analyzed`
dropped ~8× from cold to incremental. Its recommendation was to
proceed to this ADR on **approach B** (drive the JetBrains-provided
`kotlin-build-tools-api`), and to isolate the experimental API behind
a thin adapter so the daemon core never sees `@ExperimentalBuildToolsApi`
types directly.

The decisions below are the commitments the B-2 implementation will be
built against. Full rationale and measurements live in
`spike/incremental-ic/REPORT.md`; this document records only the
load-bearing choices and their reasons.

## Decision

### 1. Adopt `kotlin-build-tools-api` 2.3.20 (approach B)

> **Superseded by ADR 0022 §7.** The 2.3.20 pin below is now the baseline
> for a per-Kotlin-version scheme; kolt routes every supported Kotlin
> version (floor 2.3.0) through its own BTA-API/impl pair. The
> lockstep-with-kotlinc principle stated in this section is preserved;
> only its single-version framing changes.

Phase B-2 drives incremental JVM compilation through
`org.jetbrains.kotlin:kotlin-build-tools-api:2.3.20` with
`kotlin-build-tools-impl:2.3.20` as its runtime pair. The entry points
(`KotlinToolchains`, `JvmPlatformToolchain`, `JvmCompilationOperation`,
`JvmSnapshotBasedIncrementalCompilationConfiguration`, `SourcesChanges`,
`BuildOperation.METRICS_COLLECTOR`) are all annotated
`@ExperimentalBuildToolsApi`. Approaches A (self-built dependency graph)
and C (import-based conservative dirty set) are rejected — see
§Alternatives Considered.

The BTA artifact pin must move in lockstep with the `kotlinc` version
that `SharedCompilerHost` loads. The existing Kotlin-version bump
workflow (change one constant in the daemon build) gains a second
artifact to update; that is the entire operational cost of this
dependency.

The old `CompilationService` entry point is `@Deprecated(level = ERROR)`
on current master of `jetbrains/kotlin`; it must not be used even
though much of the public documentation still references it.

### 2. Scope: JVM daemon path only, including test compilation

Incremental compilation is wired into the **JVM daemon compile backend
only**. Specifically:

- **In scope**: the `DaemonCompilerBackend` path used by
  `kolt build` for main sources, and the equivalent path for test
  source compilation (main+test → test classes / test jar). Test
  *execution* is not affected — it consumes the compiled outputs and
  is agnostic to how they were produced.
- **Out of scope**: the `SubprocessCompilerBackend` fallback (ADR 0016
  §5) continues to do full recompile. A build that falls back to
  subprocess — because the daemon is unavailable, the bootstrap JDK is
  missing, etc. — loses IC for that build. This is acceptable because
  the fallback path is by construction rare and disposable.
- **Out of scope**: Kotlin/Native (`konanc`) compilation. Native
  target support (Issue #16) uses a different compiler binary with a
  different incremental story; it is explicitly deferred.

### 3. Adapter layer: `BtaIncrementalCompiler`

The adapter isolates `@ExperimentalBuildToolsApi` surface from the
daemon core. Daemon core code **must not** import any `-api` or `-impl`
type, annotation, or exception.

The adapter lives in a new Gradle subproject of the
`kolt-compiler-daemon` included build, tentatively named
`kolt-compiler-daemon/ic/`. It is the only module with a dependency on
`kotlin-build-tools-api` and `kotlin-build-tools-impl`.

The daemon-core-facing interface is experimental-API-free:

```kotlin
interface IncrementalCompiler {
    fun compile(request: IcRequest): Result<IcResponse, IcError>
}

data class IcRequest(
    val projectId: String,        // stable, caller-derived hash
    val sources: List<Path>,      // full source set
    val classpath: List<Path>,    // deps + stdlib, full
    val outputDir: Path,
    val workingDir: Path,         // per-project IC state dir
    // plugin / compilerArguments fields intentionally absent — see §9
)

data class IcResponse(
    val wallMillis: Long,
    val compiledFileCount: Int?,  // null if metrics unavailable
)

sealed interface IcError {
    data class CompilationFailed(val messages: List<String>) : IcError
    data class InternalError(val cause: Throwable) : IcError
}
```

The concrete `BtaIncrementalCompiler` implementation owns the
`SharedApiClassesClassLoader` + `URLClassLoader` hierarchy that loads
the `-impl` artifact, mirrors the classloader pattern established by
`SharedLoaderCompileDriver` in compile-bench spike #86, and wraps every
BTA call in a `runCatching { ... }.fold(...)` that converts
`KotlinBuildToolsException` and any other thrown type into
`IcError.InternalError`.

The design rule for future maintenance: **if a 2.3.x → 2.4 compiler
bump breaks the build, the only file(s) that need editing are inside
`kolt-compiler-daemon/ic/`**. If an API change forces a daemon-core
edit, that is a signal that the adapter has leaked and must be
repaired.

### 4. Wire protocol: no change to `Message.Compile`

ADR 0016 §3 `Message.Compile` is **unchanged**. Its existing fields
(`projectRoot`, `sources`, `classpath`, `outputDir`) are sufficient for
the daemon to drive IC. The spike confirmed this: the BTA API diffs
against its own persisted state and does not require the client to
send a session ID, a build ID, or a dirty-file delta.

No version negotiation is added. No new frame type is introduced. A
client compiled against the Phase A wire protocol talks to a Phase B
daemon unchanged and transparently benefits from IC.

This is deliberate. Wire format is a forever-contract; we only widen
it when data on the wire is genuinely missing, and the spike showed
it is not.

**Deferred wire extension** (follow-up, not in B-2 scope): a future
file-watcher client (e.g. `kolt watch`, #15) may want to pass an
explicit `modifiedSources` / `removedSources` set so the daemon can
use `SourcesChanges.Known(...)` instead of `SourcesChanges.ToBeCalculated`.
That extension is additive and can be introduced later without
breaking existing clients. B-2 does not need it.

### 5. IC state ownership: daemon-owned, outside `build/`

IC state lives at:

```
~/.kolt/daemon/ic/<kotlinVersion>/<sha256(projectRoot)>/
```

- **Daemon-owned**, not client-owned. The daemon is the sole writer
  and reader, the sole authority for invalidation, and the sole thing
  that knows when to wipe the dir.
- **Outside `build/`**, so that `kolt clean` does not destroy IC
  caches. A user running `kolt clean` is typically investigating a
  suspected source-or-dependency problem; they are not asking for the
  incremental cache to be discarded. Forcing every post-clean build to
  go cold would make `clean` punitively expensive.
- **Version-stamped** by Kotlin compiler version at the directory
  level. Bumping `kotlinc` invalidates the cache in one move without
  touching the per-project subdirectories — the daemon just stops
  writing under the old version segment, and the old segment is left
  for a future reaper to clean up. (See §Consequences / Negative:
  daemons still accumulate state under `~/.kolt/` without bound.)

The `sha256(projectRoot)` segment matches ADR 0016's daemon socket
path convention, so moving or renaming a project directory invalidates
both the daemon and the IC state in the same move. That is the right
behaviour: a renamed project is a different project as far as absolute
source paths are concerned.

### 6. Session model: none on the wire, `ProjectId` internal to the daemon

The BTA API's `BuildSession` is JVM-local and `AutoCloseable`. It
scopes a group of operations that share IC caches; its runtime
identity is not meaningful outside the daemon process.

The daemon opens a `BuildSession` at the start of each
`Message.Compile` dispatch and closes it when the compile completes.
The stable identifier is `ProjectId`, derived as `sha256(projectRoot)`
and matching §5. Neither `BuildSession` nor `ProjectId` appears on the
wire — both are daemon-internal.

No `previousBuildId` field is added to `Message.Compile`. The spike
resolved this definitively: BTA's `SourcesChanges.ToBeCalculated` mode
handles change detection itself using the on-disk state under
`workingDirectory`, so the client does not need to remember which
build came before.

### 7. Failure classification and self-healing

Five failure classes cross the adapter boundary:

| BTA outcome | Adapter reports | Daemon action |
|---|---|---|
| `CompilationResult.COMPILATION_SUCCESS` | `Ok(IcResponse)` | Return success to client |
| `CompilationResult.COMPILATION_ERROR` | `IcError.CompilationFailed(messages)` | Return compile-failed to client (real user code error) |
| `CompilationResult.COMPILATION_OOM_ERROR` or `COMPILER_INTERNAL_ERROR` (any non-SUCCESS / non-COMPILATION_ERROR `CompilationResult` variant) | `IcError.InternalError(cause)` + **wipe `workingDir`** | Retry once in full-recompile mode, transparently |
| Thrown `KotlinBuildToolsException` or any other `Throwable` *except* `VirtualMachineError` | `IcError.InternalError(cause)` + **wipe `workingDir`** | Retry once in full-recompile mode, transparently |
| Thrown `VirtualMachineError` (OOM / StackOverflow / any other JVM-level `Error`) | **rethrown unchanged** — adapter does not absorb | Propagates past daemon core to `FallbackCompilerBackend` (ADR 0016 §5); never self-heal, never retry |

Key rules:

- **No recoverable thrown exception escapes the adapter.** Every BTA
  call is wrapped at the boundary. A bug in BTA or a corrupted cache
  must not kill the daemon process. The single exception is
  `VirtualMachineError`: the adapter deliberately lets it propagate
  because absorbing an `OutOfMemoryError` into `InternalError` would
  fire the self-heal retry path below, which allocates more objects
  and reproduces the OOM in a loop. JVM-fatal errors belong to the
  subprocess fallback path (ADR 0016 §5), not to IC's in-adapter
  recovery.
- **`IcError.InternalError` triggers silent in-adapter full recompile,
  not daemon-to-subprocess fallback.** The ADR 0016 `FallbackCompilerBackend`
  escape hatch is reserved for "the daemon JVM itself is broken". IC
  falling back to full is a daemon-internal concern; forcing it up to
  the subprocess path would throw away the warm-JVM win for an
  entirely warm-JVM-capable build.
- **Self-healing on cache corruption.** When IC fails with an
  `InternalError`, the adapter deletes the per-project `workingDir`
  tree before retrying full recompile. This is load-bearing: without
  it, the next incoming `Message.Compile` would read the same broken
  state and fail again in a loop. The retry does a cold compile into a
  fresh state dir, which is exactly what Phase A did on every build.
- **Observability via metrics, not log spam.** Daemon core sees
  `Ok(IcResponse)` regardless of whether the adapter did a true
  incremental compile, fell back to full-in-adapter, or did a
  self-healing retry. The adapter emits structured metrics
  (`ic.success`, `ic.fallback_to_full`, `ic.self_heal`) so that
  Phase B-2 smoke tests and a future `kolt doctor` can see what
  actually happened. A single stderr `warning` line is emitted on
  self-heal so dogfooding notices the event; routine
  `fallback_to_full` is metrics-only.

Daemon core sees one of `Ok(IcResponse)` / `CompilationFailed` / the
`InternalError` variant that survived the retry. In the last case
(full recompile also failed after a self-heal wipe), the adapter
surfaces the original `CompilationFailed` messages if the retry
produced them, or the retry's own `InternalError` cause if it did not
— "failed to compile" is always the message the user sees, never "the
incremental cache was corrupt".

### 8. Relation to existing BuildCache: fast-path is non-negotiable

`src/nativeMain/kotlin/kolt/build/BuildCache.kt` implements a
coarse-grained "nothing changed" check in the native client. When it
says "up to date", the build completes in 0.00–0.01 s without invoking
the daemon at all. That fast path **stays**. IC is only invoked when
BuildCache has already decided there is work to do.

This is non-negotiable because:

- BuildCache fires on the empty-change case (developer hits `kolt
  build` twice), which is the most frequent interaction. Dispatching
  to the daemon on every such call — even with a fast IC result —
  would reintroduce the ~0.7 s daemon-warm fixed-cost floor to a path
  that currently costs nothing.
- IC's "nothing changed" answer is not free either: BTA still walks
  its source set, hashes entries, and consults its state dir. The
  BuildCache check is a single mtime scan in native code.
- #103's original framing (a "recoverable budget" that IC must beat
  or the whole approach loses) was retracted specifically because it
  assumed IC was replacing the BuildCache fast path. It is not. The
  two layers compose: BuildCache handles "no changes", IC handles
  "some changes".

Practical rule: BuildCache's current interface does not change. The
daemon's `DaemonCompilerBackend` is only called when BuildCache has
already returned `Stale`, and that is exactly where IC slots in.

### 9. Plugin and compilerArguments plumbing: inside the adapter

Plugin resolution and `JvmCompilerArguments` construction live
**inside the adapter**, not in daemon core.

Concretely: `IcRequest` in §3 intentionally omits a `compilerArguments`
/ `pluginClasspaths` / `pluginOptions` field. The adapter reads
`kolt.toml` `[plugins]` (and other compile-relevant sections) from
`projectRoot` directly, translates them to BTA's
`JvmCompilerArguments`, and loads plugin jars into the same classloader
hierarchy that holds `kotlin-build-tools-impl`. The
`SharedLoaderCompileDriver` pattern from compile-bench spike #86 is the
working template for the classloader topology.

**Post-#148 mechanism note.** The translator emits `-Xplugin=<jar>`
tokens and the adapter pushes them through
`CommonToolArguments.applyArgumentStrings`, not the structured
`CommonCompilerArguments.COMPILER_PLUGINS` key. The structured key was
only added in BTA 2.3.20 and rejects assignment on 2.3.0 / 2.3.10
impls even with an empty list; `applyArgumentStrings` is the one
passthrough surface present across the full 2.3.x family the daemon
supports (ADR 0022 §3). Ordering is load-bearing: the passthrough
call must precede any structured `set(...)` on the builder, because
`applyArgumentStrings` resets every non-mentioned argument to the
parser default. See `spike/bta-compat-138/REPORT.md` for the empirical
verdict.

Rationale: the alternative (option (b), passing plugin settings from
daemon core via an `IcRequest` field) would force daemon core to carry
a `pluginClasspaths: List<Path>` and a `compilerOptions: Map<String, String>`
that exist solely to be translated into BTA types. That is the BTA shape
leaking across the adapter boundary through a different door. Keeping
plugin translation inside the adapter preserves the invariant that
daemon core imports nothing from `-api`/`-impl`, directly or in effect.

There is one tradeoff: the adapter now parses `kolt.toml` twice — once
in the native client's existing config load, once again in the JVM
adapter. This is acceptable. `kolt.toml` is tiny, parsing is cheap,
and the alternative is worse (see above). B-2 may factor out a small
shared TOML reader inside the JVM side if the duplication becomes
uncomfortable.

### 10. Rollout: default-on from day one

IC is the default compile path for `kolt build` from the first B-2
release. No opt-in flag. No `kolt.toml` knob. No "experimental" caveat
in user-facing docs.

The escape hatches are exactly those of ADR 0016:

- `kolt build --no-daemon` bypasses the daemon entirely and therefore
  bypasses IC (subprocess path, full recompile).
- `FallbackCompilerBackend` catches daemon unavailability and falls
  back to subprocess, which also does full recompile.

The Phase A decision log (ADR 0016 §5) applies here verbatim: every
transient artifact an opt-in rollout would produce — a flag, a doc
caveat, an interim ADR status — has to be reversed one release later.
Going straight to default-on avoids that churn. The risk is bounded by
the in-adapter self-heal path (§7) and by `FallbackCompilerBackend`
(ADR 0016): a broken IC never means a broken build. The worst case for
a user is Phase A's ~0.7 s warm build plus per-file slope, which is
already shipped and known to work.

## Consequences

### Positive

- **Bimodal IC win survives realistic workflows.** The spike measured
  6.9×–7.5× speedup on ABI-neutral edits with a 1-file recompile set.
  "Add a log line", "tweak a literal", "fix a typo in a function body"
  are the common iterative-development edits; they fall inside that
  best case. ABI-affecting edits land nearer the #103 ceiling, but
  they are less frequent.
- **Adapter isolates experimental API risk.** A 2.3.x → 2.4 compiler
  bump that renames, reorders, or removes BTA types is a one-module
  change inside `kolt-compiler-daemon/ic/`. Daemon core, wire
  protocol, and native client are all unaffected by definition.
- **No wire-protocol breakage.** Clients compiled against Phase A's
  `Message.Compile` talk to Phase B daemons unchanged. Downgrade and
  upgrade are both non-events.
- **State survives `kolt clean`.** `build/` is the client's
  working area; IC cache is daemon's working area. Clean destroys
  the first, not the second. Post-clean builds re-benefit from IC
  immediately.
- **Self-heal is in the adapter, not escalated to subprocess.** A
  corrupted IC state recovers to a working daemon compile within one
  request. The warm JVM is never thrown away for a cache-level issue.
- **Fast-path for no-op builds costs zero.** BuildCache's 0.00–0.01 s
  path is preserved; the most-frequent interaction (nothing changed)
  is unchanged by Phase B.

### Negative

- **`@ExperimentalBuildToolsApi` is load-bearing.** The BTA surface
  can change shape on any minor compiler release. We pin the artifact
  version and pair it lockstep with `kotlinc`, but we are accepting a
  runtime failure mode that did not exist in Phase A. Mitigation:
  `IcError.InternalError` self-heal in §7, plus the adapter integration
  test that exercises the full cold-then-incremental cycle on every
  build.
- ~~**Classpath snapshots are recomputed per request.**~~ Resolved by
  #127: `ClasspathSnapshotCache` caches `ClasspathEntrySnapshot` files
  keyed by `(path, mtime, size)` in a shared, version-stamped
  directory. Steady-state kotlin-stdlib snapshotting cost was ~310ms
  per request before caching.
- **Plugin loading is unverified.** The spike fixtures used plain
  Kotlin; no `kotlinx.serialization`, no `kotlin-test`, no kapt. B-2
  must prototype at least one real plugin (kotlinx.serialization is
  the canonical choice) before B-2 is declared done. This is the
  residual risk from spike O.Q. 6.
- **IC fallback-to-full is unvalidated at the behavioral level.** The
  spike saw only `COMPILATION_SUCCESS`. A smoke test pointing IC at a
  truncated cache file is required in B-2 to confirm that the
  `KotlinBuildToolsException` → `InternalError` → self-heal path
  actually works end-to-end, not just in theory.
- **`~/.kolt/daemon/ic/` accumulates state without a reaper.** Same
  class of problem as ADR 0016's socket-path directories, and it
  gets the same answer: a future reaper cleans up old project hashes
  and old Kotlin version segments. Filed as a follow-up, not a B-2
  blocker.
- **TOML reader duplicated across native and JVM sides.** The JVM
  adapter parses `kolt.toml` again inside the daemon to extract
  plugin settings. Small cost, avoidable if it gets out of hand, but
  it is real code duplication we are choosing to accept.

### Neutral

- **BTA, not a self-built dependency graph.** We are outsourcing the
  ABI snapshot / dependency tracking logic to JetBrains. That saves
  us from reimplementing the hard parts of incremental Kotlin
  compilation, at the cost of tracking an experimental surface.
- **`SourcesChanges.Known(...)` wire extension deferred.** A
  file-watcher client that already knows which files changed could
  skip BTA's own change detection. B-2 does not need this, and `kolt
  watch` (#15) has its own dependencies; extending the wire is
  cheaper when we have a concrete client driving the requirement.

## Alternatives Considered

1. **Approach A: self-built dependency graph.** Implement our own
   ABI hashing, per-symbol dependency tracking, and dirty-set
   propagation on top of `kolt`'s existing source model. Rejected:
   correctly tracking Kotlin's notions of ABI (reified type
   parameters, inline function bodies, `internal` visibility across
   compilation units, sealed hierarchies, annotation retention) is
   a full reimplementation of what `kotlin-build-tools-impl` already
   does. The maintenance cost of tracking compiler-internal semantics
   release over release is strictly higher than the cost of a pinned
   dependency on a versioned adapter.

2. **Approach C: import-based conservative dirty set.** Parse imports
   statically and recompile any file that imports something downstream
   of an edit. Rejected: the recompile set is always near the #103
   ceiling, because import graphs are strictly coarser than BTA's ABI
   snapshots. The bimodal win shown in the spike (1-file recompile on
   ABI-neutral edits) is unreachable from import-level analysis, so
   the approach loses the common case that makes IC worth building.

3. **IC state under `build/.kolt-ic/`, client-owned.** The original
   #105 issue body suggested this placement. Rejected for two reasons.
   (a) `kolt clean` would destroy the IC cache, making every
   post-clean build pay the full cold cost; users run `clean` to
   investigate source/dependency issues, not to discard IC caches.
   (b) Daemon-owned state keeps invalidation authority — including
   Kotlin version stamping and cache wipe on self-heal — in one
   place, whereas client-owned state would require the native client
   to learn when the daemon's cache was no longer valid.

4. **Plugin plumbing option (b): daemon core passes plugin settings
   through `IcRequest`.** Rejected. Adding `pluginClasspaths:
   List<Path>` and `compilerOptions: Map<String, String>` to
   `IcRequest` would force daemon core to carry fields whose shape
   is dictated by BTA's `JvmCompilerArguments`. That is the BTA
   surface leaking across the adapter boundary in a different form.
   Keeping translation inside the adapter preserves the invariant
   that daemon core imports nothing from `-api`/`-impl`, at the cost
   of one extra `kolt.toml` parse on the JVM side.

5. **Bubble IC internal errors up to `FallbackCompilerBackend`.** The
   alternative to §7's in-adapter self-heal: convert every
   `KotlinBuildToolsException` into a daemon-level
   `CompileError.BackendUnavailable` and let the native client fall
   back to the subprocess compile path. Rejected: the subprocess
   path is a ~8 s cold build, throwing away the warm JVM and every
   cached classloader. An IC cache-corruption event is a
   daemon-internal problem with a daemon-internal fix (wipe and
   retry). Promoting it to a subprocess fallback would trade a
   ~1–3 s recovery (cold IC compile) for a ~8 s recovery (cold
   subprocess compile) every time it fires.

6. **Per-build IC state (`workingDirectory` = new temp dir per
   request).** Trivially safe but defeats the purpose: every build
   is cold to BTA. Rejected without measurement because it is
   definitionally incapable of being incremental.

7. **Wire a `previousBuildId` / session token into `Message.Compile`.**
   Rejected as unnecessary. BTA's
   `SourcesChanges.ToBeCalculated` mode performs change detection
   against its own persisted state, so the client does not need to
   carry session continuity on the wire. Adding a field we do not
   consume makes the protocol harder to change later.

## Follow-ups for B-2 (not decisions of this ADR)

These are work items the B-2 implementation should carry, and
deliberately **not** decisions made here. Filing them as separate
issues before B-2 starts would risk bit-rot; filing them inside B-2
scope keeps them attached to the person doing the work.

**Status (2026-04-16):** all items are resolved or explicitly deferred.
The ADR is Implemented.

- ~~**Prototype `kotlinx.serialization` through the adapter**~~ —
  done in B-2c (`BtaSerializationPluginTest`). Spike residual risk
  O.Q. 6 is resolved: real plugin jar delivery through `--plugin-jars`
  + `PluginTranslator` produces the synthetic `$serializer` /
  `$Companion` classes and a compiling `@Serializable data class`.
- ~~**Smoke test for IC cache corruption**~~ — done in B-2c
  (`BtaIncrementalCorruptionSmokeTest`). Drives the composed
  `SelfHealingIncrementalCompiler(BtaIncrementalCompiler)` stack
  against a workingDir whose state files have been truncated to
  0 bytes; the `ic.self_heal` counter fires and the second compile
  returns `Ok(IcResponse)`.
- ~~**ABI-affecting cascade validation**~~ — done in B-2c
  (`BtaIncrementalAbiCascadeTest`). Mid-chain signature change +
  matching caller edit on a linear-5 fixture; recompile set is
  `>= 2 && < totalCount`, pinning both "cascade happened" and
  "cascade stopped short of full".
- ~~**Classpath snapshot caching**~~ — done in #127
  (`ClasspathSnapshotCache`). Each classpath entry is snapshotted via
  `classpathSnapshottingOperationBuilder` and cached keyed by
  `(path, mtime, size)`. Snapshot files are shared across projects
  under `<icRoot>/<kotlinVersion>/classpath-snapshots/`. Phase 0
  measurement showed kotlin-stdlib snapshotting costs ~310ms steady
  state, confirming caching is load-bearing.
- ~~**Scaling run on jvm-100 / jvm-250**~~ — not pursued. The
  slope-collapse claim is decidable on jvm-1..50: per-file slope is
  ~0ms across all four fixture sizes (B-2c results). Extending the
  harness to larger fixtures would confirm "flat is still flat" but
  cannot change the conclusion.
- ~~**`~/.kolt/daemon/ic/` reaper**~~ — done in #125 (PR #126,
  `cdf5da9`). Wholesale delete of non-current `<kotlinVersion>`
  segments + breadcrumb-based delete of dangling projectId dirs.
- **Post-B-2 wire extension for `SourcesChanges.Known(...)`**: deferred
  to Phase C `kolt watch` (#15). Not an ADR 0019 deliverable.

## Related

- #3 — Phase B parent issue (incremental build)
- #14 — Phase A daemon parent issue
- #86 / #87 — compile-bench spike, source of `SharedLoaderCompileDriver`
- #88 / #90 — Phase A follow-up benches (rotating fixture, long-run
  leak); unrelated to IC correctness but share the benchmark harness
- #96 — Phase A daemon scaling benchmark (the slope this ADR targets)
- #103 — Phase B-1a: incremental ceiling from the bench-scaling harness
- #104 — Phase B-1b: `kotlin-build-tools-api` spike and report
- #105 — this ADR
- ADR 0001 — `Result<V, E>` error handling discipline (applied to
  `IncrementalCompiler` and `IcError`)
- ADR 0016 — warm JVM compiler daemon (this ADR extends its
  compile backend, not its wire protocol)
- `spike/incremental-ic/REPORT.md` — full spike measurements,
  adapter-layer draft, and the wire-protocol-deltas list this ADR
  resolves
