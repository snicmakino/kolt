# Gap Analysis: daemon-self-host

**実施日:** 2026-04-22

## 要約

- 設計決定は ADR 0018 / 0025 / 0026 / 0027 で揃っており、本スペックでの
  新規設計選択はない。2026-04-22 spike で 2 daemon が既存 `kind = "lib"`
  pipeline を経由して一発で thin jar を emit することを確認済み。
- 主な変更点は (i) `outputRuntimeClasspathPath` ヘルパーと emit 呼び出しの
  追加、(ii) resolver の戻り値から jar list を manifest 用途に取り出す経路
  の整備、(iii) 2 daemon の `kolt.toml` 追加 (`:ic` ソース merge)、
  (iv) `scripts/assemble-dist.sh` の新規作成、(v) `self-host-smoke.yml`
  companion job の追加。
- リスクは低い。新規 public schema は ADR 0027 で pin 済み、
  `shadowJar` 経路は温存、既存 CI job は touch しない。
- 複雑度は M (3〜5 日) 目安。assemble-dist.sh の platform separator
  処理と `jdk = "21"` provisioning 配線の有無が主要な研究項目。

## 現状調査

### 既存アセット

| 要素 | 場所 | 現状 | 関連 |
|---|---|---|---|
| JVM compile + thin jar pipeline | `kolt.build.Builder.kt:225` `jarCommand`、`kolt.cli.BuildCommands.kt` の JVM 経路 | 完成済み、Main-Class 属性なし (ADR 0025 §3 / ADR 0018 §2 と整合) | Req 1, 2 |
| Artifact path helper | `kolt.build.Builder.kt:15-23` `outputJarPath` / `outputNativeKlibPath` / `outputKexePath` / `outputNativeTestKlibPath` | `outputRuntimeClasspathPath` は未実装 | Req 2 |
| Dependency resolver | `kolt.resolve.Resolver.kt` / `TransitiveResolver.kt`、JVM は stdlib を skip しない | classpath 文字列は返る (`buildClasspath`)、jar list そのものは内部 (`resolveResult.deps.map { it.cachePath }`) | Req 2 |
| `resolveDependencies` API | `kolt.cli.DependencyResolution.kt:36-93` | `Result<String?, Int>` を返す。jar list は関数スコープで consume されて外に出ない | Req 2 |
| `kolt.toml` schema | `kolt.config.Config.kt:63-73` `BuildSection` | `target = "jvm"`, `jvm_target`, `jdk`, `main`, `sources` 等すべて揃う | Req 1 |
| Daemon Gradle build | `kolt-jvm-compiler-daemon/build.gradle.kts`、`kolt-native-compiler-daemon/build.gradle.kts` | `shadowJar` + `verifyShadowJar` + `stageBtaImplJars`。JDK 21 toolchain | Req 5 (並走) |
| `:ic` subproject | `kolt-jvm-compiler-daemon/settings.gradle.kts:3` `include(":ic")`、`ic/build.gradle.kts` (java-library + kotlin serialization + `buildToolsImpl` / `fixtureClasspath` 等) | `implementation(project(":ic"))` で daemon 本体から参照 | Req 1 (merge 戦略) |
| `self-host-smoke.yml` | `.github/workflows/self-host-smoke.yml` | native 自己 rebuild 1 job のみ。Gradle bootstrap → `kolt.kexe build` → `--version` 確認 | Req 4 |
| `scripts/` ディレクトリ | (存在しない) | 新規作成 | Req 3 |
| `kolt run` JVM app 経路 | `BuildCommands.kt` 487-491 周辺 | resolver 戻り値を in-process に渡す。manifest ファイルは経由しない | Req 2 (AC 8) |

### コードの規約と制約

- `kolt.cli` → `kolt.build` → `kolt.resolve` の下向き依存方向 (steering
  structure.md)。manifest emit は `kolt.build` 層が自然。
- ADR 0011: Kotlin stdlib skip は **native resolver 限定**。JVM 経路では
  transitive closure に stdlib がそのまま含まれる。Req 2 AC 6 と整合。
- `kolt.toml` の `sources` は複数ディレクトリ配列を受け付ける。`:ic` の
  ソース merge は `sources = ["src/main/kotlin", "ic/src/main/kotlin"]`
  で宣言できる (spike で確認済み)。
- daemon の Gradle build は並走維持すべき (Req 5)。`shadowJar` /
  `stageBtaImplJars` / `verifyShadowJar` は一切触らない。

## Requirement to Asset Map

### Req 1: Daemon の kolt.toml による build 可能性

