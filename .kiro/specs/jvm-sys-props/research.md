# Research & Design Decisions — jvm-sys-props

## Summary

- **Feature**: `jvm-sys-props`
- **Discovery Scope**: Extension (kolt config / resolver / lockfile / test+run runner に新 schema を縦断追加)
- **Key Findings**:
  - 既存の `parseConfig` 二段階パース (`RawKoltConfig` → `KoltConfig`) パターンが新 schema に再利用可能。validator も `validateKind`/`validateTarget` の追加で対応できる。
  - 既存 lockfile は version 3、`LockEntry(version, sha256, transitive, test)` 構造。bundle の追加は version 4 への bump が自然 (no-backcompat-pre-v1 ポリシー適用)。
  - sysprop value の polymorphism (`"literal" | { classpath = ... } | { project_dir = ... }`) は ktoml の serializer 表面で扱う必要があり、custom `KSerializer<SysPropValue>` が build 対象になる。

## Research Log

### Topic: Existing config parsing flow

- **Context**: 新 schema の追加先と validator の組み込み点を確定する
- **Sources Consulted**: `src/nativeMain/kotlin/kolt/config/Config.kt`
- **Findings**:
  - `parseConfig(tomlString)` は `RawKoltConfig` (`target`/`main` などが optional な緩い shape) を decode → `validateKind` → `lib`/`app` × `main` 整合 → `validateMainFqn` → `resolveEffectiveTarget` → `validateTarget` → 最終的に validated `KoltConfig` を組み立てる
  - エラーは `ConfigError.ParseFailed(message: String)` として `Result<KoltConfig, ConfigError>` で返る
  - ktoml は `Toml.decodeFromString(serializer, str)` で kotlinx.serialization の generated serializer を使う形で運用されている。AST 直触りは現時点で皆無
  - ktoml は inline table を `Map<String, V>` または `@Serializable data class` に decode できる。ただし「string OR inline table」のような sum 型は直接サポートされていない
- **Implications**:
  - 新 section の追加は `RawKoltConfig` フィールド追加 → 専用 validator → `KoltConfig` 投影、で既存 pattern に乗る
  - sysprop value の sum 型は custom `KSerializer<SysPropValue>` で吸収する必要あり (build, see Decision: SysPropValue polymorphic decoding)

### Topic: Dependency resolution & lockfile

- **Context**: classpath bundle が main / test と並ぶ第一級依存集合となるため、resolver と lockfile への接続点を握る
- **Sources Consulted**: `src/nativeMain/kotlin/kolt/resolve/{Resolver.kt,Lockfile.kt,Dependency.kt}`, `src/nativeMain/kotlin/kolt/cli/{DependencyCommands.kt,DependencyResolution.kt}`
- **Findings**:
  - lockfile schema: `version=3`, `kotlin`, `jvmTarget`, `dependencies: Map<String, LockEntry>`. `LockEntry(version, sha256, transitive, test)`。`test: Boolean` で main/test を区別する 1-bit 表現
  - **resolver kernel は main/test を独立 graph として解いていない**。`fixpointResolve` は `mainSeeds` と `testSeeds` を 1 つの effective graph に投入し、 各 GA に `(fromMain, fromTest)` の origin bit を accumulate する **overlay 構造**。version conflict resolution は GA 全域で global (`strictPins` / `rejects` が scope を跨いで作用)、 main classpath / test classpath への分割は materialize 後段で `Origin` 値の filter として行われる
  - これにより、 当初想定していた「seeds を N 系列化するだけで bundle 隔離可能」は **誤り**。 bundle を main/test の overlay graph に乗せると Req 4.5 が破れる (例: bundle が要求する `foo:1.0` と main の `foo:2.0` が strictPin で conflict 化、 または bundle の transitive が main の Origin filter を経由して main classpath に混入)
  - `resolveDependencies(config) -> Result<JvmResolutionOutcome, Int>` は CLI dispatch の最終層。`JvmResolutionOutcome(mainClasspath, mainJars, allJars)` を返す
  - `kolt deps install` / `update` / `tree` の入口は `DependencyCommands.kt` 内の `doInstall` / `doUpdate` / `doTree`
  - `buildClasspath(paths) -> String` (`Dependency.kt:70`) が colon-join 担当。bundle classpath にも再利用できる
