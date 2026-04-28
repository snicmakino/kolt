# Gap Analysis: release-build-profile

Generated: 2026-04-28T04:35:00Z
Phase: requirements approved → pre-design

## Existing State Snapshot

### Build pipeline anatomy
- **Native compile**: `src/nativeMain/kotlin/kolt/build/Builder.kt`
  - `nativeLibraryCommand()` (lines 109-135) — klib stage; passes `-target`, sources, `-p library`, `-nopack`, `-l <klibs>`, `-o`. **No `-opt` or `-g` today.**
  - `nativeLinkCommand()` (lines 140-169) — link stage; passes `-target`, `-p program`, `-e <main>`, `-l`, `-Xinclude=`, `-Xenable-incremental-compilation`, `-Xic-cache-dir=$NATIVE_IC_CACHE_DIR`, `-o`. **No `-opt` or `-g` today.**
  - `outputKexePath(config)` (line 60) → `"$BUILD_DIR/${config.name}.kexe"`. `BUILD_DIR = "build"` is the kolt-managed (not Gradle) artifact root.
  - `outputJarPath(config)` (line 20) → `"$BUILD_DIR/${config.name}.jar"`.
  - `NATIVE_IC_CACHE_DIR` (line 18) — `internal const val "$BUILD_DIR/.ic-cache"`. **Hardcoded const.**
- **JVM compile**: `Builder.kt:89-105` (`checkCommand`) and `TestBuilder.kt:9-35` (`testBuildCommand`). **No `-opt` / `-g`.** No optimization-class flag is currently routed to kotlinc.
- **JVM daemon IC**: `kolt-jvm-compiler-daemon/ic/.../IcStateLayout.kt:51-52` — `<icRoot>/<kotlinVersion>/<sha256(projectRoot).take(16)>`. Path is **client-computed** in `KoltPaths.daemonIcDir` and passed via `IcRequest.workingDir` (wire schema: `kolt-jvm-compiler-daemon/.../protocol/Message.kt:11-18`).
- **Native daemon IC**: `kolt-native-compiler-daemon/.../protocol/Message.kt:18-19` — `data class NativeCompile(val args: List<String>)`. **Path is embedded in the konanc args** (`-Xic-cache-dir=...`), not a wire-level field.
- **CLI flag parsing**: `Main.kt:8-87`. Existing build-level flags (`--no-daemon`, `--watch`) are extracted by `koltLevel.contains(...)` and stripped via `filter`. No parametrized flags exist; `--release` fits this pattern.
- **Entry commands**: `BuildCommands.kt` `doBuild` (206), `doCheck` (127), `doTest` (708), `doRun` (644). Each is invoked from `Main.kt` after lock + flag-extract.
- **Watch loop**: `WatchLoop.kt:275-369` calls path-computation functions (`outputKexePath`, etc.) on each rebuild; profile changes propagate automatically per cycle.
- **`scripts/assemble-dist.sh`**:
  - Line 102: `KOLT="${KOLT:-./build/bin/linuxX64/releaseExecutable/kolt.kexe}"` — Gradle release path (bootstrap kolt).
  - Line 120: `"$KOLT" build` — invokes self-host kolt; **no profile flag today**.
  - Line 218: `ROOT_KEXE="$ROOT_DIR/build/kolt.kexe"` — self-host kolt output; **moves to `build/release/kolt.kexe` after this feature**.
  - Line 228-229: `daemon_jar="$ROOT_DIR/$daemon/build/${daemon}.jar"` and `manifest="$ROOT_DIR/$daemon/build/${daemon}-runtime.classpath"` — JVM artifacts; **JVM is no-op so paths unchanged**, except if we still partition under `build/release/` for consistency (design decision).

### No existing profile concept
- No `Profile` type, no `release` / `debug` reserved subcommand or `kolt.toml` key.
- `kolt.config.Config.BuildSection` (`Config.kt:65-74`) does not carry profile state.
- `release` appears only in Gradle path strings (`releaseExecutable`) and code comments. No naming collisions.

