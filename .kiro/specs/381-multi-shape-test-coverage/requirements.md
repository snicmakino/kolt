# Requirements Document

## Introduction

PR #376 は `kolt test` (JVM) を JVM compile daemon 経由に変更したが、 その回帰カバレッジは `kolt-jvm-compiler-daemon` 自身を build/test する self-host smoke (`BtaIncrementalCorruptionSmokeTest` ほか) のみである。 当該プロジェクトは serialization plugin あり、 多数の依存、 大規模テスト集合という一つの shape に偏った特徴を持つため、 別 shape (plugin なし、 依存なし) でしか発火しない regression は見落とされる。

本 spec は kolt の native integration test 集合に「複数の project shape を scaffold して `kolt build && kolt test` を daemon 経由で実走させ、 daemon 側 IC state segment と build artifact survival を assert する」テスト群を追加することで、 #376 の回帰防御層を多様化する。 production code は変更しない。

## Boundary Context

- **In scope**:
  - JVM プロジェクトの 2 種類の shape (plugin なし最小構成、 serialization plugin あり) に対する end-to-end regression test の追加
  - `kolt build` と `kolt test` を daemon 経由で実走させ、 daemon-side IC state の `main/` / `test/` segment が独立して populate されることの assertion
  - `kolt build` で生成した main の `.class` artifact が、 続く `kolt test` の test compile 過程で削除されないことの assertion
  - 親 kolt の `kolt test` の中で false-RED にならないように、 既存の bootstrap-gated test pattern (`KOLT_DAEMON_JAR` env override) に従った gating
- **Out of scope**:
  - Multi-module shape のカバレッジ (#322 系の独立スコープ)
  - "Scripts only / no main code" shape (DoD 外、 必要なら別 issue)
  - Production code の変更 (test code only)
  - 既存の `BtaIncrementalCorruptionSmokeTest` 等の置き換えや構造変更
  - Native daemon (`kolt-native-compiler-daemon`) のカバレッジ拡張
- **Adjacent expectations**:
  - JVM compile daemon の挙動 (IC state layout、 wire protocol) は既存実装をそのまま観察対象とする。 daemon 側に hook を追加することは想定しない
  - `KOLT_DAEMON_JAR` env による daemon 差し替え機構は既存実装に依存する (本 spec で新規導入しない)
  - `~/.kolt/daemon/ic/<kotlinVersion>/<projectId>/<scope>/` の path 規約は `IcStateLayout` の現行定義に従う

## Requirements

### Requirement 1: マルチシェイプ regression coverage

**Objective:** kolt のメンテナーとして、 `kolt test` の daemon 経路を複数の代表的な project shape に対して回帰テストできるようにしたい。 これにより、 一つの自社 shape に固有な特徴 (plugin、 依存、 テスト規模) でしか拾えない regression が見落とされることを防げる。

#### Acceptance Criteria
1. The multi-shape daemon test suite shall include a fixture for a JVM プロジェクト with plugin なし最小構成 (`kolt.toml` に `[plugins]` セクションを持たず、 `src/main/kotlin/` に 1 ファイル、 `src/test/kotlin/` に JUnit 5 テスト 1 ファイル).
2. The multi-shape daemon test suite shall include a fixture for a JVM プロジェクト with kotlinx.serialization plugin (`kolt.toml` に serialization plugin 宣言、 `@Serializable` を使う main コードと、 シリアライズ往復を verify する test コードを含む).
3. When the multi-shape daemon test suite runs against either fixture, the suite shall execute `kolt build` followed by `kolt test` via the same JVM compile daemon code path that production `kolt test` uses.
4. The multi-shape daemon test suite shall scaffold each fixture into an isolated temporary directory so that fixtures do not share state across tests or with the host kolt project.

### Requirement 2: Daemon-side IC state segment assertion

**Objective:** kolt のメンテナーとして、 daemon が main scope と test scope の IC state を分離して populate していることを各 shape で検証したい。 これにより、 #376 で修正した「main と test が同じ workingDir を共有して BTA `inputsCache` が cross-contaminate する」回帰の再発を捕捉できる。

#### Acceptance Criteria
1. When the multi-shape daemon test suite has executed `kolt build` followed by `kolt test` against a fixture, the suite shall assert that `~/.kolt/daemon/ic/<kotlinVersion>/<projectId>/main/bta/` exists and is non-empty.
2. When the multi-shape daemon test suite has executed `kolt build` followed by `kolt test` against a fixture, the suite shall assert that `~/.kolt/daemon/ic/<kotlinVersion>/<projectId>/test/bta/` exists and is non-empty.
3. The multi-shape daemon test suite shall verify that the `main/` and `test/` segments are sibling directories under the same `<projectId>` (同一プロジェクトに対して main と test の `<projectId>` 部分が一致する).
4. If either segment is missing or empty after the test execution, the suite shall fail with a message identifying which segment is missing and for which fixture.

### Requirement 3: Build artifact survival across compile scopes

**Objective:** kolt のメンテナーとして、 `kolt build` で生成された main の `.class` artifact が、 続く `kolt test` の test compile 過程で削除されないことを各 shape で検証したい。 これは #376 が直接修正した不具合の再発を捕捉するための user-observable な assertion である。

#### Acceptance Criteria
1. When the multi-shape daemon test suite has executed `kolt build` against a fixture, the suite shall record the set of `.class` files that exist under the fixture's main output directory.
2. When the multi-shape daemon test suite has subsequently executed `kolt test` against the same fixture, the suite shall assert that every `.class` file recorded after `kolt build` still exists at the same path.
3. If any recorded main `.class` file is missing after `kolt test` completes, the suite shall fail with a message identifying the deleted artifact path and the fixture name.

### Requirement 4: Bootstrap-gated execution

**Objective:** kolt のメンテナーとして、 これらの integration test が parent kolt の通常の `kolt test` の中で false-RED にならず、 意図したときだけ走るように env-gate したい。 既存の `KOLT_DAEMON_JAR` を使う bootstrap-gated test pattern と一貫させる。

#### Acceptance Criteria
1. While `KOLT_DAEMON_JAR` env var (or 既存の integration-test gate flag) is unset, the multi-shape daemon test suite shall skip without failing.
2. Where the gate env flag is set, the multi-shape daemon test suite shall execute against all included fixtures in sequence.
3. When the suite runs, the suite shall use the `KOLT_DAEMON_JAR` でポイントされた thin jar as the JVM daemon backend, not whatever daemon the host kolt installation would resolve.
4. If the gate env flag is set but `KOLT_DAEMON_JAR` is unset or points to a non-existent path, the suite shall fail with an error message that names the missing/invalid env var rather than silently skipping or producing a misleading downstream failure.

### Requirement 5: Default `kolt test` 走行への影響回避

**Objective:** kolt のメンテナーとして、 各 shape あたり実 1 分前後かかるこの integration suite が、 default `kolt test` (gate 無し) の wall time を増やさないようにしたい。

#### Acceptance Criteria
1. When `kolt test` is invoked without the gate env flag, the multi-shape daemon test suite shall not perform any `kolt build` or `kolt test` subprocess invocation against fixtures.
2. The multi-shape daemon test suite shall surface a single skip notice (例: stderr に 1 行) when skipped due to gate flag absence, so that operator が「テストが silently 消えた」と誤解しないようにする.