- **Implications**:
  - lockfile を version 4 に bump し `classpathBundles: Map<String, Map<String, LockEntry>>` を追加 (これは当初設計通り)
  - resolver は **bundle ごとに独立 pass** を走らせる Option C を採用。 既存 `resolve()` (main/test) は触らず、 `resolveBundle()` を新関数として追加。 各 pass が独立 graph (= 独立 `fixpointResolve` 呼び出し) のため、 strictPin / rejects / transitive が他 scope に leak しない
  - `deps tree` 出力は bundle ごとのセクションを足すだけで済む

### Topic: Test / run JVM 起動の argv 組み立て箇所

- **Context**: `-D<key>=<value>` を挿入する場所を特定する
- **Sources Consulted**: `src/nativeMain/kotlin/kolt/build/{TestRunner.kt,Runner.kt}`, `src/nativeMain/kotlin/kolt/cli/BuildCommands.kt`
- **Findings**:
  - test JVM: `testRunCommand(...)` (`TestRunner.kt:5`) が `java -jar <consoleLauncher> --class-path <cp> --scan-class-path <testArgs>` を組む。`testArgs` の前に `-D` を挟む位置がある
  - run JVM: `runCommand(...)` (`Runner.kt:11`) が `java -cp <cp> <main> <appArgs>` を組む
  - `doTestInner` / `doRunInner` (`BuildCommands.kt`) が config と resolution outcome から argv を構成して `executeCommand` に渡す
- **Implications**:
  - `testRunCommand` / `runCommand` のシグネチャに `sysProps: List<Pair<String, String>>` を増やし、argv 先頭側 (java の直後、`-jar`/`-cp` の前) で `-D<k>=<v>` を unfurl
  - sysprop の resolved 値計算は別 module (`SysPropResolver`) に切り出して、runner は文字列 list を受け取るだけにすると test しやすい

### Topic: Path utilities & traversal validation

- **Context**: `{ project_dir = "..." }` の値検証 (絶対パス拒否、`..` 脱出拒否) の実装場所
- **Sources Consulted**: `src/nativeMain/kotlin/kolt/infra/FileSystem.kt`
- **Findings**:
  - `currentWorkingDirectory()` (`FileSystem.kt:420`) と `absolutise(path, cwd)` (`FileSystem.kt:426`) が存在
  - `absolutise` は `..`/`.`/symlink を解決しない (コメント明示)
  - パストラバーサル検証ヘルパーは皆無 — 新規 helper が必要
- **Implications**:
  - `validateProjectRelativePath(rel: String): Result<Unit, ConfigError>` を `Config.kt` に新設。 `rel` の絶対パス判定 (`startsWith("/")`) と segment 分解後の `..` 累積 depth 検査を行う
  - 解決時の絶対化は既存 `absolutise` を再利用できる

## Architecture Pattern Evaluation

| Option | Description | Strengths | Risks / Limitations | Notes |
|--------|-------------|-----------|---------------------|-------|
| Polymorphic SysPropValue (sealed class + custom KSerializer) | `"literal" | { classpath = "X" } | { project_dir = "Y" }` を sum 型として decode | 認可済 requirement (Req 2.1) に最も忠実、 authoring が自然 | ktoml の decoder で string vs inline table を switch する serializer を build する必要あり (前例なし) | 採用。実装段階で ktoml が壁なら fallback (下) に縮退 |
| Uniform inline-table (`{ literal = "..." }` のみ) | 全 value を inline table に統一、bare string 不可 | parser がほぼ無料、kotlinx.serialization の generated serializer で済む | requirement 改訂、 authoring が冗長 | fallback 候補。実装時の判断で再評価 |
| Multi-table split (`[test.sys_props_classpaths]` 等) | value 型ごとに table を分離 | parser が trivial | TOML 表面が肥大、value 型追加で table が増殖 | 不採用 (schema 美観を犠牲にする) |

