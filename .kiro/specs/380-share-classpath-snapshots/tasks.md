# Implementation Plan

## Phase 0 — BTA Shrunk Snapshot Spike (judgment gate)

- [x] 1. Spike T1: BTA shrunk-classpath-snapshot.bin portability and extension verification

- [x] 1.1 Construct spike harness exercising all 4 verification questions
  - Build a small Kotlin/JVM harness under `spike/bta-shrunk-portability/` that drives BTA 2.3.20 directly via `KotlinToolchains` to compile a fixture Kotlin module (mirror the structure of existing `spike/bta-compat-138/`)
  - Cover Question 1 (portability): produce shrunk file in workingDir A, copy to workingDir B with same classpath, run BTA compile in B and assert success
  - Cover Question 2 (extension activation): produce shrunk file with main classpath, place at test workingDir, compile with main+extra classpath, capture wall-time vs no-pre-place baseline (5-run median per arm)
  - Cover Question 3 (BTA write semantics): observe inode of pre-placed file before and after BTA compile via `Files.getAttribute("unix:ino")` to determine in-place write vs tmp+rename behavior
  - Cover Question 4 (compiler flag invariance): run two compiles on same classpath with `-Xfriend-paths` toggled and plugin args toggled, then `cmp -l` the produced shrunk files
  - Harness exits 0 with all 4 questions answered and raw measurements written under `spike/bta-shrunk-portability/results/`
  - _Requirements: 1.2_

- [x] 1.2 Record spike results, apply OQ-1 triage, and decide GO/GO-warning/NO-GO
  - Write `spike/bta-shrunk-portability/REPORT.md` summarizing each of the 4 question answers with raw evidence cited from the harness output
  - Apply OQ-1 triage table from design §Open Questions: GO if extension activation shows ≥5% wall-time improvement, GO-warning if 0-5%, NO-GO if no improvement
  - On GO-warning: prepare a requirements.md back-port note for Req 1.2 wording ("best-effort, no measurable target") to apply before implementation merges
  - On Question 4 finding flags affect shrunk content (R-2 fires): prepare a design.md amendment to add a compiler-flag hash to the cache key, and apply to Task 3.1 before that task starts
  - On NO-GO: stop spec impl, mark Phase 1+ task checkboxes as `[~]` with a one-line skip rationale, file a follow-up GitHub issue for Option B (sibling-scope speculative reuse) per gap analysis §3, and close this spec as "halted at spike gate"
  - REPORT.md ends with a single-line verdict (`VERDICT: GO` / `VERDICT: GO-warning, back-port required` / `VERDICT: NO-GO, halt impl`) and the day's date
  - _Requirements: 1.2_

## Phase 1 — Foundation: shared layout constants

- [x] 2. Extend IcStateLayout with cache-subdir constants and path helpers

- [x] 2.1 Add shrunk-snapshots dir constant, version-level cache-subdir set, and path helpers with unit tests
  - Add `SHRUNK_SNAPSHOTS_SUBDIR` constant alongside the existing `CLASSPATH_SNAPSHOTS_SUBDIR` in IcStateLayout
  - Add `CACHE_SUBDIRS_AT_VERSION_LEVEL` immutable set containing both cache subdir names; this is the single source of truth that IcReaper will consume
  - Add `shrunkSnapshotsDirFor(icRoot, kotlinVersion)` returning the version-scoped cache directory
  - Add `shrunkSnapshotPathFor(icRoot, kotlinVersion, classpathKey)` returning `<dir>/<key>.bin`
  - Extend the existing `IcStateLayoutTest` (or co-locate a new test class) covering: constant value stability, `<v>/shrunk-snapshots/<key>.bin` path composition, and cache-subdir set membership for both per-jar and shrunk dirs
  - All new IcStateLayout tests pass under `cd kolt-jvm-compiler-daemon && kolt test`
  - _Requirements: 1.1, 5.3_

## Phase 2 — Core implementations (parallel)

- [x] 3. ShrunkClasspathSnapshotCache and IcReaper skip-set integration