- **Exists**: JVM `kind = "app"` pipeline (lib pipeline と同一経路)。
  `BuildSection` に必要なフィールド (`target`, `jvm_target`, `jdk`,
  `main`, `sources`) すべて揃う。spike で 2 daemon 一発通過。
- **Missing**:
  - `kolt-jvm-compiler-daemon/kolt.toml` (新規、`:ic` を `sources` 配列に merge)。
  - `kolt-native-compiler-daemon/kolt.toml` (新規)。
- **Constraint**: daemon Gradle build との並走維持 (Req 5)。daemon 側
  Gradle configuration (`buildToolsImpl` / `fixtureClasspath`) は
  `kolt.toml` に引き込まない (ADR 0019 §3 の classloader isolation を
  実行時仕様として維持)。

### Req 2: Runtime classpath manifest の emit

- **Exists**: `resolveResult.deps.map { it.cachePath }` で jar 絶対パス
  リストを計算済み。`outputJarPath` の helper 慣習が `Builder.kt` に
  ある。
- **Missing**:
  - `outputRuntimeClasspathPath(config)` helper (`kolt.build.Builder.kt`
    に追加。ADR 0027 は `kolt.config.Config.kt` と書いたが、実装先は
    他 `output*Path` と同じ `Builder.kt` が一貫する。ADR 0027 §1 の
    参照先は design 段階で 1 行修正)。
  - JVM `kind = "app"` build 終端で manifest を emit する呼び出し。
  - Resolver 戻り値を manifest 用途で取得するための API 調整。
    現状 `resolveDependencies` は classpath 文字列 1 つだけ返すので、
    jar list も別途取れる形にする必要あり (後述 Option 参照)。
- **Constraint**: `kolt run` JVM app 経路が manifest を**読まない**
  という AC 8 の非対称性を、コードで明示 (Req 2 AC 8、ADR 0027 §5)。
- **Research Needed**: `resolveDependencies` の戻り値形状変更が最小か、
  新しい関数を別途追加するか (design phase で決める)。

### Req 3: `scripts/assemble-dist.sh` stitcher

- **Exists**: `scripts/` ディレクトリは存在しない。tarball layout 仕様
  は ADR 0018 §1、stitcher model は ADR 0018 §4 で pin 済み。
- **Missing**: スクリプト本体。
  - pre-self-host モード (`./gradlew build` 1 回) の実装。
  - post-self-host モード (`kolt build` × 3) の実装。
  - tarball layout 組立て (`bin/`, `libexec/<daemon>/{<name>.jar,deps/*.jar}`,
    `libexec/classpath/<daemon>.argfile`, `VERSION`)。
  - argfile 生成 (platform separator `<SEP>`、main class FQN)。
- **Research Needed**:
  - モード切替方法 (環境変数 / `--mode` flag / 自動検出)。自動検出は
    daemon `kolt.toml` 存在有無で判定可能。design phase で決める。
  - argfile の platform 対応 (`:` vs `;`)。シェルスクリプトとして
    cross-platform にするか、Linux / macOS 前提で割り切るか
    (現状 kolt は linuxX64 のみ支援、macOS / Windows は v1 前の話)。
  - Self jar path の扱い: `libexec/<daemon>/<daemon>.jar` と deps の
    argfile 中での順序は ADR 0027 では pin されていない。stitcher が
    self + deps の順に並べる前提で design phase で明記する。

### Req 4: Self-host smoke CI companion job

- **Exists**: `self-host-smoke.yml` に native self-host job が 1 つ。
  Gradle bootstrap → kexe build path は確立。
- **Missing**:
  - Companion job の step 列 (3 × `kolt build` → assemble-dist.sh →
    tarball 展開 → daemon spawn → Ping/Pong)。
  - Ping/Pong を CI で送る簡易クライアント。既存テストコードに daemon
    client 相当が存在するか要確認 (design phase)。
- **Research Needed**:
  - Ping/Pong 確認の最小クライアント (`kolt daemon` サブコマンド経由で
    済むか、独立バイナリが要るか)。
  - native job と companion job の artifact 共有 (`actions/upload-artifact`
    + `actions/download-artifact`) のコスト。

### Req 5: Gradle 並走の健全性

- **Exists**: `./gradlew build` が daemon fat jar + `stageBtaImplJars` を
  生成する既存経路。
- **Missing**: なし (並走維持のための能動的作業は不要)。
- **Constraint**: 本スペックの変更が `shadowJar` / `verifyShadowJar` /
  `stageBtaImplJars` / `DaemonJarResolver` の DevFallback 経路を一切
  変えないこと。チェック手段は既存 `unit-tests.yml` の jar assertion。

## 実装アプローチの選択肢

### Option A: 既存 pipeline を最小拡張 (推奨)

