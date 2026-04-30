# Requirements Document

## Introduction

本機能は kolt.toml に「named classpath bundle 宣言」と「test/run JVM 用 sysprop 宣言」を追加し、kolt 単体で JVM プロセスに `-D<key>=<value>` を渡せるようにする。狙いは以下:

1. main / test 通常 classpath とは独立な「名前付き依存集合」を declare 可能にする (compiler plugin、隔離 classloader、build-time fixture など、後段プロセスに `URLClassLoader` 等で供給したい場面が背景)
2. `kolt test` / `kolt run` が起動する JVM に sysprop を宣言ベースで渡せるようにする

ダウンストリームの直近 consumer は #315 (`./gradlew check` 撤去) であり、daemon サブプロジェクト群は本 schema 追加なしでは Gradle 依存を切れない。本 spec はその前段として schema 表面のみを確定させる。

## Boundary Context

- **In scope**:
  - `[classpaths.<name>]` top-level table を kolt.toml に追加
  - `[test.sys_props]` および `[run.sys_props]` table を追加
  - sysprop value 型として literal string / `{ classpath = "<bundle>" }` / `{ project_dir = "<rel>" }` の 3 種を定義
  - bundle が `kolt.lock` / `kolt deps tree` / `kolt deps update` で第一級として扱われる
  - env-agnostic 原則 (kolt.toml は machine 共有の宣言のみ) を ADR に成文化
