---
status: implemented
date: 2026-04-16
---

# ADR 0019: Incremental JVM compilation via kotlin-build-tools-api

## Summary

- Drive incremental JVM compilation through `kotlin-build-tools-api` (approach B). The entry point is `@ExperimentalBuildToolsApi`; the BTA artifact version moves lockstep with `kotlinc`. (§1; §1 single-version pin superseded by ADR 0022 §7)
- Scope: JVM daemon path only (main + test sources). Subprocess fallback and native/`konanc` compilation remain full-recompile. (§2)
- Isolate all `@ExperimentalBuildToolsApi` types in a thin `BtaIncrementalCompiler` adapter (`kolt-compiler-daemon/ic/`). Daemon core must not import any `-api`/`-impl` type. (§3)
- `Message.Compile` wire format gained `compileScope` and `friendPaths` fields in #376 (multi-target-per-project shape). Strict deserialization (no `ignoreUnknownKeys`) is intentional: stale daemons fail-loud at frame parse rather than silently dropping the scope discriminator. BTA performs its own change detection via `SourcesChanges.ToBeCalculated`. (§4)
- IC state lives at `~/.kolt/daemon/ic/<kotlinVersion>/<sha256(projectRoot)>/<scope>/`, daemon-owned and outside `build/`. The `<scope>` segment (`main` / `test`) was added in #376 so each compile scope owns its own BTA `inputsCache`; `kolt clean` does not destroy IC caches. (§5)
- On any non-`VirtualMachineError` BTA failure the adapter wipes `workingDir` and retries full recompile silently; `VirtualMachineError` is rethrown so `FallbackCompilerBackend` (ADR 0016 §5) handles it. (§7)
- BuildCache's 0.00–0.01 s no-op path is preserved; IC is only invoked after `BuildCache` returns `Stale`. (§8)

## Context and Problem Statement

