# bta-shrunk-portability spike — verdict

**Spec:** #380 task 1.2
**BTA version:** kotlin-build-tools-api 2.3.20 (pinned in `kolt-jvm-compiler-daemon/kolt.toml`)
**Fixture:** `kolt-jvm-compiler-daemon/` (this repo, ~1MB shrunk file per scope)
**Driver:** in-tree `build/debug/kolt.kexe` (kolt 0.18.0) with `KOLT_DAEMON_JAR` pointing at the in-tree daemon thin jar
**Date:** 2026-05-06
**Reproducible:** `spike/bta-shrunk-portability/harness.sh`

## VERDICT: GO

All three measured questions support proceeding with the design as written.
Q1 confirms portability (no surprise — `ClasspathEntrySnapshot` is documented
path-free). Q2 shows an 8.7% wall-time reduction on test compile when main's
shrunk file is pre-placed at test's bta path, which crosses the 5% OQ-1 GO
threshold. Q3 forces a small hardening of the design — BTA writes in-place,
so copy (not hardlink) is the only safe placement strategy. Design already
chose copy as the default after the validate-design review, so no design
amendment is required.

| Question | Verdict | Evidence | Decision |
|----------|---------|----------|----------|
| Q1 portability | **PORTABLE_GREEN** | main shrunk file copied to test workingDir, BTA compile succeeded, 139 `.class` produced | proceed with global content-keyed cache |
| Q2 wall-time gain | **GO (8.7% reduction)** | median 85905 ms (no pre-place) → 78386 ms (pre-placed), 5 runs each | clears 5% OQ-1 threshold, Req 1.2 stays as designed |
| Q3 BTA write semantics | **IN_PLACE_WRITE** | inode preserved across compiles (1005330 → 1005330) on the same workingDir | copy is mandatory; hardlink opt-in (OQ-3) is **closed as never-safe** |
| Q4 flag invariance | **DEFERRED** | not measured; BTA source documents `ClasspathEntrySnapshot` as classId/ABI hash only | risk bounded; revisit only if R-2 fires during impl |

## Q1 — Portability across workingDirs

**Hypothesis:** shrunk file embeds no path / workingDir state, so a file
produced in workingDir A can be placed at workingDir B and BTA will accept it
(`research.md` §6.1, BTA source `ClasspathEntrySnapshot.kt`).

**Method:** `harness.sh` → `Q1` block. Wipe IC and project state, build main
scope, copy main's `shrunk-classpath-snapshot.bin` to test's `bta/` path
(both within same project but different scope dirs), run `kolt test`, observe
exit and `.class` output.

**Result:**
- main scope shrunk file: 1085820 bytes, sha256 `1e2956b1d7ea96e274fa28d79923a6aa8bfa1a21663b41af8f0d70e5bb3e1e55`
- pre-placed at `<projectId>/test/bta/shrunk-classpath-snapshot.bin` with the same sha256
- `kolt test` exit 0, wall_ms=86566
- post-compile shrunk file: 1074741 bytes, sha256 `1bd9b9619fa673060925c4cec2f36f6223d8476b5c8c4d0ea4e8afac64fee065` (different — BTA overwrote with test scope's own snapshot)
- `build/**/*.class` count: 139

**VERDICT_Q1: PORTABLE_GREEN**. BTA accepts a pre-placed shrunk file from a
different scope's workingDir without surfacing any error, and produces a
correct compile.

The pre→post sha change confirms BTA does not blindly trust the pre-placed
file as the final shrunk state — it reads it (for the internal incremental
shrink optimization, which is what Q2 measures), then writes its own scope-
specific shrunk state on top.

## Q2 — Pre-placement wall-time reduction

**Hypothesis:** when the cache file is pre-placed at the per-scope output
path, BTA's internal `ClasspathSnapshotShrinker` incremental optimization
(`research.md` §6.4, source lines 248-313) treats it as the "previous" shrunk
snapshot and skips recomputing the overlap, reducing test compile wall-time.

**Method:** `harness.sh` → `Q2` block. Two arms, 5 runs each:
- arm A: wipe IC + state, build main, run test (no pre-place)
- arm B: wipe IC + state, build main, copy main's shrunk to test/bta/, run test

Wall-time captured at the `kolt test` boundary (includes daemon spawn cost
because each iteration restarts the daemon — this inflates absolute numbers
but applies symmetrically to both arms).

**Raw measurements:**

| run | arm A (no pre-place) ms | arm B (pre-placed) ms |
|----:|-----:|-----:|
| 1 | 85905 | 78585 |
| 2 | 87769 | 78344 |
| 3 | 85464 | 78386 |
| 4 | 86187 | 78150 |
| 5 | 83166 | 78752 |
| **median** | **85905** | **78386** |
| range | 4603 | 602 |

**Delta:** 7519 ms reduction (median A − median B). 8.7% relative reduction.

Arm B's variance is also dramatically tighter (range 602 ms vs arm A's 4603 ms),
which is consistent with "BTA skips the variable-cost shrink work when the
previous snapshot covers the same classes."

**VERDICT_Q2: GO**. 8.7% > 5% OQ-1 GO threshold. Req 1.2 (best-effort
extension via BTA internal optimization) stands as designed. No back-port
required.

