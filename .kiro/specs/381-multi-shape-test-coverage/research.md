# Research & Design Decisions

## Summary
- **Feature**: `381-multi-shape-test-coverage`
- **Discovery Scope**: Extension (test code only; integrate with existing IT scaffolding)
- **Key Findings**:
  - 既存 `JvmTestSysPropIT.kt` の `createFixtureProject` + `executeCommand` パターンが scaffold + subprocess 実走の rolemodel として完全に再利用可能
  - Daemon-side IC layout は native 側にも `daemonIcProjectIdOf` (`IcStateCleanup.kt`) として復元式があり、 native test から直接 expected path を計算できる
  - `BtaSerializationPluginTest` の fixture (kolt.toml に `[kotlin.plugins] serialization = true`、 `@Serializable` data class) が serialization shape のテンプレートとして借用できる

## Research Log

### 既存 integration test の scaffolding パターン
- **Context**: 新規 IT を追加するにあたり、 既存 IT の構築・ subprocess 実走・ gate 機構を再利用可能か確認する
- **Sources Consulted**:
  - `src/nativeTest/kotlin/kolt/cli/JvmTestSysPropIT.kt`
  - `src/nativeMain/kotlin/kolt/infra/Process.kt`
- **Findings**:
  - `createFixtureProject(prefix)` は `mkdtemp` で temp dir を作り `kolt.toml` + `src/Main.kt` + `test/Test.kt` を書き出す。 各 fixture は独立 dir
  - `executeCommand(args, extraEnv)` は POSIX `fork+execvp+waitpid` を wrap。 `extraEnv` で env 注入可能。 stdout/stderr は bash harness 経由でファイルにキャプチャ
  - `locateKoltKexe()` は `currentWorkingDir() + build/debug/kolt.kexe` (なければ release) を返す
  - Gate: `getenv("KOLT_INTEGRATION") == "1"` のときのみ実行、 それ以外は早期 return + stderr に 1 回だけ skip notice
- **Implications**:
  - 本 spec も同パターンを踏襲。 専用 helper を `kolt.cli.testfixture` か同等の場所に置く必要なく、 IT 内に閉じ込めて良い (1 file)

### Daemon-side IC state の native 側からの観察
- **Context**: 各 fixture の build/test 後、 `~/.kolt/daemon/ic/<v>/<projectId>/{main,test}/bta/` segment が populate されたかを native test から assert する必要がある
- **Sources Consulted**:
  - `kolt-jvm-compiler-daemon/ic/src/main/kotlin/kolt/daemon/ic/IcStateLayout.kt` (JVM 側 path 規約: `<icRoot>/<kotlinVersion>/<sha256(projectRoot).take16bytes=32hex>/<scope>`)
  - `src/nativeMain/kotlin/kolt/build/daemon/IcStateCleanup.kt` (native 側に `daemonIcProjectIdOf(absProjectPath: String): String = sha256Hex(...).take(32)` が既に存在)
  - `src/nativeMain/kotlin/kolt/infra/Sha256.kt` (`sha256Hex(bytes: ByteArray): String`)
- **Findings**:
  - JVM 側の `IcStateLayout.projectIdFor(projectRoot)` は `sha256(projectRoot.toString()).take(16 bytes).toHex()` = 32 hex chars
  - Native 側 `daemonIcProjectIdOf(absProjectPath)` は同じ算式 (32 hex chars take)
  - `~/.kolt/daemon/ic` の literal は `kolt.cli.KoltPaths` または `kolt.infra` 系から取得可能 (要確認、 既存 `IcStateCleanup` がどう取っているかを参照)
- **Implications**:
  - Native test は `daemonIcProjectIdOf(fixtureDirAbs)` を直接呼んで expected `<projectId>` を組み立てる
  - Kotlin version は fixture の `kolt.toml` で hard-pin した値 (例 `2.3.20`) を test code で同じ literal として使う

### Daemon jar override (KOLT_DAEMON_JAR) の挙動
- **Context**: テストが parent kolt の daemon resolution を bypass し、 deterministic な jar を使うために env override の semantics を確認する
- **Sources Consulted**:
  - `src/nativeMain/kotlin/kolt/build/daemon/DaemonJarResolver.kt`
  - Memory: `reference_kolt_daemon_jar_env`、 `feedback_bootstrap_gated_test`
