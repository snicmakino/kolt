# Gap Analysis: 341-tools-section

調査日: 2026-05-08

## Analysis Summary

- `[tools]` の **大半は既存 infrastructure の composition** で実現できる。 jar download/cache (`ensureTool`)、 process spawn + arg passthrough + exit code propagation (`executeCommand`)、 JDK 解決 (`ensureBootstrapJavaBin` / `ensureJdkBins`)、 lockfile schema (`classpathBundles` の nested Map precedent)、 SHA-256 verification (`ResolveError.Sha256Mismatch`) はすべて存在する
- **新規実装が必要な箇所は限定的**: schema parse + validation, alias 文法, lockfile への `toolsBundles` field 追加, Main-Class 検証経路、 cause-distinguishable な exit code mapping
- **package name の潜在衝突**: 既存の `kolt.tool` package は **toolchain provisioning** (kotlinc / JDK / konanc / 内部 jar の `ensureTool`) を担う。 `[tools]` config section の新規コードは別 package に置く必要がある (例: `kolt.toolsection` / `kolt.usertool`)。 命名は design.md で確定
- **ADR 0028 §3 凍結面の影響**: schema は additive-only。 alias 文法、 coords 形状、 lockfile format、 cache layout、 CLI surface はすべて ship で凍結。 後から narrow できないので、 design.md で一括で決定する
- **推奨 implementation 戦略**: Option C (Hybrid) — 既存の `BundleResolver` パターンを踏襲して `[classpaths]` と並ぶ第三の bundle カテゴリとして実装、 + alias-による起動経路と Main-Class 検証経路だけ新規。 effort M (3〜7 日)、 risk Low-Medium

## 1. Current State Investigation

### 1.1 設定 parse (`kolt.config`)

| 要素 | ファイル / 型 | 役割 |
|---|---|---|
| schema 中間型 | `Config.kt` `RawKoltConfig` | ktoml `@Serializable`、 `ignoreUnknownNames = true` |
| schema 公開型 | `Config.kt` `KoltConfig` | validated、 immutable |
| validation chain | `Config.kt` `validateKind` / `validateTarget` / `validateMainFqn` / `validateProjectRelativePath` / `validateBundleReferences` ほか | parse 後に直列で走る |
| 既存 nested table 例 | `[classpaths.<name>]` (Map) | `[tools.<alias>]` の precedent |
| 既存 path 計算 | `KoltPaths.kt` `cacheBase = "$home/.kolt/cache"`, `jdkPath`, `javaBin` | tools 専用 path もここに置く |

**新セクション追加で触るファイル**: `Config.kt` (`RawKoltConfig`, `KoltConfig`, validation 1 件追加), `KoltPaths.kt` (cache path)

### 1.2 依存解決と Maven coords (`kolt.resolve`)

| 要素 | 場所 | 注意点 |
|---|---|---|
| 座標型 | `Dependency.kt` `Coordinate(group, artifact, version)` | **classifier フィールドなし**。 classifier は `buildMavenUrl(..., classifier)` で URL builder に渡される |
| URL/path builder | `Dependency.kt` `buildMavenUrl`, `buildCachePath`, `buildSourcesCachePath` | classifier 別に関数分離している慣例 |
| 解決 kernel | `Resolution.kt` `fixpointResolve()` | childLookup callback 注入式。 children を空にすれば single-jar 解決 |
| transitive 解決 | `TransitiveResolver.kt` `resolveTransitive()` / `downloadFromRepositories()` / `materialize()` | repo iterate + 404 fallthrough、 download 後 SHA-256 verify |
| bundle 解決 | `BundleResolver.kt` `resolveBundle()` / `materialiseBundleJarsFromLock()` | bundle isolation の precedent |
| 失敗型 | `Resolver.kt` `ResolveError` enum (含む `Sha256Mismatch`) | 新たな失敗種は enum 拡張で済む |

**新セクション追加で触るファイル**: `Resolver.kt` (`buildLockfileFromResolved` に tool 経路追加), 新 file `ToolResolver.kt`、 もしくは `BundleResolver.kt` を再利用

### 1.3 lockfile (`kolt.lock`)

