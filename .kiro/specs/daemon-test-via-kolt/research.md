# Gap Analysis: daemon-test-via-kolt

## 1. Current State Investigation

### ADR 0032 schema は実装完了 (#318 / PR #321)

- `src/nativeMain/kotlin/kolt/config/Config.kt`
  - `KoltConfig.classpaths: Map<String, Map<String, String>>`
  - `TestSection.sysProps: Map<String, SysPropValue>` / `RunSection.sysProps`
  - target=native との衝突 / kind="lib" の `[run.sys_props]` 拒否、 undeclared bundle 参照拒否、 `project_dir` の絶対 / `..` escape 拒否
- `src/nativeMain/kotlin/kolt/config/SysPropValue.kt` — `Literal` / `ClasspathRef` / `ProjectDir` の三項 sum type
- `src/nativeMain/kotlin/kolt/build/SysPropResolver.kt` — bundle 名 → colon-joined absolute path、 `project_dir` → `<projectRoot>/<rel>` の絶対化
- `src/nativeMain/kotlin/kolt/cli/DependencyResolution.kt`
  - `resolveBundleClasspaths` が `[classpaths.<name>]` ごとに resolveBundle を回し、 lockfile に書き戻し
  - `bundleLockChanged` で classpaths キー集合の追加 / 削除 / リネームを検知
- `src/nativeMain/kotlin/kolt/cli/DependencyCommands.kt`
  - `kolt deps install/tree/update` が classpaths を `[dependencies]` / `[test-dependencies]` と同じ update policy で処理
  - `kolt deps tree` の bundle section レンダリング
- `src/nativeMain/kotlin/kolt/cli/BuildCommands.kt`
  - `BuildResult.bundleClasspaths` を doBuild → doTest / doRun に持ち回し
  - `jvmTestArgv` / `jvmRunArgv` が SysPropResolver を回して `-D<key>=<value>` を JUnit Console Launcher / app JVM の argv に追加

### JUnit 5 console launcher 経路は既存

- `src/nativeMain/kotlin/kolt/tool/ToolManager.kt` で `junit-platform-console-standalone-1.11.4.jar` を Maven Central から fetch して `~/.kolt/tools/` にキャッシュ
- `src/nativeMain/kotlin/kolt/build/TestRunner.kt` が argv builder を担う
- `kolt test` の JVM target 経路はこの console launcher を使う構造 (kotlin.test との混在前提)

### 既存 daemon 副プロジェクトの状態

- `kolt-jvm-compiler-daemon/kolt.toml`
  - `sources = ["src/main/kotlin", "ic/src/main/kotlin"]` で root + ic main を fat に統合
  - `test_sources = []` (kolt 経由 test は no-op)
  - `[classpaths]` / `[test.sys_props]` 未使用
  - 依存: kotlin-build-tools-api / kotlinx-serialization-json / kotlin-result / ktoml-core
- `kolt-jvm-compiler-daemon/ic/kolt.toml` — **存在しない**
- `kolt-native-compiler-daemon/kolt.toml`
  - `sources = ["src/main/kotlin"]`、 `test_sources = []`
  - 依存: kotlinx-serialization-json / kotlin-result
- 各副プロジェクトに `kolt.lock` がある (build/run path 経由で生成済)

### Cross-module 依存

- root daemon main (`kolt-jvm-compiler-daemon/src/main/kotlin/kolt/daemon/Main.kt`、 `server/DaemonServer.kt`、 `reaper/IcReaper.kt`) は `kolt.daemon.ic.*` を import している。 すなわち **root daemon main は ic main の symbols を必要とする**。
- ic main は root daemon の symbol を import していない (一方向依存、 ic は leaf 側)。
- ic test 群 (`kolt-jvm-compiler-daemon/ic/src/test/kotlin/`) も root daemon の symbol を import していない (ic 自身のみで完結)。
- 既存 Gradle config では `kolt-jvm-compiler-daemon/build.gradle.kts` が `implementation(project(":ic"))` で ic を取り込み。

### kolt config schema の制限

- `[dependencies]` / `[test-dependencies]` は `"group:artifact" = "version"` の Maven coordinate のみ。 path-based / file-based / project-based 依存は **未実装**。
- Workspace / multi-module 機能は無い (`includeBuild` 相当の概念が無い)。

