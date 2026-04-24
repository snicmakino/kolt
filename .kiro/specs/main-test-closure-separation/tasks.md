# Implementation Plan

## 1. Foundation: data types, schema, 自動注入条件

- [x] 1.1 (P) Lockfile schema を v3 に bump し `test: Boolean` フラグを追加
  - `LockEntry` に `test: Boolean = false` を追加 (既存 `transitive: Boolean = false` と同形)
  - `LockEntryJson` に `@SerialName("test") val test: Boolean = false` を追加
  - `parseLockfile` を `version != 3` で `LockfileError.UnsupportedVersion` を返すよう変更 (v1 / v2 は reject)
  - `serializeLockfile` は `version = 3` を出力し、辞書順 + per-entry `test` フィールドを含める
  - `LockfileTest.kt` に v3 roundtrip テスト (`test: true` / `test: false` / 欠如 default) と v1 / v2 が `UnsupportedVersion` を返す回帰テストを追加
  - 完了条件: `LockfileTest.kt` の新旧テスト群が pass し、`.kolt.lock` 文字列に `"version": 3` と per-entry `"test": true` を持つサンプルがテストから確認できる
  - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5_
  - _Boundary: kolt.resolve.Lockfile_

- [x] 1.2 (P) `Origin` enum と resolver data class の origin フィールド
  - `kolt.resolve` に `enum class Origin { MAIN, TEST }` を新設
  - `ResolvedDep` に `origin: Origin = Origin.MAIN` を追加 (default は Native 経路 / 既存呼び出し互換用)
  - `DependencyNode` にも `origin: Origin` を追加
  - `ResolverTest.kt` 相当で `ResolvedDep(..., origin = Origin.TEST)` の copy/equals が期待通りであることを確認
  - 完了条件: `ResolvedDep` / `DependencyNode` が `origin` フィールドを持ち、既存 resolver テスト全量が回帰しない
  - _Requirements: 1.1, 2.1, 2.2_
  - _Boundary: kolt.resolve.Resolver, kolt.resolve.Resolution_

- [x] 1.3 (P) `autoInjectedTestDeps` を test ソース / test 依存が空のとき skip
  - `target == "jvm"` かつ (`testSources.isNotEmpty()` または `testDependencies.isNotEmpty()`) の時のみ `kotlin-test-junit5` を返すよう条件追加
  - `TestDepsTest.kt` に (a) `testSources = []` / `testDependencies = {}` 時に空 Map を返す、(b) 従来条件では継続 inject する、2 ケースを追加
  - 完了条件: `TestDepsTest.kt` の新旧ケースが pass、`autoInjectedTestDeps(config)` の呼び出しで daemon 相当の config (`test_sources = []`) に空 Map が返る
  - _Requirements: 1.4_
  - _Boundary: kolt.build.TestDeps_

## 2. Core: 2-seed resolver と caller orchestration

- [x] 2.1 `fixpointResolve` を 2-seed API に拡張し kernel で main wins on overlap を保証
  - signature を `fixpointResolve(mainSeeds, testSeeds = emptyMap(), childLookup)` に拡張
  - BFS queue entry と versions state に `OriginSet(fromMain, fromTest)` を保持し、child 展開時に親の origin を OR 継承
  - main / test 両方から到達した GA は materialize 時に `Origin.MAIN` に畳む (main wins)、test-only 到達は `Origin.TEST` で返す
  - 既存 strict / rejects / exclusions / 版数比較の不変条件が壊れていないことを確認
  - `ResolutionTest.kt` に:
    - 両 seed で origin 付き結果を返すケース
    - 同一 GA が main/test 両方から seed された時 main 版が勝ち origin=MAIN
    - test seed のみから到達した GA は origin=TEST
    - 既存 strict / rejects / exclusions テストを 2-seed API で呼んでも回帰しない
  - 完了条件: `ResolutionTest.kt` の新旧テストが全量 pass、Native 呼び出し経路 (`testSeeds` default) で既存挙動
  - _Requirements: 1.1, 1.2, 1.5, 4.2_
  - _Boundary: kolt.resolve.Resolution_
  - _Depends: 1.2_

