# Implementation Plan

> 本 plan は SDD の design.md / research.md に基づく。 各 task は TDD (Red → Green → Refactor) を 1 単位で完結させ、 RED-only commit を残さない (memory: feedback_tdd_red_green_atomic)。 観察可能完了条件は各 task の最後の detail bullet に記述。

## 1. Foundation: schema 表面と infrastructure

- [x] 1.1 (P) SysPropValue sealed class と ktoml polymorphic decoding を確定する
  - SysPropValue を 3 variant の sealed class (Literal / ClasspathRef / ProjectDir) として導入する
  - **ktoml probe を最初に書く**: `Map<String, SysPropValue>` を decode する小さな test を書き、 入力が TOML string と inline table を混在で受けられるかを確認する
  - probe 成功時: custom KSerializer を実装し、 string/structure 入力を判別して 3 variant に dispatch する
  - probe 失敗時: schema を uniform inline-table (`{ literal = "..." }` 必須) に縮退させ、 同 PR で requirements.md の Req 2.1 を amend する。 amend は本 task 内で実施する
  - **Map decode 順序の probe** も併走させる: ktoml が TOML declaration order を保つ Map を返さない場合、 `parseConfig` 内に LinkedHashMap 正規化 step を追加する
  - 観察可能完了条件: `SysPropValueDecodeTest` (3 形態 happy path + malformed reject) と `ConfigOrderingTest` (3 entry の declaration order が iteration order に一致) が green
  - _Requirements: 2.1, 2.2, 2.7_
  - _Boundary: SysPropValue, Config_

- [x] 1.2 (P) Lockfile schema を v4 に bump し migration UX を実装する
  - LOCKFILE_VERSION を 4 に bump、 `classpathBundles: Map<String, Map<String, LockEntry>> = emptyMap()` を schema に追加する
  - `kolt deps install` の v3 detection: stderr に `warning: kolt.lock v3 detected, regenerating as v4 (one-time migration for v0.X)` を出して fresh resolve → v4 で上書きする
  - `kolt build` / `kolt test` / `kolt run` の v3 detection: 明確 error (`error: kolt.lock v3 is no longer supported, run 'kolt deps install' to regenerate`) で停止する。silent な再解決は行わない
  - 観察可能完了条件: `LockfileV4Test` (round-trip read/write + classpathBundles persist) と `LockfileV3MigrationTest` (deps install で warning + overwrite, build 系で明確 error) が green
  - _Requirements: 4.1, 4.4_
  - _Boundary: Lockfile, DependencyCommands_

- [x] 1.3 KoltConfig schema 拡張と validator 4 種を実装する
  - `RawKoltConfig` に `classpaths: Map<String, ClasspathBundle> = emptyMap()`、 `test: RawTestSection? = null`、 `run: RawRunSection? = null` を追加する
  - `KoltConfig` に対応 non-null フィールド (default empty) を投影、 不在時に既存挙動が完全に保たれることを test で確認する
  - `validateNewSchemaTargetCompat` を追加: native target × 新 table 非空 reject、 lib + `[run.sys_props]` 非空 reject、 lib + `[test.sys_props]` 受理、 lib + `[classpaths.X]` (target=jvm) 受理
  - `validateBundleReferences` を追加: 全 sysprop の `ClasspathRef.bundleName` が `[classpaths.<name>]` で declare 済か確認する
  - `validateProjectRelativePath` を追加: 絶対パス / `..` 脱出 / 空文字列を reject、 `.` / 末尾 `/` は受理
  - 観察可能完了条件: `ClasspathBundleConfigTest` (1.1–1.5 全網羅) と `ConfigSysPropValidationTest` (Req 2.3–2.6 / 5.1–5.4 全網羅) が green、 既存 `ConfigTest` が継続 green
  - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 2.3, 2.4, 2.5, 2.6, 5.1, 5.2, 5.3, 5.4, 7.1_
  - _Depends: 1.1_
  - _Boundary: Config_

## 2. Core: resolver と runner の構成要素