### `./gradlew check` 言及箇所 (要撤去)

- `.kiro/steering/tech.md` `## Common Commands` Special-purpose ブロック (2 行)
- それ以外の user / dev surface 走査結果 0 件 (CI / scripts / CLAUDE.md / SKILL に該当行なし)
- orphan な `build.gradle.kts` / `settings.gradle.kts` 内の言及は #316 で削除予定なので本 spec 対象外
- 履歴記録 (specs / ADR / spike) は意図的に保持

## 2. Requirement-to-Asset Map

| 要件 | 既存資産 | Gap タグ |
|---|---|---|
| R1.1: root daemon directory で `kolt test` 実行 | `doTest` / `jvmTestArgv` 経路あり、 daemon kolt.toml は `test_sources = []` | **Missing**: root daemon kolt.toml の `test_sources` / `[test-dependencies]` / `[test.sys_props]` 整備 |
| R1.2: ic directory で `kolt test` 実行 | なし | **Missing**: `kolt-jvm-compiler-daemon/ic/kolt.toml` 新設 |
| R1.3: native daemon directory で `kolt test` 実行 | native daemon kolt.toml は `test_sources = []` | **Missing**: native daemon kolt.toml の `test_sources` / `[test-dependencies]` 整備 |
| R1.4–R1.6: pass/fail outcome / exit code が Gradle と一致 | `doTest` の exit code 経路は既存 | **Unknown**: Maven 解決 transitive 集合差分による classpath 揺れがテスト結果に影響しないか |
| R2.1–R2.4: ic 4 classpath bundle | `[classpaths]` resolver / lockfile 既実装 | **Missing**: ic kolt.toml に bundle 4 個 declare、 transitive 解決の妥当性確認 |
| R2.5: lockfile への bundle 反映 | `resolveBundleClasspaths` + `bundleLockChanged` | **Constraint**: 既存実装のまま動作 |
| R2.6: bundle と main / test-deps の transitive 隔離 | resolver は bundle ごと独立 closure | **Constraint**: 既存実装のまま動作 |
| R3.1–R3.5: 4 sysprop の delivery | `SysPropResolver` + `jvmTestArgv` | **Constraint**: 既存実装のまま動作、 ic kolt.toml に `[test.sys_props]` を declare すれば自動追従 |
| R4.1–R4.3: `kolt.daemon.coreMainSourceRoot` | `SysPropValue.ProjectDir` / `SysPropResolver` | **Missing**: root daemon kolt.toml に `[test.sys_props]` declare |
| R5.1–R5.4: doc 撤去 | 機械的編集のみ | **Constraint**: tech.md と関連サーフェスの 1 箇所 |
| R6.1–R6.4: behavioural parity | 既存 daemon test 本体に手を入れない | **Unknown**: kolt jvmTestArgv が組む argv が Gradle `tasks.test` の argv と等価か (working dir / JDK args / env / classloader) |

## 3. Implementation Approach Options

### Option A: ic を独立 kolt project にする (推奨)

**構造**:
- `kolt-jvm-compiler-daemon/ic/kolt.toml` を新設、 `sources = ["src/main/kotlin"]`、 `test_sources = ["src/test/kotlin"]`
- ic kolt.toml の `[dependencies]` は kotlin-build-tools-api / kotlin-result / ktoml-core (Gradle と同じ Maven coord)
- ic kolt.toml の `[test-dependencies]` で junit-jupiter / junit-platform-launcher / kotlin-test
- ic kolt.toml の `[classpaths]` に 4 bundle、 `[test.sys_props]` で sysprop 4 個
- `kolt-jvm-compiler-daemon/kolt.toml` は **既存 sources を維持** (`["src/main/kotlin", "ic/src/main/kotlin"]`)、 `test_sources = ["src/test/kotlin"]` 追加、 `[test.sys_props]` で `kolt.daemon.coreMainSourceRoot` を declare
- `kolt-native-compiler-daemon/kolt.toml` は `test_sources = ["src/test/kotlin"]` + `[test-dependencies]` を追加するのみ

