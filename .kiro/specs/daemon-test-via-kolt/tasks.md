# Implementation Plan

- [x] 1. Pre-flight: filter passthrough 動作確認 (結果: 動作不能 → R1.2 を #323 へ deferred)
- [x] 1.1 JUnit Console Launcher の filter passthrough を smoke
  - 実施: `/tmp/filter-smoke/` に簡易 JVM kolt project (Alpha/Beta 2 test class) を作成、 `kolt test -- --select-class=filtersmoke.AlphaTest` を実行
  - 結果: JUnit Platform Console Launcher 1.11.4 が `Scanning the classpath and using explicit selectors at the same time is not supported` で reject
  - `testRunCommand` の `--scan-class-path` 固定挿入を抑止する fix は kolt CLI 側の change で本 spec の Out of Boundary、 follow-up issue #323 に分離
  - R1.2 を deferred 扱い、 本 spec で R1.2 関連 task (5.3) は同様に deferred
  - dogfood log (`docs/dogfood.md`) に 1 行記録
  - _Requirements: 1.2_

- [ ] 2. JVM daemon kolt.toml 整備 (root + ic 統合)
- [x] 2.1 test_sources と test-dependencies を追加
  - `kolt-jvm-compiler-daemon/kolt.toml` の `[build]` に `test_sources = ["src/test/kotlin", "ic/src/test/kotlin"]` を追加
  - `[test-dependencies]` を新設し `org.junit.jupiter:junit-jupiter = "5.11.3"` と `org.junit.platform:junit-platform-launcher = "1.11.3"` を pin (Gradle 時代と同 version)
  - `kolt info` または config parse smoke で構文 error なく読み込める
  - 既存 `[build] sources = ["src/main/kotlin", "ic/src/main/kotlin"]` は touch しない
  - _Requirements: 1.1, 1.6, 6.2, 6.3_
  - _Boundary: kolt-jvm-compiler-daemon/kolt.toml_

- [x] 2.2 4 classpath bundle を declare
  - `[classpaths.bta_impl]` に `org.jetbrains.kotlin:kotlin-build-tools-impl = "2.3.20"` を追加
  - `[classpaths.fixture]` に `org.jetbrains.kotlin:kotlin-stdlib = "2.3.20"` を追加
  - `[classpaths.serialization_plugin]` に `org.jetbrains.kotlin:kotlin-serialization-compiler-plugin-embeddable = "2.3.20"` を追加
  - `[classpaths.serialization_runtime]` に `org.jetbrains.kotlin:kotlin-stdlib = "2.3.20"` と `org.jetbrains.kotlinx:kotlinx-serialization-core-jvm = "1.7.3"` を追加
  - 観察可能: `kolt info` で 4 bundle が一覧表示され、 parse error なし
  - _Requirements: 2.1, 2.2, 2.3, 2.4_
  - _Boundary: kolt-jvm-compiler-daemon/kolt.toml_

- [x] 2.3 test sysprop 6 個を declare
  - `[test.sys_props]` に classpath ref 4 個を追加: `kolt.ic.btaImplClasspath` → `bta_impl`、 `kolt.ic.fixtureClasspath` → `fixture`、 `kolt.ic.serializationPluginClasspath` → `serialization_plugin`、 `kolt.ic.serializationRuntimeClasspath` → `serialization_runtime`
  - `kolt.daemon.coreMainSourceRoot` を `{ project_dir = "src/main/kotlin" }` で declare
  - `kolt.daemon.icTestSourceRoot` を `{ project_dir = "ic/src/test/kotlin" }` で declare
  - 観察可能: `kolt info` の test sysprop 一覧に 6 entry が表示され、 dotted key (`kolt.daemon.*` / `kolt.ic.*`) が分割されずそのまま preserve されていること
  - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 4.1, 4.2, 4.3, 6.5_
  - _Boundary: kolt-jvm-compiler-daemon/kolt.toml_

- [x] 2.4 lockfile 再生成と Maven transitive cross-check
  - `cd kolt-jvm-compiler-daemon && kolt deps install` を実行し `kolt.lock` を再生成
  - `kolt deps tree` の bundle セクション出力と、 Gradle 側の `./gradlew :kolt-jvm-compiler-daemon:dependencies` / `./gradlew :ic:dependencies` の transitive を side-by-side で比較
  - 差分があれば bundle 内に explicit pin (転居 transitive を直接書き出し) を追加して再 install、 一致を確認
  - 観察可能: `kolt.lock` が PR diff として review 可能、 4 bundle すべての direct + transitive jar が Gradle と同一集合
  - _Depends: 2.2_
  - _Requirements: 2.5, 2.6_
  - _Boundary: kolt-jvm-compiler-daemon/kolt.lock_

- [ ] 3. (P) Native daemon kolt.toml 整備
- [x] 3.1 (P) test_sources / test-deps 追加 + lockfile 再生成
  - `kolt-native-compiler-daemon/kolt.toml` の `[build]` に `test_sources = ["src/test/kotlin"]` を追加
  - `[test-dependencies]` で `org.junit.jupiter:junit-jupiter = "5.11.3"` / `org.junit.platform:junit-platform-launcher = "1.11.3"` を pin
  - `cd kolt-native-compiler-daemon && kolt deps install` で `kolt.lock` を再生成
  - 観察可能: `kolt info` で test 構成が表示され、 `kolt.lock` が PR diff
  - _Requirements: 1.3, 6.2, 6.3_
  - _Boundary: kolt-native-compiler-daemon/kolt.toml, kolt-native-compiler-daemon/kolt.lock_

- [ ] 4. Foundation fix + invariant test
- [x] 4.1 kolt CLI test compile に -module-name と -Xfriend-paths を forward
  - `src/nativeMain/kotlin/kolt/build/TestBuilder.kt` の `testBuildCommand` argv に `-module-name <config.name>` と `-Xfriend-paths=<classesDir>` を追加
  - `src/nativeMain/kotlin/kolt/build/SubprocessCompilerBackend.kt` の `subprocessArgv` で `request.moduleName` を `-module-name <moduleName>` として forward、 line 29 の "intentionally not forwarded" コメントは削除 / 更新
  - `src/nativeTest/kotlin/kolt/build/` に native unit test を追加 (`testBuildCommand` の argv に新 flag が含まれること、 `subprocessArgv` の argv に新 flag が含まれることを assert)
  - 修正後 `kolt build` で kolt 自身を再 build し、 `cd kolt-jvm-compiler-daemon && build/debug/kolt.kexe test` で daemon test の compile が `internal in file` error なく成功することを smoke (full pass までは task 5.1 の責務、 ここでは compile error 解消の確認のみ)
  - 観察可能: native unit test 追加分が `kolt test` で discovered + green、 daemon test compile が `internal` access error 0 件で進行
  - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5_
  - _Boundary: src/nativeMain/kotlin/kolt/build/{TestBuilder,SubprocessCompilerBackend}.kt, src/nativeTest/kotlin/kolt/build/_

- [x] 4.2 IcModuleBoundaryInvariantTest を作成
  - `kolt-jvm-compiler-daemon/src/test/kotlin/kolt/daemon/IcModuleBoundaryInvariantTest.kt` を新設、 既存 `AdapterBoundaryInvariantTest` の `Files.walk(sourceRoot)` パターンを踏襲
  - sysprop `kolt.daemon.icTestSourceRoot` から source root を受け、 不在時は明示的な error message で fail
  - `import kolt.daemon.Main`、 `import kolt.daemon.server.`、 `import kolt.daemon.reaper.` のいずれかの prefix を含む行を violations として収集、 空でないなら fail message に違反 file / 行 / 行番号を列挙
  - 観察可能: `cd kolt-jvm-compiler-daemon && kolt test` で本 invariant test が discovered + green (現状 ic test 配下に違反なし)
  - _Depends: 2.3, 4.1_
  - _Requirements: 6.5_
  - _Boundary: IcModuleBoundaryInvariantTest_

- [ ] 5. End-to-end verification と Gradle parity cross-check
- [ ] 5.1 JVM daemon directory での kolt test smoke
  - `cd kolt-jvm-compiler-daemon && kolt test` を実行
  - root daemon test 群 (8 ファイル相当) と ic test 群 (16 ファイル相当) の union が全 pass、 exit code 0
  - test 失敗時の出力を確認 (意図的に 1 test を一時 fail させて exit code が non-zero になることも cross-check)
  - 観察可能: pass count が Gradle 時代の `./gradlew :kolt-jvm-compiler-daemon:check` + `./gradlew :ic:check` と一致 (count + class 名で confirm)
  - _Depends: 2.4, 4.1, 4.2_
  - _Requirements: 1.1, 1.4, 1.5, 1.6_

- [ ] 5.2 (P) Native daemon directory での kolt test smoke
  - `cd kolt-native-compiler-daemon && kolt test` を実行
  - 既存 native daemon test 群が全 pass、 exit code 0
  - 観察可能: pass count が `./gradlew :kolt-native-compiler-daemon:check` と一致
  - _Depends: 3.1_
  - _Requirements: 1.3, 1.4, 1.5_
  - _Boundary: kolt-native-compiler-daemon_

- [x] 5.3 ic 単体 test の filter 経由実行 smoke (deferred → #323)
  - 1.1 の Pre-flight Gate 結果により skip。 filter passthrough は kolt CLI 側 fix (#323) 完了まで利用不能
  - 本 spec では ic-only DX を一時的に放棄、 maintainer は `cd kolt-jvm-compiler-daemon && kolt test` で全 test を実行する
  - _Requirements: 1.2_

- [ ] 5.4 Gradle vs kolt verdict 一致 cross-check と test body 不変 verify
  - 同じ source tree で `./gradlew check` (orphan Gradle config 経由、 #316 lands 前) を実行し全 verdict を記録
  - `cd kolt-jvm-compiler-daemon && kolt test` と `cd kolt-native-compiler-daemon && kolt test` を実行し、 両者の pass / fail set が `./gradlew check` と一致することを confirm
  - `git diff` で `kolt-jvm-compiler-daemon/**/src/test/` と `kolt-native-compiler-daemon/src/test/` 配下の既存 file body に変更がないこと (新規 invariant test 1 ファイル追加のみ) を verify
  - 観察可能: `git status` で test 配下の modified が `IcModuleBoundaryInvariantTest.kt` 新規追加のみ、 verdict 集合が Gradle と一致
  - _Depends: 5.1, 5.2_
  - _Requirements: 6.1, 6.4_

- [ ] 6. Documentation cleanup
- [ ] 6.1 tech.md から ./gradlew check 言及を撤去
  - `.kiro/steering/tech.md` の `## Common Commands` 末尾にある Special-purpose ブロック (`./gradlew check` 行 + `./gradlew linkDebugExecutableLinuxX64 \\` 行 + 続く `&& ...kolt.kexe build` 行 + 周辺の "Special-purpose" 注釈) を削除
  - 削除後の `## Common Commands` セクションが文構造として整っている (見出し / 空行 / Development for working on kolt itself のブロック整合)
  - `grep -rn "gradlew check" .` を実行し、 historical record (`.kiro/specs/`、 `docs/adr/`、 `spike/`) と orphan Gradle config を除いた user / dev surface に該当が 0 件であることを confirm
  - 観察可能: tech.md の diff が Special-purpose ブロックの削除のみ、 surface grep が 0 件
  - _Depends: 5.4_
  - _Requirements: 5.1, 5.2, 5.3, 5.4_
  - _Boundary: .kiro/steering/tech.md_

## Implementation Notes

- 2026-05-01 task 1.1: filter passthrough は JUnit Console Launcher 1.11.4 の `--scan-class-path` mutex 制約により動作不能。 #323 (`testRunCommand` の conditional `--scan-class-path` 抑止) で別途対応。 R1.2 関連 task (5.3) は deferred として close、 本 spec の DoD は filter なしの全 test 実行で完結させる。
- 2026-05-01 task 2.1: `test_sources` を空から有効化したことで、 これまで kolt fmt の対象外だった test 配下の既存 file が ktfmt 0.54 と format 違反を持っていることが pre-commit hook で発覚。 26 ファイルを `kolt fmt` で apply して別 commit に分離。 task 3.1 (native daemon の test_sources 有効化) でも同じ現象が起きる可能性が高い、 同様に format apply を切り分ける。
- 2026-05-01 task 2.4: PATH の `kolt` (`/home/makino/.local/bin/kolt`) は古い v3 binary で、 `kolt deps install` を実行すると lockfile を v3 に書き戻す。 v4 lockfile を生成するには dev-built `build/debug/kolt.kexe` を直接呼ぶ必要があった (bootstrap pinch、 #316 で解消予定)。 また kolt と Gradle の transitive 表示には platform-suffix の有無 (`-jvm`)、 declared vs resolved version 等の cosmetic 差があるが、 on-disk jar 集合は一致 (bundle 単位で byte-identical を確認)。 `apiguardian-api:1.1.2` は kolt が POM scope に従って `compile` として取り込み、 Gradle module metadata では `compileOnlyApi` として除外、 これは harmless な annotation jar の追加で test 動作に影響なし。
- 2026-05-01 task 4.1 (旧): kolt の JVM test compile が `-module-name` を渡しておらず、 main と test が別 module として compile される結果、 test source set から main の `internal` symbol が見えない bug を発見。 `MainCliArgsTest`、 `SelfHealingIncrementalCompiler.METRIC_*`、 `CapturingKotlinLogger`、 `BtaIncrementalCompiler.METRIC_LOG_ERROR`、 `ClasspathSnapshotCache.METRIC_*` など多数の test が compile fail。 元 task 4.1 (invariant test) を 4.2 に renumber、 新規 task 4.1 として CLI fix を foundation 扱いで追加。 R7 を requirements に追加。 fix 規模: `TestBuilder.kt` と `SubprocessCompilerBackend.kt` の argv builder + native unit test。 line 29 の「moduleName intentionally not forwarded」 コメントは削除予定。