- **Findings**:
  - `DaemonJarResolver.resolveDaemonJarPure()` は env (`KOLT_DAEMON_JAR`) を最優先 probe。 set されていれば libexec / dev fallback を skip
  - Env は jar path を指定するだけで daemon を起動はしない。 kolt 側が解決後の classpath で spawn する
  - `KOLT_DAEMON_JAR` は path 指定であり gate flag ではない。 ただし本 spec の文脈では「set されていれば integration test 実行を許可、 unset なら skip」と扱う (issue body の "env-gated KOLT_DAEMON_JAR override" の解釈)
- **Implications**:
  - Test 側 gate condition は `getenv("KOLT_DAEMON_JAR") != null && jar が実在する` の AND 条件
  - 環境変数は subprocess (`kolt build && kolt test`) にも `extraEnv` で伝搬する (parent test process の env を inherit するので明示的に追加しなくても良いが、 念のため明示する)

### 既存の serialization plugin fixture
- **Context**: serialization shape の fixture 構築を、 既存 daemon ic test で使われている形に合わせるか確認
- **Sources Consulted**:
  - `kolt-jvm-compiler-daemon/ic/src/test/kotlin/kolt/daemon/ic/BtaSerializationPluginTest.kt`
- **Findings**:
  - `kolt.toml` minimal: `[kotlin] version = "2.3.20"` + `[kotlin.plugins] serialization = true`
  - Source: 単一 `Payload.kt` に `@Serializable data class Payload(val id: Int, val name: String)`
  - Test 側 assertion は `$serializer` symbol が .class に含まれることだが、 本 spec では subprocess の終了 code 0 + IC segment 存在で済む
- **Implications**:
  - Native IT 側の serialization fixture も同様 minimal で OK。 main コードに `@Serializable` を含む 1 ファイル、 test コードに `Json.encodeToString` の往復 assertion 1 ケースで足りる

### Build artifact output path
- **Context**: `build/classes/` survival assertion を行うため、 JVM build の `.class` 出力先を確定する
- **Sources Consulted**:
  - `src/nativeMain/kotlin/kolt/build/Builder.kt` (`BUILD_DIR = "build"`、 `CLASSES_DIR = "build/classes"`、 `TEST_CLASSES_DIR = "build/test-classes"`)
  - `kolt.build.Profile`
- **Findings**:
  - JVM build は profile に依らず `build/classes/` (main) と `build/test-classes/` (test) に出力
  - Native build と異なり `build/<profile>/` segregation は適用されない
- **Implications**:
  - Survival assertion の対象は fixture root 直下 `build/classes/**/*.class` の集合

## Design Decisions

### Decision: 単一 IT ファイルに 2 shape の test を並べる (parameterize しない)
- **Context**: 2 shape (no-plugin、 serialization plugin) のテストをどう構造化するか
- **Alternatives Considered**:
  1. `MultiShapeDaemonTestCoverageIT.kt` 1 ファイル + `@Test` 2 個 + 共通 helper 関数
  2. Parameterized test (kotlin.test には JUnit5 相当の `@ParameterizedTest` がない、 自作 dispatcher が必要)
  3. Shape ごとに別ファイル
- **Selected Approach**: (1) — 1 ファイル + 2 `@Test` + private helper
- **Rationale**: shape 数が 2 で、 fixture content だけが違う。 helper を切れば test コードは 5 行程度に収まる。 parameterize は overengineering、 別ファイル化は近接性が下がる
- **Trade-offs**: shape 追加時に `@Test` を 1 個増やすだけで済む反面、 dynamic shape registry はない (将来 shape が 5 個超えたら見直す)
- **Follow-up**: なし

### Decision: Gate 条件は `KOLT_DAEMON_JAR` 単独
- **Context**: `KOLT_INTEGRATION` (既存)、 `KOLT_DAEMON_JAR`、 あるいは新規 dedicated env のどれを gate にするか
- **Alternatives Considered**:
  1. `KOLT_DAEMON_JAR` 単独 (set されていれば実行)
  2. `KOLT_INTEGRATION=1` AND `KOLT_DAEMON_JAR` set
  3. 専用 env (`KOLT_DAEMON_TEST_COVERAGE=1`)