- [x] 2.1 (P) BundleResolver: bundle ごとの独立 fixpointResolve pass を実装する
  - `BundleResolver.kt` に `resolveBundle(config, bundleName, bundleSeeds, existingLock, cacheBase, deps)` を新設する
  - 内部で `fixpointResolve(mainSeeds = bundleSeeds, testSeeds = emptyMap(), ...)` を呼び、 main / test / 他 bundle の state を引数として受けない関数 contract を保つ
  - `BundleResolution(jars, classpath)` を返す。jar 順序は既存 main resolver の materialize 順と同一ポリシーに従う
  - `BundleResolverIsolationTest` を書く: bundle A が transitive で X を pull する状況で、 bundle B (X 非要求) の `BundleResolution.jars` に X が **含まれない** ことと、 main/test outcome に X が leak しないことを確認する
  - `BundleResolverVersionDivergenceTest` を書く: bundle A:`foo:1.0`、 bundle B:`foo:2.0` を要求する状況で、 各 bundle が自分の版を含み conflict にならないことを確認する
  - 観察可能完了条件: 上記 2 test が green、 既存 `TransitiveResolverTest` が継続 green (main/test pass の挙動不変)
  - _Requirements: 4.1, 4.5_
  - _Depends: 1.2_
  - _Boundary: BundleResolver_

- [x] 2.2 (P) SysPropResolver: pure function で 3 形態 value を解決する
  - `resolveSysProps(sysProps, projectRoot, bundleClasspaths): List<Pair<String, String>>` を新設、 戻り値は `Result` 型を **使わない** (invariant 違反は `error()` で fail-fast、 ADR 0001 引用)
  - 解決規則: `Literal(v)` → verbatim、 `ClasspathRef(name)` → `bundleClasspaths[name]` の colon-joined string、 `ProjectDir(rel)` → `<projectRoot>/<rel>` を `absolutise` で絶対化
  - 環境変数展開を一切行わないこと、 declaration order を保つことを test で固定する
  - ProjectDir の corner case を test で網羅する: `.` 許容 / 末尾 `/` 保持 / 存在しない dir 通す / symlink 解決しない
  - 観察可能完了条件: `SysPropResolverTest` (3 形態 + ProjectDir corner case 表 + ordering + env 非展開) が green
  - _Requirements: 2.7, 3.3, 3.4, 3.5_
  - _Boundary: SysPropResolver_

- [x] 2.3 (P) testRunCommand と runCommand に sysProps 引数を追加する
  - `testRunCommand` と `runCommand` のシグネチャに `sysProps: List<Pair<String, String>> = emptyList()` を追加する
  - `java` 直後に `-D<k>=<v>` を `sysProps` の iteration 順で挿入、 `-jar` / `-cp` / `--class-path` / main class 等は従来位置を保つ
  - `sysProps = emptyList()` のとき argv は従来と byte-verbatim 同一
  - 観察可能完了条件: `TestRunnerSysPropTest` と `RunnerSysPropTest` が green、 既存 `TestRunnerTest` / `RunnerTest` (もしあれば) が継続 green
  - _Requirements: 3.1, 3.2, 3.6, 3.7, 7.2, 7.3_
  - _Boundary: TestRunner, Runner_

## 3. Integration: resolution pipeline と CLI への配線

- [x] 3.1 JvmResolutionOutcome と resolveDependencies に bundle を統合する
  - `JvmResolutionOutcome` に `bundleClasspaths: Map<String, String>` と `bundleJars: Map<String, List<ResolvedJar>>` を追加する
  - `resolveDependencies` を拡張: 既存 main/test resolve の後に config の各 bundle を `resolveBundle` で順に解き、 outcome と lockfile の `classpathBundles` 双方に書き込む
  - bundle declaration の hash 変更時のみ当該 bundle を再 resolve する logic を追加する
  - 観察可能完了条件: 複数 bundle を持つ fixture を resolve すると、 outcome の `bundleClasspaths` 各 entry が想定 jar 集合の colon-joined string を返し、 `kolt.lock` v4 の `classpathBundles` に persist される
  - _Requirements: 4.1, 4.4_
  - _Depends: 1.2, 2.1_
  - _Boundary: DependencyResolution_

