# Gap Analysis: main-test-closure-separation

## 1. Current State Investigation

### Domain-related assets

- **`kolt.build.TestDeps`** (`src/nativeMain/kotlin/kolt/build/TestDeps.kt`)
  - `autoInjectedTestDeps(config)` — JVM 時に無条件で `kotlin-test-junit5` を返す。`test_sources` / `[test-dependencies]` の有無を見ない。
  - `mergeAllDeps(config)` — `auto < main < test` 優先順で単一 `Map<String, String>` に畳み込み。
- **`kolt.cli.DependencyResolution`** (`src/nativeMain/kotlin/kolt/cli/DependencyResolution.kt`)
  - `resolveDependencies(config)` — `mergeAllDeps` の結果を `config.copy(dependencies = allDeps)` として resolver に渡す (L53, L61)。
  - `findOverlappingDependencies` — main/test 版数ズレの警告 (L36-44, 既存動作、保持要)。
  - `JvmResolutionOutcome(classpath, resolvedJars)` — 単一 classpath と `ResolvedJar` リストを返す (L25-28)。
- **`kolt.resolve.Resolver`**
  - `resolve(config, existingLock, cacheBase, deps)` — Native / JVM で分岐 (L111-118)。
  - `ResolvedDep(groupArtifact, version, sha256, cachePath, transitive)` — origin 属性なし (L72-77)。
  - `ResolveResult(deps, lockChanged)` (L80)。
- **`kolt.resolve.TransitiveResolver`**
  - `resolveTransitive(config, existingLock, cacheBase, deps)` — `resolveGraph(config.dependencies, pomLookup)` 経由 (L11-41)。
  - `materialize()` — 解決済 node を `ResolvedDep` に詰めて download / sha256 確認 (L160-236)。
- **`kolt.resolve.Resolution`**
  - `fixpointResolve(directDeps, childLookup)` — 単一 directDeps Map を受ける BFS fixpoint (L52-74)。
  - `pomChildLookup` — test scope フィルタは既に `isIncludedScope` で適用済 (L248-251、AC 的に動作済)。
- **`kolt.resolve.Lockfile`**
  - `LockEntry(version, sha256, transitive = false)` (L17)。
  - `Lockfile(version, kotlin, jvmTarget, dependencies: Map<String, LockEntry>)` (L20-25)。
  - `parseLockfile` は `version !in 1..2` を拒否 (L56)。`transitive` は v1 でも `@SerialName` default で互換。
- **`kolt.build.Builder`**
  - `writeRuntimeClasspathManifest(config, resolvedJars)` — 全 `ResolvedJar` を ADR 0027 §1 ルールで書き出し (L38-53)。
  - 呼び出しは `BuildCommands.handleRuntimeClasspathManifest` (`BuildCommands.kt` L41-52) で JVM `kind = "app"` にのみ発火。
- **`kolt.cli.BuildCommands`**
  - `doBuild` — `resolveDependencies` → `BuildResult(config, classpath, javaPath)` (L139-349、manifest 発火は L329)。
  - `doRun(config, classpath, ...)` — build で得た classpath を `java` subproc に渡す (L604-)。
  - `doTest(config, ...)` — `doBuild` を L672 で再利用し、返ってきた単一 `classpath` で test コンパイル (L709) と test run (L723) の両方を駆動 (L661-741)。
- **`kolt.build.Workspace`**
  - `generateWorkspaceJson(config, resolvedDeps)` — main module と test module の両方に全 `resolvedDeps` を流し込んでいる (L32-109)。本 spec 下では main module を main closure のみ、test module を main ∪ test にするのが自然。
  - `generateKlsClasspath` は単純に `resolvedDeps.joinToString(":")`。
- **ADR 0027 §1 / §4 / §5** — `docs/adr/0027-runtime-classpath-manifest.md`。manifest は "transitive closure, post-exclusion"、JVM `kind = "app"` のみ emit、`kolt run` は manifest を read-back しない asymmetry。
- **ADR 0003 §2 / §5** — `docs/adr/0003-toml-config-json-lockfile.md`。lockfile schema 進化は `@SerialName` + nullable/default + version ディスクリミネータ、v1 → v2 bump の前例あり。
- **ADR 0025 §3** — JVM lib artifact shape (本 spec からは読むだけ、書き換え不要)。