- [x] 3.1 (P) Implement ShrunkClasspathSnapshotCache with classpath hashing, place-on-lookup, and atomic store
  - Implement `classpathKey` derivation: per-entry SHA-256 of `path|mtime|size`, accumulator-hashed and truncated to 16-byte hex (matching the existing per-jar cache key idiom)
  - Implement `lookupAndPlace(classpath, destination)`: exact-match first, longest-prefix-match second (bounded depth, e.g. 8 entries), placement via `Files.copy` (NOT hardlink — see design §Decision notes), returning `Empty` / `Placed(key)` / `PlacedPrefix(key, prefixLen)` outcomes inside `Result<PlacementOutcome, CacheError>`
  - Implement `storeIfNew(classpath, producedSnapshot)`: write to `<key>.bin.tmp`, atomic rename to `<key>.bin`, skip when target exists with identical size+mtime
  - Wire the existing `IcMetricsSink` (mirror per-jar `ClasspathSnapshotCache` constructor pattern) and emit `shrunk_cache.lookup.{hit, prefix_hit, miss}` and `shrunk_cache.store.{success, skip, failure}` counters; emit a parallel info-level log line so the multi-shape IT can observe outcomes via daemon log scrape
  - Surface only `IoFailure(IOException)` as `CacheError`; never propagate cache errors to the compile path
  - Cover with `ShrunkClasspathSnapshotCacheTest`: hash determinism on identical inputs and divergence on any change, exact match places copy (verify destination is a fresh inode, not a hardlink), prefix match selects longest matching prefix, atomic store idempotency under repeated calls, IO error returns `Result.Err` without throwing
  - All new tests green under `cd kolt-jvm-compiler-daemon && kolt test`
  - _Requirements: 1.1, 1.2, 1.4, 4.1, 4.4_
  - _Boundary: ShrunkClasspathSnapshotCache_
  - _Depends: 2.1_

- [x] 3.2 (P) Modify IcReaper to skip cache subdirs at the current-version branch
  - Reference `IcStateLayout.CACHE_SUBDIRS_AT_VERSION_LEVEL` from IcReaper to keep the skip set as a single source of truth
  - In the current-version `directoryChildren(versionDir).forEach` loop (IcReaper.kt:71-79), short-circuit `return@forEach` when the child name is in the skip set, before incrementing `scanned`
  - Leave the non-current-version branch (lines 60-67) unchanged — when the kotlin version is stale the entire dir is wiped including cache content, which is the correct behavior
  - Update `IcReaperTest` (existing class) with three new cases: (a) `<v>/classpath-snapshots/` and `<v>/shrunk-snapshots/` survive a reaper run on the current version, (b) a stale `<projectIdHash>` dir (no breadcrumb, no LOCK) co-existing with the cache subdirs is still deleted by the reaper after the skip is in place (Req 4.3 regression guard), (c) non-current-version dir cleanup including its cache subdirs is unchanged
  - All IcReaper tests green; existing assertions for stale-projectIdDir cleanup remain passing
  - _Requirements: 4.1, 4.2, 4.3_
  - _Boundary: IcReaper_
  - _Depends: 2.1_

## Phase 3 — Integration

- [x] 4. Wire ShrunkClasspathSnapshotCache into the BTA compile lifecycle

- [x] 4.1 Integrate cache hooks into BtaIncrementalCompiler.compile
  - Extend `BtaIncrementalCompiler` constructor (and the `create` factory) to accept a `ShrunkClasspathSnapshotCache` instance
  - Before BTA `executeOperation` (around BtaIncrementalCompiler.kt:198), call `lookupAndPlace(request.classpath, shrunkClasspathSnapshot)`; the placement outcome is logged at info level by the cache, no extra logging needed at this layer
  - After successful BTA `executeOperation` return, call `storeIfNew(request.classpath, shrunkClasspathSnapshot)`
  - Errors from cache calls are surfaced as warn-level log entries by the cache and never propagate to the compile result; per-scope `inputs/` segregation, `friendPaths`, and the existing LOCK acquisition flow are unchanged
  - Update `BtaIncrementalCompilerColdPathTest` and related tests to construct the compiler with a real `ShrunkClasspathSnapshotCache` (pointed at a `tempDir` cache root) and assert that a successful compile still produces the expected `.class` output, and that a second compile with the same classpath emits a `shrunk_cache.lookup=hit` log line
  - Existing BtaIncrementalCompiler test suite passes; the new `shrunk_cache.lookup=...` log line appears at least once in the daemon test logs
  - _Requirements: 1.1, 1.2, 1.3, 1.5, 5.1, 5.2_
  - _Depends: 3.1_