**動作**:
- ic directory で `kolt test` → ic 単独 build + ic test (4 sysprop)
- root daemon directory で `kolt test` → root + ic main 全 build + root daemon test (1 sysprop)
- native daemon directory で `kolt test` → native daemon test
- ic main を root daemon が `sources` で重複 build するが kolt 機能拡張不要

**Trade-offs**:
- ✅ Issue 本文 "from its own directory" を素直に満たす
- ✅ kolt CLI / schema 拡張ゼロ (ADR 0032 schema のみで完結)
- ✅ Gradle の `:ic` subproject 構造との対応関係が明瞭
- ❌ ic main の二重 build (root daemon の `kolt build` 時に ic source が再 compile される)
- ❌ ic kolt.toml と root daemon kolt.toml で kotlin-build-tools-api / kotlin-result バージョンを 2 箇所 pin する必要 (drift 注意)

### Option B: 統合 kolt project (ic を root daemon の test 集合に統合)

**構造**:
- `kolt-jvm-compiler-daemon/kolt.toml` の `test_sources = ["src/test/kotlin", "ic/src/test/kotlin"]`
- `[classpaths]` に 4 bundle、 `[test.sys_props]` に sysprop 5 個 (ic 4 + root 1) を統合
- `kolt-jvm-compiler-daemon/ic/kolt.toml` は **作らない**
- ic directory で `kolt test` → no-op (kolt.toml 不在 or test_sources empty)
- root daemon directory で `kolt test` → root + ic test 全部実行

**Trade-offs**:
- ✅ kolt.toml が 2 箇所のみ (root daemon + native daemon)、 dep / version pin 重複なし
- ✅ kolt 機能拡張ゼロ
- ❌ Issue 本文 "from its own directory" を文字通り満たさない (ic 単体 directory での実行は不可)
- ❌ root daemon の test JVM が ic test の sysprop 4 個も受け取る (実害なし、 ic test が読まなければ無視されるだけ)
- ❌ ic test 単体実行が `kolt test --filter=PluginTranslatorTest` 等で代替する形になる

### Option C: ic を独立 kolt project + path-based dep schema を新規追加

**構造**:
- ic を完全に分離、 root daemon が ic を path-based dep として消費する schema (`{ path = "../ic", target = "jar" }` 等) を kolt に新設
- ic main の二重 build を排除