### Conventions / 依存方向

- `cli → build → resolve / infra` の一方向 (steering `structure.md`)。resolver API を変える場合、cli / build の呼び出し側はすべて追従対象。
- `kotlin-result 2.x` の value class 制約 (`getOrElse` / `isErr`、`is Ok` 禁止、auto memory)。
- Pre-v1 の "no backward compatibility"。lockfile schema は clean break で v3 に上げてよい。

### 既存テスト (回帰 contract を pin しているもの)

- `src/nativeTest/kotlin/kolt/build/TestDepsTest.kt` — `mergeAllDeps` / `autoInjectedTestDeps` の現行契約。
- `src/nativeTest/kotlin/kolt/resolve/LockfileTest.kt` — v1 / v2 parse / serialize、version 受理範囲。
- `src/nativeTest/kotlin/kolt/resolve/TransitiveResolverTest.kt` — scope フィルタ (`scopeFilteringSkipsTestAndProvided` 他)、transitive、exclusions。
- `src/nativeTest/kotlin/kolt/cli/DependencyResolutionTest.kt` — `findOverlappingDependencies`、`resolveDependencies` no-deps。
- `src/nativeTest/kotlin/kolt/build/RuntimeClasspathManifestTest.kt` — manifest 書き出し (ADR 0027 §1 ルール)。
- `src/nativeTest/kotlin/kolt/build/WorkspaceTest.kt` — workspace.json 構造。
- `src/nativeTest/kotlin/kolt/cli/JvmAppBuildTest.kt` / `RunLibraryRejectionTest.kt` / `BuildLibraryTest.kt` / `DoInitTest.kt` — kind=app / lib の end-to-end 挙動。

### Integration surfaces

- **IDE (workspace.json / kls-classpath)**: main / test で dep 可視範囲を分けるのが本来の IDE 契約。現行は両 module に同じ list が入っており、本 spec でむしろ IDE 側の挙動も改善する。
- **`scripts/assemble-dist.sh`**: `build/<daemon>-runtime.classpath` を入力として `libexec/<daemon>/deps/*.jar` を生成する passive consumer。manifest 側が main closure のみになれば自動追従。
- **`.kolt.lock`**: 手で読む開発者、レビュアー、CI 差分チェック。v3 bump は stderr + `kolt deps install` による再生成を要求するだけで済む (pre-v1 方針)。

---

## 2. Requirements Feasibility Analysis

### Technical needs per requirement

| Req | 技術ニーズ | 既存 asset | 状態 |
|-----|--------|--------|--------|
| **R1** main / test closure 分離 | resolver へ 2 入力を渡す API、または 1 入力で origin タグ付き出力 | `resolveDependencies`, `resolveTransitive`, `fixpointResolve` すべて単一 Map 前提 | **Missing** (API signature 拡張) |
| **R1 AC4** test_sources 空 skip | `autoInjectedTestDeps` 内の条件分岐 | 現行は無条件 inject | **Missing** (条件追加) |
| **R1 AC5** Native 対象外 | `resolveNative` が既に `config.dependencies` のみ使用 | `NativeResolver.kt` 既存 | **Partial** (既に別経路、API 変更に追従するだけ) |
| **R2** Lockfile origin フィールド | `LockEntry`, `LockEntryJson`, `parseLockfile`, `serializeLockfile`、schema version | `transitive` 追加の前例 (v1→v2) | **Missing** (v3 schema + parser 拡張) |
| **R2 AC4** 旧 schema 拒否 | `parseLockfile` の `version !in 1..2` 判定 | 既存 | **Partial** (範囲拡張 + メッセージ文言追加) |
| **R3** runtime manifest main-only | `writeRuntimeClasspathManifest` の入力フィルタ、`JvmResolutionOutcome.resolvedJars` の意味付け | manifest ルール (alphabetical, GAV tiebreak) は既存 | **Missing** (main closure のみ渡す経路) |
| **R3 AC4** ADR 0027 §1 文言補足 | `docs/adr/0027-runtime-classpath-manifest.md` | 既存 ADR | **Missing** (文言追加) |
| **R4** test classpath = main ∪ test | `doTest` で classpath を構成 | 現行は単一 merged classpath を使い回し | **Partial** (union merge logic 追加) |
| **R4 AC3** test_sources 空時 no-op | 既存 `doTest` の early return 経路 | `BuildCommands.kt:677-680` | **Existing** (維持のみ) |
| **R5** kolt run main-only | `JvmResolutionOutcome.classpath` の意味を main-only に再定義 | 現行は merged | **Partial** (build 経路で main-only を返す) |
| **R5 AC2** manifest read-back しない asymmetry | ADR 0027 §5 確立済、既存コードで守られている | 既存 | **Existing** (保持のみ) |
| **R6** daemon self-host 再検証 | `kolt-jvm-compiler-daemon/kolt.lock` / `spike/daemon-self-host-smoke/kolt.lock` の regen、`assemble-dist.sh` smoke | 既存 self-host-post CI | **Operational** (実装後に再生成) |

