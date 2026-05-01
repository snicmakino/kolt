# Gap Analysis: toml-change-handling

Date: 2026-05-01
Phase: requirements-generated → design (pre-design gap analysis) → design-generated (synthesis applied)
Investigator: Explore agent + main analysis

## 0. Design Synthesis Outcomes (added during design phase)

### Generalization
- 9 Requirement は SectionAction 3-value taxonomy (AutoReload / NotifyOnly / NoOp) に既に収束。 requirements 確定時点で抽象化済、 追加 generalization なし

### Build vs Adopt
- inotify event polling、 `settleAndDrain` debounce、 `parseConfig`、 `KoltConfig` data-class equality、 `Result<V, E>`、 `eprintln` stderr — すべて既存 stack を **adopt**
- 新規 library なし。 ChangeMatrix の matrix table 部分のみ **build** (project-specific config matrix logic、 既存 library で代替不能)

### Simplification
- **In-flight build flag を導入しない**: WatchLoop event handler が synchronous (line 211) で OS inotify buffer が暗黙 serialize するため、 Req 7.1〜7.3 は構造的に成立済。 explicit flag は speculative。 handler 同期前提を Revalidation Triggers に明記し、 将来非同期化時に再検討
- **`SectionAction.Deferred` 第 4 variant を持たない**: 3-value taxonomy (Req 8.2) を維持。 schema-未知 section は defensive `NotifyOnly("This section is not yet classified...")` fallback で扱う
- **`commandRunner` lambda の signature 変更なし** (R-5 結論): rebuild skip は handler 側で `commandRunner` を call しないことで表現。 lambda 拡張は不要

## 1. 現状アセット (Current State)

### 1.1 Watch loop 中核 (`src/nativeMain/kotlin/kolt/cli/WatchLoop.kt`)

- **Entry**: `watchCommandLoop()` (line 164) と `watchRunLoop()` (line 278) の 2 系統。 各々 `KoltConfig` を startup 時に 1 回 load (line 181, line 286) して保持
- **Event loop**: `pollEvents(timeoutMs = 500)` for build/test、 200ms for run。 inotify driven、 OS が event を serialize する前提
- **Debounce**: `settleAndDrain()` (line 150) が 100ms poll を idle まで loop。 既存ロジックが debounce window として機能している
- **kolt.toml 検知**: line 111 で `event.name == "kolt.toml"` を ROOT_DIR watch で検知済 — 検知自体は今日の動作
- **Rebuild 起動**: line 211 で `commandRunner(...)` を **synchronous call**。 lambda parameter (line 170-178) で渡された関数を直接呼ぶ。 OS inotify buffer が rebuild 中の event を保持
- **State**: `wdKinds: MutableMap<Int, WatchKind>` (line 188)、 `sigintReceived: AtomicInt` (line 42)。 **in-flight build flag は無い** が、 handler 自体が blocking なので暗黙 serialize されている
- **出力**: `eprintln()` (stdlib) で stderr。 既存 marker は `\n--- change detected, rebuilding ---` (line 210, 219, 359)、 `watching for changes (Ctrl+C to stop)...` (line 191)、 `error: ...` (error path)。 **section 単位の構造化出力は無い**
- **Result usage**: `loadProjectConfig()` は `Result<KoltConfig, Int>` を返す。 watch 内エラー時は `getOrElse { ... }` で fallthrough、 throw 無し ✓

### 1.2 Config 表現 (`src/nativeMain/kotlin/kolt/config/Config.kt`)

- **`KoltConfig`** (line 92): 11 field の `@Serializable data class`
- **Sections**: `KotlinSection` (line 52) / `BuildSection` (line 65) / `TestSection` (line 82) / `RunSection` (line 87)、 すべて `data class` ⇒ **auto-generated `.equals()` が利用可能**
- **Diff**: `oldConfig.dependencies != newConfig.dependencies` のような section-level 比較が一発で書ける。 ただし「どの section が変わったか」を列挙する helper は無い
- **Parse**: `parseConfig(tomlString: String): Result<KoltConfig, ConfigError>` (line 388) は pure function、 副作用無し
- **Load**: `loadProjectConfig()` (BuildCommands.kt:242-256) が disk read + parse の典型 entry point

### 1.3 Per-invocation 経路 (`DependencyCommands.kt` 等)

`loadProjectConfig()` の call site 11+ 箇所:

| 箇所 | 用途 |
|---|---|
| BuildCommands.kt:266 | `doCheckInner` |
| BuildCommands.kt:350 | `doBuildInner` |
| BuildCommands.kt:953 | `doTestInner` |
| DependencyCommands.kt:39 | `doAddInner` (parse 用、 rewrite 前) |
| DependencyCommands.kt:140 | `doInstallInner` |
| DependencyCommands.kt:159 | `doUpdateInner` |
| DependencyCommands.kt:245 | `doTree` |
| FormatCommands.kt | `kolt fmt` |
| ToolchainCommands.kt | `kolt toolchain *` |
| WatchLoop.kt:181, :286 | watch startup |

