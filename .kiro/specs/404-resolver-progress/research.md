# Gap Analysis — 404-resolver-progress

## 1. Current State Investigation

### CLI integration surface (single hop from user commands)

- `kolt.cli.DependencyResolution.resolveDependencies()` (`DependencyResolution.kt:65`) — the JVM transitive entry. Called by `kolt build` (`BuildCommands.kt:316/443/468`), `kolt add` and `kolt remove` (via `DependencyCommands.kt:85`), `kolt fetch` / `kolt update` (`DependencyCommands.kt:203`).
- `kolt.cli.DependencyResolution.resolveNativeDependencies()` (`DependencyResolution.kt:349`) — Kotlin/Native target entry. Called by `kolt build` for native (`BuildCommands.kt:660/1300`).
- `kolt.cli.DependencyResolution.resolveAllBundles()` (`DependencyResolution.kt:213`) — `[classpaths.<name>]` bundle resolver, also called from build/fetch.
- `kolt.cli.DependencyCommands` deep-fetch path (`DependencyCommands.kt:161/236/266`) and `kolt.cli.OutdatedCommand` (`OutdatedCommand.kt:82`) call `downloadFromRepositories` directly.

### Resolver kernel

- `kolt.resolve.resolve()` (`Resolver.kt:197`) dispatches by target: `resolveNative` for native targets, `resolveTransitive` otherwise.
- JVM path `resolveTransitive` (`TransitiveResolver.kt:11`) → `materialize` (`TransitiveResolver.kt:199`) iterates `nodes` serially, downloading each missing JAR, then optionally `resolveSourcesPath`.
- Native path `resolveNative` (`NativeResolver.kt:38`) iterates `nodes` serially, downloading each missing klib at line 79; metadata fetches happen in `fetchAndRead` (NativeResolver.kt:253).
- `kolt.resolve.BundleResolver.resolveBundle` and `resolveSingleArtifact` follow the same materialize-loop shape.

### Single physical retry / fallback site

- `TransitiveResolver.downloadFromRepositories` (`TransitiveResolver.kt:83`) is the only place that loops over `repos`, falls through on HTTP 404, and aggregates `RepositoryAttempt`s. **All** repository-fallback retry visibility has to thread through this function or its callers.
- 9 call sites of `downloadFromRepositories` — POM, JAR, `.module`, sources, klib, plugin jar, deep-fetch direct, outdated probe.

### Dependency injection seam

- `kolt.resolve.ResolverDeps` (`Resolver.kt:168`) is the single seam used by every resolver path: `fileExists`, `ensureDirectoryRecursive`, `downloadFile`, `computeSha256`, `readFileContent`.
- `defaultResolverDeps()` wires real `kolt.infra` I/O. Tests build anonymous `object : ResolverDeps { … }` (e.g. `TransitiveResolverTest.kt:997, 1044, 1346, 1396, 1432`) and provide canned content via maps.

### Stderr writer surface

- `kolt.infra.output` (`AnsiCodes.kt`, `ColorPolicy.kt`, `DiagnosticWriter.kt`, `RenderedDiagnostic.kt`, `Severity.kt`) already provides `eprintln` (`kolt.infra.eprintln`) and `eprintDiagnostic` / `eprintError` / `eprintWarning` / `eprintNote` (all stderr-only by contract per `DiagnosticWriter.kt:28`).
- `ColorPolicy.shouldColor(Stream.Stderr)` gates ANSI output per stream; `AnsiStripper` strips on non-color sinks.

### Banner today

- `DependencyResolution.kt:124` — `println("resolving dependencies...")` is on **stdout** (println, not eprintln).
- `DependencyResolution.kt:355` — `println("resolving native dependencies...")` likewise on stdout.
- Req 4.2 explicitly relocates these to stderr.

### Concurrency

- Both `materialize` (JVM) and `resolveNative` are strict `for (node in nodes)` loops — no coroutine launch, no `Mutex`, no parallelism today. Req 3 stays an emission-contract requirement, not a behavior one.

## 2. Requirement-to-Asset Map

