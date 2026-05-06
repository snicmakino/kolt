# Gap Analysis — 380-share-classpath-snapshots

最終更新: 2026-05-06

## 1. Current State Investigation

### 1.1 関連モジュール / 配置

- `kolt-jvm-compiler-daemon/ic/src/main/kotlin/kolt/daemon/ic/`
  - `BtaIncrementalCompiler.kt` — BTA 経由の incremental compile 本体。`shrunk-classpath-snapshot.bin` の生成・配置を所有する。
  - `ClasspathSnapshotCache.kt` — per-jar `.snapshot` の global キャッシュ（`~/.kolt/daemon/ic/<v>/classpath-snapshots/`）
  - `IcStateLayout.kt` — IC ステートの path 計算と `CompileScope` の定義
  - `SelfHealingIncrementalCompiler.kt` — wipe+retry ラッパ（#384 で latch を `(projectId, workingDir)` キー化済み）
- `kolt-jvm-compiler-daemon/src/main/kotlin/kolt/daemon/`
  - `Main.kt` — daemon 起動、`IcReaper.runDetached(...)` 起動
  - `server/DaemonServer.kt` — wire `Compile` を `IcRequest` に翻訳、`workingDirFor(...)` を呼ぶ
  - `reaper/IcReaper.kt` — stale `<projectIdHash>` ディレクトリの掃除。LOCK + breadcrumb で生死判定
- `src/nativeTest/kotlin/kolt/cli/`
  - `MultiShapeDaemonTestCoverageIT.kt` — #381 で導入された multi-shape 統合テスト（regression guard として使える）
- `spike/bench-scaling/` — 既存 bench harness（ADR 0016 のサブプロセス基準時間計測で使用済み）

### 1.2 既存の規約 / パターン

- **Result ベース error handling**：`kotlin-result` の `Result<V, E>`、例外禁止（ADR 0001）
- **per-jar snapshot キー**：`SHA-256("path|mtime|size")` 先頭 16byte hex（`ClasspathSnapshotCache.kt:109-113`）
- **scope-segregated working dir**：`<icRoot>/<kotlinVersion>/<projectIdHash>/<scope>/`（`IcStateLayout.kt:53-58`）
- **LOCK / breadcrumb at `<projectIdHash>` レベル**：`projectStateDir = workingDir.parent`、LOCK は scope 全体で共有（`BtaIncrementalCompiler.kt:119-130`）
- **Self-heal は workingDir を wipe**：scope 単位の wipe であり、parent dir（LOCK 含む）は維持される（ADR 0019 §7）
- **scope の wire ↔ ic の二重 enum**：`Message.CompileScope` (wire) ↔ `IcCompileScope` (ic)、`DaemonServer.kt:272-276` で 1:1 マッピング

### 1.3 統合面 / 依存

- **BTA (kotlin-build-tools-api 2.3.x)** — `KotlinToolchains` を介した snapshot 計算と `snapshotBasedIcConfigurationBuilder`。版間 binary compat は 2.3.x 系内のみ確認済み（`spike/bta-compat-138/REPORT.md`）
- **kotlinx.serialization** — wire codec（変更しない）
- **kolt-jvm-compiler-daemon サブプロジェクト** — 唯一の bench / regression guard 対象として要件に明記

## 2. Requirements Feasibility Analysis

### 2.1 要件 → 既存資産マッピング