**結論**: Requirement 1 / 8 が要求する「毎 invocation で fresh load」 は **既に成立**。 stale config caching の edge case 無し。 Requirement 1 への対応はコード変更不要、 ADR / docs 化のみ。

### 1.4 既存テスト (`src/nativeTest/kotlin/kolt/cli/WatchLoopTest.kt`)

- `CollectWatchPathsTest` (lines 9-79、 7 件): path 収集の unit test
- `ShouldTriggerRebuildTest` (lines 81-124、 8 件): file filter (`.kt`, `.kts`, `kolt.toml`, dotfile, vim swap, temp)
- **無し**: inotify event loop の integration test、 debounce timing test、 config reload test
- Test 基盤: `kotlin.test.*` + `testConfig()` helper (root test package)、 async fixture 無し

### 1.5 ADR style 参照 (`docs/adr/0032-kolt-toml-env-agnostic.md`)

- 構造: Status → Date → Summary (bullets) → Context → Decision Drivers → Considered Options → Decision Outcome (§1〜§4 + Consequences) → Alternatives → Related
- 長さ: ~110 行
- Cross-ref: ADR 0028、 spec design 文書、 file references (line 番号付き)、 issue 番号
- **次番号**: ADR 0033 ✓

---

## 2. Requirement-to-Asset Map

注: Req 番号は requirements.md 改訂後 (9 Requirement 体制) に整合。

| Req | Title | Status | Existing Assets | Missing / Constraints |
|---|---|---|---|---|
| **1** | Per-invocation matrix | **Present (undocumented)** | `loadProjectConfig()` 11+ 箇所で fresh load、 `doAdd → doInstallInner` 連鎖 | ADR 0033 の per-invocation matrix 表、 `docs/architecture.md` への追記 |
| **2** | Watch detection + classification | **Partial** | kolt.toml 検知 (line 111)、 debounce (`settleAndDrain` line 150)、 `KoltConfig` data class equality | section-level diff 関数、 不明 section の deferred 通知、 parse 失敗時の retain ロジック |
| **3** | Auto-reload dispatch (rebuild + no-rebuild sub-categories) | **Missing** | `loadProjectConfig()` を呼ぶ手段は揃っている、 `collectWatchPaths()` で watcher path 構築可能 | 「reload + 既存 path 集合との diff + watcher 再構築」 のフロー、 sub-category 分岐 (rebuild 要否)、 reload 後 rebuild 起動 |
| **4** | Notify-only dispatch (incl. mixed-window prevail) | **Missing** | stderr 出力 helper (`eprintln`) | section → recommendation のテーブル、 通知発火後の rebuild skip ロジック、 既存 config retain、 mixed-window 検出と prevail logic |
| **5** | No-op dispatch (`[fmt]`) | **Missing** | (なし — そもそも何もしない) | matrix で NoOp 判定 → スキップする dispatch path |
| **6** | Notification contract | **Missing** | 既存 marker (`--- text ---` on stderr) | 1 group / 多 section 列挙、 同一 section 集約、 marker 区別 (新通知 vs 既存 rebuild status) |
| **7** | Build serialization | **Partial** | handler synchronous (line 211 blocking)、 OS inotify buffer の暗黙 serialize | 明示的 in-flight flag、 「reload を build 完了まで hold」 の state machine (現状は kernel buffer 任せで明示性に欠ける) |
| **8** | ADR 0033 + SectionAction taxonomy + matrix + tests | **Missing** | ADR 0032 を template として参照可、 `WatchLoopTest.kt` 既存 fixture | ADR 0033 起草、 SectionAction taxonomy (3-value) の文書化、 per-invocation / watch 2 表、 matrix セル単位の acceptance test、 `docs/architecture.md` cross-ref |
| **9** | Backward compatibility | **Present** | 非 watch 経路は本 spec で touch しない、 daemon は kolt.toml 読まない (verified) | assertion のみ (regression test) |

**Critical gaps**:
- Req 3 (internal-only dispatch) — config reload + watcher 再構築の new flow
- Req 4 (notify-only dispatch) — section 分類テーブル + dispatch logic
- Req 5 (notification format) — multi-section 列挙 + marker design
- Req 7 (test infra) — section-diff の table-driven test は新規だが unit test 範囲、 watch loop の integration test は infra 不在 (mock inotify or fs fixture)

---

## 3. Implementation Approach Options

### Option A: WatchLoop.kt に直接拡張

`watchCommandLoop` / `watchRunLoop` の中に section diff・action dispatch・通知 format をすべて inline で追加。 既存ファイル単体に閉じる。

