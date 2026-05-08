# Implementation Plan

> Each executable sub-task is TDD-atomic (test + impl in the same task) per t_wada protocol — RED-only commit は reviewer に reject される (memory: feedback_tdd_red_green_atomic)。

- [ ] 1. Foundation: infra/output 層 (severity / writer / color policy)
- [x] 1.1 (P) `Severity` enum と `RenderedDiagnostic` data class、 ANSI escape constants を導入
  - `Severity.Error / Warning / Note` の 3 値 enum を定義
  - `RenderedDiagnostic(severity, headline, context, hint)` を pure data class として定義 (`headline` 単一行、 `context: List<String>`、 `hint: String?`)
  - ANSI constants (`RED = "\\x1B[31m"`, `YELLOW`, `CYAN`, `RESET`) を定義
  - 同パッケージで 3 種の data class equality / copy / toString が動くテストと、 ANSI 定数値が `\\x1B[31m` 等の正しいバイト列であるテストを追加
  - 観察可能な完了: `kolt test --filter SeverityTest --filter RenderedDiagnosticTest --filter AnsiCodesTest` が green、 `kolt build` が green
  - _Requirements: 1.1, 1.2, 1.3, 1.4_
  - _Boundary: kolt.infra.output value types_

- [x] 1.2 (P) `ColorPolicy` sealed class と `fromEnv` / `install` / `current` を実装
  - `ColorPolicy.Always / Never / Auto(isStderrTty, isStdoutTty)` sealed 階層
  - `fromEnv(noColorFlag)` の決定順: `noColorFlag → NO_COLOR env → isatty(STDERR/STDOUT_FILENO)` を実装
  - module-level `var currentPolicy` を `install` で更新、 `current()` で読む。 startup 1 回計算契約をコメントに残す
  - 4 ケースのテスト: `noColorFlag=true → Never`、 `NO_COLOR=1 → Never`、 両者 set → `Never`、 環境クリーン → `Auto(true,true)` (isatty stub) と `Auto(true,false)` (stdout のみリダイレクト stub)
  - `isatty` / `getenv` 呼び出しは callable parameter として注入できる internal seam を `fromEnv` に持たせ、 unit test は stub、 production は POSIX 実関数を渡す
  - 観察可能な完了: `kolt test --filter ColorPolicyTest` で 4 ケース green、 stub 経由で全分岐がカバーされる
  - _Requirements: 2.2, 2.3, 2.4, 2.5_
  - _Boundary: kolt.infra.output.ColorPolicy_

- [x] 1.3 `DiagnosticWriter` 関数群 (`eprintError` / `eprintWarning` / `eprintNote` / `eprintDiagnostic`) と `AnsiStripper` を実装
  - 4 関数全てが `policy: ColorPolicy = ColorPolicy.current()` の default arg を持つ — caller 側 churn なしで testability を確保 (validate-design Issue 2 解決)
  - rendering algorithm: severity ラベル決定 → `policy.shouldColor(Stream.Stderr)` → ANSI wrap or plain → headline `eprintln` → context lines を 2 スペース indent で `eprintln` → hint があれば `note:` プレフィックス line で `eprintln`
  - `AnsiStripper.strip(s)` は CSI regex (`\\x1B\\[[0-?]*[ -/]*[@-~]`) で escape sequence を除去
  - snapshot test: 3 severity × `policy = Always / Never` × (headline-only / +context / +hint / +context+hint) の組合せで stderr 出力 byte-level pin。 `AnsiStripper.strip` は inline ANSI 混在 / 複数 sequence / non-ANSI unchanged の 3 ケース
  - 観察可能な完了: `eprintError("foo", policy = ColorPolicy.Always)` が `\\x1B[31merror:\\x1B[0m foo\\n` を stderr に書く snapshot test green、 `eprintError("foo", listOf("at /a:1"), "try X", policy = ColorPolicy.Never)` が `error: foo\\n  at /a:1\\nnote: try X\\n` を書く snapshot test green
  - _Requirements: 1.1, 1.2, 1.3, 1.4, 2.1, 2.6, 6.3_
  - _Depends: 1.1, 1.2_
  - _Boundary: kolt.infra.output.DiagnosticWriter_