| Option | Description | Strengths | Risks / Limitations | Notes |
|--------|-------------|-----------|---------------------|-------|
| Lockfile v4: `classpathBundles: Map<String, Map<String, LockEntry>>` | bundle ごとに sub-map を増やす | 読み書きが scope で完全分離、 既存 `dependencies`/`test=true` フィールドへの干渉ゼロ | version 4 への bump が必要 (no-backcompat-pre-v1 ポリシーで許容) | 採用 |
| Lockfile flat: `LockEntry.scopes: List<String>` | 各 entry に「自分が属する scope 群」を持たせる | 同一 GAV+version の 1 物理 entry で複数 scope を表現 | scope 列挙のたびに全 entry を走査、 main/test の test:Boolean とのマイグレーション複雑化 | 不採用 |

**Resolver pass 構造**:

| Option | Description | Strengths | Risks / Limitations | Notes |
|--------|-------------|-----------|---------------------|-------|
| A: 既存 `resolve()` を seeds N 系列化 | main / test / 各 bundle を 1 つの graph に投入し origin bit を N 種に拡張 | 単一 pass でメタデータ download も最小化 | 既存 graph の strictPin / rejects / version conflict が global で走るため bundle 隔離 (Req 4.5) が型レベルで保証されない。 origin bit を N 種に拡張する resolver 内部の重い改造が必要 | 不採用 (Req 4.5 の型レベル保証が得られない) |
| B: bundle を main/test の上乗せ overlay として既存 graph に追加 | 既存 fromMain/fromTest と同じ pattern | 改造規模が小さい | A と同じ理由で隔離が破れる | 不採用 |
| C: bundle ごとに独立 `fixpointResolve` pass | 各 bundle pass が独立 graph で走り、 main/test pass の state を一切共有しない | 隔離が関数 contract で保証 (bundle pass は main/test の引数を受けないため leak 不可能)、 既存 main/test の挙動は完全に不変 | 共通 transitive (`kotlin-stdlib` 等) のメタデータ取得が pass ごとに走る (cache hit があれば I/O は最小、 計算は重複) | **採用** |

**Migration UX**:

| Option | Description | Strengths | Risks / Limitations | Notes |
|--------|-------------|-----------|---------------------|-------|
| A: 全コマンドで v3 を自動 overwrite | `kolt build` でも勝手に v4 化 | user 操作不要 | build path が暗黙に network resolve を引き起こす (既存ポリシー違反)。 silent な状態変化 | 不採用 |
| B: `kolt deps install` だけ v3 を自動 overwrite (warning 出力)、 他コマンドは error | 上書きの起点を 1 コマンドに集中 | build path の純粋性を維持、 user は意識的に install を打つ | install を 1 度実行する手間 | **採用** |
| C: 全コマンドが error、 `rm kolt.lock` を release note 案内 | 最小実装 | 複数 lockfile を持つ repo (kolt 自身) の dogfood で詰まる。 git 操作との整合がぎこちない | 採用しない | 不採用 |

## Design Decisions

### Decision: SysPropValue polymorphic decoding

- **Context**: Req 2.1 が 3 形態の value (`string` / `{ classpath = ... }` / `{ project_dir = ... }`) を要求。ktoml の generated serializer は sum 型を直接扱えない
- **Alternatives Considered**:
  1. Uniform inline-table 化 (要 requirement 改訂)
  2. 複数 table 分離 (TOML 表面が肥大)
  3. Custom `KSerializer<SysPropValue>` 構築
