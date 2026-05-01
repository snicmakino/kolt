# Requirements Document

## Introduction

本機能は kolt の daemon 副プロジェクト 3 個 (`kolt-jvm-compiler-daemon`、 `kolt-jvm-compiler-daemon/ic`、 `kolt-native-compiler-daemon`) の JUnit 5 テスト集合を `kolt test` で実行可能にし、 user-facing / developer-facing surface から `./gradlew check` 参照を撤去する。

`./gradlew check` は #298 / #302 / #306 後に残った最後の Gradle 依存レシピで、 `tech.md` に「special-purpose」として annotated されている。 本 spec の完了をもって kolt-on-kolt 開発フローから Gradle が完全に消える (orphan ファイル物理削除は #316 で別途対応)。

スキーマ面の前提は #318 (PR #321) で landed 済の ADR 0032 (`[classpaths.<name>]` + `[test.sys_props]` + `[run.sys_props]`) に依拠する。

## Boundary Context

- **In scope**:
  - `kolt-jvm-compiler-daemon/` および `kolt-native-compiler-daemon/` の各 directory から `kolt test` を実行することで全 daemon JUnit 5 テスト集合が走る状態の確立 (root daemon と ic は同一の kolt project として統合運用、 design phase で確定)
  - ic テストが必要とする 4 つの classpath bundle と、 root daemon テストが必要とする 1 つの project-relative path の declaration を ADR 0032 schema で表現
  - `[test.sys_props]` 経由で 5 個のシステムプロパティ (4 classpath + 1 project_dir) が test JVM に届くことの保証
  - ic test と root daemon test を統合 kolt project で実行する際の test classpath isolation を補完する mechanism (例えば「ic test source が `kolt.daemon.{Main,server,reaper}` を import していない」 ことを source 解析で assert する新規 invariant test の追加) — Gradle module 境界の文化的な代替を確立する
  - 既存 user-facing / developer-facing surface (`tech.md`、 関連 SKILL / docs) から `./gradlew check` 言及の撤去
  - 既存 daemon テストロジックを変更せず、 同一テスト結果を保つこと