| 要素 | 場所 | 注意点 |
|---|---|---|
| 形式 | JSON (kotlinx.serialization)。 v4 (ADR 0005, redirect_target 追加) | 単一ファイル precedent |
| 公開型 | `Lockfile.kt` `Lockfile(version, kotlin, jvmTarget, dependencies, classpathBundles)` | nested Map (`Map<String, Map<String, LockEntry>>`) 既存 |
| entry 型 | `LockEntry(version, sha256, transitive=false, test=false, redirectTarget=null)` | tools 用に追加 field 必要なし |
| serialize | `LockfileJson` (private) + `serializeLockfile()` (alphabetic sort) | round-trip テスト前例あり |
| version mismatch | `LockfileError.UnsupportedVersion` | parse 前段で reject |

**選択肢**: (a) `Lockfile.classpathBundles` と並べて `toolsBundles: Map<String, Map<String, LockEntry>>` を additive 追加 (recommended、 single atomic write 維持)、 (b) 別ファイル `kolt-tools.lock` を新設 (concern 分離、 ただし atomic 性低下)

### 1.4 JDK 取り扱い (`kolt.tool`、 `kolt.build.daemon`)

| 要素 | 場所 | 役割 |
|---|---|---|
| toolchain JDK | `ToolchainManager.kt` `ensureJdkBins(version, paths): Result<JdkBins, ToolchainError>` | 任意 version の JDK を install/検証 |
| bootstrap JDK | `BootstrapJdk.kt` `BOOTSTRAP_JDK_VERSION = "25"` + `ensureBootstrapJavaBin(paths)` | daemon 起動用、 user 設定で override 不可 |
| JDK path 規則 | `KoltPaths.kt` `jdkPath(version) = "$toolchainsDir/jdk/$version"`, `javaBin(version)` | ADR 0017 §1 |

**結論**: `[tools]` の launch JDK 既定を bootstrap JDK にする場合、 `ensureBootstrapJavaBin` を流用するだけで済む (新規実装ゼロ)。 「ツールごとに別 JDK version を要求できる」を v1 で許すなら schema 拡張 + `ensureJdkBins(version, ...)` 流用

### 1.5 既存の jar 実行経路 (`kolt.tool` の `ToolManager`、 `kolt.build`、 `kolt.infra`)

| 要素 | 場所 | 役割 |
|---|---|---|
| jar download + cache | `ToolManager.kt` `ensureTool(paths, spec): Result<String, String>` | jar を Maven Central から取得して `~/.kolt/tools/<filename>` に置く。 path を返す |
| 既存 spec 例 | `ToolManager.kt` `KTFMT_SPEC`, `CONSOLE_LAUNCHER_SPEC` | runnable jar の established precedent |
| jar 起動 | `Process.kt` `executeCommand(args, extraEnv): Result<Int, ProcessError>` | fork/execvp、 引数 verbatim、 exit code そのまま返す |
| 起動例 | `Formatter.kt` `formatCommand(...)` (ktfmt)、 `TestRunner.kt` `testRunCommand(...)` (junit) | `java [-D...] -jar <path> <args...>` の組み立て precedent |
| daemon jar 起動 | `NativeDaemonBackend.kt` `java -cp <classpath> <Main> --socket <path>` | 別経路 (classpath 起動) — `[tools]` には不要 |
| process error 型 | `Process.kt` `ProcessError` sealed (`EmptyArgs / ForkFailed / WaitFailed / NonZeroExit / SignalKilled / PopenFailed`) | exit code propagation の足場 |

**結論**: `ensureTool` + `executeCommand` の組み合わせで、 `[tools]` の launch 部分は ktfmt / junit と同じ既存パターンに完全一致できる

### 1.6 CLI surface dispatch (`kolt.cli`)

| 要素 | 場所 | 役割 |
|---|---|---|
| dispatch | `Main.kt` `when(filteredArgs[0]) { ... }` (15+ subcommand) | 新 subcommand は case 1 件 + 関数 1 件 |
| arg parser | `Main.kt` `parseKoltArgs(argList)` | global flag + `--` 後 verbatim passthrough |
| nested subcommand pattern | `doToolchain(args)` (`install/list/remove`)、 `doDaemon(args)` (`stop/reap`) | `kolt tool run <alias>` パターンの precedent |
| separate binary precedent | `kolt-jvm-compiler-daemon` / `kolt-native-compiler-daemon` (sidecars) | user-facing binary としての precedent はない |