**Trade-offs**:
- ✅ 新規ファイル無し、 patch が小さい
- ✅ 既存 inotify ループとの接続が自明
- ❌ WatchLoop.kt が肥大化 (現状 ~400 行 → +200 行クラス、 単一ファイルとして読みづらくなる)
- ❌ section 分類が pure function として切り出されず、 unit test しにくい (matrix 表 = code がフラットに散らばる)
- ❌ matrix が「コード」と「ADR」で 2 重表現になり drift 余地

### Option B: 新モジュール `kolt.config.ChangeMatrix`

section 分類・diff・推奨アクションテーブルを pure function に切り出した新モジュールを作る。 `WatchLoop.kt` は event 検知と reload 起動・rebuild trigger のみ担当。 配置は `kolt.config` 配下 (kolt.watch ではない) — section 分類は config の知識であり、 `KoltConfig` を所有する config パッケージに置くのが整合的。 watch loop は ChangeMatrix を「使う側」、 ChangeMatrix 自体は watch に依存しない pure function。 将来 IDE/LSP 統合 (現 spec scope 外) が ChangeMatrix を使う際にも watch namespace に閉じない。

**新ファイル候補**:

```
src/nativeMain/kotlin/kolt/config/ChangeMatrix.kt
  - sealed class SectionAction {
      data class AutoReload(val rebuild: Boolean) : SectionAction()
      data class NotifyOnly(val recommendation: String) : SectionAction()
      object NoOp : SectionAction()
    }
  - data class SectionChange(name: String, action: SectionAction)
  - fun classifyChange(old: KoltConfig, new: KoltConfig): List<SectionChange>
  - fun formatNotificationGroup(changes: List<SectionChange>): String  // (R-4 by design)

src/nativeTest/kotlin/kolt/config/ChangeMatrixTest.kt
  - matrix セル単位の table-driven test (Req 8.13 を直接覆う)
```

**Trade-offs**:
- ✅ matrix が単一データソース (code) で表現され、 ADR は code から導出されたものとして書ける
- ✅ pure function で hermetic test 可能 (Req 8.13 の coverage が unit test 範囲で実現)
- ✅ WatchLoop.kt は event 処理のみで責務が明確
- ✅ `kolt.config` 配下に置くことで KoltConfig の所有者と整合し、 watch namespace に閉じない (将来の IDE/LSP 統合等の reuse に対応)
- ✅ 将来 section の追加は ChangeMatrix.kt の 1 関数を更新するだけ
- ❌ 新規ファイル + 新 sealed class の追加 (cognitive load 微増)
- ❌ WatchLoop.kt と ChangeMatrix.kt 間で `KoltConfig` 受け渡しの interface 設計が必要

### Option C: Hybrid — 段階的に Option B へ収束

Phase 1 で WatchLoop.kt にすべて inline (Option A) して挙動を仕上げ、 Phase 2 で ChangeMatrix を抽出。

**Trade-offs**:
- ✅ 動く実装が早く出る
- ❌ 「test しにくい inline」 → 「pure function」 のリファクタを後回しにすると、 inline 段階の test が薄いまま land しやすい
- ❌ 2 段階 PR / 2 段階レビューでコストが上がる

---

## 4. Effort & Risk

| 項目 | Effort | Risk | 理由 |
|---|---|---|---|
| ADR 0033 起草 | S | Low | 既存 ADR 0032 を template、 design call 確定済 |
| Per-invocation matrix doc | S | Low | コード不変、 docs/architecture.md + ADR 0033 §に追記のみ |
| `ChangeMatrix.kt` モジュール (Option B) | M | Low | pure function、 既存 data class equality 利用、 well-defined input/output |
| WatchLoop.kt への dispatch 配線 | M | Medium | watch 統合 test 無し、 振る舞いを既存挙動と整合させる必要、 `commandRunner` lambda の signature が既存 contract |
| 通知 format & multi-section 集約 | S | Low | stderr に書くだけ、 ChangeMatrix.kt 内で format helper |
| Build serialization の明示化 | S | Low | 構造的にすでに synchronous、 in-flight flag を 1 個追加するだけ |
| Matrix セル単位テスト (Req 7.9) | M | Low (Option B) / Medium (Option A) | Option B なら ChangeMatrixTest.kt に table-driven、 Option A なら WatchLoopTest にぶら下がるが mock infra が要る |
| Watch integration test (将来) | L | High | inotify mock or fs fixture の新 infra 必要、 本 spec scope 外として defer 候補 |

**Total**: M〜L (~1-2 週間)。 Option B 採用前提で M 寄り。

**Total Risk**: Low-Medium。 主たる不確実性は WatchLoop.kt 配線時の既存挙動との互換 (Req 8 backward compat) と、 watch integration test 不在のため手動検証で済ませる範囲の見極め。