- **内容**:
  - `Builder.kt` に `outputRuntimeClasspathPath(config)` を追加。
  - `resolveDependencies` が返す classpath 文字列に加え、jar list
    (`List<String>`) も公開する。単一戻り値を `Pair<String?, List<String>>`
    か data class に切り替える、あるいは別関数 (`resolveJvmRuntimeJars`)
    を追加するのいずれか (design 段階で選定)。
  - `BuildCommands.kt` の JVM `kind = "app"` 経路末尾で、Req 2 AC 1-8
    を満たす manifest を書き出す (sort、tie-break、self 除外、
    native/lib で emit しない)。
  - 2 daemon `kolt.toml` を新規作成。
  - `scripts/assemble-dist.sh` を新規作成 (pre/post モード両対応)。
  - `self-host-smoke.yml` に companion job を追加。
- **トレードオフ**:
  - ✅ 既存の下向き依存方向 (cli → build → resolve) を崩さない。
  - ✅ 変更範囲が既存層の中に収まる (新規パッケージ不要)。
  - ✅ ADR 0027 の pin にそのまま乗る。
  - ❌ `resolveDependencies` の戻り値変更は 2 〜 3 callers に波及する。
    (design phase でシグネチャを具体化)。

### Option B: Manifest 専用の新モジュール

- **内容**: `kolt.build.manifest` パッケージを新設し、manifest 生成
  ロジックを分離。`BuildCommands.kt` からは薄く呼ぶ。
- **トレードオフ**:
  - ✅ manifest の schema 変更に対する変更範囲が局所化される。
  - ❌ 現時点の manifest ロジックは「sort して書く」程度で、専用
    パッケージを切るほどの厚みがない。steering structure.md の
    「responsibility で分ける」基準に対して over-engineering 気味。
  - ❌ Option A と比べて追加ファイルが増えるが、得られる隔離効果は
    限定的。

### Option C: Hybrid (Option A + assemble-dist.sh を段階リリース)

- **内容**: Req 1, 2 (kolt 内の emit) を先に landing、Req 3, 4
  (assemble-dist.sh + CI companion) を次の PR で追加。
- **トレードオフ**:
  - ✅ 小さい PR で landing でき、レビュー集中が効く。
  - ✅ 万一 manifest schema を実装段階で微調整する必要が出ても、
    assemble-dist.sh 側の修正を後追いで済ませられる。
  - ❌ #97 の DoD を閉じる順序が複数 PR に跨る。issue 閉じまで時間が
    かかる。

**推奨**: **Option A を軸に、タスク粒度で C を採用** (Option A の
技術方針で実装しつつ、landing は 2 〜 3 PR に分ける)。理由:

1. 設計は ADR で pin 済みなので、新モジュール分離の必然性がない (B 却下)。
2. `resolveDependencies` の戻り値変更が波及するので、その単独変更を
   独立 PR にするとレビューコストが下がる (C の分割アプローチ)。
3. `scripts/assemble-dist.sh` + CI companion は build pipeline の内側
   ロジックとは切り離して review できる (C の分割アプローチ)。

## 複雑度と Risk

- **Effort: M (3〜5 日)**
  - 既存 pattern に沿う最小拡張が主体。spike で spec の実現可能性は
    確認済み。
  - `scripts/assemble-dist.sh` の新規作成と CI companion 配線が最多の
    新規コードだが、schema は pin されており迷いなく書ける。
- **Risk: Low**
  - 新規 public schema (manifest 形式) は ADR 0027 で pin。
  - Gradle 並走経路を一切触らない前提 (Req 5)。
  - 既存 `self-host-smoke.yml` job を temper しない (companion 追加のみ)。

## Research Needed (design phase で決めること)

1. `resolveDependencies` の API 形状変更方法 (戻り値拡張 vs 別関数追加)。
   callers (`BuildCommands.kt` JVM / native 双方) への波及を見て最小
   変更を選ぶ。
2. `assemble-dist.sh` のモード切替方式 (flag / env var / 自動検出)。
   daemon `kolt.toml` の存在有無による自動検出が最も軽量。
3. argfile の platform separator 扱い (linuxX64 only 前提で `:` 固定、
   または `uname` で判定)。現状サポート target は linuxX64 のみ。
4. CI companion job での Ping/Pong 検証クライアント (`kolt daemon`
   サブコマンドの既存機能で代替できるかを design 段階で確認)。
5. ADR 0017 (bootstrap JDK provisioning) と `[build] jdk = "21"` の
   配線が、`kolt-jvm-compiler-daemon/kolt.toml` で実効的に効くか。
   spike は host の system JDK 21 が利用可能だったため素通り。
   design 段階でコード検索して配線の有無を確定する。