**結論**: subcommand 案 (`kolt tool run ...` / 新 top-level `kolt tool ...`) は既存パターンと natural fit。 separate binary 案 (`kolt-x`) は user-facing には前例ゼロ — 採るなら新規 build target を kolt-toml 自己ホストで定義する必要があり、 release tarball の packaging (ADR 0018) と install.sh も拡張必要

## 2. Requirements Feasibility Analysis

### 2.1 Requirement → Asset Map

| Req | 必要な技術要素 | 既存資産 | gap tag |
|---|---|---|---|
| R1.1 (`[tools]` parse) | ktoml deserialize の Map field | `RawKoltConfig` の `[classpaths]` field 形 | **Reusable** |
| R1.2 (coords 形状) | `group:artifact:version[:classifier]` parse | `parseCoordinate(...)` (classifier なし) | **Constraint**: 既存 `Coordinate` に classifier がない。 design で「coords 文字列に classifier を含めるか / 別 field か」を決める必要 |
| R1.3 (alias 文法) | regex validation | `validateMainFqn` / `validateBundleReferences` の precedent | **Missing**: alias 専用 validator は新規。 regex は design で確定 |
| R1.4 (coords 不正 reject) | parse 後 reject | `Config.kt` validation chain | **Reusable** |
| R1.5 (alias 重複 reject) | duplicate key 検知 | ktoml の重複キー → `SerializationException` | **Reusable** (ktoml の動作に依存) |
| R2.1 (alias 起動 + arg verbatim) | subcommand dispatch + `executeCommand` | `Main.kt` + `executeCommand` | **Reusable** |
| R2.2 (exit code propagation) | `executeCommand` の戻り | `ProcessError.NonZeroExit(exitCode)` | **Reusable** |
| R2.3 (未知 alias) | dispatch 内 alias lookup | 新規 (`KoltConfig.tools` map lookup) | **Missing (trivial)** |
| R2.4 (app classpath 非汚染) | tool jar を `[dependencies]` 経路に通さない | `BundleResolver` の isolation pattern | **Reusable** |
| R3.1 (初回 fetch + cache 格納) | jar download + path 返却 | `ensureTool(paths, spec)` | **Reusable** (ただし alias-名で path 計算する必要 — design に依存) |
| R3.2 (cache hit でネット skip) | 既存 cache existence check | `ensureTool` 内に既存 | **Reusable** |
| R3.3 (integrity check 失敗 → 停止) | SHA-256 verify | `ResolveError.Sha256Mismatch` | **Reusable** |
| R3.4 (取得不能 → 停止) | repo download fail | `downloadFromRepositories` | **Reusable** |
| R4.1 (lockfile 記録) | `Lockfile` 拡張 | `classpathBundles` の precedent | **Reusable** + extension |
| R4.2 (lockfile pin 優先) | lockfile load → resolved 上書き | bundle 経路の `materialiseBundleJarsFromLock` | **Reusable** |
| R4.3 (toml と lock 矛盾 → loud) | mismatch detection | `Resolver` の lockChanged compute | **Constraint**: 既存はサイレント overwrite。 `[tools]` は loud reject (新挙動) — design で `kolt update` 等の指示文言を確定 |
| R4.4 (再現情報) | `LockEntry` の version + sha256 | 既存 entry にあり | **Reusable** |
| R5.1 (Main-Class で起動) | `java -jar <path>` で JVM が manifest 読む | `executeCommand` で実現 | **Reusable** |
| R5.2 (Main-Class 不在 reject) | 起動失敗 detect | JVM が exit code 1 + stderr「no main manifest attribute」を出す | **Missing**: kolt 側でこれを認識して cause-distinguishable に分類するロジック新規 (pre-launch で manifest を読むか、 post-launch で stderr / exit code を classify するか — design で決定) |
| R5.3 (非 runnable 形 reject) | distribution zip / launcher script | 現状なし | **Missing**: 形検出ロジック新規 (pre-launch で magic-byte / extension check) |
| R5.4 (cause-distinguishable surface) | 失敗種の分類 | `ProcessError` + `ResolveError` enum 拡張 | **Reusable** + extension |
| R6.1 (JDK 決定論) | bootstrap JDK fallback | `ensureBootstrapJavaBin` | **Reusable**、 ただし「ツールごとに別 JDK」を v1 で許すかは design 判断 |
| R6.2 (JDK 不在 → loud) | 失敗 surface | `ToolchainError` + `BootstrapJdkInstallFailed` | **Reusable** |
| R6.3 (JDK 出所の事後確認) | log / verbose 出力 | 既存 daemon 経路で precedent あり | **Reusable** |
| R7.1〜7.2 (orchestration field reject) | 未知 field の strict reject | 現状 ktoml は `ignoreUnknownNames=true` | **Constraint**: 全体 default が「ignore unknown」なので、 `[tools]` 配下だけ strict にする必要。 design で「専用 strict deserializer」を組むか、 「validation 段階で blacklist field を明示 reject」するか決定 |
| R7.3 (subcommand 自動生成しない) | dispatch 設計 | 単に実装しないだけ | **Trivial** |
| R7.4 (lifecycle 自動連携しない) | build/test 経路から外す | 単に実装しないだけ | **Trivial** |