Phase A (ADR 0016) reduced warm `kolt build` to a ~0.70 s fixed floor plus ~12 ms per source file. The Phase B-1a bench (#103) measured the ceiling for a smarter-than-full-recompile strategy; the Phase B-1b spike (#104) drove `kotlin-build-tools-api` end-to-end against two fixture shapes:

| Fixture | Cold | Incremental | Speedup | Recompile set |
|---|---|---|---|---|
| linear-10 (touch F5) | 3914 ms | 565 ms | 6.9× | 1 / 20 class files |
| hub-10 (touch Leaf1) | 3665 ms | 488 ms | 7.5× | 1 / 10 class files |

The spike verdict was "it works" — `COMPILATION_SUCCESS`, no silent fallbacks, one `.class` changed, `lines analyzed` dropped ~8× from cold to incremental. Full measurements are in `spike/incremental-ic/REPORT.md`.

The old `CompilationService` entry point is `@Deprecated(level = ERROR)` on current `jetbrains/kotlin` master and must not be used even though much public documentation still references it.

## Decision Drivers

- ABI-neutral edits ("add a log line", "fix a typo in a function body") must benefit from IC on the JVM daemon path.
- An experimental BTA API change must require only changes inside `kolt-compiler-daemon/ic/`; daemon core, wire protocol, and native client must be unaffected.
- IC state corruption must not kill the daemon or degrade the build below Phase A behaviour; self-heal must work silently.
- The BuildCache no-op fast path must be undisturbed.

## Decision Outcome

Chosen: **approach B — drive `kotlin-build-tools-api`**, because it avoids reimplementing ABI snapshot / dependency tracking logic that `kotlin-build-tools-impl` already provides, and the spike confirmed the adapter layer seals the experimental surface effectively.

### §1 BTA version pin

Phase B-2 uses `org.jetbrains.kotlin:kotlin-build-tools-api:2.3.20` with `kotlin-build-tools-impl:2.3.20` as the runtime pair. The BTA pin moves lockstep with the `kotlinc` version `SharedCompilerHost` loads.

**Superseded by ADR 0022 §7:** the 2.3.20 single-version pin is the baseline for a per-Kotlin-version scheme; kolt routes every supported Kotlin version (floor 2.3.0) through its own BTA-API/impl pair. The lockstep-with-kotlinc principle is preserved.

### §2 Scope: JVM daemon path only, including test compilation

In scope: `DaemonCompilerBackend` for main sources and the equivalent path for test source compilation (main+test → test classes / test jar). Test execution is unaffected — it consumes compiled outputs.

Out of scope: `SubprocessCompilerBackend` (full recompile on fallback), Kotlin/Native `konanc` compilation.

### §3 Adapter layer: BtaIncrementalCompiler

The adapter lives in `kolt-compiler-daemon/ic/`, the only module with a dependency on `kotlin-build-tools-api` and `kotlin-build-tools-impl`. Daemon core must not import any `-api`/`-impl` type, annotation, or exception. If a compiler bump forces a daemon-core edit, that is a signal the adapter has leaked.

Daemon-core-facing interface (experimental-API-free):

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

`BtaIncrementalCompiler` owns the `SharedApiClassesClassLoader` + `URLClassLoader` hierarchy that loads `-impl`, mirrors `SharedLoaderCompileDriver` from spike #86, and wraps every BTA call in `runCatching { ... }.fold(...)` that converts `KotlinBuildToolsException` and any other `Throwable` (except `VirtualMachineError`) into `IcError.InternalError`.

### §4 Wire protocol: no change to Message.Compile

`Message.Compile` fields (`projectRoot`, `sources`, `classpath`, `outputDir`) are sufficient for IC. BTA diffs against its own persisted state; the client does not send a session ID, build ID, or dirty-file delta. No version negotiation and no new frame type are added.

Deferred: a file-watcher client (e.g. `kolt watch`, #15) may later pass `modifiedSources`/`removedSources` so the daemon can use `SourcesChanges.Known(...)`. That extension is additive and does not break existing clients.

**Wire evolution (#376).** The original framing assumed one compile per project per session; `kolt test` invalidates this by issuing a second compile against the same project. `Message.Compile` gained two fields: `compileScope: CompileScope = Main` (discriminates main vs test for §5 workingDir routing) and `friendPaths: List<String> = []` (replaces ad-hoc `-Xfriend-paths` in `extraArgs`, since the daemon strips unknown extraArgs). Both have defaults so old clients continue to work against new daemons. New clients against old daemons fail at deserialization (`kotlinx.serialization` strict mode) — a `SerializationException` propagates as `FrameError.Malformed`, triggering `FallbackCompilerBackend` (ADR 0016 §5) to retry via subprocess. This is the desired failure mode: silent ignore of `compileScope` would route test compiles to `main` workingDir and re-introduce the `build/classes/` wipe bug §5 fixes.

The fail-loud guarantee is asymmetric: `kotlinx.serialization` defaults `encodeDefaults = false`, so `compileScope = Main` (the default) is wire-omitted entirely and an old daemon parses the frame as pre-#376 main-compile. Only the test compile (`compileScope = Test`) carries a non-default value and triggers strict-mode rejection. This is why `ignoreUnknownKeys = true` would be a regression: it would silently downgrade the test compile to main-scope routing, while the default-omission already keeps the main path silently working.

### §5 IC state: daemon-owned, outside build/

```
~/.kolt/daemon/ic/<kotlinVersion>/<sha256(projectRoot)>/<scope>/
```

Daemon-owned: the daemon is the sole writer, reader, and invalidation authority. Outside `build/`: `kolt clean` destroys source/dependency outputs, not IC caches — a user investigating a build problem is not asking to pay a cold IC penalty. Version-stamped: a `kotlinc` bump invalidates the cache by switching the directory segment; the old segment is left for a future reaper. `sha256(projectRoot)` matches ADR 0016's socket path convention — renaming a project invalidates both the daemon and IC state in the same move.

**Scope segment (#376).** The original "1 project ⇒ 1 module ⇒ 1 outputDir" framing this section assumed does not match the JVM `kolt test` shape, which compiles the same project twice — once for main (`build/classes/`) and once for test (`build/test-classes/`). BTA persists its `inputsCache` under `workingDir/inputs/` keyed by source-file path. Sharing one workingDir across the two compiles makes BTA see the *other* compile's source list as "removed" and invoke `removeOutputForSourceFiles` against the previously-tracked outputs — wiping `build/classes/` after a test compile. The `<scope>` segment (`main` / `test`) gives each scope its own workingDir under the shared `<projectIdHash>` directory, mirroring Gradle KGP's one-task-one-workingDir model. `LOCK` and the `project.path` breadcrumb stay at the `<projectIdHash>` level (one per project) so the reaper continues to see one entry per project regardless of scope.

### §6 Session model: no wire state

`BuildSession` is JVM-local and `AutoCloseable`. The daemon opens one at the start of each `Message.Compile` dispatch and closes it on completion. `ProjectId` = `sha256(projectRoot)`. Neither appears on the wire. No `previousBuildId` field is added to `Message.Compile`; `SourcesChanges.ToBeCalculated` handles change detection from on-disk state.

### §7 Failure classification and self-healing

| BTA outcome | Adapter reports | Daemon action |
|---|---|---|
| `COMPILATION_SUCCESS` | `Ok(IcResponse)` | Return success |
| `COMPILATION_ERROR` | `IcError.CompilationFailed(messages)` | Return compile-failed (real user error) |
| `COMPILATION_OOM_ERROR`, `COMPILER_INTERNAL_ERROR`, or any other non-success/non-error variant | `IcError.InternalError` + wipe `workingDir` | Retry once in full-recompile mode |
| `KotlinBuildToolsException` or any non-`VirtualMachineError` `Throwable` | `IcError.InternalError` + wipe `workingDir` | Retry once in full-recompile mode |
| `VirtualMachineError` | Rethrown unchanged | Propagates to `FallbackCompilerBackend` (ADR 0016 §5) |

`VirtualMachineError` is not absorbed because absorbing `OutOfMemoryError` into `InternalError` would trigger a self-heal retry that allocates more objects and reproduces the OOM in a loop.

`IcError.InternalError` triggers silent in-adapter full recompile, not daemon-to-subprocess fallback. IC cache corruption is a daemon-internal concern; promoting it to subprocess would discard the warm JVM for a cache-level problem with a cache-level fix.

Self-heal wipes `workingDir` before retrying so the next request does not read the same broken state. Observability: structured metrics (`ic.success`, `ic.fallback_to_full`, `ic.self_heal`). One stderr `warning` line on self-heal; routine `fallback_to_full` is metrics-only.

### §8 Relation to BuildCache: non-negotiable

`BuildCache.kt` in the native client short-circuits to 0.00–0.01 s when nothing has changed. IC is only invoked when `BuildCache` returns `Stale`. BuildCache's interface is unchanged. Dispatching to the daemon on every `kolt build` — even with a fast IC result — would reintroduce the ~0.70 s floor to the most frequent interaction (nothing changed); BTA's change detection is not free (source walk, hash, state dir read).

### §9 Plugin and compilerArguments plumbing: inside the adapter

`IcRequest` does not carry `pluginClasspaths` or `compilerOptions`. The adapter reads `kolt.toml` `[kotlin.plugins]` from `projectRoot` directly and translates to `JvmCompilerArguments`. Plugin jars load into the same classloader hierarchy as `kotlin-build-tools-impl`.

Plugin passthrough mechanism (post-#148): the adapter emits `-Xplugin=<jar>` tokens via `CommonToolArguments.applyArgumentStrings`, not the structured `CommonCompilerArguments.COMPILER_PLUGINS` key. The structured key was added only in BTA 2.3.20 and rejects assignment on 2.3.0 / 2.3.10 impls even with an empty list. `applyArgumentStrings` is the passthrough surface present across the full 2.3.x family. Call order is load-bearing: `applyArgumentStrings` must precede any structured `set(...)` call because it resets non-mentioned arguments to parser defaults. See `spike/bta-compat-138/REPORT.md` for the empirical record.

Passing plugin settings through `IcRequest` (option b) would force daemon core to carry fields whose shape is dictated by BTA's `JvmCompilerArguments` — the adapter boundary leaking by a different door. The tradeoff is one extra `kolt.toml` parse on the JVM side, accepted as the lesser cost.

### §10 Rollout: default-on from day one

IC is the default compile path from the first B-2 release. No opt-in flag, no `kolt.toml` knob. Escape hatches are exactly those of ADR 0016: `--no-daemon` bypasses the daemon entirely; `FallbackCompilerBackend` catches daemon unavailability. A broken IC path never means a broken build; the worst case is Phase A's ~0.70 s warm floor plus per-file slope.

## Consequences

**Positive**
- ABI-neutral edits (the common iterative case) achieve 6.9–7.5× speedup with a 1-file recompile set.
- A 2.3.x → 2.4 BTA API change requires only changes inside `kolt-compiler-daemon/ic/`.
- Phase A clients talk to Phase B daemons unchanged; no downgrade/upgrade ceremony.
- `kolt clean` does not destroy IC state; post-clean builds re-benefit from IC immediately.
- Cache corruption recovers within one request without touching the warm JVM.
- BuildCache's 0.00–0.01 s no-op path is unchanged.

**Negative**
- `@ExperimentalBuildToolsApi` is load-bearing. BTA surface can change on any minor compiler release. Mitigated by version pinning and the adapter integration test that exercises the cold-then-incremental cycle on every build.
- `~/.kolt/daemon/ic/` accumulates state without a reaper (resolved: #125/PR #126 `cdf5da9` ships a reaper for non-current `<kotlinVersion>` segments and dangling projectId dirs).
- The JVM adapter parses `kolt.toml` a second time to extract plugin settings (accepted duplication; avoidable if it gets out of hand).
- Classpath snapshot cost was ~310 ms per request before caching (resolved: #127 `ClasspathSnapshotCache` caches snapshots keyed by `(path, mtime, size)` in `<icRoot>/<kotlinVersion>/classpath-snapshots/`).

### Confirmation

- `BtaSerializationPluginTest` (B-2c): real `kotlinx.serialization` plugin through the adapter.
- `BtaIncrementalCorruptionSmokeTest` (B-2c): `ic.self_heal` fires and compile succeeds after truncating state files to 0 bytes.
- `BtaIncrementalAbiCascadeTest` (B-2c): mid-chain signature change on linear-5; recompile set is `>= 2 && < totalCount`.

## Alternatives considered

1. **Approach A — self-built dependency graph.** Correctly tracking Kotlin ABI (reified type parameters, inline function bodies, `internal` visibility, sealed hierarchies, annotation retention) is a full reimplementation of what `kotlin-build-tools-impl` already does. Maintenance cost per compiler release exceeds the cost of a pinned adapter. Rejected.
2. **Approach C — import-based conservative dirty set.** Import graphs are strictly coarser than BTA's ABI snapshots; the recompile set is always near the #103 ceiling. The bimodal win (1-file recompile on ABI-neutral edits) is unreachable. Rejected.
3. **IC state under `build/.kolt-ic/`, client-owned.** `kolt clean` destroys the cache; a user investigating a build problem pays a full cold penalty. Invalidation authority would split across native client and daemon. Rejected.
4. **Plugin plumbing option b — IcRequest carries plugin settings.** BTA surface leaks across the adapter boundary in a different form; rejected (see §9).
5. **Bubble IC errors to FallbackCompilerBackend.** IC cache corruption routes to the subprocess path (~8 s cold), discarding the warm JVM for a daemon-internal problem with a daemon-internal fix. Rejected.
6. **Per-build workingDirectory.** Every build is cold to BTA by definition. Rejected.
7. **Wire `previousBuildId` / session token.** `SourcesChanges.ToBeCalculated` handles change detection from on-disk state; the client does not need session continuity. Rejected.

## Related

- #3 — Phase B parent issue (incremental build)
- #14 — Phase A daemon parent issue
- #86 / #87 — compile-bench spike; source of `SharedLoaderCompileDriver` pattern
- #96 — Phase A daemon scaling benchmark (the per-file slope this ADR targets)
- #103 — Phase B-1a incremental ceiling
- #104 — Phase B-1b `kotlin-build-tools-api` spike and REPORT.md
- #105 — this ADR's tracking issue
- #376 — JVM `kolt test` daemon route + scope segment in §5
- ADR 0001 — `Result<V, E>` discipline applied to `IncrementalCompiler` and `IcError`
- ADR 0016 — JVM daemon (compile backend this ADR extends; wire protocol unchanged)
- ADR 0022 — Kotlin version policy (supersedes §1's single-version pin)
- ADR 0026 — current daemon naming authority (see for new module names; this ADR's identifiers are preserved as historical record)
- `spike/incremental-ic/REPORT.md` — full spike measurements and adapter-layer draft
- `spike/bta-compat-138/REPORT.md` — empirical record for §9 plugin passthrough mechanism