- **Selected Approach**: 3 (custom serializer)。`SysPropValue` を sealed class (`Literal(value)` / `ClasspathRef(bundle)` / `ProjectDir(path)`) として定義し、`KSerializer` 実装は decoder の入力 shape (string か structure か) で dispatch する
- **Rationale**: requirement に忠実、authoring 体験が良い、 sum 型を native に表現できるため後段 validator が型安全
- **Trade-offs**: ktoml の decoder API を一部 reflection 的に触る可能性があり、 ktoml バージョン更新時の脆弱性が増える
- **Follow-up**: 実装の最初の task で ktoml 上での decode を probe し、 もし decoder が string/structure dispatch を支援できないと判明した場合は uniform inline-table へ fallback (requirement 改訂)

### Decision: Lockfile schema v4 with `classpathBundles`

- **Context**: bundle dep が `kolt.lock` に persist される必要 (Req 4.1)、 bundle 間 transitive の隔離 (Req 4.5) を表現する必要
- **Alternatives Considered**:
  1. `LockEntry.scopes: List<String>` で 1 entry 多 scope
  2. Lockfile に `classpathBundles: Map<String, Map<String, LockEntry>>` を新設
- **Selected Approach**: 2。version 3 → 4 bump、 既存 `dependencies` / `test:Boolean` をそのまま残し sub-map を増設
- **Rationale**: scope 隔離が schema レベルで自然に守られる。既存 main/test logic に干渉しない
- **Trade-offs**: 同一 GAV+version が複数 bundle に存在する場合 lockfile 上で重複登場する (cache の jar はファイル単位で 1 物理コピー、disk overhead は無視可)
- **Follow-up**: v3 lockfile を見つけた場合の挙動は次の Decision (Migration UX) で扱う

### Decision: Resolver pass 構造 — Option C (bundle ごとの独立 pass)

- **Context**: 既存 resolver は `fromMain`/`fromTest` overlay を 1 graph で解く構造。 bundle を同居させると Req 4.5 (bundle 間 transitive 隔離) が型レベルで守れない
- **Alternatives Considered**:
  1. A: 既存 `resolve()` を N seed 系列化 (main + test + N bundles を 1 graph)
  2. B: bundle を main/test に上乗せ
  3. C: bundle ごとに独立 `fixpointResolve` pass を走らせる
- **Selected Approach**: C。 既存 `resolve()` (main/test) は触らず、 `resolveBundle(config, bundleName, bundleSeeds, ...)` を新関数として追加。 各 bundle pass は `fixpointResolve(mainSeeds = bundleSeeds, testSeeds = emptyMap(), ...)` で独立 graph を構築
- **Rationale**: bundle pass が main/test pass の state を **引数として受け取らない** ため、 関数 contract レベルで leak が不可能。 strictPin / rejects も bundle 内ローカル。 既存 main/test の挙動は完全に不変
- **Trade-offs**: 共通 transitive (`kotlin-stdlib` 等) のメタデータ取得が pass ごとに走る。 cache hit があれば実 I/O は最小だが、 POM 解析等の CPU コストは重複する。 5+ bundle が普通になったら最適化を検討
- **Follow-up**: `BundleResolverIsolationTest` を実装初期に Red で書き、 transitive が一方の bundle にしか存在しないシナリオで他 scope への leak が無いことを構造的に検証

### Decision: Migration UX — Option B (`kolt deps install` のみ自動 overwrite)