| Req | 既存資産 | ギャップ | タグ |
|---|---|---|---|
| 1.1 同一 classpath なら snapshot 再利用 | `ClasspathSnapshotCache` のキャッシュ機構（per-jar）、`SHRUNK_CLASSPATH_SNAPSHOT` 定数 | shrunk 用のキャッシュ層が存在しない | Missing |
| 1.2 strict ordered prefix で増分拡張 | — | BTA の "extend shrunk snapshot" API が未確認 | Unknown |
| 1.3 fallback to from-scratch | 既存の cold-path 経路がそのまま流用可能 | — | OK |
| 1.4 stale 検出 | `(path, mtime, size)` で per-jar はやっている | shrunk 単位でも同じ手法が拡張可能 | Constraint |
| 1.5 scope segregation 維持 | `IcStateLayout`, per-scope `inputs/`（#376） | shrunk のみ共有、`inputs/` は scope ごと、を明示する設計 | Constraint |
| 2.x cold-path 計測 | `spike/bench-scaling/` あり | multi-shape × scope 順序を測る scenario 追加が必要 | Missing |
| 3.x warm 不変 | warm ベースライン (~490ms) は dogfood に記録 | 現在 self-host smoke 以外で連続計測する仕組みがない | Missing |
| 4.x reaper safety | `IcReaper`、`LOCK`、breadcrumb、`projectStateDir` | 新 cache 配置場所によって reaper のスコープと protect 条件を再評価 | Constraint |
| 5.x wire / friendPaths 不変 | `DaemonServer.messageCompileToIcRequest`、現状 wire はそのまま | 変更しないことを test で固定 | OK |
| 6.x multi-shape regression | `MultiShapeDaemonTestCoverageIT` | shrunk reuse 経由のアサート（IC 配下の存在確認）を 1 ケース追記 | OK |

### 2.2 主要な未確認事項（Research Needed）

これらは design / spike phase で決着が必要：

1. **BTA は "extend shrunk snapshot" を公式に supports しているか？**
   - 観察：`snapshotBasedIcConfigurationBuilder(workingDirectory, sourcesChanges, dependenciesSnapshotFiles, shrunkClasspathSnapshot)` の signature には base shrunk snapshot を渡す引数がない（`BtaIncrementalCompiler.kt:198-206`）
   - 仮説：BTA は shrunk を毎回 from-scratch で計算する。`shrunkClasspathSnapshot` 引数は **出力 path** であって入力 base ではない
   - **Spike が必要**：`spike/bta-shrunk-extend/` を新設し、(a) BTA に既存 shrunk file を渡したら活用するか、(b) shrunk file は workingDir 非依存に再利用可能か、を実測

2. **shrunk snapshot ファイルは workingDir 非依存に portable か？**
   - 観察：BTA snapshot は opaque binary、saveSnapshot/loadSnapshot 経由
   - もし workingDir / source set の状態を baked-in しているなら project 横断（同 classpath で複数プロジェクト）の cache 化はできない
   - **Spike が必要**：shrunk file を別 project の workingDir にコピーして compile が通るか

3. **BTA snapshot binary 形式は Kotlin minor 版間で stable か？**
   - 観察：`spike/bta-compat-138` は 2.3.x 系内 binary compat を確認済み、2.x 系跨ぎは RED
   - 緩和策：cache directory に `<kotlinVersion>` を含める（既存の `IcStateLayout` がやっている）。これで OK

### 2.3 複雑度シグナル

- アルゴリズム：classpath ハッシュ計算 + ファイルシステムベースキャッシュ（既存 `ClasspathSnapshotCache` と同一形）
- 並行性：daemon 内同時 compile（同一 project の main/test 並行 / 別 project 並行）への対応が必要 — 既存の `compute()` パターンで吸収可能
- 永続化：shared cache が reaper safe であること
- 外部 SDK 依存：BTA semantics の不確実性（最大の risk）

## 3. Implementation Approach Options

### Option A: Global classpath-keyed shrunk snapshot cache（推奨）

**位置**：`~/.kolt/daemon/ic/<kotlinVersion>/shrunk-snapshots/<classpathHash>.bin`（`classpath-snapshots/` と並列の新ディレクトリ）

**Key**：classpath エントリ列の `(path, mtime, size)` 列を順序付きで連結 → SHA-256

**フロー**：
1. compile 開始時、classpath から hash 計算
2. `<v>/shrunk-snapshots/<hash>.bin` が存在 → per-scope `bta/shrunk-classpath-snapshot.bin` へ hardlink or copy
3. 不在 → BTA に従来通り compute させ、完了後に shared 位置へ copy（atomic rename）
4. Reaper：`<v>/shrunk-snapshots/` 全体を `<v>/classpath-snapshots/` と同じく version-key 単位で管理（projectId 非依存）