- [x] 2.2 `resolveTransitive` に test seeds を透過し materialize で origin を `ResolvedDep` に転写
  - `resolveTransitive(config, existingLock, cacheBase, deps, testSeeds = emptyMap())` に拡張
  - kernel から受けた `DependencyNode.origin` を `materialize` ループで `ResolvedDep.origin` に転写
  - `TransitiveResolverTest.kt` に test seeds 入力 → 返却 `ResolvedDep` の origin が MAIN / TEST で分かれることを確認するケースを追加
  - 既存 `scopeFilteringSkipsTestAndProvided` 等の scope フィルタ回帰テストが通ること
  - 完了条件: `TransitiveResolverTest.kt` の既存+新規テストが pass、test-only 依存が `ResolvedDep.origin == Origin.TEST` で返る
  - _Requirements: 1.1_
  - _Boundary: kolt.resolve.TransitiveResolver_

- [x] 2.3 `DependencyResolution.resolveDependencies` を 2-seed 呼び出し化し `JvmResolutionOutcome` を再設計
  - `JvmResolutionOutcome` を `(mainClasspath: String?, mainJars: List<ResolvedJar>, allJars: List<ResolvedJar>)` に差し替え
  - `config.dependencies` を main seeds、`autoInjectedTestDeps(config) + config.testDependencies` を test seeds として `resolve()` を 1 回呼ぶ
  - 戻り `ResolvedDep` list を origin で分け、`mainJars` / `allJars` を計算 (`allJars = mainJars + testJars`、disjoint 保証済み)
  - 既存 `findOverlappingDependencies` 警告は従前の位置で維持
  - Lockfile 書き出しは全エントリに `test` flag を付けて v3 で永続化
  - `mergeAllDeps` の定義と呼び出し箇所を削除
  - `DependencyResolutionTest.kt` に (a) main / test が origin 通り分離される、(b) `JvmResolutionOutcome.mainClasspath` / `mainJars` / `allJars` が期待通りに出るケースを追加
  - 完了条件: `DependencyResolutionTest.kt` の新旧テストが pass、lockfile サンプルが `"version": 3` と `"test": true` を含む
  - _Requirements: 1.1, 1.3, 2.1, 2.2, 2.3, 2.5_
  - _Boundary: kolt.cli.DependencyResolution_
  - _Depends: 1.1, 1.3, 2.2_

## 3. Integration: caller 側の origin filter と出力経路

- [x] 3.1 (P) `BuildCommands` の build / run / test 経路を新 `JvmResolutionOutcome` に追従
  - `doBuild` の返り (`BuildResult`) を拡張し `mainJars` と `allJars` を後段に持ち回る経路を確保
  - `doRun` は既存 `classpath` (main-only) をそのまま `java` subproc に渡す (意味のみ狭まる)
  - `doTest` は compile (L709 相当) と run (L723 相当) の両方で `allJars` から classpath を組み立てる (disjoint 前提で単純結合)
  - `doTest` の early-return 経路 (`existingTestSources` 空時の no-op) が本変更後も維持されていることを確認
  - `handleRuntimeClasspathManifest` 呼び出しの入力を `mainJars` に差し替え (JVM `kind = "app"` のみ発火、lib/native は従前のガード維持)
  - `BuildCommandsTest.kt` / `JvmAppBuildTest.kt` に以下を追加/更新:
    - JVM app build 後の `build/<name>-runtime.classpath` に test-only jar (junit 系 / opentest4j / apiguardian) が含まれない
    - JVM lib / Native build は `*-runtime.classpath` を emit しない既存ガードが回帰しない
    - `kolt test` compile/run classpath に main deps と test deps 両方が載り、重複 GA は main 版 1 個のみ
    - `test_sources = []` の JVM app で `kolt test` が no-op (classpath 生成も走らない)
  - 完了条件: manifest ファイルに test 由来の絶対パスが存在しないことを test で pin でき、`kolt run` の classpath 組立てが main-only に縮む
  - _Requirements: 3.1, 3.2, 3.3, 4.1, 4.2, 4.3, 5.1, 5.2_
  - _Boundary: kolt.cli.BuildCommands, kolt.build.Builder_
  - _Depends: 2.3_

