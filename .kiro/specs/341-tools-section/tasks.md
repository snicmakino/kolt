# Implementation Plan

> 注: 本機能は kolt の v1.0 milestone 候補で、 ADR 0028 §3 凍結面に新たに `[tools]` schema / `tools_bundles` lockfile field / `kolt tool run` CLI surface / `~/.kolt/tools/bundles/<alias>/<version>/` cache layout / `EXIT_TOOL_ERROR = 7` を加える。 Task 7.2 で ADR 改訂を land させ、 Task 7.1 までで実装を完結させる。
>
> TDD 方針: 各実装サブタスクは「test を先行で書き、 implementation を完成させる」を 1 task 内 atomic に行う (memory `feedback_tdd_red_green_atomic.md` 準拠)。 RED-only commit を残さない。

## 1. Foundation: lockfile / paths / exit code 拡張

- [x] 1.1 (P) Lockfile に `tools_bundles` field を additive 追加
  - `Lockfile` data class に `toolsBundles: Map<String, Map<String, LockEntry>> = emptyMap()` を `classpathBundles` の隣に追加
  - `LockfileJson` の snake_case マッピングと `parseLockfile` / `serializeLockfile` を round-trip 対応に拡張
  - 既存 lockfile (`tools_bundles` 不在) を読めることを `LockfileTest` の既存 `classpathBundles` round-trip pattern で assert
  - 観測完了条件: 新 round-trip テストで `tools_bundles` を含む lockfile を serialize → parse して同一値が返り、 既存 lockfile (tools_bundles 不在) を読むと empty map が返る
  - _Requirements: 4.1, 4.4_
  - _Boundary: Lockfile_

- [x] 1.2 (P) KoltPaths に tool cache 専用 path helper を追加
  - `toolsBundleDir(alias: String, version: String): String` で `~/.kolt/tools/bundles/<alias>/<version>/` を計算
  - `toolsBundleJarPath(alias: String, version: String, fileName: String): String` で配下の jar path
  - 既存 `~/.kolt/tools/<filename>` (kolt-internal flat layout) と path 衝突しないこと unit test で確認
  - 観測完了条件: 同一 alias + 異なる version で別ディレクトリが返り、 既存 `KoltPaths` の他 helper と並んで使える
  - _Requirements: 3.1, 3.2_
  - _Boundary: KoltPaths_

- [x] 1.3 (P) ExitCode.kt に `EXIT_TOOL_ERROR = 7` を追加
  - 既存 `EXIT_LOCK_TIMEOUT = 6` の次の slot に const 追加、 命名は kolt 既存 semantic (BUILD/CONFIG/DEPENDENCY/TEST/FORMAT/LOCK_TIMEOUT) に並べる
  - 既存 exit code 値 (0/1/2/3/4/5/6/127) と非衝突であることをテストで assert (mechanical sweep)
  - 観測完了条件: `EXIT_TOOL_ERROR = 7` を import して使えるようになり、 `ExitCode.kt` の他 const と整合
  - _Requirements: 5.4_
  - _Boundary: ExitCode_

## 2. Schema parse: `[tools]` の types と validation

- [x] 2.1 `parseCoordsString` utility を新設
  - `group:artifact:version[:classifier]` 文字列を `(Coordinate, classifier?)` に分解
  - group / artifact が ASCII letters/digits/`-`/`_`/`.`、 version が non-empty、 classifier 同 charset で optional
  - 構文逸脱は `Result.Err(reason: String)` を返し、 alias 文脈は呼び出し側で添える
  - 観測完了条件: unit test で `"a.b:c:1.0"` / `"a.b:c:1.0:cls"` の両形が成功、 group 欠落・version 欠落・空文字は loud に Err を返す
  - _Requirements: 1.2_
  - _Boundary: ToolSectionParse_

- [x] 2.2 `RawToolEntry` / `ToolEntry` / `KoltConfig.tools` field を追加
  - `RawToolEntry(coords: String?, dependsOn: String?, args: List<String>?, main: String?)` で `@SerialName("depends-on")` 付きの 4 field を nullable 宣言
  - `ToolEntry(coords: Coordinate, classifier: String?)` を public validated 型として導入
  - `RawKoltConfig.tools: Map<String, RawToolEntry>?` と `KoltConfig.tools: Map<String, ToolEntry>` を additive 追加 (既存 field 順序を保つ)
  - 観測完了条件: `[tools.foo] coords = "a:b:1.0"` だけを書いた kolt.toml が parse 通り、 `KoltConfig.tools["foo"]?.coords` が `Coordinate("a", "b", "1.0")` を返す
  - _Requirements: 1.1_
  - _Boundary: ToolSection, RawKoltConfig, KoltConfig_