**利点**：
- ✅ プロジェクト横断で再利用可能（kolt-jvm-compiler-daemon と kolt-native-compiler-daemon が同じ deps を使うとき双方が hit）
- ✅ Self-heal が per-scope `workingDir` を wipe しても shared cache は無事
- ✅ 既存 per-jar cache と同じ idiom、reaper 規則も "non-current Kotlin version dir を消す" を再利用するだけ
- ✅ sibling-scope path manipulation 不要 — classpath だけ見れば良い
- ✅ Req 1.1（同一 classpath 再利用）を素直に満たす

**欠点・リスク**：
- ❌ Req 1.2（strict prefix 増分拡張）は **BTA がそれを supports していなければ実装不能**（高確率で不能）
- ❌ shrunk file が workingDir/source 状態を baked-in していると portable でない → spike 要
- ❌ atomic rename / hardlink の concurrency（同時に同じ hash の compute が走る）の制御が必要

**前提**：上記 Research Needed #1 と #2 が肯定的に解決すること。否定なら Option B にフォールバック。

### Option B: Sibling-scope speculative reuse（Option A が NG な場合）

**位置**：従来の per-scope `<projectId>/<scope>/bta/shrunk-classpath-snapshot.bin`（変更なし）

**フロー**：
1. test scope の compile 開始時、`<projectId>/main/bta/shrunk-classpath-snapshot.bin` を確認
2. 存在し、main 用 classpath（別途 sidecar に保存しておく）と test classpath が一致 / strict prefix → そのファイルを test 用 path に hardlink or copy
3. 不在 / 不一致 → 通常の cold-path

**追加で必要**：
- main scope compile 時に "このファイルが対象とした classpath ハッシュ" を sidecar JSON で残す（同じ `bta/` 配下に `shrunk-meta.json` 等）
- test → main の path 計算は `request.workingDir.parent.resolve("main/bta/...")` で OK

**利点**：
- ✅ shared cache 機構を新設しないので reaper への影響が最小（既存 LOCK の傘下に収まる）
- ✅ shrunk file の portability 不安定でも、同 projectId 内なら同じ source-set コンテキストで安全な可能性が高い（risk が局所化）

**欠点・リスク**：
- ❌ プロジェクト横断の reuse はない（kolt-jvm-compiler-daemon の main と kolt-native-compiler-daemon の main で重複計算）
- ❌ 順序依存（main を先に build しない限り効かない） — 多くのワークフローで OK だが、`kolt test` 単独実行は依然 cold
- ❌ self-heal で main scope dir が wipe されると test の reuse も失敗する → 局所的な regression を生む

### Option C: Hybrid（A + B）

A の global cache を primary、B の sibling-scope を fallback / fast-path として持つ。

- 同一 project の連続 build/test では sibling 参照（直近のローカリティを活かす）
- 異なる project や cold daemon 起動直後は global cache 参照

**評価**：実装複雑度が増し、両系統の test とリーパー設計が必要。明確な利点が小さい場合、初期は A 単独を推奨。**Option A を最初に選び、A の cache hit 率が低いと観測された場合に C へ拡張**するのが妥当。

## 4. Effort & Risk

- **Effort: M (3–7 days)**
  - 内訳：spike 1日（BTA 挙動確認）、Option A 実装 2日、reaper 改修 0.5日、test（unit + IT + bench）2–3日
  - Option B フォールバック分岐が必要な場合 +1日

- **Risk: Medium**
  - High 寄りの要因：BTA snapshot の portability が未確認、silent corruption は ADR 0016 fallback で拾えない
  - Low 寄りの要因：既存の per-jar cache idiom を再利用、wire / scope segregation 不変、影響は daemon 内に局所化

## 5. Recommendations for Design Phase

### 5.1 推奨アプローチ