- [x] 3.2 doTestInner と doRunInner に SysPropResolver を組み込む
  - `BuildCommands.doTestInner` で `resolveSysProps(config.testSection.sysProps, projectRoot, outcome.bundleClasspaths)` を呼び、 結果を `testRunCommand(..., sysProps = ...)` に渡す
  - `doRunInner` も同形で `runCommand` に渡す
  - argv 順序が deterministic であることを assertion 付きで test する
  - 観察可能完了条件: `[test.sys_props]` を 3 entry 持つ fixture で `kolt test` を実行したとき、 spawn された java プロセスの argv に `-D<k>=<v>` が declaration order で並ぶ (subprocess argv を assertion する unit test、 e2e は 4.1 で実施)
  - _Requirements: 3.1, 3.2_
  - _Depends: 2.2, 2.3, 3.1_
  - _Boundary: BuildCommands_

- [x] 3.3 kolt deps install / update / tree を bundle 対応にする
  - `doInstall` で bundle resolve 結果を lockfile の `classpathBundles` に書き出す
  - `doUpdate` で各 bundle entry を main/test と同じ policy で更新する
  - `doTree` で `Bundles` セクションを既存 main / test セクションの後に描画する
  - 観察可能完了条件: 複数 bundle を持つ fixture で `kolt deps tree` を実行すると、 main / test / bundles の 3 セクションがそれぞれ別ラベルで出力される (`DepsTreeBundleTest` green)
  - _Requirements: 4.2, 4.3_
  - _Depends: 3.1_
  - _Boundary: DependencyCommands_

## 4. Validation: end-to-end 検証と dogfood

- [ ] 4.1 KOLT_INTEGRATION gate 付き end-to-end test を追加する
  - `JvmTestSysPropIT` を新設、 fixture プロジェクトに `[classpaths.foo]` (任意の小さい dep) と `[test.sys_props]` (literal / classpath / project_dir 各 1 個) を declare する
  - 実 `kolt test` を起動し、 fixture テストが `System.getProperty(...)` で 3 値を取得し assert する
  - KOLT_INTEGRATION env gate の既存 pattern (例: `ConcurrentBuildIT`) に従う
  - 観察可能完了条件: `KOLT_INTEGRATION=1 kolt test --tests JvmTestSysPropIT` が green、 fixture テスト内で `System.getProperty` の値が SDD design.md の resolved-value 規則と一致する
  - _Requirements: 3.1, 3.3, 3.4_
  - _Depends: 3.2_

- [ ] 4.2 dogfood: kolt 自身の kolt.lock を v4 に rotate する
  - root + `kolt-jvm-compiler-daemon` + `kolt-native-compiler-daemon` の 3 つの `kolt.lock` を `kolt deps install` で v4 に再生成する
  - 各 lockfile の git diff が `version: 3 → 4` と (空の) `classpathBundles: {}` 追加に閉じていることを目視確認する
  - 全 test suite (`kolt test` × 3) が green であることを確認する
  - 観察可能完了条件: 3 ディレクトリの `kolt.lock` が version 4、 全 build / test が green、 dogfood 観察を `docs/dogfood.md` に 1 行記録する
  - _Depends: 1.2, 3.1, 3.2_

## 5. Out-of-code deliverables: ADR と follow-up issues

- [ ] 5.1 (P) follow-up issue を open: CLI -D flag for kolt test/run
  - title: `Add CLI -D<key>=<value> flag to kolt test and kolt run`
  - body: 問題 (env-specific 値を kolt.toml に書けない、 ad-hoc override が欲しい場面)、 完了条件 (両コマンドで `-D` を accept、 kolt.toml 宣言の value を override)、 スコープ (`-D` のみ、 `-X` 系の他 JVM flag は別 issue)
  - label: `size: M`
  - 観察可能完了条件: GitHub issue が open 状態で URL を取得済み、 ADR 0032 の Follow-ups 節で参照可能
  - _Requirements: 6.4_