### Complexity signals

- **Algorithmic**: 2 closure 計算と overlap dedup (R1 AC2)。既存 `fixpointResolve` は直接 dep のみ "main wins" を保証しているので、2 入力版では「main 選択が test 選択より優先」ルールの表現を要設計。
- **Schema evolution**: lockfile v3 bump。前例 (v1 → v2) で確立されたパターンをそのまま踏襲可能。
- **Integration**: cli → build → resolve への signature change が下流 (manifest / run / test / workspace) すべてに伝搬。touch 箇所多いが、各々の変更は局所的。

### Research Needed (design 時に詰める項目)

- **R-1**: `fixpointResolve` を 2 入力 API に拡張するか、結果に `origin` タグを持たせる単一 API か。前者は BFS 実装を 2 度走らせるか、同時に走らせるか選択。後者は main-seeded entry のキャッシュが test-seeded entry に影響しないよう state の分離を要設計。
- **R-2**: `LockEntry` に `origin: String` を直書きするか、`test: Boolean = false` フラグで書くか (既存 `transitive: Boolean` と対称)。ADR 0003 の「`@SerialName` + default で互換」とどちらが整合するか。
- **R-3**: `kolt test` 実行時 classpath の merge ルール (R1 AC2 の "main wins on overlap" を test classpath 構築側でも守る)。重複 jar path の除去キーは GA か `cachePath` か。
- **R-4**: `JvmResolutionOutcome` を `mainClasspath` / `testClasspath` / `mainJars` / `testJars` の 4 フィールドに拡張するか、`ResolvedDep.origin` を外出しにして単一 list で返すか。
- **R-5**: v2 lockfile を検出したとき、unsupported version エラー (R2 AC4) か、main-origin として silently 読めるか。後者は clean break 原則に反するが、CI 互換で議論余地あり。

---

## 3. Implementation Approach Options

### Option A: `ResolvedDep.origin` タグ付きの単一 resolver パス

- **概要**: `ResolvedDep` に `origin: Origin` (MAIN / TEST) を追加し、resolver は `config.dependencies` と `config.testDependencies` + `autoInjectedTestDeps` を同じパスで走らせつつ、main seed から到達した node は MAIN、test seed のみから到達した node は TEST とタグ付けして返す。`materialize` は一度だけ走る。呼び出し側は origin で filter する。
- **整合**: `kolt.resolve` 内の fixpoint state に "seeded by" 情報を足す必要あり。既存の "direct wins" 扱い (`second: Boolean` in `Resolution.versions`) の隣に `originFlags: Set<Origin>` を持たせる形。
- **Trade-offs**:
  - ✅ resolver 一回で済むので wire / cache / 404 フォールバックの再実装不要。
  - ✅ overlap 解決が kernel 内部で完結し、呼び出し側は単純な filter のみ。
  - ❌ `fixpointResolve` の state モデルに origin 概念を染み込ませるため、kernel の認知負荷が上がる。
  - ❌ kernel の既存テスト (ResolutionTest.kt) に origin 軸を足すので test 追加が多い。