- [ ] 2. Foundation: infra/suggest 層 (Levenshtein + closestMatch) — Major 1 と並行実行可、 sub-task が parallel-safe
- [x] 2.1 `levenshtein(a, b): Int` を実装
  - 標準 dynamic-programming edit distance (insert / delete / substitute コスト各 1)
  - テスト: distance 0 (`"foo" / "foo"`)、 1 (`"foo" / "fo"`)、 2 (`"foo" / "fox"`)、 完全相違 (`"" / "abc"` → 3) の 4 ケース
  - 観察可能な完了: `kolt test --filter LevenshteinTest` 4 ケース green
  - _Requirements: 5.4_
  - _Boundary: kolt.infra.suggest.Levenshtein_

- [x] 2.2 `closestMatch(input, candidates, maxDistance)` を実装、 deterministic ordering を保証
  - `adaptiveThreshold(inputLength) = if (inputLength <= 4) 1 else 2` の default
  - candidates 走査は順序保存、 同点最小距離は最初に見つかったものを返す。 caller が sorted list を渡せば lex 順 deterministic
  - テスト: 距離 1 で hit、 閾値超過で `null`、 同点候補 2 つで先頭が選ばれる、 空 candidates で `null` の 4 ケース
  - 観察可能な完了: `closestMatch("buidl", listOf("build", "test"))` が `"build"` を返す test green、 `closestMatch("xyz", listOf("build"))` が `null` を返す test green
  - _Requirements: 3.4, 5.2, 5.3, 5.4_
  - _Depends: 2.1_
  - _Boundary: kolt.infra.suggest.ClosestMatch_

- [ ] 3. Renderer ADT migration (existing renderer の signature 変更 + 全 caller 同時更新)
- [x] 3.1 (P) `formatResolveError` を `String` 返却から `RenderedDiagnostic` 返却に migrate、 全 cli caller を同時更新
  - 各 `ResolveError` variant の `error: ...` プレフィックスを削除し、 `headline` / `context: List<String>` / `hint` の 3 slot に分解
  - 例: `Sha256Mismatch` は headline = `"sha256 mismatch for {ga}"`、 context = `["expected: ...", "got:      ..."]`、 hint = null
  - 例: `DownloadFailed.AllAttemptsFailed` は context に各 attempt 行を `"  {url} -> {status}"` 形式で格納
  - cli call site (`BuildCommands.kt`, `DependencyCommands.kt`) の `eprintln(formatResolveError(...))` を `eprintDiagnostic(formatResolveError(...))` に書き換え、 既存の string-snapshot test を `RenderedDiagnostic` field-level assertion に置換
  - 観察可能な完了: `kolt test --filter ResolveErrorTest` 全 variant green、 `kolt build` で resolve error fixture が cli 経由で従来同等の出力 (color disabled で平文比較) を出す
  - _Requirements: 4.1, 4.2, 4.3, 4.4, 5.1, 6.1_
  - _Depends: 1.3_
  - _Boundary: kolt.resolve.formatResolveError + その cli consumer_

- [x] 3.2 (P) `ToolError.formatStderr` を `ToolError.render(): RenderedDiagnostic` に migrate、 全 cli caller を同時更新
  - sealed class `ToolError` の `abstract fun formatStderr(): String` を `abstract fun render(): RenderedDiagnostic` に置換、 全 variant を更新
  - `LockfileMismatch` の inline `"Run \`kolt update\` to refresh tool pins."` を `hint` slot へ移動 (R5.1 の severity 分離)
  - `ToolCommands.kt` の `eprintln(error.formatStderr())` を `eprintDiagnostic(error.render())` に書き換え
  - 既存 `ToolErrorTest.exactlyThreeVariantsRouteThroughExitToolError` の exit code pin が green を維持していることを確認
  - 観察可能な完了: `kolt test --filter ToolErrorTest` 全 variant green、 `EXIT_TOOL_ERROR=7` ルーティングが unchanged
  - _Requirements: 4.1, 5.1, 6.1_
  - _Depends: 1.3_
  - _Boundary: kolt.usertool.ToolError + その cli consumer_