- [x] 3.2 (P) `Workspace.generateWorkspaceJson` を main / test 分離入力に切り替え
  - `generateWorkspaceJson(config, mainDeps, testDeps)` に signature 変更
  - `buildMainModule` は `mainDeps` のみを library 列挙
  - `buildTestModule` は `mainDeps + testDeps` を列挙 (test コードは main も見る)
  - top-level `libraries` は `mainDeps + testDeps` を列挙
  - `generateKlsClasspath` は caller が結合して渡す
  - `WorkspaceTest.kt` に (a) main module dep list に testJars が含まれない、(b) test module dep list に main + test の両方が含まれる、(c) top-level libraries 漏れなし、の 3 ケースを追加
  - `DependencyResolution.writeWorkspaceFiles` 側で `(mainJars, testJars)` を渡すよう更新
  - 完了条件: `WorkspaceTest.kt` の新旧テストが pass、生成された workspace.json の JSON で main / test module の dep 配列が期待通り
  - _Requirements: 2.1, 2.2_
  - _Boundary: kolt.build.Workspace, kolt.cli.DependencyResolution_
  - _Depends: 2.3_

- [x] 3.3 (P) `DependencyCommands.doTree` / `doInstall` を origin 別に組み直す
  - `doTree` (L254, L334 付近) の `mergeAllDeps` 呼び出しを main seeds (`config.dependencies`) と test seeds (`autoInjectedTestDeps + config.testDependencies`) の別 tree 表示に置換 (既存「test dependencies:」セクション出力は維持)
  - `doInstall` は `resolveDependencies` 経由で統一 (直接 resolver 呼び出しの箇所があれば新 `JvmResolutionOutcome` に追従)
  - 既存の deps tree 整形出力 (`formatDependencyTree`) はそのまま使用
  - `DependencyCommandsTest.kt` (既存に合わせて) に (a) `[dependencies]` / `[test-dependencies]` 両方宣言時に 2 section 出力、(b) auto-inject skip 条件下では test section が空、の回帰テストを追加
  - 完了条件: `kolt deps tree` / `kolt deps install` が手動スモークで期待通り動き、`DependencyCommandsTest.kt` の test が pass
  - _Requirements: 1.4_
  - _Boundary: kolt.cli.DependencyCommands_
  - _Depends: 2.3_

## 4. Validation: ADR / 成果物の更新とスモーク

- [ ] 4.1 (P) ADR 0027 §1 と ADR 0003 §2 の文言を新契約に揃える
  - ADR 0027 §1 の "transitive closure, post-exclusion" を「main closure (非test closure) の transitive closure, post-exclusion」に限定する文言を追加
  - ADR 0027 §4 の kind マトリクス脚注に「emit 対象は main closure のみ」の補足
  - ADR 0003 §2 の schema evolution 節に v2 → v3 (`LockEntry.test: Boolean` 追加、v1/v2 は unsupported で reject) の行を追記
  - 完了条件: `docs/adr/0027-*.md` / `docs/adr/0003-*.md` の diff が上記文言追加のみを含み、markdown として整形されている
  - _Requirements: 3.4_
  - _Boundary: docs/adr_

- [ ] 4.2 Daemon と spike の lockfile を v3 で再生成
  - `kolt-jvm-compiler-daemon/kolt.lock` を `kolt deps install` 相当で再生成し tracked 状態でコミット
  - `spike/daemon-self-host-smoke/kolt.lock` を同様に再生成
  - 生成された lockfile の `"version": 3` と test-origin エントリの `"test": true` を目視確認
  - 完了条件: 両 lockfile が v3 schema で、`kotlin-test-junit5` / junit-jupiter 系が `"test": true` で記録されるか、そもそも 出現しない (daemon は test_sources 空なので auto-inject skip 後は記録ゼロが期待値)
  - _Requirements: 2.1, 6.3_
  - _Depends: 3.1, 3.2, 3.3_

- [ ] 4.3 Daemon self-host runtime manifest に test deps が混入しないことを smoke で確認
  - `./gradlew build` 経由で `kolt.kexe` を生成 → `kolt.kexe build` を `kolt-jvm-compiler-daemon/` に対して走らせ、`build/kolt-jvm-compiler-daemon-runtime.classpath` を grep
  - 出力に `org.junit.jupiter` / `org.junit.platform` / `org.opentest4j` / `org.apiguardian` / `kotlin-test` / `kotlin-test-junit5` のいずれも含まれないことを CI の `self-host-post` 経路または相当のテストで pin
  - `JvmAppBuildTest.kt` か追加 smoke test に「auto-inject が skip され test_sources 空の JVM app では manifest が test 由来 jar を含まない」ケースを入れる
  - 完了条件: grep 結果が空であることが CI で検証される、もしくは相当のテストが green
  - _Requirements: 6.1, 6.2_
  - _Depends: 4.2_