- [ ] 2.3 `ToolSectionParse.parseToolSection` を実装
  - alias を `^[a-z][a-z0-9_-]{0,63}$` の regex で照合、 違反は `InvalidAlias`
  - `RawToolEntry.dependsOn` / `args` / `main` のいずれかが non-null なら `ForbiddenField` で reject
  - `coords` が null なら `MissingCoords`、 `parseCoordsString` 失敗は `MalformedCoords`
  - 重複 alias は ktoml の TOML spec 準拠 parse error に乗る (本コンポーネントには到達しない) — 動作確認テストを追加
  - 観測完了条件: unit test で alias `Foo` / 65 字 / `args = []` / `depends-on = "..."` / `main = "..."` / `coords` 不在の各 case が指定された error variant を返す
  - _Requirements: 1.3, 1.4, 1.5, 7.1, 7.2_
  - _Boundary: ToolSectionParse_

- [ ] 2.4 `Config.parseConfig` の validation chain に `validateToolSection` を組み込む
  - 既存 `validateBundleReferences` の隣に `validateToolSection` を呼び出し、 失敗は `Config.parseConfig` の Err 経路に乗せる
  - parse 結果の `KoltConfig.tools` が `RawKoltConfig.tools` の validated 像になっていることを Config 全体テストで確認
  - 観測完了条件: `[tools]` 含む実 kolt.toml fixture が `loadProjectConfig()` で正常 parse され、 forbidden field 入りの fixture は load 段階で Err を返す
  - _Requirements: 1.1, 1.3, 1.4, 7.1, 7.2_
  - _Boundary: Config.parseConfig_

## 3. Error type: `ToolError` sealed の集約

- [ ] 3.1 `ToolError` sealed top-level + `ToolSectionParseError` / `ToolResolutionError` / `ToolLaunchError` sealed sub-types を作成
  - 各 sub-error は design.md の State Management 表に従い variant 毎の data class を持つ (`Parse`, `Resolve`, `Launch`, `UnknownAlias`)
  - `toExitCode(): Int` を `EXIT_CONFIG_ERROR=2` / `EXIT_DEPENDENCY_ERROR=3` / `EXIT_TOOL_ERROR=7` に振り分け
  - `formatStderr(): String` で variant ごとに `tool '<alias>': <variant>: <detail>` の prefix を固定 (R5.4 cause-distinguishable)
  - `LockfileMismatch` の message は `tool '<alias>': lockfile pin '<pinned>' differs from kolt.toml '<toml>'. Run \`kolt update\` to refresh tool pins.` で固定
  - 観測完了条件: 各 variant の `toExitCode()` 値と `formatStderr()` 文字列を unit test で全網羅、 `EXIT_TOOL_ERROR=7` を経由する変種は 3 つだけ (NotRunnableJar / MainClassMissing / JdkUnavailable) であることを assert
  - _Requirements: 2.3, 3.3, 3.4, 4.3, 5.2, 5.3, 5.4, 6.2_
  - _Boundary: ToolError_

## 4. Resolve: BundleResolver 拡張 と ToolResolution

- [ ] 4.1 `BundleResolver.resolveSingleArtifact` (transitive-skip mode) を expose
  - 既存 `resolveBundle` の childLookup を `{ emptyList() }` に固定する薄い wrapper を導入、 もしくは既存 internal 関数を `internal` から `internal kolt.usertool` に open
  - 単一 coordinate を受け取り、 transitive を fetch せず POM + jar を返す経路にする
  - 既存 `[classpaths]` 経路に regression が出ないことを既存 BundleResolver テストで担保
  - 観測完了条件: 単一 `Coordinate("a","b","1.0")` を渡したテストで POM+jar の 2 entry のみ返り、 transitive 列が emptyList であることを assert
  - _Requirements: 2.4, 3.1_
  - _Boundary: BundleResolver_

- [ ] 4.2 `ToolResolution.ensureTool` の happy-path (cache + first fetch + lockfile write-through) を実装
  - alias から `ToolEntry` を引き、 `paths.toolsBundleJarPath` 配下に jar が存在し lockfile pin の `version` / `sha256` と一致するなら network skip
  - lockfile に該当 alias の pin 不在 (初回) は `BundleResolver.resolveSingleArtifact` で fetch、 SHA-256 verify、 cache に格納、 lockfile に新 pin を書き戻す (R3.1, R4.1)
  - cache miss だが lockfile pin あり の場合、 pin の coords / SHA-256 に従って fetch・verify (R4.2)
  - 観測完了条件: cache hit テストで network mock が呼ばれない、 cache miss テストで lockfile に新 entry が書かれて round-trip 成立
  - _Requirements: 2.4, 3.1, 3.2, 4.1, 4.2, 4.4_
  - _Boundary: ToolResolution_
  - _Depends: 1.1, 1.2, 2.2, 3.1, 4.1_