- [ ] 5.2 (P) follow-up issue を open: per-user kolt.local.toml override file
  - title: `Per-user kolt.local.toml override file for environment-specific values`
  - body: 問題 (env-agnostic な kolt.toml と env-specific 値の分離、 .gitignore 推奨)、 完了条件 (`kolt.local.toml` が存在する場合 kolt.toml の上に overlay される)、 スコープ (overlay 範囲は `[test.sys_props]` / `[run.sys_props]` の値のみ、 `[classpaths]` 上書きは別議論)
  - label: `size: L`
  - 観察可能完了条件: GitHub issue が open 状態で URL を取得済み、 ADR 0032 の Follow-ups 節で参照可能
  - _Requirements: 6.4_

- [ ] 5.3 ADR 0032 を発行する
  - `docs/adr/0032-kolt-toml-env-agnostic.md` を新設、 Status / Summary / Context / Decision / Alternatives Considered / Consequences / Follow-ups の standard ADR shape で書く
  - Decision 節で `${env.X}` interpolation 不採用を明記する
  - Alternatives Considered 節で Maven 流 interpolation / Cargo 流 separate file / CLI flag escape hatch を比較する
  - Follow-ups 節で 5.1 と 5.2 の issue 番号を埋める
  - 観察可能完了条件: ADR 0032 が repo に存在し、 5.1 / 5.2 の issue 番号への hyperlink が両方 resolve する
  - _Requirements: 6.1, 6.2, 6.3_
  - _Depends: 5.1, 5.2_

## Implementation Notes

- **Task 1.1**: ktoml 0.7.1 の `decodeFromString` は `Map<String, V>` の declaration order を保つ (ConfigOrderingTest で確認済)。 future task で `parseConfig` 内に LinkedHashMap 正規化 step を入れる必要なし
- **Task 1.1**: ktoml 0.7.1 の標準 `KSerializer` 表面では「TOML string OR inline-table」polymorphism を扱えない (TomlNode 内部型 cast が必須で fragile)。 `SysPropValue` は uniform inline-table (`{ literal | classpath | project_dir = "..." }`) で確定、 Req 2.1 amendment 済
- **Task 1.2**: `kolt deps install` だけが v3 → v4 自動 migration を許す pattern を `allowLockfileMigration: Boolean = false` でカプセル化。 build path (`BuildCommands.kt:171, 291`) は default false を引き継ぐので silent re-resolve は構造的に発生しない。 `doUpdate` は元から `existingLock = null` 固定なので干渉なし
- **Task 1.2**: `LockfileLoadResult` を sealed 5 variant にして v3 detection 結果を caller policy で分岐。pure 関数 `classifyLockfileLoad` で eprintln から切り離して unit test 可能にし、 stderr message 自体は call site の visual review で検証する pattern を採用
- **Task 1.3**: design.md の `ClasspathBundle` data class wrapper は実装しなかった。 `[classpaths.<name>]` の TOML shape は `[dependencies]` と同形 (`"GAV" = "version"`) なので、 ラッパなしの `Map<String, Map<String, String>>` 直書きで型情報も意味も損なわない。 wrapper を将来必要にする要素 (per-bundle excludes 等) が出た時点で導入する
- **Task 1.3**: parseConfig 経由の sysprop decode は `RawSysPropValue`-based の `liftSysPropsMap` を使い、 `SysPropValueSerializer` (1.1 の custom serializer) は通らない。 これは production path で offending key 名を error message に組み込むため。 1.1 の serializer + test は 「decode shape の単体テスト」用に残す 2 path 共存設計 (どちらも `RawSysPropValue` を共有するため整合は保たれる)
- **Task 2.2**: `.gitignore` の `**/build/` パターンが `src/**/kolt/build/` の source package を silently 巻き込んでいた (新規ファイルが untracked にすら出ない)。 task 2.2 commit で `!src/**/kolt/build/` + `!src/**/kolt/build/**` の re-include 規則を追加。 同根の問題は `src/**/kolt/build/` 配下に新規ファイルを置く 2.3 / 3.2 / その他で再発しないよう確定済
- **Task 3.2**: `kolt run --watch` 経路 (`WatchLoop.kt`) は `runCommand(...)` を直呼びしているため `[run.sys_props]` を thread しない。 task 3.2 の boundary は `BuildCommands` だったので out-of-scope として document 化、 follow-up issue を後で立てる