- **Context**: lockfile v3 → v4 への切り替えをどう user に届けるか。 kolt 自身が複数の `kolt.lock` を持つ dogfood 対象であり、 `rm` 案内のみは UX として粗い (review #2)
- **Alternatives Considered**:
  1. A: 全コマンドが暗黙に v3 → v4 上書き
  2. B: `kolt deps install` だけ v3 検出時に warning 出して上書き、 他コマンド (`kolt build` / `test` / `run`) は明確 error で停止
  3. C: 全コマンドが error、 `rm kolt.lock` 手順を release note で案内
- **Selected Approach**: B
- **Rationale**: build path が暗黙の network resolve を引き起こさないという既存ポリシーを維持しつつ、 user は `kolt deps install` という意識的なコマンドで lockfile を最新版に揃えられる。 dogfood 時は repo 内の全 `kolt.lock` を 1 PR で再生成する流れに整合
- **Trade-offs**: A より明示的なステップが 1 つ増える。 C より実装コードが増える (v3 detection 分岐 + warning 出力)
- **Follow-up**: `LockfileV3MigrationTest` で warning 出力と上書き挙動、 build 系での停止挙動を検証

### Decision: SysProp resolution as a separate module

- **Context**: doTestInner / doRunInner で sysprop の resolved 値を計算する責務を runner argv builder から分離するか
- **Alternatives Considered**:
  1. `testRunCommand` / `runCommand` 内に inline で計算
  2. `SysPropResolver` 専用 module を切り出し、 runner には `List<Pair<String, String>>` を渡す
- **Selected Approach**: 2
- **Rationale**: resolver が classpath bundle resolution outcome / project root / config のみに依存する pure function になるため unit test しやすい。runner は string list を appendix するだけで済み boundary が clean
- **Trade-offs**: file が 1 つ増える
- **Follow-up**: なし

### Decision: env-agnostic 原則を ADR 0032 として成文化

- **Context**: kolt.toml に `${env.X}` interpolation を入れない決定を後世に残す必要 (Req 6)
- **Alternatives Considered**: ADR 0032 / 既存 ADR への追記 / docs/ 配下の通常 markdown
- **Selected Approach**: ADR 0032 を新規作成。Status / Context / Decision / Consequences の標準 ADR shape
- **Rationale**: kolt.toml schema に関わる方針決定であり、 後段 (CLI flag や override file) の judging anchor になる。 ADR にすることで後世の review に乗る
- **Trade-offs**: ADR が 1 本増える
- **Follow-up**: follow-up issue 2 本 (CLI `-D` flag / per-user override file) を ADR から #issue 番号で参照

## Risks & Mitigations

- **Risk [High]**: 既存 resolver の単一 graph 構造に bundle を載せると Req 4.5 が破れる
  - Mitigation: Option C (bundle ごとに独立 pass) を採用。 関数 contract レベルで leak が不可能になる。 `BundleResolverIsolationTest` / `BundleResolverVersionDivergenceTest` で構造的に検証
- **Risk [Medium]**: ktoml の decoder API が string/structure dispatch を簡潔に書けない → custom serializer の品質低下
  - Mitigation: 実装初期 task で probe spike、 fallback 経路 (uniform inline-table) を design.md の Open Question に明示
- **Risk [Medium]**: ktoml の Map decode 結果が TOML declaration order を保たない場合、 `-D` argv 順序が non-deterministic になる
  - Mitigation: 実装初期 task で probe、 保たない場合は `parseConfig` 内で `LinkedHashMap` 正規化。 `ConfigOrderingTest` で probe
- **Risk [Medium]**: lockfile v4 移行で既存 user の `kolt.lock` が parse error
  - Mitigation: Migration UX = Option B (`kolt deps install` が自動 overwrite + warning)。 build 系は明確 error で停止するため silent な状態変化なし
- **Risk [Low]**: sysprop の名前空間衝突 (`kolt.foo` を user が宣言した瞬間に kolt 内部 sysprop が将来追加されたら)
  - Mitigation: kolt 内部から JVM へ渡す built-in sysprop を将来作る場合に備え、 「kolt.toml で declare 可能な key には予約語を設けない」を Req 3.6 で約束。 内部 sysprop が必要になった時点で別 ADR で reserve

## References

- `docs/adr/0023-target-and-kind-schema.md` — `[build] target` / `kind` validation の前例
- `docs/adr/0028-v1-release-policy.md` — pre-v1 backcompat 不採用ポリシー
- `kolt.lock` schema (`src/nativeMain/kotlin/kolt/resolve/Lockfile.kt`) — version bump 前例
- ktoml-core: https://github.com/akuleshov7/ktoml — TOML parsing library (現状 v0.7.1)
- Cargo: `Cargo.toml` の env-agnostic 原則 / `.cargo/config.toml` の env overlay (env 戦略の reference 先)