### 2.2 既存 codebase との非自明な constraint

- **`Coordinate` に classifier フィールドがない** — coords 文字列に classifier を含める設計 (`group:artifact:version:classifier`) を採るなら、 schema 表面と内部型の不一致を design で documented (parser が string → `(Coordinate, classifier?)` に分解) する必要
- **既存 `~/.kolt/tools/` flat layout** — `ensureTool` は `~/.kolt/tools/<filename>` (filename はツール固有)。 `[tools]` がそのまま使うと、 ktfmt / junit-console と user tool の path collision が起き得る。 design で `~/.kolt/tools/<alias>/<version>/` のような nested layout に切るか、 flat 維持で命名衝突を許さない alias 文法を pin するか決定
- **ktoml `ignoreUnknownNames = true`** が global default — `[tools]` の orchestration ガードレール (R7.1, R7.2) をどう実装するか。 専用 strict serializer か、 validation で blacklist 列挙か、 design 判断
- **lockfile silent overwrite vs loud** — 既存 `[dependencies]` は toml の宣言と lockfile の差分を silent に取り込む。 `[tools]` で R4.3 を loud にするには新挙動。 design で `[tools]` の「lock 出力モード」をどう trigger するか (例: 専用 `kolt update` flag vs 自動再解決) を確定

### 2.3 Research Needed (design 段階で深掘り)