### Option B: 2 度の resolver 呼び出し (main 一回 / test 一回) を caller で orchestrate

- **概要**: `resolveDependencies` が `resolve(config.copy(dependencies = mainDeps), ...)` と `resolve(config.copy(dependencies = testDeps ∪ autoInject), ...)` を続けて呼ぶ。test pass では main 結果を先に populate して "main wins on overlap" を確保 (もしくは test 結果から main GA を除外)。lockfile 書き出し時に origin タグを付与。
- **整合**: resolver 本体は無変更。`Resolver.resolve` / `fixpointResolve` / `ResolvedDep` のシグネチャそのまま。
- **Trade-offs**:
  - ✅ kernel を触らないので既存テストの影響が最小。
  - ✅ Native は今まで通り 1 回呼びで済む (test は JVM 限定なので関係なし)。
  - ❌ cache / 404 フォールバック / sha256 確認が重複呼び出しで 2 倍走る。パフォーマンスは cache hit が前提なので実害は小さいが、lock 一致性 (test pass 中に main の同一 jar を再 download しない) を caller 側で保証する必要。
  - ❌ overlap の解決ロジックが caller 側に出るので `resolveDependencies` が太る。`findOverlappingDependencies` の警告と位置関係を再設計。

### Option C: Hybrid — kernel は 2-seed を受ける、materialize は一度

- **概要**: `fixpointResolve` に `mainSeeds: Map` と `testSeeds: Map` の 2 入力を追加、BFS は両 seed から開始するが `versions` に origin bitmap を保持し、conflict は "main wins"。`materialize` は 1 回のみ走り、`ResolvedDep.origin` を返す。呼び出し側は `origin` で filter。
- **整合**: kernel API は軽く拡張するが、external state (cache / download) は一度だけ動く。B の重複 I/O を回避し、A の kernel 染み込みを局所化。
- **Trade-offs**:
  - ✅ 外部 I/O (download / sha256) が 1 回。
  - ✅ overlap ルールが kernel 内で表現され、caller は filter だけ。
  - ✅ 既存 `fixpointResolve` の signature に `testSeeds: Map<String, String> = emptyMap()` を default 付きで足せば Native 呼び出し箇所は変更不要。
  - ❌ kernel に origin bitmap が入り、A よりは局所的だが B よりは複雑。既存 ResolutionTest.kt に origin 軸のテストを足す。
  - ❌ design で state モデル (Pair<String, Boolean> → Triple<String, Boolean, OriginSet> 相当) の shape を確定する必要あり。

### Non-trivial sub-decisions (いずれの Option でも共通)

- **`autoInjectedTestDeps` の skip 条件 (R1 AC4)**: `config.build.testSources.isEmpty() && config.testDependencies.isEmpty()` を単純 AND で判定、inject を emptyMap に。config の読み方は既存。
- **Lockfile v3 schema (R2)**: `LockEntry.origin: String = "main"` を追加し、`LockEntryJson` にも default 付きで `@SerialName("origin")` 追加。`parseLockfile` の version 許容を `1..3` に。v1 / v2 は今まで通り `origin = "main"` で補完するか、clean break で reject するかは R-5 で決める。
- **`writeRuntimeClasspathManifest` (R3)**: 呼び出し元 `handleRuntimeClasspathManifest` で `resolvedJars.filter { it.origin == MAIN }` を適用、manifest 生成自体は既存ロジック。
- **`doTest` classpath (R4)**: `doBuild` 経路が返す `mainClasspath` を受け取り、test closure 由来 jar を後段で append、`:` 結合。dedup キーは GA (main wins)。
- **Workspace / kls-classpath (IDE 副次効果)**: main module は main closure、test module は main ∪ test。今まで test module の可視範囲が正しくなかったが、本 spec でむしろ契約を修正する形になる (別 issue 化するか、同 PR で一緒に直すかは design で判断)。