- [ ] 4. kolt.toml 設定エラー enrichment (path / lineNo / keyPath / suggestion)
- [x] 4.1 ktoml 例外 message から `Line N: ` プレフィックスを抽出する helper を実装
  - `extractKtomlLineNo(message: String?): Pair<Int?, String>` を `kolt.config` 内 internal で定義
  - regex `^Line (\\d+): ` で前置きにマッチ、 数値 + 残りメッセージを返す。 message null / マッチなし / `Line abc:` (toIntOrNull fail) の 3 fallback で `null` to original を返す
  - テスト: `"Line 5: foo" → (5, "foo")`、 `"foo" → (null, "foo")`、 `null → (null, "")`、 `"Line abc: foo" → (null, "Line abc: foo")` の 4 ケース
  - 観察可能な完了: `kolt test --filter ExtractKtomlLineNoTest` 4 ケース green
  - _Requirements: 3.2_
  - _Boundary: kolt.config (extractKtomlLineNo)_

- [x] 4.2 unknown key 検出 + top-level section の Did-you-mean を実装
  - `KNOWN_TOP_LEVEL_SECTIONS` を `["build", "cinterop", "classpaths", "dependencies", "fmt", "kotlin", "repositories", "run", "test", "tools"]` の sorted hardcoded list として定義 (R3.4 narrow scope)
  - `parseUnknownKey(detail: String): Pair<String?, String?>` を実装、 ktoml の `"Unknown key received: <X> in scope <Y>"` regex 抽出。 `Y` が空 (top-level) のとき `closestMatch(X, KNOWN_TOP_LEVEL_SECTIONS)` を呼ぶ、 ネストでは suggestion なし (key path だけ返す)
  - テスト: top-level typo (`<koltn>` in scope `<>`) → key="koltn", suggestion="kotlin"、 nested unknown (`<compilerr>` in scope `<kotlin>`) → key="compilerr", suggestion=null、 regex 不一致 → both null
  - 観察可能な完了: `kolt test --filter ParseUnknownKeyTest` 3 ケース green、 hardcoded list が `KoltConfigRaw` の field 名と一致することを確認する drift guard test 1 件 green
  - _Requirements: 3.3, 3.4_
  - _Depends: 2.2_
  - _Boundary: kolt.config (parseUnknownKey + KNOWN_TOP_LEVEL_SECTIONS)_

- [x] 4.3 `ConfigError.ParseFailed` shape を nullable default 付きで拡張 (compile-safe migration)
  - field 追加: `path: String? = null`, `lineNo: Int? = null`, `keyPath: String? = null`, `suggestion: String? = null` を既存 `message: String` の後に **default 付き** で追加。 これにより `kolt.config.Config.kt` (~24 ヶ所)、 `kolt.config.Main.kt` (2 ヶ所)、 `kolt.cli.BuildCommands.kt:266` の既存全 ~28 構築箇所は no-op で再コンパイル成功する
  - 既存パターン `is ConfigError.ParseFailed -> ... err.message` を読む箇所 (`WatchChangeDispatch.kt:76`, `InfoCommand.kt:380`, `DependencyCommands.kt:42`, `BuildCommands.kt:251`) は `message` field アクセスのみなので無変更で動作することを確認
  - data class equality / copy / toString の test を更新、 nullable field を含む新 shape の round-trip pin
  - 観察可能な完了: `kolt build` (kolt 自身) が green、 既存 `ConfigError.ParseFailed` の string-message-only 構築が全ファイル compile-success、 `ParseFailed("foo")` と `ParseFailed("foo", path = "/k.toml")` の両形式が共存する unit test green
  - _Requirements: 3.1_
  - _Boundary: kolt.config (ConfigError data class shape)_