- [ ] 4.3 `ToolResolution.ensureTool` の failure-path (mismatch / integrity / network) を実装
  - toml の coords と lockfile の resolved coords が group:artifact:version[:classifier] のいずれかで不一致なら `LockfileMismatch` を返す (R4.3、 design.md 固定 message)
  - cache 上の jar が SHA-256 mismatch なら `IntegrityMismatch` を返し、 自動 refetch しない (R3.3)
  - network 取得不能 (全 repo 404 / 接続不能) は `ResolveFailed` を返す、 試行 repo URL を message に含める (R3.4)
  - 観測完了条件: 各 failure case の unit test が ToolError 経路で固定 stderr prefix と exit code を返す、 SHA mismatch ケースで再 fetch が起きないことを mock で assert
  - _Requirements: 3.3, 3.4, 4.3_
  - _Boundary: ToolResolution_
  - _Depends: 4.2_

## 5. Launch: MANIFEST 読 と ToolLauncher

- [ ] 5.1 (P) libarchive で jar (zip) MANIFEST.MF を読む utility を追加
  - `kolt.usertool` 内 internal 関数 `readMainClassFromJar(jarPath: String): Result<String, ToolLaunchError>` を実装
  - libarchive の `archive_read_open_filename` + entry iterate で `META-INF/MANIFEST.MF` を locate、 entry data を読み出す
  - `Main-Class:` 行を抽出 (RFC の line-folding は v1 では非対応で十分、 spec 準拠で改行直後 SP/HT 開始は continuation として concatenate)
  - libarchive open 失敗 (zip magic mismatch、 truncated) は `NotRunnableJar` を返す
  - MANIFEST.MF 不在 / `Main-Class:` 行不在は `MainClassMissing` を返す
  - 観測完了条件: fixture jar (Main-Class 有 / 無 / non-zip) の各テストで対応する Result variant が返る、 ADR 0031 既存 libarchive cinterop 経路に regression なし
  - _Requirements: 5.1, 5.2, 5.3_
  - _Boundary: ToolLauncher (jar manifest read sub)_
  - _Depends: 3.1_

- [ ] 5.2 `ToolLauncher.launch` で MANIFEST 検証 + bootstrap JDK + executeCommand を組む
  - `readMainClassFromJar(jarHandle.jarPath)` を呼び、 失敗は `ToolLaunchError` (NotRunnableJar / MainClassMissing) を上に伝播
  - `BootstrapJdk.ensureBootstrapJavaBin(paths)` を呼び、 失敗は `JdkUnavailable` に lift (R6.2)
  - `executeCommand(listOf(javaBin, "-jar", jarPath) + args, env)` で起動、 exit code をそのまま `Result.Ok(Int)` で return (R2.1, R2.2)
  - `KOLT_VERBOSE=1` 環境で起動時に `tool=<alias> jdk=<javaBin> jar=<jarPath>` を stderr に 1 行出力 (R6.3)
  - 観測完了条件: fixture runnable jar (引数を echo するだけの jar) を launch して引数列が verbatim に渡り exit code がそのまま帰ってくる、 verbose 出力が想定形式で stderr に現れる
  - _Requirements: 2.1, 2.2, 5.1, 5.2, 5.3, 6.1, 6.2, 6.3_
  - _Boundary: ToolLauncher_
  - _Depends: 1.2, 5.1_

## 6. CLI integration: dispatch と kolt update 拡張

- [ ] 6.1 `ToolCommands.doTool` で `kolt tool run <alias> [-- args...]` を dispatch
  - 第 2 引数が `run` 以外 (もしくは欠落) なら usage 表示 + `EXIT_CONFIG_ERROR` (R7.3 — `tool` の sub-action は v1 では `run` のみ)
  - alias を `KoltConfig.tools` で lookup、 不在なら `UnknownAlias` を返し、 既知 alias 一覧を stderr に提示 (R2.3)
  - alias 後の引数は verbatim 透過、 `--` 区切りは optional (`kolt tool run ktlint -F` も `kolt tool run ktlint -- -F` も等価) — 既存 `doTest` の pattern と整合 (R2.1)
  - `ensureTool` → `launch` を順に呼び、 launch 成功時はツール自身の exit code、 ToolError 経路は `toExitCode()` を返す (R2.2, R5.4)
  - 観測完了条件: alias 後の `--reporter plain --foo bar` がそのままツールに届く E2E、 未知 alias で exit 2 と既知 alias 一覧 stderr が出る
  - _Requirements: 2.1, 2.2, 2.3, 7.3_
  - _Boundary: ToolCommands_
  - _Depends: 4.2, 4.3, 5.2_

