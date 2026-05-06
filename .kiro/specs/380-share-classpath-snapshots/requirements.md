# Requirements Document

## Introduction

#376 で JVM compile daemon が `kolt test` の test compile を経路に乗せたあと、各 project の main / test scope は独立した `shrunk-classpath-snapshot.bin` を保持するレイアウト (`<projectIdHash>/<scope>/bta/`) になった。一方、同一 project の main scope と test scope の compile classpath は kotlin-stdlib / plugins / 共通 deps の大部分が重複し、test scope は通常 main classpath を真部分集合として包含する。現状その重複部分を再計算するコストが daemon-routed cold-path test compile の wall-time（#376 dogfood: ~12s）に乗っており、subprocess fallback (~6-8s) に逆転されている。

本 spec は BTA-side のキャッシュレイアウトを拡張し、scope 間で shrunk classpath snapshot を再利用または増分拡張することで、daemon-routed test compile の cold-path を縮める。reaper との整合性を含む invariants は維持する。

## Boundary Context

- **In scope**:
  - `shrunk-classpath-snapshot.bin` を scope 間で共有または prefix 増分で再利用するキャッシュ層の挙動
  - 新規導入される shared cache state の reaper safety（LOCK / breadcrumb による保護）
  - 改善計測の対象 project（`kolt-jvm-compiler-daemon`）における cold-path / warm-path の挙動
- **Out of scope**:
  - daemon wire protocol（`Message.Compile` / `CompileResult` 等）の変更
  - per-jar snapshot 層（既に `classpath-snapshots/` でグローバル共有済み）の構造変更
  - native compile (konanc) 側の snapshot
  - daemon 起動時間 / JVM warmup / classloader 設計
  - multi-project / multi-module 跨ぎでの shrunk snapshot 共有
- **Adjacent expectations**:
  - `IcStateLayout` が定義する `<projectIdHash>/<scope>/` 区分と LOCK / breadcrumb 規約に従う（#376 で確立）
  - `BtaIncrementalCompiler` の `removeOutputForSourceFiles` 副作用を引き起こす per-scope inputsCache 分離は維持する
  - `friendPaths` セマンティクス（test compile が `-Xfriend-paths=<main classes>` を受ける）は変えない
  - `kolt-jvm-compiler-daemon` セルフホストプロジェクトを benchmark 兼 regression guard として再利用する

## Requirements

### Requirement 1: Cross-scope shrunk snapshot reuse

**Objective:** As a kolt user running `kolt build` followed by `kolt test`, I want the daemon to reuse the BTA shrunk classpath snapshot computed for the main compile when the test compile starts, so that the cold-path test compile does not pay the full snapshot recomputation cost.

