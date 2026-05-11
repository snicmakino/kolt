# Requirements Document

## Project Description (Input)
Short-term fix for #426 (Layer 1 only): when the native resolver's .module fetch returns 404, fall back to fetching the .pom and use its dependency list for transitive resolution. JetBrains does not publish Gradle Module Metadata for JVM-only Kotlin artifacts like kotlin-reflect (verified across 2.1.0-2.3.20); currently kolt hard-fails on the .module 404 instead. Same problem class as kotlin-stdlib-common (ADR 0011), which is currently solved by a 2-name hardcoded skip list (isKotlinStdlib in NativeResolver.kt:18). This issue is scoped to the .pom fallback only — Layer 2 (variant interpretation audit: why does kotlin-reflect reach native resolution at all?) is explicitly out of scope and will be filed as a separate issue.

## Introduction

`target = "linuxX64"` プロジェクトで JVM 専用 Kotlin artifact (例: `kotlin-reflect`) が transitive dependency として現れた際、kolt が `.module` の 404 で hard-fail し、ビルドが進められない問題を解消する。

現状 kolt は `org.jetbrains.kotlin:kotlin-stdlib` と `org.jetbrains.kotlin:kotlin-stdlib-common` をハードコードされた skip リストで処理しているが、これは 2-name の patch であって、同型の問題 (Gradle Module Metadata が公開されていない JVM 専用 Kotlin artifact が native の transitive closure に現れる) を一般に救うものではない。本仕様では、その状況を構造的に検出して native build を継続できるようにする。

Layer 2 (なぜ `kotlin-reflect` が native variant の transitive に現れるのか、 mixed-platform ライブラリの variant 解釈の audit) は別 issue で扱う。

## Boundary Context

- **In scope**:
  - Native target の transitive 依存解決において、ある artifact の `.module` 取得が HTTP 404 で失敗したときの fallback 経路。
  - その artifact が Gradle Module Metadata を公開していない (= native variant を構造的に持ちえない) ことを確認する手段。
  - 既存の `kotlin-stdlib` / `kotlin-stdlib-common` ハードコード skip 動作の継続性。
  - ADR 0011 §4 で `kotlin-stdlib-common` が解決していた構造的問題への一般化された対応。

- **Out of scope**:
  - Direct dependency に JVM 専用 artifact が指定された場合の policy 変更 (引き続きエラー)。
  - Variant 解釈の audit (Layer 2): mixed-platform ライブラリの native variant に JVM 専用 deps が含まれてしまう問題そのものの修正。
  - JVM target の依存解決経路。
  - lockfile schema の変更。
  - `kolt deps tree` などの依存表示の改修。

- **Adjacent expectations**:
  - 本仕様完了後、ADR 0011 のハードコード skip リスト (具体的には `kotlin-stdlib-common`) は構造的検出で再現できるため一部冗長になる。ADR の改訂は本仕様の必須範囲外だが、実装後に整合を取ることが望ましい。
  - 別 issue (Layer 2) は本仕様で残った構造的疑問 ("そもそも JVM 専用 artifact が native transitive に出るべきか") を引き継ぐ前提。

## Requirements

### Requirement 1: Transitive 解決における `.module` 404 の許容

**Objective:** As a kolt user building a Kotlin/Native project, I want `kolt add` / `kolt build` / `kolt fetch` to succeed when a transitive dependency lacks Gradle Module Metadata, so that JVM-only Kotlin artifacts appearing in the transitive closure of a mixed-platform library do not block native builds.

#### Acceptance Criteria

1. When kolt is resolving transitive dependencies for a native target and an artifact's `.module` request returns HTTP 404, the Native Resolver shall attempt to retrieve the corresponding `.pom` from the same repository as a fallback step.
2. When the `.module` returns 404 and the `.pom` is successfully retrieved, the Native Resolver shall treat that artifact as having no native variant available and shall not abort the build.
3. When both the `.module` and the fallback `.pom` are unavailable, the Native Resolver shall surface a download failure for that artifact with the original group:artifact coordinate and the failure mode preserved (i.e., the user still sees a real error for typos and genuinely missing artifacts).
4. The Native Resolver shall apply the fallback only for HTTP 404 on `.module`; transient errors (network failure, 5xx) shall be reported with the existing error model and shall not trigger the `.pom` fallback.