- [ ] 6.2 `Main.kt` の `when (filteredArgs[0])` に `"tool"` case と printUsage を追加
  - `"tool" -> doTool(filteredArgs.drop(1)).getOrElse { exitProcess(it) }` を既存 case 群に並べる
  - `printUsage()` に `kolt tool run <alias> [-- args...]` の help 1 行を追加
  - 観測完了条件: `kolt --help` 出力に新 line が含まれ、 `kolt tool run` の引数経路が他コマンドと干渉しない
  - _Requirements: 2.1, 7.3_
  - _Boundary: Main_
  - _Depends: 6.1_

- [ ] 6.3 (P) `doUpdateInner` を `[tools]` の re-resolve にも対応するよう拡張
  - 既存 `[dependencies]` 経路と並べて `config.tools` を seed として `BundleResolver.resolveSingleArtifact` を回し、 `Lockfile.toolsBundles` を rewrite
  - `kolt update` の出力に「updating tools…」段を追加、 `[tools]` が空ならスキップ
  - 既存 `[dependencies]` の更新挙動に regression なし (既存 `DependencyCommandsTest` パス)
  - 観測完了条件: `[tools]` の coords を編集して `kolt update` を打つと lockfile の `tools_bundles` が新 coords / SHA に置き換わる E2E、 `[dependencies]` のみのプロジェクトでは挙動不変
  - _Requirements: 4.1, 4.3, 4.4_
  - _Boundary: doUpdateInner_
  - _Depends: 1.1, 2.2, 4.1_

## 7. Validation: E2E と ADR 改訂

- [ ] 7.1 fixture runnable jar を使った CLI E2E を追加
  - `src/nativeTest/resources/` (もしくは既存 fixture 配置場所) に「argv を JSON で stdout に echo して exit code を引数で受け取る」極小 runnable jar を commit
  - `kolt tool run <fixture-alias> <args...>` で argv が verbatim にツールに届くこと、 exit code がツール側の指定値そのままで返ることを assert (R2.1, R2.2, R5.1)
  - 観測完了条件: `kolt test` で新 E2E test が green、 self-host CI (`self-host-post`) も既存通り通過
  - _Requirements: 2.1, 2.2, 5.1_
  - _Boundary: ToolCommands E2E_

- [ ] 7.2 (P) `kolt.usertool` が build / test lifecycle に連携していないことを assert する guard test を追加
  - `src/nativeMain/kotlin/kolt/build/` および `src/nativeMain/kotlin/kolt/cli/BuildCommands.kt` / `TestCommands.kt` (もしくはそれに準ずる existing build/test entry) の source 全文を grep し、 `kolt.usertool` を import / 参照していないことを assert (precedent: `DriftGuardsTest` 既存 sweep pattern)
  - 「build/test 経路から `[tools]` の hook が刺さっていない」ことを compile-time + grep で 2 重に保証
  - 観測完了条件: 新 guard test が green で、 将来誰かが build/test 経路から `kolt.usertool` を呼び出す変更を入れたら test が RED になる
  - _Requirements: 7.4_
  - _Boundary: DriftGuards (新規 sweep 追加)_

- [ ] 7.3 ADR 0028 §3 を改訂し、 本 spec の凍結面追加を pin
  - §3 に新セクションを追加: `[tools]` toml schema additive 規則、 `tools_bundles` lockfile field additive 規則、 `kolt tool run` CLI surface 凍結、 `~/.kolt/tools/bundles/<alias>/<version>/` cache layout 凍結、 `EXIT_TOOL_ERROR=7` 凍結、 `kolt update` の scope 拡張 ([dependencies]+[tools] 両対応) を 1 セクションに集約
  - 既存 §3 の `~/.kolt/toolchains/{jdk|kotlinc|konanc}/` 凍結に並べる形で配置
  - ADR Summary の bullet 1 行も追加
  - 観測完了条件: ADR 改訂が docs/adr/0028 に commit され、 本 spec の Migration Strategy セクションが ADR の該当項に対応している
  - _Requirements: 5.4_
  - _Boundary: docs/adr/0028-v1-release-policy.md_

## Implementation Notes

- 各 task の commit 前に `./scripts/fmt.sh` (もしくは `kolt fmt`) を必ず実行する。 pre-commit hook が tree 全体に対して `fmt.sh --check` を走らせるため、 staged files 外でも未 format があると commit が reject される。 implementer subagent も READY_FOR_REVIEW 前に fmt を回しておくと、 parent の commit 段階での fmt-fail retry が減る。