### Drift guard coverage
- `DriftGuardsTest.kt` asserts three triangles: daemon Kotlin version pins, daemon main-class FQNs, bootstrap JDK pins. **None touch path computation.**
- The current path consts (`BUILD_DIR`, `NATIVE_IC_CACHE_DIR`) and the upcoming profile-aware computations have **no automated drift guard**. If we encode `"debug"` / `"release"` literals in multiple places, drift surface grows.

### Test fixture path coupling
- ~44 occurrences of `build/<...>.kexe` / `build/<...>.jar` in `src/nativeTest/`.
- The vast majority are **Gradle paths** (`build/bin/linuxX64/debugExecutable/kolt.kexe`), used by tests that resolve the bootstrap kolt binary (e.g. `DaemonJarResolverTest`, `NativeDaemonJarResolverTest`, `ConcurrentBuildIT`). Those are **out of scope** — Gradle's output layout is not changing.
- A smaller set asserts kolt-managed paths: `TestBuilderTest.kt:43` (`build/classes:...`), classpath manifest tests, `outputKexePath` assertions. Estimated **5-10 tests** require updating to expect `build/<profile>/...`.

### Documentation footprint
- `README.md` / `README.ja.md`: 5 occurrences each of `build/...` in user-facing examples (mostly Gradle paths for self-host smoke; mixed with kolt-managed paths in cinterop / `--watch` examples).
- `CLAUDE.md`: 0 mentions of `build/` layout; touches only `./gradlew build` invocations.
- `.kiro/steering/tech.md`: documents user-facing CLI commands; no path examples to update.
- `kolt-usage` skill (`.claude/skills/kolt-usage/SKILL.md`): TBD scan — Req 7 lists it.

## Requirement-to-Asset Map