- **Main-Class 検証の実装手段**: jar (=zip) から `META-INF/MANIFEST.MF` を読む native 経路 (libarchive cinterop は ADR 0031 で導入済 — extraction だけでなく zip read API があるか) vs JVM 起動して exit code/stderr 分類
- **alias 文法 regex** の妥当性 — Cargo の crate name 規則 (`[a-zA-Z][a-zA-Z0-9_-]*`)、 npm package 名規則あたりを比較し、 pinned regex を design で決める
- **CLI surface の `--`-passthrough 方針** — `kolt tool run ktlint --reporter=plain` を `--` 必須にするか optional にするか。 既存 `doTest` の `--` 区切り precedent との整合
- **`KOLT_TOOL_ARGS` の env 透過** — `executeCommand(args, extraEnv)` の `extraEnv` をどこまで埋めるか (project-root, build-dir 等を `[hooks]` 系で渡す既存議論 #119 と整合させる)
- **fat-jar tool で transitives を skip するか / main-classifier だけ取るか** — `fixpointResolve` の childLookup を空 callback にする vs 別 path、 影響範囲は `TransitiveResolver`

## 3. Implementation Approach Options

### Option A: 既存 component を最大限拡張 (BundleResolver 全乗り)

**やり方**: `[tools]` を `[classpaths]` の第三カテゴリとして扱う。 `BundleResolver.resolveBundle()` の childLookup を「transitive を取らない (children=[])」にして tool 専用 entry point を `BundleResolver.kt` 内に追加。 lockfile は `Lockfile.classpathBundles` と並ぶ `toolsBundles` を additive 追加。 CLI は `Main.kt` に `tool` case + `doTool(args)` 関数 1 件追加。 launch は `ensureTool` のジェネリック化 + `executeCommand` 流用

**変更ファイル**:
- `kolt.config.Config.kt` (RawKoltConfig + KoltConfig + validation)
- `kolt.resolve.BundleResolver.kt` (tool resolver entry point 追加 + transitive=false mode)
- `kolt.resolve.Lockfile.kt` (`toolsBundles` field 追加)
- `kolt.tool.ToolManager.kt` (`ensureTool` を spec ジェネリックに)
- `kolt.cli.Main.kt` (`tool` case 追加) + 新 file `ToolCommands.kt`
- `kolt.config.KoltPaths.kt` (tool cache path)

**Trade-offs**:
- ✅ 新 file 最少 (1〜2)、 既存 test 資産再利用 (round-trip lockfile test の precedent)
- ✅ ADR 上は既存 §3 凍結面の **拡張** で済み、 新セクションが少ない
- ❌ `BundleResolver` が「app classpath bundle (`[classpaths]`)」「user tool bundle (`[tools]`)」の双方を担うことで責務肥大、 single-responsibility が薄れる
- ❌ `[tools]` の orchestration-not-allowed 性 (R7) と `[classpaths]` の自由形 schema が同居して読みにくい

### Option B: 新 component を別 package で分離

**やり方**: 新 package `kolt.toolsection`/`kolt.usertool` を作り、 schema parse / resolve / launch を全て新規。 `kolt.tool` (toolchain) と命名衝突を避ける。 lockfile は別ファイル `kolt-tools.lock` を新設

**変更ファイル**:
- 既存変更は最小: `kolt.config.Config.kt` (`tools` field 追加だけ)、 `kolt.cli.Main.kt` (case 追加)
- 新規 multiple files: `kolt.toolsection.{ToolSection.kt, ToolResolver.kt, ToolLauncher.kt, ToolsLockfile.kt}` 等

**Trade-offs**:
- ✅ 責務分離が明確、 R7 の orchestration-not-allowed 境界をパッケージ境界で表現
- ✅ test isolation が高い
- ❌ 新規ファイル多 (5〜6)、 既存 `BundleResolver` / `Lockfile` の round-trip test 資産が再利用しづらい
- ❌ 別 lockfile (`kolt-tools.lock`) は atomic 性低下、 `kolt update` の意味論が複雑化、 ADR 0028 §3 の lockfile-format-stable 凍結面を **2 ファイル** に拡張する負担 (面が増えるとデフォ deprecation budget も増える)

### Option C: ハイブリッド (推奨)

**やり方**:
- **Schema / lockfile / cache layout** は **既存 infrastructure 拡張** (Option A 寄り):
  - `RawKoltConfig.tools` を additive 追加
  - `Lockfile.toolsBundles` を additive 追加 (single file 維持)
  - cache は `~/.kolt/tools/<alias>/<version>/<artifact>-<version>[-<classifier>].jar` で nested に切る (既存の `~/.kolt/cache/` と user `~/.kolt/tools/` の慣例を踏襲しつつ衝突回避)
- **CLI dispatch / Main-Class 検証 / orchestration ガードレール** は **新規ファイル** (Option B 寄り) — 別 package `kolt.usertool` (仮)
  - `ToolCommands.kt` (CLI dispatch)
  - `ToolLauncher.kt` (Main-Class 検証 + java -jar 起動 + JDK 解決 wiring)
  - `ToolValidation.kt` (alias regex、 orchestration field の blacklist reject)
- **解決経路** は `BundleResolver.resolveBundle()` を transitive=false モードで呼ぶ (もしくは `TransitiveResolver` の薄い wrapper を新規作る) — design で確定
- **launch JDK** は v1 では bootstrap JDK 固定 (`ensureBootstrapJavaBin` 流用、 schema 拡張なし)。 ツールごとの JDK pin は v1.1 以降の additive に倒す

**Trade-offs**:
- ✅ schema / lockfile / cache の凍結面は既存パターンに乗り、 ADR 0028 §3 への新セクション追加が最小
- ✅ `[tools]` 固有の振る舞い (Main-Class 検証、 orchestration reject、 user-facing CLI surface) は新 package で隔離 — R7 の境界をコードで表現できる
- ✅ effort は M (3〜7 日)、 risk Low-Medium
- ❌ 「拡張」と「新規」の境界線が design で正確に引かれていないと、 担当が曖昧になる

## 4. Effort & Risk

| 領域 | Effort | 備考 |
|---|---|---|
| Schema parse + alias validation (R1, R7.1〜7.2) | S (1〜2 日) | ktoml + 既存 validation chain にのる。 strict mode の orchestration reject だけ新規 |
| Resolve + cache (R3) | S-M (2〜3 日) | `BundleResolver` の transitive-skip variant を作る or 既存に flag。 cache layout 設計込み |
| Lockfile pin + 矛盾検知 (R4) | S (1〜2 日) | `toolsBundles` field 追加 + bundle 経路の lockChanged 流用、 R4.3 (loud reject) は新挙動 |
| Launch + Main-Class 検証 (R2, R5) | M (2〜3 日) | `executeCommand` 流用は trivial だが、 R5.2/R5.3 の cause-distinguishable 分類が新規。 jar manifest pre-read か post-launch classify かの design 判断次第 |
| JDK 経路 (R6) | S (0.5〜1 日) | bootstrap JDK 流用なら最小。 ツール JDK pin を v1 で許すなら + S |
| CLI dispatch + help (R2.1, R7.3) | S (0.5〜1 日) | `Main.kt` case 追加 + 新 `doTool` 関数 |
| Test (round-trip lockfile, parse rejection, e2e launch) | S-M (2〜3 日) | nativeTest 既存パターン踏襲 |
| ADR 起草 + §3 update | S (1 日) | design.md と並行 |

**Total: M (5〜10 日)**

**Risk: Low-Medium**

- Low 側: 既存 infrastructure (download / cache / SHA-256 / launch / lockfile) が established で、 Kotlin/Native 上での動作実績あり
- Medium 側:
  - Main-Class 検証経路の design 選択が未確定 (pre-read vs post-classify) — 実装複雑度に直接影響
  - ADR 0028 §3 凍結面に **同時に複数追加** (toml schema + CLI surface + lockfile field + cache layout) — 1 つでも narrow が必要になると post-v1 で痛い、 design.md で全部きっちり pin する必要
  - alias 文法と既存 `[classpaths]` / `[dependencies]` 名と衝突しないかは grep + spike 必要

## 5. Recommendations for Design Phase

### 5.1 Preferred approach
- **Option C (Hybrid)** で進める。 既存 infra は拡張、 新挙動 (Main-Class 検証、 orchestration reject、 user CLI surface) は別 package に隔離

### 5.2 Key decisions to lock in design.md (= ADR-equivalent, ADR 0028 §3 凍結対象)

1. **CLI surface**: `kolt tool run <alias> [args]` (subcommand、 既存 `doTest` の `--`-passthrough と整合) を **強く推奨**。 separate binary は user-facing precedent ゼロでコスト高
2. **Coords shape**: `group:artifact:version[:classifier]` 文字列形 (issue body のまま) を採用、 内部 parse で `(Coordinate, classifier?)` に分解。 schema 表面は単純維持
3. **Lockfile**: 既存 `kolt.lock` に additive (`toolsBundles: Map<String, Map<String, LockEntry>>`) — `classpathBundles` precedent
4. **Alias regex**: `^[a-zA-Z][a-zA-Z0-9_-]{0,63}$` あたりが Cargo precedent と整合 — design で正式 pin
5. **Launch JDK**: v1 は bootstrap JDK 固定 (schema 拡張なし)。 ツールごと JDK pin は v1.1 以降に follow-up
6. **Cache layout**: `~/.kolt/tools/<alias>/<version>/<filename>` nested layout — 既存 `~/.kolt/tools/` flat (kolt-internal 用) と区別しつつ衝突を避ける
7. **Failure exit codes**: 既存 `ProcessError` / `ResolveError` を流用しつつ、 user-facing には 3 段階くらいで分類 (resolve fail / Main-Class issue / launch fail) — `install.sh` 粒度に揃える
8. **§3 update**: 上記 1〜7 を全部 §3 凍結面に組み込む単一 ADR 改訂 (新 ADR ではなく ADR 0028 §3 拡張) を design.md で起草

### 5.3 Research items to carry forward (design 中に深掘り)

- jar の `META-INF/MANIFEST.MF` を Kotlin/Native から読む手段 (libarchive cinterop が zip read をサポートするか / `unzip -p` shell out をどう廃するか / 単に JVM 起動の exit code + stderr で post-classify するか)
- ktoml で「ある table 配下だけ unknown field を strict reject する」モードの組み立て方 (config-level override が可能か、 専用 deserializer 必要か)
- `kolt tool run` 経路で `[tools]` 未宣言 alias を呼ぶときの error message と類似 alias 提案 (既存 `kolt fmt --check` 等の precedent と整合)
- 既存 `kolt.tool` package 命名と衝突しない新 package 名 (`kolt.usertool` / `kolt.toolsection` / `kolt.runtool` 候補) — 命名は design で確定、 影響は小