- [x] 4.4 ktoml 例外 catch 節を新 helper 経由にし、 `renderConfigError` を実装、 cli caller を更新
  - `Config.kt:520-524` の catch 節を `extractKtomlLineNo` + `parseUnknownKey` 経由にし、 `ParseFailed(message, path = absKoltTomlPath, lineNo = ..., keyPath = ..., suggestion = ...)` を構築
  - `renderConfigError(e: ConfigError.ParseFailed): RenderedDiagnostic` を実装: headline = `e.message`、 context = `["at {path}:{lineNo}", "key: {keyPath}"]` (path / lineNo / keyPath が null なら該当行省略)、 hint = `suggestion?.let { "Did you mean \`$it\`?" }`
  - cli caller (`BuildCommands.kt:251`, `DependencyCommands.kt:42`) の `eprintln("error: ${error.message}")` を `eprintDiagnostic(renderConfigError(error))` に置換。 `WatchChangeDispatch.kt:76` / `InfoCommand.kt:380` は `.message` を文字列として再加工する経路なので renderer 経由に書き換え (情報が落ちないよう確認)
  - 観察可能な完了: synthetic broken `kolt.toml` (構文エラー / 未知 top-level key / 未知 nested key) 3 ケースの integration test で、 stderr 出力に絶対パス + 行番号 (該当時) + key path (該当時) + suggestion (top-level 未知時) が正しく出る
  - _Requirements: 3.1, 3.2, 3.3, 3.4_
  - _Depends: 1.3, 4.1, 4.2, 4.3_
  - _Boundary: kolt.config.Config (ktoml catch + renderConfigError) + cli consumers_

- [x] 4.5 ktoml message-format pin test (`ConfigParseMessageFormatTest`) を追加
  - `tmp/kolt.toml` に故意に壊した TOML (構文エラー / unknown top-level / unknown nested) を生成、 ktoml decode を呼んで実 exception の `message` 文字列を取得
  - regex `^Line \\d+: ` と `^Unknown key received: <[^>]+> in scope <[^>]*>` の 2 つが現行 ktoml 0.7.1 出力と一致することを assert
  - ktoml major bump 時にこのテストが RED 化することで形式変更を検知。 README にこの test の役割をコメントとして書く
  - 観察可能な完了: `kolt test --filter ConfigParseMessageFormatTest` 3 ケース green、 ktoml 依存の正規表現 contract が test fixture で固定される
  - _Requirements: 3.2, 3.3_
  - _Depends: 4.1, 4.2_
  - _Boundary: kolt.config (test fixture)_

- [ ] 5. CLI startup + dispatcher integration
- [x] 5.1 `parseKoltArgs` に `--no-color` flag、 `KoltArgs.useColor` field、 `Main.kt` startup で `ColorPolicy.install` を実装
  - `internal const val NO_COLOR_FLAG = "--no-color"` を追加、 `KoltArgs` に `noColor: Boolean` field を追加
  - `parseKoltArgs` で `--no-color` を抽出、 `filteredArgs` から strip
  - `main` 入口直後で `ColorPolicy.install(ColorPolicy.fromEnv(noColorFlag = koltArgs.noColor))` を呼ぶ。 既存 dispatch 順序は不変
  - テスト: `parseKoltArgs(["--no-color", "build"])` → `noColor=true`, `filteredArgs=["build"]`、 `parseKoltArgs(["build"])` → `noColor=false`、 `parseKoltArgs(["--no-color", "--no-daemon", "build"])` → 両 flag 抽出されて filteredArgs から削除
  - 観察可能な完了: `kolt --no-color build <synthetic-fail>` 実行で stderr 出力に ANSI escape (`\\x1B[`) が一切含まれない smoke test green、 `kolt build` (TTY redirect 想定の test 環境) でも非 TTY 経路により ANSI なし
  - _Requirements: 2.4_
  - _Depends: 1.2_
  - _Boundary: kolt.cli.Main (parseKoltArgs + main startup)_

- [x] 5.2 未知サブコマンドへの Did-you-mean 提案を dispatcher に実装
  - `Main.kt` の `else -> eprintln("error: unknown command ...")` 節を `eprintError(headline, hint = closestMatch(unknown, KNOWN_SUBCOMMANDS_SORTED)?.let { "Did you mean \`$it\`?" })` に置換
  - `KNOWN_SUBCOMMANDS_SORTED` を dispatcher `when` の隣に sorted alphabetical list として hardcoded (drift guard コメントを残す)
  - `printUsage()` の出力は維持。 exit code は `EXIT_COMMAND_NOT_FOUND` のまま
  - テスト: `kolt buidl` → stderr に `error: unknown command 'buidl'` + `note: Did you mean \`build\`?` + exit code 確認
  - 観察可能な完了: integration test で `kolt buidl` が `Did you mean \`build\`?` を出すこと、 `kolt xxx-totally-unrelated` (距離超過) では suggestion 行が出ないこと
  - _Requirements: 5.2, 5.4_
  - _Depends: 1.3, 2.2, 5.1_
  - _Boundary: kolt.cli.Main (unknown subcommand path)_