- **Selected Approach**: (1) `KOLT_DAEMON_JAR` 単独
- **Rationale**:
  - Issue body の "env-gated KOLT_DAEMON_JAR override per the bootstrap-gated test pattern" を素直に解釈
  - これらの test は daemon override がない限り意味を持たない (素の daemon resolution だと parent kolt 実装に依存して deterministic でない)
  - 既存 memory (`feedback_bootstrap_gated_test`) の "env gate で false-RED を回避" にも合致
- **Trade-offs**: `KOLT_INTEGRATION` の慣習から外れる。 ただし当該 IT は性質上 daemon jar override を必須とするので、 gate 条件を一致させる方が運用上明快
- **Follow-up**: 本 IT の README / コメントに gate condition を明記

### Decision: Helper を file-private で IT 内に置く (testfixture/ には出さない)
- **Context**: 共通 scaffold + assert helper を `testfixture/` package に出して再利用性を上げるか
- **Alternatives Considered**:
  1. IT file 内 file-private 関数
  2. `kolt.cli.testfixture.MultiShapeDaemonFixture` として外出し
- **Selected Approach**: (1) file-private
- **Rationale**: 現状 caller は本 IT 1 件のみ。 別 spec が同形を欲しがった時点で外出しを検討
- **Trade-offs**: 将来別 IT で再利用したくなった場合 refactor 必要。 ただしそれまで out
- **Follow-up**: なし

### Decision: Tests are skipped silently when KOLT_DAEMON_JAR is unset (no notice)
- **Context**: `JvmTestSysPropIT` は skip 時 stderr に 1 行 notice を出す。 同パターンを踏襲するか
- **Alternatives Considered**:
  1. Silent skip
  2. Stderr に 1 回だけ notice
- **Selected Approach**: (2) — `JvmTestSysPropIT` と同じく `printOnceSkipNotice` 相当を踏襲
- **Rationale**: Operator が「テストが消えた」と誤解しないため (Req 5.2)
- **Trade-offs**: `kolt test` 出力に 1 行追加。 既存 IT も同様なので許容
- **Follow-up**: なし

## Risks & Mitigations
- **Risk**: 並列 test 実行時に共有 daemon socket への競合が起きる
  - Mitigation: Native test は kotlin.test のデフォルト sequential 実行。 fixture ごとに projectId が異なる (温度 dir → 異なる sha256) ので IC state は分離。 socket 競合は daemon 側のロックで吸収される
- **Risk**: Test 実行時間が CI の budget を超える (各 1 分 × 2 = 2 分超)
  - Mitigation: Gate (`KOLT_DAEMON_JAR`) で default `kolt test` から除外。 CI 側で意図的に opt-in した job だけが回す
- **Risk**: Fixture 内 `kolt.toml` の Kotlin version が実装と drift
  - Mitigation: Test 内に literal `KOTLIN_VERSION_FIXTURE = "2.3.20"` を 1 か所だけ置き、 fixture toml と IC path 計算で共通参照
- **Risk**: Daemon が build と test の間に再起動される (e.g., daemon stale auto-restart) と build/classes survival 仮説が崩れる
  - Mitigation: Test は `kolt build && kolt test` を bash 1 行で実行。 daemon が間に死ぬ規模の障害が起きたらそれ自体がテスト失敗 (#379 の対象範囲) で扱う

## References
- `kolt-jvm-compiler-daemon/ic/src/main/kotlin/kolt/daemon/ic/IcStateLayout.kt` — JVM-side IC path 算式
- `src/nativeMain/kotlin/kolt/build/daemon/IcStateCleanup.kt` — native-side projectId 算式 `daemonIcProjectIdOf`
- `src/nativeTest/kotlin/kolt/cli/JvmTestSysPropIT.kt` — IT scaffolding rolemodel
- `kolt-jvm-compiler-daemon/ic/src/test/kotlin/kolt/daemon/ic/BtaSerializationPluginTest.kt` — serialization fixture template
- `src/nativeMain/kotlin/kolt/infra/Process.kt` — `executeCommand` API
- Memory: `reference_kolt_daemon_jar_env`, `feedback_bootstrap_gated_test`
- ADR 0019 (BTA), ADR 0016 (warm daemon), ADR 0030 (build profiles)