| Req | Capability needed | Asset present | Gap |
| --- | --- | --- | --- |
| 1.1 `[N/M]` line before each network fetch | A sink the resolver calls at fetch start | None | **Missing**: progress sink interface + wiring through `resolve()` → `materialize` / `resolveNative` |
| 1.2 cache hit silent | Cache short-circuit before fetch | `if (!deps.fileExists(...)) { … download … }` exists in materialize, resolveNative, sources, etc. | None — emit only inside the `!fileExists` branch |
| 1.3 fully warm cache silent | Same as 1.2 | Same | None |
| 1.4 JVM + native parity | Same sink wired to both kernels | Both use same ResolverDeps and same `downloadFromRepositories` | Single design covers both |
| 2.1 retry annotation | Hook inside `downloadFromRepositories` repo loop | `attempts` list builds in-loop, but no callback | **Missing**: per-attempt callback alongside accumulating `attempts` |
| 2.2 ordering (after `[N/M]`, before next attempt) | In-loop emission | Serial loop — natural | None |
| 2.3 no retry annotation on non-404 | 404-only fallthrough | `if (error.statusCode != 404) return Err(...)` already enforces this | None |
| 2.4 no duplicate `[N/M]` on retry | Single emission per artifact | Need to emit at the artifact-level call site, not inside the repo loop | **Constraint**: artifact-level emission must wrap the whole `downloadFromRepositories` call, not be inside it |
| 3.1–3.2 non-interleaving (serial) | Synchronous emission | Serial today | None |
| 3.3 non-interleaving (future parallel) | Buffered or serialized emission contract | None | **Constraint**: sink interface must keep "one artifact = contiguous block" achievable when caller buffers. Solvable by making the sink stateless from the resolver's POV — caller decides serialize vs. buffer. |
| 4.1 progress on stderr | Stderr writers | `eprintln` exists | None |
| 4.2 banner on stderr | Same | Currently `println` | **Missing**: 2-line change in `DependencyResolution.kt:124, 355` |
| 4.3 piped stdout clean | Logical consequence of 4.1, 4.2 | — | None additional |
| 5.1 sources fetch silent | Skip sink in `resolveSourcesPath` | `resolveSourcesPath` exists | None — sink not invoked for sources |
| 5.2 sources failure silent | Same | `resolveSourcesPath` already swallows failure | None |

Tags: **Missing** = code does not exist; **Constraint** = existing structure imposes a shape on the new code.

### Out-of-scope but adjacent

- `OutdatedCommand` and the deep-fetch path call `downloadFromRepositories` directly with no `[N/M]` framing. Issue scope is `build / add / fetch`. **Open question** for design: do `kolt outdated` and `kolt update` (which goes through `resolveDependencies`) need progress too? `update` goes through `resolveDependencies` so it gets it for free; `outdated` is its own loop and is not in the issue's enumerated commands.

## 3. Implementation Approach Options

### Option A: Inject a `ProgressSink` into resolver entry points

Add a sink parameter (defaulted to no-op) to `resolve()`, `resolveTransitive`, `resolveNative`, `materialize`, `resolveBundle`, `resolveSingleArtifact`, and threaded into `downloadFromRepositories` for retry callbacks.

```
interface ProgressSink {
  fun onArtifactStart(index: Int, total: Int, groupArtifact: String, version: String)
  fun onRetryAgainst(repository: String)
  companion object { val NoOp: ProgressSink = ... }
}
```