- [x] 5.3 未知 global flag への Did-you-mean 提案を `parseKoltArgs` 経路に実装
  - `KoltArgs` に `unknownFlags: List<String>` field を追加 (Result 返却ではなく値返却で統一、 既存 `parseKoltArgs` の signature と対称)
  - `parseKoltArgs` で kolt-level block (subcommand 名より前) に既知 flag set / `-D` / passthrough のいずれにも該当しない `--xxx` を検出して `unknownFlags` に積む
  - `KNOWN_KOLT_FLAGS = ["--no-color", "--no-daemon", "--release", "--watch"]` を sorted alphabetical list として hardcoded (drift guard コメント)
  - dispatcher (`main`) で `unknownFlags` が非空なら最初の 1 つに対して `eprintError("unknown flag '$flag'", hint = closestMatch(flag, KNOWN_KOLT_FLAGS)?.let { "Did you mean \`$it\`?" })` を出力、 既存 `EXIT_COMMAND_NOT_FOUND` (=127) を流用して `exitProcess(EXIT_COMMAND_NOT_FOUND)` (新 exit code constant 追加は本仕様の対象外、 R1.6 の既存 exit code 不変方針に従う)
  - テスト: `kolt --no-clor build` → `error: unknown flag '--no-clor'` + `note: Did you mean \`--no-color\`?` + exit code 127、 `kolt --xxx build` (距離超過) → suggestion なし、 `kolt --no-daemon build` (既知 flag) → 通常実行
  - 観察可能な完了: integration test 上記 3 ケース green、 既存 `--no-daemon` / `--watch` / `--release` / `--no-color` parsing が regression なし
  - _Requirements: 5.3, 5.4_
  - _Depends: 1.3, 2.2, 5.1_
  - _Boundary: kolt.cli.Main (parseKoltArgs unknown flag path)_

- [ ] 6. Subprocess color forwarding (env injection + daemon blob strip)
- [x] 6.1 (P) kotlinc subprocess (direct + JVM daemon spawn) への `NO_COLOR` env injection
  - `kolt.build.SubprocessCompilerBackend` の subprocess 起動 helper に `if (!ColorPolicy.current().shouldColor(Stream.Stderr)) put("NO_COLOR", "1")` を入れる。 caller (`BuildCommands` 等) は ColorPolicy を意識しない
  - JVM daemon の spawn 経路 (`kolt.build.daemon.*`) でも同じ env injection を入れる、 daemon 自体が起動する kotlinc 子プロセスにも env が継承されるよう確認
  - 既存 subprocess 起動経路の other env (`JAVA_HOME` 等) 設定を退化させないこと
  - テスト: env capture mock subprocess に対して、 `ColorPolicy.Never` 時に env 内 `NO_COLOR=1` が含まれること、 `ColorPolicy.Always` 時に含まれないこと
  - 観察可能な完了: unit test で env injection 動作確認、 self-host smoke で `kolt build --no-color` が JVM daemon path で動くこと
  - _Requirements: 6.2, 6.3_
  - _Depends: 1.2_
  - _Boundary: kolt.build.SubprocessCompilerBackend + kolt.build.daemon spawn_

- [x] 6.2 (P) konanc subprocess (direct + Native daemon spawn) への `NO_COLOR` env injection
  - `kolt.build.NativeSubprocessBackend` および `kolt.build.nativedaemon.*` の spawn 経路で同じ env injection
  - kotlin-result `Result<V, E>` chain を破らないように env 構築 helper を共通化 (kotlinc 経路と一貫させる)
  - テスト: env capture mock で `NO_COLOR=1` 注入確認 (kotlinc 経路と並列 fixture)
  - 観察可能な完了: unit test green、 self-host smoke で `kolt build --no-color` が native daemon path でも動く
  - _Requirements: 6.2, 6.3_
  - _Depends: 1.2_
  - _Boundary: kolt.build.NativeSubprocessBackend + kolt.build.nativedaemon spawn_