- **Out of scope**:
  - `${env.X}` 等 kolt.toml 内 environment-variable interpolation
  - `kolt test -D<key>=<value>` / `kolt run -D<key>=<value>` CLI flag (follow-up issue)
  - per-user override file (例: `kolt.local.toml`、follow-up issue)
  - daemon サブプロジェクトの kolt.toml への新 schema 適用と `./gradlew check` 撤去 (これは #315)
- **Adjacent expectations**:
  - 既存の `[dependencies]` / `[test-dependencies]` 機構と同じ resolver / lockfile / cache を流用する前提
  - 既存の `kolt test` / `kolt run` の挙動 (target / kind / build profile) は不変
  - kolt.toml に新 table が一切無い既存プロジェクトの挙動は完全に保たれる (no-op 拡張)

## Requirements

### Requirement 1: Classpath bundle declaration

**Objective:** As a kolt user, I want to declare named, resolvable dependency bundles in `kolt.toml` independent of the main / test classpath, so that I can supply isolated jar collections to compiler plugins, child class loaders, or sysprop-driven test infrastructure without polluting the main compile classpath.

#### Acceptance Criteria

1. When `kolt.toml` contains a `[classpaths.<name>]` table with entries of shape `"group:artifact" = "version"`, the kolt config parser shall accept it as a named classpath bundle.
2. The kolt config parser shall accept multiple distinct `[classpaths.<name>]` tables in the same `kolt.toml`.
3. The kolt config parser shall accept an empty `[classpaths.<name>]` table (zero entries) and treat the bundle as resolving to an empty jar list.
4. If two `[classpaths.<name>]` tables share the same `<name>`, the kolt config parser shall reject the configuration (TOML duplicate-table semantics).
5. The kolt config parser shall accept the same `"group:artifact"` GAV in `[dependencies]`, `[test-dependencies]`, and one or more `[classpaths.<name>]` tables independently of one another, even when versions differ.

### Requirement 2: JVM sys_props declaration

**Objective:** As a kolt user, I want to declare `-D<key>=<value>` system properties for the JVM that `kolt test` and `kolt run` spawn, so that test fixtures and applications can read project-derived configuration without machine-specific authoring.

#### Acceptance Criteria

1. The kolt config parser shall accept three value shapes for entries inside `[test.sys_props]` and `[run.sys_props]`, all of which are inline TOML tables with exactly one field set:
    - `{ literal = "<value>" }` for a verbatim literal string,
    - `{ classpath = "<bundle-name>" }` referencing a `[classpaths.<bundle-name>]` declaration,
    - `{ project_dir = "<relative-path>" }` denoting a project-root-relative directory.

    Note: this uniform inline-table shape was selected after probing ktoml 0.7.1's decoder support for "string OR inline table" polymorphism. The polymorphic shape would require reaching into ktoml's internal `TomlNode` types from a custom `KSerializer`, which is fragile and library-version-coupled. The uniform shape decodes via a straightforward `RawSysPropValue` data class (three nullable fields, validated to exactly-one-set) and preserves the three typed variants at the public layer.
2. If a value in `[test.sys_props]` or `[run.sys_props]` does not match one of the three shapes above, the kolt config parser shall reject the configuration with a message naming the offending key.
3. If a `{ classpath = "<bundle-name>" }` value names a bundle not declared as `[classpaths.<bundle-name>]`, the kolt config parser shall reject the configuration with a message naming the missing bundle.
4. If a `{ project_dir = "<relative-path>" }` value uses an absolute path, the kolt config parser shall reject the configuration.
5. If a `{ project_dir = "<relative-path>" }` value contains `..` segments that resolve outside the project root, the kolt config parser shall reject the configuration.
6. The kolt config parser shall accept `[test.sys_props]` and `[run.sys_props]` containing zero entries (empty table) without error.
7. The kolt config parser shall not perform environment-variable expansion on any value within `[test.sys_props]` or `[run.sys_props]`; literal strings are passed through verbatim.

### Requirement 3: Sysprop runtime delivery

**Objective:** As a kolt user, I want declared sys_props to actually arrive at the spawned JVM, so that the values I authored in `kolt.toml` reach `System.getProperty(...)` at test/run time.

#### Acceptance Criteria

1. When `kolt test` spawns the test JVM, the kolt test runner shall append `-D<key>=<resolved-value>` for every entry in `[test.sys_props]` to the JVM invocation.
2. When `kolt run` spawns the application JVM, the kolt run runner shall append `-D<key>=<resolved-value>` for every entry in `[run.sys_props]`.
3. For `{ classpath = "<bundle>" }` values, the resolved-value shall be the colon-joined sequence of absolute paths to every jar resolved for `<bundle>` (same path semantics as `kolt deps install` writes to the local cache), in declaration order.
4. For `{ project_dir = "<rel>" }` values, the resolved-value shall be the absolute filesystem path obtained by joining the project root (the directory containing `kolt.toml`) with `<rel>`.
5. For literal-string values, the resolved-value shall be the verbatim string.
6. The kolt test runner and kolt run runner shall not introduce any built-in sysprop with a key that overlaps a user-declared sys_prop.
7. The kolt test runner shall pass declared sys_props in addition to (not in place of) its pre-existing JVM argument set.

### Requirement 4: Lockfile and deps-tooling integration

**Objective:** As a kolt user, I want classpath bundles to behave as first-class declared dependencies, so that I can audit, reproduce, and update them with the same workflows I already use for `[dependencies]`.

#### Acceptance Criteria

1. When `kolt deps install` resolves dependencies, the kolt resolver shall resolve every `[classpaths.<name>]` entry and persist its locked GAV+SHA into `kolt.lock` such that subsequent runs produce a deterministic jar set.
2. When `kolt deps tree` is invoked, the kolt deps command shall list every declared bundle and its transitive resolution under a labelled section distinct from `[dependencies]` and `[test-dependencies]`.
3. When `kolt deps update` is invoked, the kolt resolver shall update entries inside `[classpaths.<name>]` declarations using the same policy it applies to `[dependencies]` and `[test-dependencies]`.
4. When any `[classpaths.<name>]` declaration changes between runs, the kolt build shall re-resolve that bundle and refresh the corresponding `kolt.lock` entry.
5. The kolt resolver shall isolate each bundle's transitive closure: a transitive coming through `[classpaths.A]` shall not silently appear in `[classpaths.B]`, in `[dependencies]`, or in `[test-dependencies]` unless explicitly declared there.

### Requirement 5: Target and kind compatibility

**Objective:** As a kolt user, I want the parser to fail loudly when the new schema appears on a project that cannot consume it, so that I do not silently ship configuration that has no effect.

#### Acceptance Criteria

1. If `[build] target` is a native target (any value in `NATIVE_TARGETS`) and any of `[classpaths.<name>]`, `[test.sys_props]`, or `[run.sys_props]` contains at least one entry, the kolt config parser shall reject the configuration with a message naming the offending table.
2. If `[build] kind = "lib"` and `[run.sys_props]` contains at least one entry, the kolt config parser shall reject the configuration (a library has no `kolt run` target to receive sys_props).
3. The kolt config parser shall accept `[test.sys_props]` regardless of `kind` (libraries still run tests).
4. The kolt config parser shall accept `[classpaths.<name>]` regardless of `kind`, provided `target` is JVM, since libraries may legitimately declare bundles consumed by `[test.sys_props]`.

### Requirement 6: Environment-agnostic principle and follow-up tracking

**Objective:** As a kolt maintainer, I want the env-agnostic decision recorded as an ADR with explicit follow-up tracking, so that future contributors do not relitigate the choice and concrete env-specific needs have a known landing zone.

#### Acceptance Criteria

1. The kolt project shall publish a new ADR under `docs/adr/` that records the env-agnostic principle for `kolt.toml`.
2. The ADR shall state that `${env.X}` style interpolation is explicitly rejected for `kolt.toml` values, with rationale referencing the project-shared / commit-tracked nature of the file.
3. The ADR shall name a CLI `-D<key>=<value>` flag for `kolt test` / `kolt run` and a per-user override file as the two designated future answers for environment-specific values, each linked to a tracking issue.
4. The kolt project shall open one tracking issue for the CLI `-D<key>=<value>` flag and one tracking issue for the per-user override file, and the ADR shall reference both by issue number.

### Requirement 7: Backward compatibility for unaffected projects

**Objective:** As an existing kolt user with no need for the new schema, I want the upgrade to be silent, so that my project keeps building unchanged.

#### Acceptance Criteria

1. The kolt config parser shall accept any pre-existing `kolt.toml` that contains none of `[classpaths.<name>]`, `[test.sys_props]`, or `[run.sys_props]` without behavior change.
2. When the new tables are absent, the kolt test runner shall spawn the JUnit Console Launcher with exactly the same argument set as before this feature.
3. When the new tables are absent, the kolt run runner shall spawn the application JVM with exactly the same argument set as before this feature.