### Requirement 2: Direct dependency に対する厳格性の維持

**Objective:** As a kolt user, I want `kolt add` and `kolt build` to clearly reject JVM-only libraries when I declare them directly in `[dependencies]` of a native project, so that I am not silently given a broken dependency graph.

#### Acceptance Criteria

1. When a dependency listed directly in `[dependencies]` of `kolt.toml` has its `.module` returning 404 on a native target, the Native Resolver shall not silently skip that dependency.
2. If a direct dependency on a native target cannot produce a usable klib because its Gradle Module Metadata is absent, the Native Resolver shall surface a non-zero exit identifying the offending coordinate.
3. The existing silent-skip behavior for `org.jetbrains.kotlin:kotlin-stdlib` and `org.jetbrains.kotlin:kotlin-stdlib-common` when declared directly shall be preserved.

### Requirement 3: 構造的検出の優先とハードコード skip リストの整合

**Objective:** As a kolt maintainer, I want the new `.module` 404 fallback to subsume the structural reason `kotlin-stdlib-common` is on the hardcoded skip list, so that future JVM-only Kotlin artifacts appearing transitively do not require additions to that list.

#### Acceptance Criteria

1. When the native resolver encounters `kotlin-stdlib-common` (or any artifact whose `.module` returns 404) as a transitive dependency, the Native Resolver shall apply the structural fallback defined in Requirement 1 rather than relying solely on a name match.
2. The Native Resolver shall continue to skip `kotlin-stdlib` (which publishes `.module` but causes a double-link with konanc-bundled stdlib per ADR 0011 §1) via the existing name-based predicate, because its problem class is link-time bundling rather than missing metadata.
3. After this change, JVM-only Kotlin artifacts newly appearing in native transitive closures (e.g., `kotlin-reflect`, future `kotlin-stdlib-jdk8`) shall not require code changes to the name-based skip list.

### Requirement 4: 観測可能性

**Objective:** As a kolt user debugging a native dependency resolution, I want some visible indication when a transitive artifact was skipped because it lacks Gradle Module Metadata, so that I can distinguish "silently dropped" from "never encountered" when investigating downstream link issues.

#### Acceptance Criteria

1. When kolt applies the `.module` 404 → `.pom` fallback and skips a transitive artifact for a native target, kolt shall emit a single diagnostic line on stderr identifying the group:artifact:version and the reason (e.g., `note: <group:artifact>:<version> has no Gradle Module Metadata; skipping for native target`).
2. The diagnostic shall not be emitted for `org.jetbrains.kotlin:kotlin-stdlib` or `org.jetbrains.kotlin:kotlin-stdlib-common`, preserving the silent-skip behavior established for those known cases.
3. The diagnostic shall not fire on direct dependencies skipped via the existing name-based predicate.

### Requirement 5: JVM 経路の不変

**Objective:** As a kolt user with JVM-target projects, I want the `.module` 404 fallback to leave the JVM resolution path unchanged, so that this fix does not introduce risk for projects that do not use the native pipeline.

#### Acceptance Criteria

1. The `.module` 404 fallback shall be applied only inside the native resolution path.
2. The JVM-target transitive resolver shall not be modified by this change.
3. The JVM resolver's existing behavior (POM-based transitive walk, lockfile interaction) shall remain unchanged.

### Requirement 6: 再現ケースのカバレッジ

**Objective:** As a kolt maintainer, I want a regression test that exercises the reported repro case, so that future native resolver changes cannot reintroduce the abort.

#### Acceptance Criteria

1. When an integration test runs the equivalent of `kolt add io.ktor:ktor-server-core` on a `target = "linuxX64"` configuration and the transitive closure contains an artifact whose `.module` is absent (specifically `kotlin-reflect`), the test shall verify that resolution completes without surfacing the `.module` 404 as a fatal error.
2. The test shall not rely on adding `kotlin-reflect` to any name-based skip list; it shall pass via the structural `.module` 404 fallback defined in Requirement 1.
3. The test fixture shall use a deterministic repository state (recorded `.module` 404 + `.pom` 200 + transitive content) rather than depending on live Maven Central at test time.