## 次フェーズへの持ち越し

- **Design phase** で ADR 0027 §1 の `outputRuntimeClasspathPath` 参照
  先を `kolt/config/Config.kt` ではなく `kolt/build/Builder.kt` に
  訂正する (1 行修正、別 commit)。
- **Design phase** で `resolveDependencies` のシグネチャ案を固め、
  `kolt run` が manifest を読まない invariant をコード上で担保する
  方法を具体化する (Req 2 AC 8)。
- **Tasks phase** で Option C に従った PR 分割案を提示する。

---

# Design Phase Synthesis (2026-04-22)

## Synthesis outcomes

### Generalization
- `outputRuntimeClasspathPath` は `outputJarPath` / `outputNativeKlibPath` /
  `outputKexePath` の延長として同 `Builder.kt` に置く。新しい抽象層を
  挟まず、既存 path-helper pattern を素直に拡張。
- Manifest format (sort + tiebreak + self 除外) は Kotlin stdlib の
  `sortedWith(compareBy(..., ...))` で宣言的に表現でき、ロジックを
  専用パッケージに切り出す必要はない。

### Build vs Adopt
- **Adopt**: 既存 `resolveDependencies` の内部 jar list (`resolveResult.deps`)、
  既存 `installJdkToolchain` による `jdk = "21"` プロビジョニング配線
  (`ToolchainCommands.kt:44-45` で確認済み、spec 側で新規配線不要)、
  既存 daemon Gradle build (並走維持)。
- **Build**: `JvmResolutionOutcome` data class は resolver と emit の間の
  型安全な境界として新規。小ぶり (2 フィールド) のため build。
- **Not Build (Simplification)**: `kolt daemon ping` 新 CLI サブコマンド
  は追加しない。Req 4.4 の IPC 健全性 verification は「installed
  `bin/kolt` で fixture project を build 成功させる」で cover できる。
  `kolt build` 内部で既に daemon IPC が使われるため、build 成功は IPC
  往復の正常性を含意する。

### Simplification
- Req 4 CI companion の Ping/Pong 検証は fixture build 成功で代替。
  専用 IPC チェックツール不要。
- assemble-dist.sh の pre/post モード共有化は見送り。pre モードは
  Gradle 経由で生成された fat jar を `libexec/<daemon>/<daemon>.jar` に
  直置きする簡易 layout、post モードは ADR 0018 §1 の deps/ 展開 layout、
  と明示的に二分岐。
- stale manifest (kind 変更で app→lib になったあと残った manifest) は
  kolt 側で自動削除。assemble-dist.sh に「念のため古い manifest を
  disregard」のようなロジックを持ち込まない。

## Research-needed items — resolved

| # | 項目 | 決定 |
|---|------|------|
| 1 | `resolveDependencies` API 形状変更 | `JvmResolutionOutcome(classpath: String?, resolvedJars: List<ResolvedJar>)` data class を導入。戻り値型を `Result<String?, Int>` から `Result<JvmResolutionOutcome, Int>` に拡張、2〜3 callers を追従修正 |
| 2 | assemble-dist.sh モード切替 | 自動検出 (2 daemon `kolt.toml` 両方存在 → post、一方でも欠落 → pre)。`--mode=pre\|post` フラグで明示上書き可能 |
| 3 | argfile platform 対応 | `:` 固定、linuxX64 only 前提。macOS / Windows 対応時に separator ロジック追加 (本スペック out of scope) |
| 4 | Ping/Pong 検証クライアント | 専用コマンド追加せず。CI companion が installed `bin/kolt` で fixture build を走らせ、成功を IPC 健全性の evidence とする |
| 5 | `jdk = "21"` 配線 | 既存 `ToolchainCommands.kt:44-45` で `installJdkToolchain(config.build.jdk, paths)` が呼ばれることを確認済み。本スペックで追加配線不要 |

## Design-phase follow-ups to record

- ADR 0027 §1 の helper 参照先 `kolt/config/Config.kt` を
  `kolt/build/Builder.kt` に 1 行修正する (Phase 1 タスクに含める)。
- 既存 daemon Gradle build の `shadowJar` / `verifyShadowJar` /
  `stageBtaImplJars` / `ic/build.gradle.kts` は本スペックの変更範囲に
  含まない (Req 5)。assemble-dist.sh が BTA impl jars を取得する
  ロジックは ADR 0019 §3 を守る形で別途設計するが、pre モードでは
  Gradle 生成物をそのまま流用して済む (post モードでは `-impl` を
  Maven 解決するコードを追加、本スペックでは Phase 3 タスクに含める)。