**Option A（global classpath-keyed shrunk cache）を主軸、Option B を spike 結果次第のフォールバックとして温存**。

理由：
- Req 1.1 を最も素直に達成
- 既存 per-jar cache と一貫した path 規約 / reaper 規約で済む
- プロジェクト横断 hit は副次的だが将来価値が大きい

### 5.2 主要な設計判断

1. **Spike を design phase 冒頭で実施**：`spike/bta-shrunk-portability/` を新設し以下を 1 日以内で結論付ける：
   - shrunk file を別 workingDir に置いて再利用できるか
   - BTA に既存 shrunk file を渡すと活用するか、それとも常に上書き再計算するか
   - 結論次第で Option A / B / 撤退（Req 1.1 のみ実装し 1.2 を defer）を確定
2. **Req 1.2 の取り扱い**：BTA が増分拡張を提供しないことが判明した場合、Req 1.2（strict prefix 増分）は **scope を絞って "プレフィックス一致なら同じ snapshot を再利用" に縮小**することを設計書で提案（reuse のみ、extend はやらない）
3. **Reaper の新規則**：`<v>/shrunk-snapshots/` は projectId 横断グローバルなので、reaper のスコープを「stale `<projectIdHash>` 削除」と「stale `<v>/shrunk-snapshots/<hash>.bin` 削除」の二系統に拡張するか、あるいは shrunk-snapshots は LOCK 不要（再生成可能 + version-key 内 GC）と割り切るかを設計
4. **Bench harness の拡張**：`spike/bench-scaling/` を流用しつつ、`kolt build → kolt test` 連続シナリオを追加。WSL2 9p 1s mtime granularity は既知の罠（synthetic epoch 必須）
5. **silent corruption リスクへの対策**：reuse で生成された `.class` の正しさを Multi-shape IT で hash 比較する（reuse on/off 両モードで `build/classes/` 配下の `.class` ファイルが byte-identical であること）

### 5.3 Carry-forward Research Items

- **R-1**: BTA `snapshotBasedIcConfigurationBuilder` が既存 shrunk file を入力として活用するか（spike 必須）
- **R-2**: BTA shrunk snapshot は workingDir / source set を baked-in しないか（spike 必須）
- **R-3**: 同 classpath で異なる Kotlin compiler flag 設定（`-Xfriend-paths` 等）が shrunk snapshot に反映されるか — されるなら hash key に compiler flag も含める必要

### 5.4 Out-of-Scope 確認

設計フェーズで以下は *扱わない*：
- Native compile (konanc) 側の snapshot
- multi-module / multi-project 横断シナリオ
- daemon 起動時間 / classloader リワーク

---

## 6. Design Phase Discovery (2026-05-06 追記)

BTA 2.3.20 ソース直読み（`gh api repos/JetBrains/kotlin/contents/...?ref=v2.3.20`）で以下を確定。

### 6.1 BTA snapshot file の portability — 確定 YES

`compiler/incremental-compilation-impl/src/.../classpathDiff/ClasspathSnapshot.kt`：

```
class ClasspathEntrySnapshot(
    /**
     * NOTE: It's important that the path to the classpath entry is not part of this snapshot.
     * The reason is that classpath entries produced by different builds or on different
     * machines but having the same contents should be considered the same for better build performance.
     */
    val classSnapshots: LinkedHashMap<String, ClassSnapshot>
)
```

- shrunk file は class metadata（classId, classAbiHash, member-level snapshot, supertypes）のみを serialize し、絶対パス・workingDir・project 識別子を embed しない
- 別 workingDir / 別 project に copy しても BTA が拒否する根拠なし
- **結論**：`~/.kolt/daemon/ic/<v>/shrunk-snapshots/<classpathHash>.bin` に保管し、各 scope の `bta/` 配下に copy/hardlink する設計は安全

### 6.2 `shrunkClasspathSnapshot` 引数の input/output 性 — 両方