#### Acceptance Criteria
1. When the JVM compile daemon receives a compile request for scope B and an existing valid shrunk classpath snapshot is on disk for scope A in the same project, and scope B's classpath equals scope A's classpath, the JVM compile daemon shall reuse scope A's snapshot for scope B without recomputing it.
2. When the JVM compile daemon receives a compile request for scope B and an existing valid shrunk classpath snapshot is on disk for scope A in the same project, and scope A's classpath is a strict ordered prefix of scope B's classpath, the JVM compile daemon shall produce scope B's snapshot by extending scope A's snapshot with only the additional classpath entries.
3. If no valid prior-scope snapshot exists or the candidate snapshot's classpath does not match scope B's classpath as equal or as a prefix, the JVM compile daemon shall compute scope B's shrunk classpath snapshot from scratch.
4. If a candidate prior-scope snapshot is detected as stale (the underlying classpath entries' identity has changed since the snapshot was written), the JVM compile daemon shall not reuse it and shall compute scope B's snapshot from scratch.
5. The JVM compile daemon shall keep each scope's IC inputs state and per-scope shrunk snapshot writes scoped to that scope's working directory, so that the scope-segregation property established in #376 — where compiling one scope must not invalidate or delete the other scope's compiled outputs — is preserved.

### Requirement 2: Measurable cold-path improvement on the benchmark project

**Objective:** As a kolt maintainer evaluating whether the change earns its complexity, I want a reproducible cold-path measurement on `kolt-jvm-compiler-daemon` that demonstrates the snapshot reuse path actually shortens the first daemon-routed test compile.

#### Acceptance Criteria
1. When the cold-path test compile scenario is reproduced on the `kolt-jvm-compiler-daemon` project (fresh `~/.kolt/daemon/ic/`, run `kolt build` to populate main scope IC and snapshot, then run `kolt test` and measure first BTA compile wall-time), the JVM compile daemon shall complete the test compile in less wall-time with cross-scope snapshot reuse enabled than with reuse disabled.
2. The cold-path test compile measurement on `kolt-jvm-compiler-daemon` with cross-scope snapshot reuse enabled shall be recorded with a reproducible procedure (script or test harness) so the reduction can be verified by a third party.
3. Where the bench harness `spike/bench-scaling/` is reused, the harness shall use synthetic epoch-based mtime manipulation rather than plain `touch` so that WSL2 9p 1s mtime granularity does not silently invalidate the cache being measured.

### Requirement 3: No regression on warm incremental rebuild

**Objective:** As a kolt user editing source between builds, I want the warm-path daemon-routed rebuild to remain at or near the #376 baseline so that the cold-path improvement does not come at the cost of incremental editing latency.

#### Acceptance Criteria
1. While the JVM compile daemon is hot and IC state is warm, when a single test source file is edited and `kolt test` is invoked, the JVM compile daemon shall complete the BTA compile within the documented #376 warm-rebuild baseline (~490 ms on `kolt-jvm-compiler-daemon`) plus a tolerance band that does not mask a real regression.
2. When `kolt test` is invoked twice consecutively without any source change, the JVM compile daemon shall continue to honor the existing up-to-date short-circuit behavior recorded in #376 dogfood (~0.019 s no-op test path).

### Requirement 4: Reaper safety for shared cache state

**Objective:** As a kolt operator running parallel `kolt` invocations, I want the IC reaper to leave any shared snapshot state alone while a build is in progress, so that cross-scope sharing does not introduce mid-build cache corruption.

#### Acceptance Criteria
1. Where new shared cache files are introduced to support cross-scope snapshot reuse, the JVM compile daemon shall claim those files under an existing or newly added LOCK / breadcrumb so that `IcReaper` can identify them as live and skip deletion.
2. While a project's `<projectIdHash>/LOCK` is held by an active daemon, the IC reaper shall not delete that project's shrunk snapshot state (per-scope or shared).
3. If a `<projectIdHash>` directory has neither a valid breadcrumb pointing at a live project nor a held LOCK, the IC reaper shall continue to delete that directory in full, including any newly introduced shared snapshot state under it.
4. When two daemon processes attempt cross-scope snapshot reuse on the same project concurrently (race scenario), the JVM compile daemon shall serialize writes to any shared snapshot file or scope-segregate the write so that neither daemon observes a torn / partial snapshot.

### Requirement 5: Wire protocol and scope segregation invariants preserved

**Objective:** As a kolt user with an older `kolt.kexe` talking to a newer daemon (or vice versa), I want this change to leave the daemon wire contract unchanged so that no client-daemon mismatch is introduced by snapshot sharing alone.

#### Acceptance Criteria
1. The JVM compile daemon shall accept and respond to the same `Message.Compile` / `CompileResult` shapes as before this change, with no added or modified fields specific to cross-scope snapshot reuse.
2. The JVM compile daemon shall continue to honor the existing `friendPaths` semantics so that a test compile receives `-Xfriend-paths=<main classes dir>` regardless of whether the shrunk snapshot was reused or recomputed.
3. The JVM compile daemon shall continue to write per-scope IC inputs state under each scope's working directory so that the scope-segregated layout introduced by #376 (`~/.kolt/daemon/ic/<v>/<projectIdHash>/<scope>/`) remains observable from the file system for a third-party tool inspecting it.

### Requirement 6: Multi-shape regression coverage

**Objective:** As a kolt maintainer protecting against shape-specific regressions, I want the existing #381 multi-shape integration suite to also exercise the cross-scope snapshot reuse path so that no-plugin and serialization-plugin shapes both validate the new behavior.

#### Acceptance Criteria
1. When the no-plugin and serialization-plugin shapes from the #381 multi-shape integration suite run `kolt build` followed by `kolt test`, the JVM compile daemon shall produce identical compiled outputs to the pre-change behavior, with no test failure attributable to snapshot reuse.
2. When the multi-shape integration suite executes the cold-path test compile after a fresh main build, the JVM compile daemon shall populate IC state under both `<projectIdHash>/main/` and `<projectIdHash>/test/` segments as before, with the shared snapshot state additionally present at the location chosen in design.
3. The JVM compile daemon shall remain compatible with the bootstrap-gated env override pattern (`KOLT_DAEMON_JAR` / `KOLT_NATIVE_DAEMON_JAR`) so the multi-shape suite can exercise the changed daemon under test.