---

## 5. Research Needed

### Resolved in requirements (no further action needed)

- ~~R-1 Mixed-window action dispatch~~ → Req 4.6 で notify-only prevails をピン
- ~~R-2 `[run.sys_props]` / `[test.sys_props]` seam~~ → Req 3.2/3.5 で AutoReload(rebuild=false) をピン
- ~~R-3 `[fmt]` / `name` / `version` / `kind` seam~~ → Req 3 (name/version=AutoReload+rebuild)、 Req 4.3 (kind/[build] target=NotifyOnly)、 Req 5 (`[fmt]`=NoOp) でピン。 SectionAction 3-value は Req 8.2 でピン
- ~~R-7 Watch integration test 基盤~~ → Boundary Context Out of scope に明記、 ChangeMatrix unit test + 手動 smoke で覆う

### Carried forward to design phase

R-4. **Parse failure 時の通知形式**: Req 2.5 で「parse error を notification として emit」を要求。 これが Req 6 の "1 group per debounce window" contract に乗るのか、 別ストリームか。 design でフォーマット決定。

R-5. **`commandRunner` lambda 引数の拡張可否**: WatchLoop.kt の `commandRunner` (line 170-178) は現在 build/test の lambda。 ChangeMatrix の判断結果を伝えるには signature 変更が要るか、 別経路 (例えば watch loop が rebuild 自体を skip する) が良いか。 後者で十分そう (Explore agent 見立て、 design で確認)。

R-6. **`docs/adr/template.md` の有無 / 整合**: 0032 を直接 copy するか template に従うか確認。

R-8 (新). **`[run.sys_props]` 変更時の running app 即時 respawn**: matrix 上は AutoReload + no rebuild だが、 `kolt run --watch` の running app process に新 sysprop を即時反映するため respawn するか、 次の source-driven rebuild まで待つか。 #322 の `cliSysProps` 経路との整合含めて design で確定。 Boundary Context Adjacent expectations にも明記済

---

## 6. Recommendations for Design Phase

### Preferred approach: **Option B** (新モジュール `ChangeMatrix.kt` 抽出)

理由:

1. **Matrix を code に encode する**ことで Req 7.9 (matrix セル = test cell) が unit test 範囲で自然に実現される
2. **Pure function** にすると Option A の「watch loop に統合 test 無しで実装が land」リスクを回避できる
3. **将来 section 追加** (`[hooks]` 等) が ChangeMatrix.kt の 1 関数 + 1 test 追加で済み、 メンテ動線が明確
4. WatchLoop.kt は event 処理 + side effect (rebuild / 通知出力) に責務を絞れる

### Key decisions to make in design

#### Resolved by requirements (no further action)
- ~~D-1 SectionAction shape~~ → Req 8.2-4 で 3-value taxonomy + AutoReload sub-category + NotifyOnly recommendation 文字列をピン。 sealed class の field shape (例: `AutoReload(rebuild: Boolean)`) のみ design で決める
- ~~D-2 mixed-window dispatch policy~~ → Req 4.6 で notify-only prevails
- ~~D-3 runtime-only sections~~ → Req 3.2/3.5 で AutoReload(rebuild=false)

#### Carried forward to design phase
D-1' **`SectionAction` の Kotlin 表現**: 3-value は確定、 残るは sealed class の具体形 (`AutoReload(rebuild: Boolean)` か `AutoReloadWithRebuild` / `AutoReloadNoRebuild` を別 case にするか) と `NotifyOnly` の recommendation 型 (生 String か enum か)
D-4. **Notification marker**: 既存 `--- ... ---` と区別する prefix (例: `[watch] ⚠ ` or `>>> kolt.toml: `)
D-5. **In-flight build flag の表現**: `AtomicInt` で十分か、 build 結果を伝達する必要があるか (Req 7 build-serialization の実装方法)
D-6. **Parse failure 通知の format** (R-4 確定)
D-7. **ADR 0033 の matrix table 形式**: per-invocation 表 + watch 表の 2 表に分けるか、 1 表に統合して edit-source 列を持たせるか
D-8 (新). **`[run.sys_props]` running app respawn policy** (R-8)
D-9 (新). **未ピン section の matrix 分類**: 主に `[build] jdk` / `[build.targets.<target>]` / `[[cinterop]]` / `[test]` の sub-fields (sysprops 以外)。 ADR 0033 起草時に matrix 表で確定

### Out of design phase scope

- watch integration test 基盤の新設 (R-7) は本 spec scope 外。 design では「unit test で覆える範囲」 + 「手動 smoke test 手順」 を最低線とし、 inotify mock 等は将来 issue
- daemon protocol 変更 (α1 で恒久 out)
- `kolt deps remove` 実装 (γ2 follow-up)