`JvmSnapshotBasedIncrementalCompilationConfiguration.kt`：
- KDoc 上は "The path to the shrunk classpath snapshot file from a previous compilation"（input 想定の文言）
- `ClasspathSnapshotShrinker.shrinkAndSaveClasspathSnapshot()` 実装：compile 終了後に必ず write（output）。同 path に既存ファイルがあればそれを **previous** として読み、内部の incremental shrink optimization に活用する

**設計含意**：cached file を per-scope output path に **pre-place** すれば、BTA は (a) 自身で write するので破壊リスクなし、(b) 既存 file を previous として活用する optimization 経路に乗る可能性がある。後者の発火条件は内部実装依存のため、spike で実測確認する。

### 6.3 公開 API での extend 操作 — 不在

`JvmClasspathSnapshottingOperation`, `JvmSnapshotBasedIncrementalCompilationConfiguration.Builder` には extend / append / merge 系メソッドなし。Req 1.2（strict prefix 増分）を **公開 API で直接** 表現する手段はない。

**設計含意**：
- Req 1.2 の素直実装は不可能
- ただし「cached file を pre-place → BTA 内部 optimization で部分活用」という間接経路の効果は spike で測定可能
- requirements.md は「同一 classpath 完全再利用」+「prefix 一致時は best-effort で BTA 内部 optimization に委譲」の二段に分けて再表現することが妥当

### 6.4 BTA 内部 incremental shrink ロジック

`ClasspathSnapshotShrinker.kt:248-313`（v2.3.20）：

```
val shrunkClasses = shrunkCurrentClasspathAgainstPrevLookups.mapTo(mutableSetOf()) { it.classId }
val notYetShrunkClasses = currentClasspath.filter { it.classId !in shrunkClasses }
val shrunkRemainingClassesAgainstNewLookups = shrinkClasses(notYetShrunkClasses, shrinkMode.addedLookupSymbols)
val shrunkCurrentClasspath = shrunkCurrentClasspathAgainstPrevLookups + shrunkRemainingClassesAgainstNewLookups
```

`previousShrunkClasses` に存在しない classId のみを再 shrink する流れが内部にある。発火は `shrinkMode` の状態次第で、外部から強制する API はない。**spike で発火条件と効果を実測する**。

### 6.5 設計確定事項

| 項目 | 決定 | 根拠 |
|---|---|---|
| Cache 配置 | `~/.kolt/daemon/ic/<kotlinVersion>/shrunk-snapshots/<classpathHash>.bin` | per-jar cache と同じ idiom、reaper も version-key で一括管理可 |
| Cache key | SHA-256 of ordered `(path|mtime|size)` over classpath entries | per-jar `ClasspathSnapshotCache` と同方式 |
| Pre-compile hook | cache hit → per-scope path に hardlink、miss → 何もしない | BTA に対して input でも output でも安全 |
| Post-compile hook | per-scope file を cache へ atomic rename copy | 並行 write は last-write-wins 安全（content 決定的） |
| 同時実行制御 | per-key in-memory mutex（`ConcurrentHashMap.compute`）+ atomic rename | per-jar cache と同パターン |
| Reaper | shrunk-snapshots は version dir 単位で wipe 可（recomputable）。LOCK 不要 | per-jar cache と同 risk profile |
| Req 1.2 の扱い | requirements を "best-effort prefix optimization via BTA internal logic" に降格再表現 | 公開 API で contractual に保証できないため |

### 6.6 Spike T1 計画（design 採択前提）

`spike/bta-shrunk-portability/` を新設し、1 日以内に：

1. 同一 classpath で daemon を 2 回起動 → shrunk file が cached path から copy で per-scope に運ばれた状態で BTA が compile を完了するか確認（portability の実機検証）
2. main classpath の shrunk file を test workingDir に pre-place → test classpath（main + 追加 jar）で compile → BTA log / wall-time を計測し、内部 incremental optimization の発火を確認
3. 結果に応じて Req 1.2 の最終形を決定（fully drop / soften to best-effort / keep with measured target）
