# Implementation Plan

- [x] 1. Foundation: IT skeleton with gate and skip notice
- [x] 1.1 Establish gated IT class with silent-skip and invalid-path failure
  - 新規ファイルを `src/nativeTest/kotlin/kolt/cli/` 配下に作成し、 IT class と一つの no-op `@Test` (gate check のみを呼ぶ) を置く
  - File-private gate helper を実装: `KOLT_DAEMON_JAR` env が unset なら 1 度だけ stderr に skip notice を出して early return、 set されているが指す path が存在しなければ `fail(...)` で明示メッセージ付き失敗
  - Top-level `const val FIXTURE_KOTLIN_VERSION = "2.3.20"` を file 先頭に置き、 後続タスクで fixture toml と IC path 計算が共通参照する
  - Observable: `KOLT_DAEMON_JAR` 未設定で parent `kolt test` を回したとき、 当該 IT class が discovered されつつ stderr に 1 行の skip notice が出力され、 一切の subprocess / fixture I/O が起きない。 `KOLT_DAEMON_JAR=/nonexistent/path` を指定したときは IT 失敗メッセージに env var 名と invalid path が両方含まれる
  - _Requirements: 4.1, 4.4, 5.1, 5.2_

- [x] 2. Core: per-shape end-to-end coverage
- [x] 2.1 Plugin なし shape の end-to-end test と共通 helper 一式
  - File-private helpers を IT 内に追加: `scaffoldNoPluginFixture`、 `runKoltBuildAndTest`、 `snapshotMainClassFiles`、 `assertMainClassesSurvive`、 `assertIcSegmentsPopulated`。 IC path 計算は `daemonIcProjectIdOf` を呼ぶか visibility が `private/internal` で見えなければ同算式を IT 内に再実装する
  - `scaffoldNoPluginFixture` は `mkdtemp` で温度 dir を作り、 `[kotlin] version`、 `[build] target = "jvm"` のみの `kolt.toml` と `src/main/kotlin/Main.kt` + `src/test/kotlin/MainTest.kt` (kolt.test の最小ケース) を書き出す
  - `runKoltBuildAndTest` は `executeCommand` で `bash -c "cd <fixture> && <kolt.kexe> build && <kolt.kexe> test"` を実行、 `KOLT_DAEMON_JAR` を child env に明示注入、 exit code を返す
  - `assertIcSegmentsPopulated` は `~/.kolt/daemon/ic/<FIXTURE_KOTLIN_VERSION>/<projectId>/main/bta/` と `.../test/bta/` の 2 dir が exist かつ entry を持つことを確認、 失敗時 message に欠損 segment 名と fixture 名を含める。 同じ `<projectId>` の sibling であることも確認
  - `snapshotMainClassFiles` は `<fixtureDir>/build/classes/` を再帰列挙して `.class` の abs path 集合を返す。 `assertMainClassesSurvive` は集合の各 path が `stat` で file として残存していることを確認、 欠損時 message に削除された path と fixture 名を含める
  - `@Test fun runs daemon-routed test on JVM project without plugins()` を追加: gate → scaffold → `kolt build` → snapshot → `kolt test` → exit 0 assert → IC segments assert → class survival assert
  - Observable: `KOLT_DAEMON_JAR` 設定下で parent `kolt test` を回したとき、 当該 `@Test` が緑になり、 IC layout 配下に `<projectId>/{main,test}/bta/` が物理生成され、 fixture 内 `build/classes/**/*.class` が `kolt build` 直後と `kolt test` 通過後で同一集合
  - _Requirements: 1.1, 1.3, 1.4, 2.1, 2.2, 2.3, 2.4, 3.1, 3.2, 3.3, 4.2, 4.3_