- [x] 6.3 daemon が string blob で返す stderr に対する ANSI strip wiring
  - `BuildCommands.kt:526` 周辺の `eprintln(body)` を `eprintln(if (!ColorPolicy.current().shouldColor(Stream.Stderr)) AnsiStripper.strip(body) else body)` に書き換え (forwarded body は kolt prefix を付けない pass-through 維持、 R6.1)
  - 同様の forward 経路が DependencyCommands / ToolCommands にあれば同 pattern を適用
  - テスト: `body = "error: \\x1B[31mfoo\\x1B[0m\\n"` を mock daemon が返す状況で、 `ColorPolicy.Never` 時は `eprintln` 引数が `"error: foo\\n"` (ANSI 削除済み)、 `ColorPolicy.Always` 時は元 byte 列のまま
  - 観察可能な完了: unit test green、 daemon 経路で kolt が wrap する headline は 1 つ + body は forwarded verbatim (R6.4) という ordering pattern を維持していることを assert
  - _Requirements: 6.1, 6.2, 6.3, 6.4_
  - _Depends: 1.3_
  - _Boundary: cli forward point at BuildCommands daemon body forwarding_

- [ ] 7. 残りの raw `eprintln("error/warning: ...")` 呼び出しの mass migration
- [ ] 7.1 (P) `BuildCommands.kt` の resolve / tool / config 以外の raw eprintln 呼び出しを `eprintError` / `eprintWarning` に置換
  - 対象: file IO error (`could not read`, `could not create directory`, `could not list Kotlin sources`, `could not copy resources` 等)、 generic `eprintln("error: ${it.message}")`、 `eprintln("warning: ...")` (clean / daemon IC state cleanup)
  - 各置換で headline はコロン以降のテキスト、 file path / cause 等は context list に分解できる場合は分解。 既存 1 行記述は headline のみで OK
  - 既存の build/check/run/test/clean 各経路で stderr 出力 byte が (色 off で) 旧文言と一致していることを既存 string-snapshot test で確認
  - 観察可能な完了: BuildCommands.kt 内に raw `eprintln("error:...` / `eprintln("warning:...` が残らないこと (`grep` で 0 件)、 `kolt test` 全体 green
  - _Requirements: 1.1, 1.2, 1.5, 1.6, 7.2_
  - _Depends: 1.3, 3.1, 4.4, 6.3_
  - _Boundary: kolt.cli.BuildCommands (raw eprintln sites — daemon forward line は 6.3 所有、 ConfigError display は 4.4 所有のため対象外)_

- [ ] 7.2 (P) `DependencyCommands.kt` の残り raw eprintln 呼び出しを置換
  - 対象: `kolt fetch` / `kolt update` / `kolt tree` / `kolt add` / `kolt remove` の error / warning 出力
  - 各置換で R6.4 (subprocess wrap pattern) を破壊しないよう注意 — daemon body forward + kolt headline の順序を 7.1 と同じ規則で維持
  - 既存 string-snapshot test の更新
  - 観察可能な完了: DependencyCommands.kt 内 raw `eprintln("error:...` 0 件、 dep 系コマンドの自動テスト green
  - _Requirements: 1.1, 1.2, 1.5, 1.6, 7.2_
  - _Depends: 1.3, 3.1, 4.4_
  - _Boundary: kolt.cli.DependencyCommands (raw eprintln sites)_