- **Out of scope**:
  - orphan な Gradle 設定ファイル (`build.gradle.kts`、 `settings.gradle.kts`、 `gradlew`、 `gradle/wrapper/`) の物理削除 (#316)
  - `./gradlew linkDebugExecutableLinuxX64` 等価レシピの提供 (vestigial、 #316)
  - multi-module project 機能 (#4) への統合 (Option B 採用により ic を独立 kolt project にしないため、 path-based dep schema は本 spec で導入しない)
  - kolt CLI / schema の **新機能追加** (= 新しい API / コマンド / オプションの追加。 既存挙動の bug fix は本 spec で必要に応じて含める。 R7 が代表例: 既存 test compile path の `-module-name` / `-Xfriend-paths` 欠落を修正、 これは Gradle parity を満たすための bug fix で新機能ではない)
  - daemon 副プロジェクトの test 範囲拡張 (Gradle 集合と同一を保つ。 invariant test の新規追加は `ic test の import 制約` を assert する目的に限る)
  - `kolt-jvm-compiler-daemon/ic/` directory 単体での `kolt test` 実行サポート (本 spec の元案では Requirement 1.2 の filter 経路で代替する想定だったが、 Pre-flight Gate で filter 経路自体も動作不能と判明、 fix を #323 へ defer。 本 spec では ic-only / root-only DX 自体を一時的に犠牲にする)
- **Adjacent expectations**:
  - ADR 0032 で定義された `[classpaths.<name>]` / `[test.sys_props]` の挙動 (resolver / lockfile / sysprop delivery) はそのまま流用する
  - `kolt test` 共通の挙動 (target / kind / build profile / exit code) はそのまま流用する
  - daemon 副プロジェクトの bootstrap / build 経路 (`kolt build`) は既存どおりで、 本 spec では変更しない
  - CI workflow は既に `./gradlew check` を呼んでおらず、 self-host smoke も daemon test を直接走らせていないため、 CI 経路の変更はスコープ外

## Requirements

### Requirement 1: Daemon JUnit 5 suites run via `kolt test`

**Objective:** As a kolt-on-kolt maintainer, I want every daemon's JUnit 5 test suite to be runnable via `kolt test`, so that I can verify daemon changes without invoking Gradle.

#### Acceptance Criteria

1. When the maintainer runs `kolt test` from the `kolt-jvm-compiler-daemon/` directory, the kolt test runner shall execute the union of every JUnit 5 test that `./gradlew :kolt-jvm-compiler-daemon:check` and `./gradlew :kolt-jvm-compiler-daemon:ic:check` exercise today (i.e. tests currently under `kolt-jvm-compiler-daemon/src/test/kotlin/` and `kolt-jvm-compiler-daemon/ic/src/test/kotlin/`).
2. **Deferred to #323**: The kolt test runner shall accept a passthrough argument form (e.g. `kolt test -- <junit-console-launcher-args>`) that lets the maintainer narrow execution to a single class or package. Pre-flight Gate during impl confirmed this is currently impossible because `testRunCommand` injects `--scan-class-path` unconditionally and JUnit Platform Console Launcher 1.11.4 rejects scanning together with explicit selectors. The fix lives in kolt CLI (TestRunner argv builder) and is tracked in issue #323. Until #323 lands, ic-only and root-daemon-only test runs are not supported via filter; maintainers reproduce them by running the full suite from `kolt-jvm-compiler-daemon/`.
3. When the maintainer runs `kolt test` from the `kolt-native-compiler-daemon/` directory, the kolt test runner shall execute every JUnit 5 test that `./gradlew :kolt-native-compiler-daemon:check` exercises today (i.e. tests currently under `kolt-native-compiler-daemon/src/test/kotlin/`).
4. If any test in the suites covered by criteria 1 and 3 fails, the kolt test runner shall exit with a non-zero status code.
5. When every test in the suites covered by criteria 1 and 3 passes, the kolt test runner shall exit with status code zero.
6. The kolt test runner shall produce the same set of pass / fail outcomes that the corresponding `./gradlew :<subproject>:check` invocations produce against the same source tree, treating the criterion-1 union as equivalent to the union of `./gradlew :kolt-jvm-compiler-daemon:check` and `./gradlew :kolt-jvm-compiler-daemon:ic:check`.

### Requirement 2: Classpath bundles for ic test fixtures

**Objective:** As a kolt-on-kolt maintainer, I want the ic subproject's four Gradle-resolved classpath bundles declared in `kolt.toml` using ADR 0032 schema, so that the ic test JVM receives the same jar sets it currently receives via Gradle custom configurations.

#### Acceptance Criteria

1. The kolt configuration that backs the ic subproject's `kolt test` shall declare a named classpath bundle resolving to the same artifact set that the Gradle `buildToolsImpl` configuration resolves today (currently `org.jetbrains.kotlin:kotlin-build-tools-impl:2.3.20` and its transitive closure under the same Maven Central resolution policy).
2. The kolt configuration that backs the ic subproject's `kolt test` shall declare a named classpath bundle resolving to the same artifact set that the Gradle `fixtureClasspath` configuration resolves today (currently `org.jetbrains.kotlin:kotlin-stdlib:2.3.20` and its transitive closure).
3. The kolt configuration that backs the ic subproject's `kolt test` shall declare a named classpath bundle resolving to the same artifact set that the Gradle `serializationPluginClasspath` configuration resolves today (currently `org.jetbrains.kotlin:kotlin-serialization-compiler-plugin-embeddable:2.3.20` and its transitive closure).
4. The kolt configuration that backs the ic subproject's `kolt test` shall declare a named classpath bundle resolving to the same artifact set that the Gradle `serializationRuntimeClasspath` configuration resolves today (currently `org.jetbrains.kotlin:kotlin-stdlib:2.3.20` plus `org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.7.3` and their transitive closure).
5. When `kolt deps install` is run against the ic subproject's kolt project, the kolt resolver shall persist the four bundles' locked GAV+SHA into `kolt.lock` per ADR 0032 Requirement 4.
6. The four bundles' contents shall remain isolated from `[dependencies]` and `[test-dependencies]` of the same kolt project: a transitive coming through any of the four bundles shall not silently appear on the main / test classpath unless explicitly declared there.

### Requirement 3: System property delivery to ic test JVM

**Objective:** As an ic test author, I want the four classpath bundles delivered to the test JVM under the same system-property keys the existing Gradle `tasks.test` block uses, so that test code reading `System.getProperty(...)` continues to function unchanged.

#### Acceptance Criteria

1. When `kolt test` spawns the ic test JVM, the kolt test runner shall set `-Dkolt.ic.btaImplClasspath=<value>` where `<value>` is the colon-joined sequence of absolute paths to every jar in the bundle declared per Requirement 2.1.
2. When `kolt test` spawns the ic test JVM, the kolt test runner shall set `-Dkolt.ic.fixtureClasspath=<value>` where `<value>` is the colon-joined sequence of absolute paths to every jar in the bundle declared per Requirement 2.2.
3. When `kolt test` spawns the ic test JVM, the kolt test runner shall set `-Dkolt.ic.serializationPluginClasspath=<value>` where `<value>` is the colon-joined sequence of absolute paths to every jar in the bundle declared per Requirement 2.3.
4. When `kolt test` spawns the ic test JVM, the kolt test runner shall set `-Dkolt.ic.serializationRuntimeClasspath=<value>` where `<value>` is the colon-joined sequence of absolute paths to every jar in the bundle declared per Requirement 2.4.
5. The four sysprop values shall arrive at `System.getProperty(...)` with the same semantics ic test code currently expects: each value is a non-empty colon-joined classpath string, every path resolves to an existing jar file on disk, and order matches declaration order in the bundle.

### Requirement 4: Source-root system property for adapter boundary invariant

**Objective:** As a daemon-core maintainer, I want `AdapterBoundaryInvariantTest` to receive the daemon-core main source root path through `kolt.toml`-declared sysprop, so that ADR 0019 §3 enforcement keeps working without Gradle's `layout.projectDirectory.dir(...)` helper.

#### Acceptance Criteria

1. The kolt configuration that backs the `kolt-jvm-compiler-daemon` daemon-core test shall declare an entry in `[test.sys_props]` whose key is `kolt.daemon.coreMainSourceRoot` and whose value resolves to the absolute filesystem path of `kolt-jvm-compiler-daemon/src/main/kotlin`.
2. When `kolt test` spawns the daemon-core test JVM, the kolt test runner shall set `-Dkolt.daemon.coreMainSourceRoot=<absolute-path>` per ADR 0032 Requirement 3 `project_dir` semantics.
3. The kolt test runner shall produce a sysprop value that points to an existing directory containing `.kt` files, such that `AdapterBoundaryInvariantTest` walks the same source root it walks today under Gradle.

### Requirement 5: Removal of `./gradlew check` references from documentation surfaces

**Objective:** As a contributor reading project docs, I want `./gradlew check` no longer presented as a development recipe, so that the kolt-on-kolt workflow is consistent across all guidance.

#### Acceptance Criteria

1. The kolt project shall remove the `./gradlew check` line and its surrounding "Special-purpose" annotation from `.kiro/steering/tech.md`.
2. The kolt project shall remove or replace any other occurrence of `./gradlew check` in `CLAUDE.md`, `.kiro/steering/`, `.claude/skills/kolt-*/`, and `docs/` such that no user-facing or developer-facing surface continues to recommend the recipe.
3. The kolt project shall keep `./gradlew check` references that exist purely as historical record (specs / ADRs / spike directories that document past decisions); these are not user-facing recipes.
4. After this requirement is satisfied, a repository-wide search for `gradlew check` shall yield zero matches in user-facing or developer-facing surfaces (excluding historical record locations enumerated in 5.3 and orphan Gradle config files retained for #316).

### Requirement 6: Behavioural parity with existing test suite

**Objective:** As a kolt-on-kolt maintainer, I want the migration to leave existing daemon test logic untouched, so that any post-migration test failure pinpoints the runner switch and not a behavioural drift in the test code.

#### Acceptance Criteria

1. The kolt project shall not modify the body of any existing `kolt-jvm-compiler-daemon/**/src/test/kotlin/**/*.kt` or `kolt-native-compiler-daemon/src/test/kotlin/**/*.kt` file as part of this spec's implementation, except for changes required strictly to receive sysprop values previously injected by Gradle (Requirements 3 and 4).
2. The kolt project shall keep the JUnit 5 platform version, kotlin-test version, and JDK toolchain (currently JDK 25) unchanged for daemon test execution.
3. The kolt project shall keep test-discovery semantics equivalent to `useJUnitPlatform()` so that the same test classes are picked up under `kolt test` as under `./gradlew :<subproject>:test`.
4. When run on the same source tree, `./gradlew check` (against the orphan Gradle config retained for #316) and `kolt test` against the new kolt configuration shall, until #316 lands, produce the same green / red verdict on the daemon test suites — treating the kolt-side `kolt test` invocation in `kolt-jvm-compiler-daemon/` as equivalent to the union of `./gradlew :kolt-jvm-compiler-daemon:check` and `./gradlew :kolt-jvm-compiler-daemon:ic:check`.
5. The kolt project may add new test files under the daemon test source roots whose sole purpose is to encode the test-classpath isolation invariant lost in the merger of root daemon and ic into a single kolt project (e.g. asserting ic test sources do not import `kolt.daemon.{Main,server,reaper}` packages). Such additions are permitted only when their failure mode is statically derivable from source code (no production-code dependency, no flake risk).

### Requirement 7: kolt CLI test compile module-name alignment

**Objective:** As a kolt-on-kolt maintainer, I want the JVM test compile path to set `-module-name` and `-Xfriend-paths` so that test source sets can access `internal` symbols of the same kolt project, just as they do under Gradle's Kotlin plugin.

This requirement was added during impl phase (task 4.1 hit a pre-existing kolt CLI bug where test compile uses a default `-module-name` derived from the output directory, so test sources see main as a different Kotlin module and lose `internal` visibility). The fix is in scope for this spec because without it R1.1 / R1.4 / R1.5 / R6.4 are unachievable: the daemon test suites depend on `internal` access (multiple symbols in `kolt.daemon.Main`, `SelfHealingIncrementalCompiler`, `BtaIncrementalCompiler`, `ClasspathSnapshotCache`).

#### Acceptance Criteria

1. When kolt's JVM test compile runs (via `testBuildCommand` or its equivalent), the kolt build pipeline shall pass `-module-name <project-name>` to kotlinc so the test source set is compiled into the same Kotlin module as main.
2. When kolt's JVM test compile runs, the kolt build pipeline shall pass `-Xfriend-paths=<main-classes-dir>` to kotlinc so the test source set is treated as a friend module of main.
3. When kolt's JVM main subprocess compile runs (via `subprocessArgv`), the kolt build pipeline shall forward `request.moduleName` as `-module-name <module-name>` so the daemon path and subprocess fallback produce identical Kotlin module identity.
4. When `internal`-marked symbols are referenced from a test source file in the same kolt project, the kolt test compile shall succeed with no `internal in file` access error.
5. The kolt project shall add unit tests under `src/nativeTest/kotlin/kolt/build/` that assert the new flags appear in the argv produced by `testBuildCommand` and `subprocessArgv` (one test per builder).