- [x] 2.2 Serialization plugin shape の end-to-end test
  - `scaffoldSerializationFixture` を IT 内に追加: `[kotlin] version` + `[kotlin.plugins] serialization = true` を持つ `kolt.toml`、 `src/main/kotlin/Payload.kt` に `@Serializable data class Payload(...)`、 `src/test/kotlin/PayloadTest.kt` に `Json.encodeToString` / `decodeFromString` の往復 assertion を 1 ケース置く。 `kotlinx-serialization-json` runtime dependency が必要なら `[dependencies]` に追加 (impl 時に最小再現で確定)
  - `@Test fun runs daemon-routed test on JVM project with serialization plugin()` を追加: gate → `scaffoldSerializationFixture` → 2.1 で追加した共通 helper (`runKoltBuildAndTest`, `snapshotMainClassFiles`, `assertMainClassesSurvive`, `assertIcSegmentsPopulated`) を呼んで同じ assertion 列を回す
  - Observable: `KOLT_DAEMON_JAR` 設定下で parent `kolt test` を回したとき、 当該 `@Test` が緑になり、 fixture の `Payload.class` が `$serializer` を含む状態で生成され、 build → test 通過後も artifact が残る
  - _Requirements: 1.2, 1.3, 1.4, 2.1, 2.2, 2.3, 2.4, 3.1, 3.2, 3.3, 4.2, 4.3_

- [x] 3. Validation
- [x] 3.1 多モード手動実走と boundary 検査
  - Parent `kolt test` を 3 モードで実走させ各挙動を確認: (a) `KOLT_DAEMON_JAR` 未設定 → IT 2 件 silent skip + stderr に 1 行 notice、 (b) `KOLT_DAEMON_JAR=<repo>/kolt-jvm-compiler-daemon/build/kolt-jvm-compiler-daemon.jar` (dev thin jar) → IT 2 件 pass、 (c) `KOLT_DAEMON_JAR=/nonexistent/path` → IT 失敗メッセージに env var 名と invalid path が含まれる
  - 当該 IT ファイルの import 文を grep し、 production import が `kolt.infra.executeCommand`、 `kolt.infra.sha256Hex`、 `kolt.build.daemon.daemonIcProjectIdOf` (借用が成立した場合)、 POSIX cinterop に限定されていることを確認
  - 借用 helper (`locateKoltKexe`、 `currentWorkingDir`、 `printOnceSkipNotice` 等) が `JvmTestSysPropIT` 内で `private` だった場合、 本 IT 内に同等関数として再実装が完了しており、 import で参照していないことを確認
  - Observable: 3 モード実走の各結果が expected と一致する記録 (test output snippet) と、 import grep の結果リストが手元に揃う
  - _Requirements: 4.1, 4.2, 4.4, 5.1, 5.2_

## Implementation Notes

- Dev daemon thin jar is at `kolt-jvm-compiler-daemon/build/debug/kolt-jvm-compiler-daemon.jar` (Cargo-style profile dir), not directly under `build/`. Used by task 3.1 mode (b) and any developer running the IT locally.
- `kolt.build.daemon.daemonIcProjectIdOf` and `KoltPaths` are `internal` to their packages. Reimplemented locally in the IT (`expectedProjectId` + `$HOME/.kolt/daemon/ic`) per design Implementation Notes risk. If production algorithm changes, IT will fail loud (the segments will not be found at the expected path).
- `JvmTestSysPropIT.locateKoltKexe()` is `private`; reimplemented locally in the IT.
- `runKoltCommand` was split from the design's `runKoltBuildAndTest` because Req 3.1 requires a `.class` snapshot between `kolt build` and `kolt test`. Design name was inconsistent with its own Req mapping.
- Serialization fixture needs `kotlinx-serialization-json:1.7.3` runtime dep in `[dependencies]`; the plugin enable alone does not bring `kotlinx.serialization.json.Json` onto the test classpath. Determined empirically per design Risks.
- The two `@Test` bodies are ~33-line near-duplicates differing only in test name + scaffold call + fixture name. Intentional per design "file-private helper + 2 `@Test` methods" pattern. Do NOT introduce a parameterizing abstraction.