---

## 4. Out-of-Scope for Gap Analysis

(design で詰める前提の領域を記録)

- `fixpointResolve` の state モデル詳細 (Origin bitmap の data class shape、overlap の具体的分岐)。
- `Workspace.kt` の main / test 分離を本 PR スコープに含めるか分離するかの判断。
- `.kolt.lock` schema v3 の正確なフィールド名 (`origin` vs `test`) の bikeshed。

## Research Needed (carry forward to design)

- R-1 / R-3 / R-4 (resolver API + test classpath merge 形)。
- R-2 (LockEntry の origin 表現: `origin` 列 vs `test: Boolean` 列)。
- R-5 (旧 schema lockfile の挙動: reject か silent upgrade か)。

---

## 5. Implementation Complexity & Risk

### Effort: **M (3–7 日)**
理由: resolver signature の局所拡張 + lockfile v3 schema bump + 呼び出し側 5 経路 (build/run/test/manifest/workspace) の追従 + テスト 40–50 本の更新。既存の "direct wins" 機構・schema evolution 前例・manifest 経路はすべて揃っており、ゼロから作る部分はない。

### Risk: **Medium**
理由: `kolt.resolve` kernel (reliability floor、steering `structure.md` に「Gradle/Maven spec 相当の信頼性」と明記) に触る変更があるため、既存の fixpoint / overlap / strict / rejects ロジックに origin 概念を乗せる際に意図せぬ回帰を入れるリスクがある。Native 経路との分岐 (signature 変更時の call site 追従漏れ) も Medium。逆に lockfile schema bump と manifest filter は前例・パターンが確立していて Low。

---

## Recommendations for Design Phase

### 推奨アプローチ: **Option C (Hybrid: kernel は 2-seed、materialize 一回)**

- `fixpointResolve` / `resolveGraph` / `resolveTransitive` に `testSeeds: Map<String, String>` を default `emptyMap()` で追加することで Native 呼び出し側 (`resolveNative`) は変更不要にできる。
- kernel 内で origin を追跡するコストを払う代わりに、外部 I/O (download, sha256, cache hit check) は 1 回に保つ。Option B の重複 I/O コストが cache hit 前提でも気持ち悪く、Option A の "kernel 全域に origin" より局所化できる。
- `ResolvedDep.origin: Origin` (enum MAIN / TEST) を新設、`LockEntryJson` に `origin: String = "main"` を @SerialName 付きで追加、`Lockfile.version` を 3 に bump。旧 v1 / v2 は `parseLockfile` が unsupported version を返し、ユーザーは `kolt deps install` で再生成 (pre-v1 方針)。

### Key decisions to lock in design

1. **Resolver signature**: `fixpointResolve(mainSeeds, testSeeds = emptyMap(), childLookup)` のどちらか。(R-1)
2. **Origin 表現**: `ResolvedDep` / `LockEntry` に `origin` を持たせるか、`test: Boolean` にするか。(R-2)
3. **test classpath merge の責務分界**: `doTest` 内 or `DependencyResolution` の返り値で main+test を返す。(R-3)
4. **`JvmResolutionOutcome` の再設計**: single `classpath` / `resolvedJars` から、`main*` / `test*` を別フィールドとして返す、あるいは `resolvedJars` 一本で origin から filter させる。(R-4)
5. **旧 lockfile 互換**: v1 / v2 は reject (clean break) で確定させるか、silent migrate で origin=main 補完するか。(R-5)
6. **Workspace.kt の main/test 修正を同一 PR に含めるか**: IDE 契約の副次効果を design で切り出すか。

### Carry to design: 研究アイテム

- `fixpointResolve` の BFS state (`Map<String, Pair<String, Boolean>>`) を origin-aware に拡張する具体的 shape。
- v3 schema の例 JSON (`origin: "main" | "test"`) と、LockfileTest.kt に追加する parse / roundtrip ケース。
- Option C における test seed からの conflict resolution (main が先 vs test が先で挙動が変わらないことの不変条件)。