- [ ] 7.3 (P) `ToolCommands.kt` および残り cli 系ファイル (InfoCommand / ScaffoldIO / DaemonCommand / CacheCommand 等) の raw eprintln 呼び出しを置換
  - 各 file 内の raw `eprintln("error:...` / `eprintln("warning:...` を typed writer に migrate
  - `printUsage()` の help 出力はそのまま (情報表示、 severity 装飾不要 — informational `eprintln` はそのまま許容)
  - 観察可能な完了: 全 cli/* ファイルで raw `eprintln("error:...` / `eprintln("warning:...` 0 件、 `kolt info` / `kolt daemon` / `kolt cache` の自動テスト green
  - _Requirements: 1.1, 1.2, 1.5, 1.6, 7.2_
  - _Depends: 1.3, 3.2, 4.4_
  - _Boundary: kolt.cli (ToolCommands + InfoCommand + ScaffoldIO + DaemonCommand + CacheCommand) — ConfigError display sites は 4.4 所有のため再 migration しない_

- [ ] 8. Validation: integration / E2E pin
- [ ] 8.1 (P) `NO_COLOR` / `--no-color` / TTY-redirect の E2E smoke (cross-cutting)
  - `kolt --no-color build <synthetic-fail-fixture>` の stderr に ANSI escape (`\\x1B[`) が一切含まれないこと (R2.4)
  - `NO_COLOR=1 kolt build <fail>` で同上 (R2.3)
  - `kolt build <fail> 2>log.txt` (stderr redirect) で log.txt に ANSI 含まれないこと (R2.2)
  - `NO_COLOR=` (空文字列) は無視されること、 `NO_COLOR=any-non-empty` で抑制発火 (no-color.org 仕様準拠)
  - 観察可能な完了: 上記 4 ケースが integration test として CI で green、 self-host CI 経路 (`self-host-post`) でも 1 ケース通る
  - _Requirements: 2.2, 2.3, 2.4, 2.6_
  - _Depends: 5.1, 7.1_
  - _Boundary: end-to-end_

- [ ] 8.2 (P) 未知 subcommand / 未知 global flag の Did-you-mean E2E
  - `kolt buidl` → exit code `EXIT_COMMAND_NOT_FOUND`、 stderr に `error: unknown command 'buidl'` と `note: Did you mean \`build\`?` (色 off で文字列比較)
  - `kolt --no-clor build` → 未知 flag error + `Did you mean \`--no-color\`?`
  - `kolt totally-unrelated-xxx` → suggestion なし (距離超過)
  - 観察可能な完了: 3 ケース integration test green、 deterministic 出力 (R5.4) を assert
  - _Requirements: 5.2, 5.3, 5.4_
  - _Depends: 5.2, 5.3_
  - _Boundary: kolt.cli.Main (unknown subcommand / flag E2E)_

- [ ] 8.3 (P) `SubprocessColorForwardingIntegrationTest` — kotlinc / konanc が `NO_COLOR=1` を honor することの実機 pin (validate-design Issue 3 解決)
  - fixture: 故意に compile error を起こす `*.kt` を配置した tmp project
  - シナリオ 1: `NO_COLOR=1 kolt --no-daemon build` (kotlinc direct subprocess 経路) — stderr に `\\x1B[` 含まれない
  - シナリオ 2: `kolt --no-color --no-daemon build` (同上、 flag 経由)
  - シナリオ 3: `NO_COLOR=1 kolt build` (JVM daemon 経路) — stderr に ANSI 含まれない
  - シナリオ 4: native target で `NO_COLOR=1 kolt build` (konanc / native daemon 経路) — 同上
  - 観察可能な完了: 4 ケース integration test green、 Kotlin 2.3.x の `NO_COLOR` honor が pin される。 Kotlin / konanc bump 時にこのテストが RED 化したら post-strip fallback の検討 (Risks 表参照)
  - _Requirements: 6.2, 6.3_
  - _Depends: 5.1, 6.1, 6.2, 6.3_
  - _Boundary: end-to-end (kotlinc / konanc subprocess + daemon)_

- [ ] 8.4 (P) `kolt info --format=json` の no-ANSI 検証 (R7.1 pin)
  - `kolt info --format=json` を TTY 環境 (isatty stub or 実 TTY) で実行、 stdout 出力に ANSI escape が含まれないこと
  - `--no-color` / `NO_COLOR=1` / TTY-redirect の各組合せでも stdout JSON は変化しない (severity 系の writer が stdout に漏れない契約)
  - 観察可能な完了: integration test green、 JSON 出力が `JSON.parse` 可能で ANSI 0 件
  - _Requirements: 7.1, 7.2_
  - _Depends: 7.3_
  - _Boundary: kolt.cli.InfoCommand JSON output path_