- [x] 4.2 Construct and inject ShrunkClasspathSnapshotCache at daemon startup
  - In `kolt-jvm-compiler-daemon/src/main/kotlin/kolt/daemon/Main.kt`, alongside the existing `ClasspathSnapshotCache` construction (around line 107), construct `ShrunkClasspathSnapshotCache(cacheDir = IcStateLayout.shrunkSnapshotsDirFor(cli.icRoot, KOLT_DAEMON_KOTLIN_VERSION), metrics = <existing IcMetricsSink instance>)` and pass it to `BtaIncrementalCompiler.create(...)`
  - Ensure the `<v>/shrunk-snapshots/` directory is created on demand (lazy on first `storeIfNew`) — daemon startup must not depend on writable disk for the cache to function
  - Daemon thin jar build (`cd kolt-jvm-compiler-daemon && kolt build`) succeeds, daemon starts cleanly, and `<v>/shrunk-snapshots/` appears on disk after the first daemon-routed compile
  - _Requirements: 1.1_
  - _Depends: 4.1_

## Phase 4 — Validation (parallel)

- [x] 5. End-to-end coverage and performance measurement

- [x] 5.1 (P) Extend MultiShapeDaemonTestCoverageIT with cache existence and survival assertions
  - Add `coldPathPopulatesShrunkSnapshotsDir` to both shapes (no-plugin and serialization-plugin): after `kolt build && kolt test`, `<v>/shrunk-snapshots/` contains at least one `.bin` file
  - Add `cacheSurvivesDaemonRestart`: build → `kolt daemon stop` → second build → assert that the first build's per-jar cache file (`<v>/classpath-snapshots/*.snapshot`) and shrunk cache file (`<v>/shrunk-snapshots/*.bin`) have unchanged inode, mtime, and content — covers B-1 reaper-bug regression for both new and existing per-jar caches
  - Add `compiledClassesAreByteIdentical_acrossCacheHitAndMiss`: clear cache, build main+test, snapshot all `build/classes/**/*.class` SHA-256 → run again with cache populated → snapshot again → assert byte-identical (silent corruption guard)
  - Optional secondary observation: `cacheLookupOutcomesLoggedAtInfoLevel` greps daemon logs for the `shrunk_cache.lookup=` line as a non-blocking sanity check
  - All Multi-shape IT cases pass under the bootstrap-gated env override pattern (`KOLT_DAEMON_JAR` pointing to the freshly-built daemon thin jar)
  - _Requirements: 6.1, 6.2, 6.3, 4.1, 4.2, 5.3, 3.1, 3.2_
  - _Boundary: MultiShapeDaemonTestCoverageIT_
  - _Depends: 4.2_

- [x] 5.2 (P) Cold-path benchmark and dogfood log update
  - Extend `spike/bench-scaling/` (or add a sibling harness) with a `cold_test_after_main` scenario: clean `~/.kolt/daemon/ic/`, `kolt build` to populate main scope IC and shrunk cache, `kolt daemon stop && start`, `kolt test`, capture BTA wall-time. Repeat with cache disabled (point `<v>/shrunk-snapshots/` at an unwritable path or use a build-time toggle) for paired comparison
  - Use the existing synthetic epoch mtime convention from `gen.sh`; do NOT use plain `touch` (WSL2 9p 1s mtime granularity will silently invalidate the cache being measured, per memory `feedback_bench_mtime_granularity`)
  - Run the bench on `kolt-jvm-compiler-daemon` as the representative project from the issue
  - Append a new entry to `docs/dogfood.md` under v0.18.1 with: cache-on vs cache-off cold-path test compile wall-time (5-run median), warm-rebuild wall-time (target ≤540 ms), no-op test wall-time (target ≤50 ms)
  - dogfood.md entry shows the measured cold-path improvement satisfying the OQ-1 triage threshold from Phase 0 (or notes the GO-warning shortfall transparently if 0-5%)
  - _Requirements: 2.1, 2.2, 2.3, 3.1, 3.2_
  - _Boundary: spike/bench-scaling, docs/dogfood.md_
  - _Depends: 4.2_