- **Files touched**: `Resolver.kt`, `TransitiveResolver.kt`, `NativeResolver.kt`, `BundleResolver.kt`, `DependencyResolution.kt`. ~5 production files plus a new sink file.
- **Wiring**: pre-count at materialize loop start (count uncached JARs/klibs), call `onArtifactStart` before each `downloadFromRepositories` JAR/klib call, pass an in-loop lambda into `downloadFromRepositories` that calls `onRetryAgainst` on each non-final 404.
- **Test ergonomics**: sink default `NoOp` keeps existing tests green; new tests use a recording sink.
- ✅ Pure interface, easy to mock.
- ✅ Sources fetch (`resolveSourcesPath`) trivially silent — just don't call `onArtifactStart` for it.
- ✅ Future parallelization can swap to a buffered sink without changing resolver signatures.
- ❌ Threading a parameter through several function signatures.
- ❌ POM / `.module` fetches happen *before* the materialize count is known; they are silent under this option (acceptable per Req 1.1's "before each fetch" reading: design call below).

### Option B: Decorate `ResolverDeps.downloadFile`

CLI wraps `defaultResolverDeps().downloadFile` with an emitting wrapper.

- ❌ The wrapper sees one URL at a time, not artifact identity (`group:artifact:version`). Recovering identity from URL is fragile (different URL shapes for POM, JAR, klib).
- ❌ `M` is not known to a per-call wrapper.
- ❌ Retry annotation needs to be inside `downloadFromRepositories`, not at `downloadFile` — wrapper sees retries as independent calls.
- ❌ Hard to keep sources fetch silent without URL pattern matching.
- Rejected.

### Option C: Hybrid — sink in resolver + retry callback in `downloadFromRepositories`

Same as Option A in shape, but explicit about the two callback sites:
- Artifact-level `onArtifactStart(N, M, ga, version)` at materialize / resolveNative call site (one per uncached JAR/klib).
- Repo-level `onRetryAgainst(repo)` passed as a lambda into `downloadFromRepositories`, fired only on 404 fallthrough (i.e. between attempts that won't be the last).

This is what Option A actually requires once Req 2 is honored — A and C are the same shape; calling out the second callback explicitly removes a design ambiguity.

- ✅ Retries surface at the right granularity.
- ✅ JVM and native paths share one sink interface.
- ✅ `M` is the count of uncached materialize entries — known up front per kernel.
- ❌ `downloadFromRepositories` signature grows by one optional parameter.

**Preferred**: Option C. It is a strict extension of A and is what the requirements actually need.

## 4. Effort & Risk

- **Effort: S (1–3 days)**.
  - Single new file (`ProgressSink.kt` or a sink type next to `Resolver.kt`).
  - Threading a defaulted parameter through 4–6 functions.
  - 2-line banner relocation in `DependencyResolution.kt`.
  - CLI sink that calls `eprintln` (~30 LoC).
  - ~6–10 unit tests (recording sink, count, retry, sources-silent, warm-cache-silent).
- **Risk: Low**.
  - No new dependencies, no concurrency rewrite, no error-handling change.
  - Existing `ResolverDeps`-fake test pattern extends naturally.
  - One acceptable judgment call (POM/`.module` silent vs. its own progress) does not block implementation; either choice is reversible.

## 5. Research Items to Carry Into Design

1. **Counting strategy**: confirm `M` = count of uncached JARs/klibs scheduled by `materialize` / `resolveNative`. POM/`.module` fetches are then silent. Alternative: dual progress prefixes (`metadata: …` then `[N/M] …`). Pick before tasks.
2. **Sink interface shape**: a small interface (`ProgressSink`) vs. two function-typed parameters (`onStart`, `onRetry`). Interface scales better; functions are smaller in this PR.
3. **Retry annotation text**: exact format. Issue suggests `… -> retry against <repo>`. Confirm wording, indent (`  -> retry against …` for 2-space indent matching `RenderedDiagnostic` context lines), and whether to truncate long URLs.
4. **Banner final shape**: keep `resolving dependencies…` on stderr, drop it entirely once `[N/M]` exists, or condense to a single counted header. Keep simple unless data argues otherwise.
5. **Adjacent commands**: `kolt outdated` and the deep-fetch `kolt fetch --refresh` path call `downloadFromRepositories` directly without the materialize loop. In or out for this spec? Issue scope says `build / add / fetch` — deep-fetch is part of `kolt fetch`, outdated is its own command. Recommend in-scope: deep-fetch; out-of-scope: outdated.
6. **Bundle resolver parity**: `[classpaths.<name>]` bundles go through `resolveBundle` and the same materialize loop. Confirm sink wiring there for parity.
7. **TTY / quiet mode**: explicitly out per requirements unless the design phase surfaces a strong reason. No action expected.

---

## Synthesis Decisions (post-design)

Applied the three synthesis lenses to the requirements + gap findings before writing `design.md`.

### Generalization

- Req 1 (per-fetch start) and Req 2 (retry) are two events in the same artifact lifecycle, not independent capabilities. They unify into one `ResolverProgressSink` interface with two methods (`onArtifactStart`, `onRetryAgainst`). Req 3 is an emission *contract* on the sink usage, not a separate component — kept as documentation, not a class.
- Req 1.4 (JVM + Native parity) is satisfied by sink injection: `materialize` (JVM), `resolveNative` (klib), and `resolveBundle` (bundle) all materialize through the same shape, so the same sink type covers all three.

### Build vs. Adopt

- No Kotlin/Native progress library exists in the project's dep set. `indicatif`-style libraries are Rust; we don't need rendering, only plain stderr lines.
- Adopted `kolt.infra.eprintln` (existing) as the sole stderr writer. Did not adopt `eprintDiagnostic` / `RenderedDiagnostic` — those carry severity (error/warning/note) which is not appropriate for progress.
- Did not adopt `ColorPolicy` integration. Progress is plain text; if styling is wanted later, it slots into the production sink without changing the resolver.

### Simplification

- Single `ResolverProgressSink` interface (not two separate function-typed parameters). Three benefits: cohesive intent (sink = "where progress goes"), one type to mock in tests via a recording fake, and one place to add a future method (e.g. `onArtifactComplete`) without churning every signature.
- Pre-count `M` is computed at the start of each materialize loop via a one-pass `nodes.filter { !cached }`. Rejected the alternative `[N/?]` live-tally formats — issue mandates `[N/M]`.
- POM / `.module` fetches are silent. Rationale: metadata fetches are typically faster than JAR / klib downloads (smaller files), the user-facing artifact identity is the JAR / klib, and the `resolving dependencies…` banner already covers the metadata phase implicitly. If users want metadata progress, follow-up issue.
- `kolt outdated`, `fetchLatestVersion`, plugin-jar fetch, BTA-impl fetch, and `resolveSourcesPath` are not wired to the sink. They go through `downloadFromRepositories` with the default `onRetry = {}` and are silent by inheritance — no extra opt-out plumbing needed.
- `StderrProgressSink` is a `private class` inside `DependencyResolution.kt` (not a public type). One production implementation; promotion to a public class is reversible if a second consumer appears.
- Banner relocation is 3 lines (`println` → `eprintln` at `DependencyResolution.kt:124, 355` and `DependencyCommands.kt:248`). Other stdout writes (`fetch complete`, `no dependencies to update`) are command output and stay on stdout.