**Trade-offs**:
- ✅ ic main が真に 1 箇所でしか build されない
- ✅ multi-module project (#4) への踏み台になる
- ❌ 本 spec の Out of scope (kolt CLI / schema の新機能追加なし) を破る
- ❌ Effort が大幅に膨らむ (resolver / lockfile / build orchestration まで波及)
- ❌ multi-module ロードマップ (#4) と方針調整が必要

## 4. Implementation Complexity & Risk

- **Effort**: M (3–7 days)
  - kolt.toml 設計 + 編集 (3 副プロジェクト): 1 day
  - GAV + bundle 解決の妥当性確認 (Gradle transitive 集合との一致 cross-check): 1–2 days
  - 各 daemon directory で `kolt test` を回して Gradle verdict と一致するまで debug: 1–2 days
  - tech.md / 関連 doc 撤去 + commit 整理: 0.5 day
  - 予備 / leftover: 1–2 days
- **Risk**: Medium
  - High-risk:
    - Gradle module metadata の transitive 解決と kolt resolver の差分。 特に `kotlin-build-tools-impl:2.3.20` の transitive (depthが深い) で含まれる jar 集合が一致しないと、 `BtaIncrementalCompilerWarmPathTest` 系がfail し得る
    - JVM 子プロセスの environment (working directory / JAVA_HOME / system property 順序) が Gradle `tasks.test` と等価か
    - `BtaSerializationPluginTest` は serialization compiler plugin を URLClassLoader で reflective load するため、 classpath 順序や file 存在で fail し得る
  - Low-risk: schema 適用そのもの (#318 で完成済)、 doc 撤去 (機械的)、 R6 parity (test 本体不変)

## 5. Research Items for Design Phase

1. **Maven transitive 解決差分の確認**
   - `kolt deps tree` 結果と `./gradlew :kolt-jvm-compiler-daemon:ic:dependencies` の transitive 集合を side-by-side 比較
   - 特に `kotlin-build-tools-impl:2.3.20`、 `kotlin-serialization-compiler-plugin-embeddable:2.3.20`、 `kotlinx-serialization-core-jvm:1.7.3` の transitive
   - 差分があれば bundle に explicit pin を追加するか、 resolver 側を寄せるか判断
2. **JVM test argv の等価性確認**
   - `jvmTestArgv` の出力と `./gradlew :ic:test` の Gradle が組む `java ...` argv (working dir / -D / -cp 順序 / `--module-path` 有無 / JDK toolchain option) を比較
   - working directory が project root か classes dir かで `AdapterBoundaryInvariantTest` の `Paths.get(...)` 解釈が変わる可能性
3. **`[test-dependencies]` でどう JUnit 5 platform を declare するか**
   - 既存 daemon kolt.toml は test-deps を持たないので、 daemon 側で junit-jupiter / junit-platform-launcher を test-dep に追加する例を design に含める
   - kotlin.test と JUnit 5 の test discovery が混在するか、 kotlin.test に頼らず JUnit 5 単独で動かすか
4. **ic main を root daemon が再 build することの cost / 副作用**
   - debug / release 両 profile で ic main classes が `build/<profile>/classes` に重複出力されることの影響
   - lockfile 2 ファイル化による `kolt deps install` 実行回数 (ic で 1 回、 root で 1 回) — 開発フローで許容できるか
5. **#316 lands 前の cross-check 戦略**
   - R6.4 で `./gradlew check` と `kolt test` を両方走らせる時期があるが、 orphan Gradle config を一時的に保持しておく必要がある
   - PR の書き方 (本 spec 完了 PR vs #316 PR の 2 段階) を design 内で確定

## 6. Recommendation for Design Phase

- **採用候補: Option A (ic を独立 kolt project)**。 Issue 文言 "from its own directory" を素直に満たし、 kolt 機能拡張なし、 Effort M / Risk Medium で完結。
- Option C (path-based dep schema) は multi-module support (#4) と統合する大きな決定になるので、 本 spec ではなく将来別 spec として切る。
- Option B (統合 project) は Option A の代替として残し、 Option A の二重 build cost が許容できないと判明した場合のフォールバックにする。

## 7. Out-of-Scope (Deferred to Design)

- 厳密な kolt.toml の構文 (どのキーをどのテーブルに置くか、 explicit pin 候補) は design phase で確定
- `[test-dependencies]` の選定 (JUnit 5 / kotlin-test の declare 方針) は design phase で確定
- Native daemon kolt.toml の test 構成 (sysprop 不要、 単純な `[test-dependencies]` のみ) も design phase で記述

---

## 追記 (2026-04-30): Option A → Option B 逆転、 推奨更新

### Option B が Gradle parity を満たすという主軸

Option B (統合 kolt project) は、 Gradle 時代の test classpath 構造と一致する ことが確認された。 これが本 spec の中心的な判断根拠になる。

**Gradle 時代の classpath 構造** (`kolt-jvm-compiler-daemon/build.gradle.kts` の `implementation(project(":ic"))` 経由):

- root daemon test classpath = root daemon main + ic main + test-deps (JUnit 5 / kotlin-test)
- ic test classpath = ic main + test-deps

**Option B 採用後の統合 classpath**:

- 統合 test classpath = root daemon main + ic main + test-deps

Gradle 時代と比較した差分:
- root daemon test 側: 完全一致 (Gradle 時代から ic main が transitive に classpath にあった)
- ic test 側: root daemon main が classpath に追加で乗る (Gradle module isolation で除外されていた分)。 ただし ic test は `kolt.daemon.{Main,server,reaper}` を import していないため、 実行結果に影響なし

つまり Option B は **production-relevant な classpath dependency を完全に保ちつつ、 module 物理境界だけを失う** 構造。 物理境界の文化的な代替は Requirement 6.5 で許容した invariant test (例: ic test source が `kolt.daemon.{Main,server,reaper}` を import していないことの source 解析 assert) で補完する。

### 確定した調査結果

1. **root daemon test → ic main の依存事実** (逆転の trigger)
   - `kolt-jvm-compiler-daemon/src/test/kotlin/kolt/daemon/server/DaemonLifecycleTest.kt` が `kolt.daemon.ic.{IcError, IcRequest, IcResponse, IncrementalCompiler}` を import
   - 同じく `DaemonServerTest.kt` が `kolt.daemon.ic.{IcError, IcRequest, IcResponse, IcStateLayout, IncrementalCompiler}` を import
   - `IcReaperTest.kt` が `kolt.daemon.ic.IcMetricsSink` を import
   - つまり root daemon test classpath に ic main が含まれていなければ Gradle 時代から compile 不能だった
2. **ic test → root daemon main の非依存事実**
   - `kolt-jvm-compiler-daemon/ic/src/test/kotlin/` 配下は `kolt.daemon.{Main, server, reaper}` を一切 import しない (grep 確定)
   - Gradle module isolation で除外されていた依存関係は実態として存在しなかった
   - 統合 classpath 化しても ic test の動作に影響しない
3. **ic test sysprop の confirm** (Option B 採用前提の安全確認)
   - ic test 群は `System.getProperty(...)` で `kolt.ic.{btaImplClasspath, fixtureClasspath, serializationPluginClasspath, serializationRuntimeClasspath}` のみ参照
   - `kolt.daemon.coreMainSourceRoot` (root daemon test の sysprop) は触らない
   - 統合 `[test.sys_props]` で 5 個全部が同じ test JVM に届いても干渉しない

### 副次的便益 (Option A 比)

- **ic main の二重 build 回避**: 1 つの kolt project が `sources = ["src/main/kotlin", "ic/src/main/kotlin"]` を build するだけで済む
- **dep version pin 重複回避**: kotlin-build-tools-api / kotlin-result / ktoml-core を 1 箇所で pin
- **lockfile 単一化**: `kolt-jvm-compiler-daemon/kolt.lock` 1 ファイルに全 dep を統合
- **main 側の取り扱いと test 側の取り扱いの非対称性を排除**: main が既に統合されているのに test だけ別 project にすると認知負荷が大きい

### 残存懸念 (Mitigation 込み)

- **test classpath isolation の文化的喪失**: 統合 classpath で ic test から root daemon main が import 可能になる (Gradle 時代は不可能)
  - Mitigation: Requirement 6.5 で許容した source-static invariant test を追加。 ic test source が `kolt.daemon.{Main,server,reaper}` を import していないことを assert。 source 解析のみで判定するため flake risk なし
- **Issue #315 DoD の "from its own directory" 文言との乖離**
  - Mitigation: design.md と PR 本文の双方で緩和判断を明示。 Issue 本文そのものは history value のため当時のまま保持し、 緩和の根拠は design.md を参照する形で PR が明文化する
- **ic 単体 test の DX**: Gradle の `:ic:test --tests <Class>` 相当が `kolt test` で代替可能か
  - Requirement 1.2 で「filter 経路の存在」 を要件化、 design phase で具体化 (有力候補は `kolt test -- --select-class=...` の passthrough)
  - **Research Item 6 として design 入り口で確認必須**: 現状 `kolt test` の testArgs が JUnit Console Launcher にどこまで passthrough されるか、 動作確認

### 残存 Research Item の更新

Section 5 の Research Item 4 (ic main の二重 build cost) は **Option B 採用により消滅**。 ic main は 1 箇所でしか build されないため検討不要。

新規 Research Item 6 を追加:

6. **`kolt test` の filter / passthrough 経路の動作確認**
   - 現状の `kolt test` が `testArgs` を JUnit Console Launcher にどう渡すか
   - `kolt test -- --select-class=PluginTranslatorTest` 形式が動くか検証
   - 動かない場合の代替経路 (CLI flag 追加は本 spec の Out of scope なので、 既存 passthrough 経路で何ができるかを確定)

### 推奨更新

- **採用: Option B (統合 kolt project)**。 Gradle 時代の classpath 構造と一致し、 二重 build / pin 重複を回避し、 main / test の取り扱いの対称性を保つ
- Option A (ic を独立 kolt project) は当初推奨だったが、 Gradle 時代に ic test classpath が module isolation により root main を除外していた構造を kolt 側で再現する必要が無い (ic test がそもそも root main を import していない) ことが確定したため不採用
- Option C (path-based dep schema) は multi-module support (#4) として将来別 spec で扱う