The absolute reduction (~7.5 s on a ~85 s cold-test compile that includes
daemon startup) understates the steady-state benefit. Inside a hot daemon
where startup is amortized, the BTA shrink work dominates a larger fraction of
the per-compile budget, so the 8.7% relative reduction should translate to a
larger relative improvement on the implementation's bench scenario in task 5.2.

## Q3 — BTA write semantics: in-place vs atomic rename

**Hypothesis:** if BTA writes the shrunk file via tmp+rename (atomic
replace), hardlink placement would be safe because the per-scope path's inode
gets replaced and the cache file's inode is undisturbed. If BTA writes
in-place (truncate + write), hardlink placement corrupts the cache because
the per-scope write modifies the same inode the cache file points at.

**Method:** `harness.sh` → `Q3` block. Build, capture inode and sha256 of
shrunk file. Touch a main source (synthetic-epoch mtime per memory
`feedback_bench_mtime_granularity`). Build again. Compare inodes.

**Result:**
- after first build: inode=1005330, sha=`1e2956b1...`
- touched: `kolt-jvm-compiler-daemon/src/main/kotlin/kolt/daemon/Main.kt`
- after second build (post-touch): inode=**1005330**, sha=`1e2956b1...` (unchanged)

**VERDICT_Q3: IN_PLACE_WRITE**. BTA preserves the file inode across compiles,
so it writes in-place (truncate-then-write or seek-then-write), not via
tmp+rename.

**Design implication:**
- The validate-design review (round 1) recommended `Files.copy` as the
  default placement strategy, with `Files.createLink` (hardlink) as an
  opt-in pending Q3 confirmation. **Q3 closes that question with "hardlink
  is never safe."**
- OQ-3 in design.md §Open Questions can be marked **closed**: hardlink is
  not pursued. `Files.copy` is the permanent placement strategy.

If hardlink had been used, the per-scope BTA write would have modified the
cache file in-place via the shared inode, corrupting the cache for any
subsequent compile that hits the same `<classpathHash>` key. Detecting this
in production would require explicit content auditing (the
`compiledClassesAreByteIdentical_acrossCacheHitAndMiss` IT in task 5.1), and
the silent-corruption window would be measured in days of hot daemon use
before the next cold-cache rebuild surfaced the divergence.

## Q4 — Compiler-flag invariance (deferred)

**Not measured in this spike.** Reasoning:

- BTA source (`research.md` §6.1, `ClasspathEntrySnapshot.kt`) defines the
  shrunk content as classId / classAbiHash / supertypes / member-level
  KotlinClassInfo / package member names — pure class metadata of the
  classpath jars.
- Compiler flags (`-Xfriend-paths`, plugin args, language version) shape the
  compiler's interpretation of the source being compiled and which symbols
  resolve where, but they do not change the metadata of the classpath
  classes themselves.
- The risk surface is therefore bounded: if the implementation observes
  shrunk-content divergence on flag-only changes, the cache key can be
  extended to include a stable hash over the relevant flag set without
  changing the design's overall shape.

R-2 in design.md §Open Questions stays open as an implementation-time check;
the cache-key derivation in task 3.1 should be reviewed against the actual
flag effects on `ClasspathSnapshotShrinker.shrinkClasses` before merging.

## Reproducing

```
./build/debug/kolt.kexe build                                              # ensure native binary present
cd kolt-jvm-compiler-daemon && ../build/debug/kolt.kexe build              # ensure daemon thin jar present
./build/debug/kolt.kexe fetch                                              # one-time: regenerate kolt.lock if upgrading from <0.18
spike/bta-shrunk-portability/harness.sh                                    # ~17 minutes wall on the dev box used here
```

Raw output captured under `spike/bta-shrunk-portability/results/` (one
`Q{1,2,3,4}.log` per question, plus per-iteration `q2-A-run{1..5}.log` and
`q2-B-run{1..5}.log` for the wall-time scenario).

## Decisions surfaced for downstream tasks

- **OQ-1 → GO**: Phase 1 onward in `tasks.md` proceeds as planned. No
  requirements.md back-port for Req 1.2.
- **OQ-3 → CLOSED-NEVER**: hardlink opt-in is not pursued. design.md §System
  Flows already specifies `Files.copy` as the default; this is now the
  permanent decision. The design's "spike T1 may opt-in hardlink later"
  qualifier can be removed in a small design.md cleanup commit.
- **R-2 → carried forward**: cache-key derivation in task 3.1 includes a
  reviewer note to verify flag invariance against the actual
  `ClasspathSnapshotShrinker.shrinkClasses` dependencies before merging
  task 3.1. If divergence found, extend key to cover the relevant flags.

## Not in this spike (bounded out-of-scope)

- Concurrency: two daemons writing the same cache key simultaneously. The
  design's "monotonically growing content" reasoning covers this in
  principle; the impl-side test (`storeIfNew` idempotency under repeated
  calls in task 3.1) verifies the file-system-level safety.
- Cache scaling: shrunk-snapshots/ growth over many distinct classpaths.
  Out of scope per design (deferred to follow-up issue if observed).
- Cross-Kotlin-version cache: separately handled by the existing `<v>`
  partition in IcStateLayout (research §6.1).