| Req | Asset | Status | Notes |
|-----|-------|--------|-------|
| 1.1-1.4 (flag parsing, debug default, kolt.toml-free) | `Main.kt:8-87`, `BuildCommands.kt` doBuild/doCheck/doTest/doRun | Extend | Add `--release` to flag extraction, thread `Profile` through 4 entry functions |
| 2.1, 2.2 (Native `-opt` routing) | `Builder.kt` `nativeLibraryCommand` / `nativeLinkCommand` | Extend | Add `-opt` only under release |
| 2.3, 2.4 (Native debug-info routing) | `Builder.kt` `nativeLinkCommand` (and library?) | Extend | Add `-g` under debug; **research needed: does `-g` belong on library stage too, or only link?** |
| 3.1-3.3 (JVM no-op, identical IC store) | JVM compile commands, `KoltPaths.daemonIcDir`, `IcStateLayout.workingDirFor` | Constraint | Pass `Profile` through but ignore on JVM side; assert via test |
| 4.1, 4.2 (output path with profile segment) | `Builder.kt` `outputKexePath`, `outputJarPath`, `outputRuntimeClasspathPath`, `outputNativeTestKexePath` | Extend | Functions take `Profile`; emit `build/<profile>/...` |
| 4.3 (both profiles coexist on disk) | n/a (emergent) | Test | Integration test alternating `kolt build` / `kolt build --release` |
| 4.4 (no precondition on directory) | `BuildCommands.kt` build orchestration | Constraint | Existing `ensureDirectoryRecursive` already idempotent (#272 EEXIST tolerance merged) |
| 5.1, 5.2 (Native IC path with profile segment) | `Builder.kt` `NATIVE_IC_CACHE_DIR` const → fn, `BuildCommands.kt:577-578` `wipeNativeIcCache` | Extend | Const → function `nativeIcCacheDir(profile)`; wipe takes profile |
| 5.3 (alternation preserves IC) | n/a (emergent from 5.1+5.2) | Test | Integration test |
| 5.4 (recreate cache idempotently) | `BuildCommands.kt` cache creation | Constraint | Existing `ensureDirectoryRecursive` covers it |
| 6.1, 6.3 (assemble-dist.sh release default) | `scripts/assemble-dist.sh:120,218` | Extend | Pass `--release`, update output path |
| 6.2 (single source of truth) | `scripts/assemble-dist.sh` | Constraint | No additional script created |
| 7.1-7.4 (docs, ADR 0030) | README.md, README.ja.md, CLAUDE.md, steering/tech.md, `kolt-usage` skill, `docs/adr/0030-build-profiles.md` | New | ADR is new; others are extends |

**Gaps tagged:**
- *Missing*: `Profile` type, profile-aware path computation, ADR 0030.
- *Unknown* (research needed for design):
  - JVM IC store path schema decision: keep flat (Req 3.3 satisfied trivially) vs partition under unified `build/<profile>/` only on the artifact side. Issue #261 says JVM IC = same store; lean toward that.
  - `-g` placement on Native: link stage only, library stage only, or both? Conservative read of konanc docs needed during design.
  - `NATIVE_IC_CACHE_DIR` location: stays under `build/.ic-cache`, moves under `build/<profile>/.ic-cache`, or moves under daemon IC root like JVM. Issue text only specifies `~/.kolt/daemon/ic/...` for daemon-managed Native IC; the local kolt-side `build/.ic-cache` predates daemon IC entirely. Need to disambiguate during design.
- *Constraint*: pre-v1 no migration shim — `~/.kolt/daemon/ic/<v>/<id>/` becomes orphan after this lands. Documentation should tell users to wipe `~/.kolt/daemon/` if iteration of mixed pre/post-flag cache is observed.

## Implementation Approach Options

### Option A: Extend in place — add `Profile` parameter to existing functions
- Add `enum class Profile { Debug, Release }` somewhere small (e.g. `kolt.build.Profile`).
- Thread `Profile` through every signature that currently uses `BUILD_DIR` or `NATIVE_IC_CACHE_DIR`: `outputKexePath`, `outputJarPath`, `outputRuntimeClasspathPath`, `outputNativeTestKexePath`, `nativeLibraryCommand`, `nativeLinkCommand`, `nativeIcCacheDir` (replaces const), `wipeNativeIcCache`.
- CLI extracts `--release` in `Main.kt`, passes `Profile` into `doBuild` / `doCheck` / `doTest` / `doRun`.
- `KoltPaths.daemonIcDir` gains optional profile suffix; JVM call sites pass `Profile.Debug` and never partition (no-op).

**✅ Pros**:
- Low ceremony, no new files for path computation.
- Type system enforces every caller updates (compiler errors point at every uncovered site — useful as a discovery tool during implementation).
- Path concentration in `Builder.kt` stays.

**❌ Cons**:
- Wide signature churn (~15-20 call sites).
- `Builder.kt` already 200+ lines; adds parameter density.

### Option B: Centralize paths in a `BuildPaths` value class
- Introduce `class BuildPaths(profile: Profile, config: KoltConfig)` exposing `kexePath`, `jarPath`, `runtimeClasspathPath`, `testKexePath`, `nativeIcCacheDir`.
- Replace existing path functions with `BuildPaths.<member>` calls.
- `Profile` still threads from CLI through entry functions, but bottom-layer functions (`nativeLibraryCommand`, etc.) take `BuildPaths` instead of raw `Profile`.

**✅ Pros**:
- One canonical place for path layout; future changes (e.g. macOS / linuxArm64 path forks) consolidated.
- Easier drift guard: a single test reads the source file and asserts profile segment appears.

**❌ Cons**:
- New abstraction with limited members today; risks under-utilization.
- Migrating ~15 call sites to `BuildPaths` is the same churn as Option A plus a new type.

### Option C: Hybrid — `Profile` enum + selective path centralization
- Introduce `Profile` enum.
- Path functions in `Builder.kt` keep their shape but each grows a `profile: Profile` parameter (Option A's threading).
- `nativeIcCacheDir(profile: Profile)` function replaces the const (small, isolated change).
- Daemon IC path computation in `KoltPaths.kt` and `IcStateLayout.kt` accepts an optional `profile: Profile?` (null = JVM no-op behavior).
- No new `BuildPaths` class — defer that consolidation until macOS / linuxArm64 forks the layout (#82 / #83).

**✅ Pros**:
- Smallest patch that satisfies all 7 requirements.
- Doesn't pre-commit to an abstraction (`BuildPaths`) before knowing the linuxArm64 / macOS shape.
- Keeps the change reviewable (touchpoints are mechanical: add parameter, wire through).

**❌ Cons**:
- Path layout knowledge stays decentralized across `Builder.kt` functions.
- Future macOS / linuxArm64 path forks may revisit Option B.

**Recommendation for design**: Option C. Pre-v1 prefers small, reversible changes; `BuildPaths` consolidation can land later if needed.

## Effort & Risk

- **Effort**: **M (3-7 days)**.
  - Core threading + path computation: ~2 days.
  - Test updates (5-10 kolt-managed-path tests + 2-3 new integration tests for Req 4.3, 5.3, JVM no-op): ~1-2 days.
  - `assemble-dist.sh` + `self-host-post` CI verification: ~0.5 day.
  - ADR 0030 + README/CLAUDE/steering/skill docs: ~1 day.
- **Risk**: **Medium**.
  - Test fixture surface (44 hits) includes Gradle paths (out of scope) — actual update count is smaller, but discovery is needed.
  - Daemon IC store path change orphans pre-v0.16 caches; pre-v1 policy absorbs this, but warrants a release-note line.
  - Native `-g` placement (library stage vs link stage) is the only konanc-spec ambiguity; design phase must confirm.
  - `assemble-dist.sh` change is reversible; `self-host-post` CI catches regressions.

## Research Items for Design Phase

1. **Native `-g` flag placement**: confirm whether konanc consumes `-g` on `-p library` (klib stage) or only on `-p program` (link stage). Docs / source survey of `kolt-native-compiler-daemon` worker.
2. **`build/.ic-cache` location decision**: keep at `build/.ic-cache` (single shared cache, profile-segment in subdir) vs. move to `build/<profile>/.ic-cache`. Both satisfy Req 5.1/5.2; pick based on cleanup ergonomics (`rm -rf build/release/` should not erase debug IC if shared).
3. **Daemon IC store partitioning**: confirm `~/.kolt/daemon/ic/<version>/<projectId>/<profile>/` (issue spec) is the schema. JVM IC keeps the same projectId-only path (Req 3.3).
4. **`Profile` representation in wire protocol**: decide whether `Profile` rides along on the daemon `Compile` / `NativeCompile` message (cleaner) or stays embedded in args/paths only (less invasive). Native already embeds; JVM uses `workingDir`.
5. **Drift guard for path partition**: should `DriftGuardsTest` gain a 4th assertion locking the `debug` / `release` literals across `Profile.Debug.dirName`, `Profile.Release.dirName`, `assemble-dist.sh`, and any test fixture that hardcodes `build/release/`? Lightweight; one new assertion.
6. **`kolt clean`**: does kolt have a `clean` command? If yes, profile-aware behavior (`kolt clean` clears all, `kolt clean --release` clears only release) is a UX question. If no, out of scope.
7. **`kolt-usage` skill scan**: enumerate exact build-command examples that need `--release` mention.

## Recommendations for Design Phase

- Adopt **Option C** (hybrid: `Profile` enum + selective path centralization).
- Resolve research items 1-3 with a 30-minute source/doc dive before drafting `design.md`.
- Treat research item 5 (drift guard) as a follow-up issue if it threatens design scope; otherwise include.
- Item 6 (`kolt clean`) is plausibly out-of-scope — keep deferred unless the user surfaces it.

---

# Design Synthesis (2026-04-28T05:00:00Z)

## Discovery resolutions for research items

### Item 1: Native `-g` flag placement
**Decision**: Add `-g` only to the link stage (`nativeLinkCommand`), not the library/klib stage.
- Rationale: `-g` controls debug-info emission in the final binary; klib stage produces an intermediate artifact that does not embed ELF debug info.
- Conservative call; if the design later finds klib-stage `-g` is also useful, it is a one-line follow-up.
- `-opt` likewise added only on the link stage (final-binary optimization).

### Item 2: `build/.ic-cache` location
**Decision**: Move to **`build/<profile>/.ic-cache`** (under the same per-profile directory as artifacts).
- Aligns with Req 4 path partition. `rm -rf build/release/` clears both release artifact and release IC together.
- Alternatives considered:
  - `build/.ic-cache/<profile>/` — keeps cache at one root, but mixes concerns (cleanup of one profile must traverse the other).
  - Rejected: keeping flat `build/.ic-cache` violates Req 5.1/5.2 (no profile segment).

### Item 3: Daemon IC schema
**Resolution**: Issue #261's "daemon IC store under `~/.kolt/daemon/ic/<v>/<id>/<profile>/`" is a misattribution. The JVM daemon owns `~/.kolt/daemon/ic/` and JVM is declared no-op (Req 3.3); Native does not use that path. Final schema:
- JVM: unchanged (`~/.kolt/daemon/ic/<kotlinVersion>/<projectIdHash>/`).
- Native: `build/<profile>/.ic-cache` (project-local, not daemon-managed).
- ADR 0030 will document this discrepancy resolution.

### Item 4: `Profile` in wire protocol
**Decision**: No wire-protocol change. Native daemon receives konanc args with profile-derived flags (`-opt`, `-g`) and IC path already embedded in `args`. JVM daemon receives the same `Compile.workingDir` regardless of profile (no-op).
- Keeps Native and JVM daemon protocols unchanged for this feature.
- Future profile-aware daemon behavior (if ever needed) is a separate ADR.

### Item 5: Drift guard expansion
**Decision**: Add a 4th `DriftGuardsTest` assertion verifying `Profile.Debug.dirName == "debug"` and `Profile.Release.dirName == "release"` literals match the `assemble-dist.sh` invocation flag.
- Lightweight (single assertion) and prevents the next time someone renames the profile literal to `dev`/`prod`.
- Keeps profile literal as a single source of truth in `Profile.kt`.

### Item 6: `kolt clean`
**Resolution**: kolt has no `clean` subcommand today. Out of scope for this feature; `rm -rf build/<profile>/` is the documented cleanup path.

### Item 7: `kolt-usage` skill scan
**Finding**: `.claude/skills/kolt-usage/SKILL.md` documents user-facing build commands. Update locations to be confirmed during task generation; expected ~2-3 lines.

## Generalization
The `Profile` concept is the only generalization candidate. It has exactly two values today (Debug, Release) and no requirement points to a third (custom profiles, project-defined profiles). Keep the enum closed; if `kolt.toml [profile.foo]` ever lands, it can extend the type then.

## Build vs Adopt
No external library is adopted. The change is mechanical threading of an enum through existing functions. Cargo's `--release` UX is the only "adoption" — it informs the flag name and default semantics, not implementation.

## Simplification
- No `BuildPaths` value class. Existing path functions accept a `profile: Profile` parameter (Option C from gap analysis).
- No optional/nullable `Profile?`. Every entry function takes a non-null `Profile`; default = `Profile.Debug` is computed once in `Main.kt` and threaded.
- No `kolt.toml` schema change.
- ADR 0030 defers `kolt.toml [profile.*]` configuration explicitly.
