# Requirements Document

## Project Description (Input)

GitHub issue #297 を起点とする v1.0 readiness テーマ。 `kolt.toml` の編集経路ごと (hand-edit / `kolt deps add` / `kolt deps install` / `kolt deps update` / watch 中の編集) で挙動が暗黙に異なり、 contributor が「いつ何が反映されるのか」を予測できない違和感を解消する。

**(a) 誰が困っているか**: kolt の contributor および将来のユーザ。 `kolt deps add` は eager full sync するのに hand-edit は次の build まで遅延、 watch 中の `kolt.toml` 編集は rebuild は走るが startup 時の stale config で実行される (`[build.sources]` を増やしても watch range は古いまま、 `[run.sys_props]` を増やしても古い env で run)、 `[kotlin] compiler` 変更は通知すらされず気付けない、 `kolt deps remove` が存在しない (add だけある非対称) — これらが「手触りの悪さ」の正体。

**(b) 現状**: 検証済 (Explore agent, 2026-05-01)。 per-invocation 経路は実は揃っている (`BuildCommands.kt:141 loadProjectConfig()` が毎回 fresh load)。 ただし暗黙ルールで未文書化。 watch loop は `WatchLoop.kt:181, :285` で startup 時に config を 1 回だけ load し以降 reload しない。 daemon は kolt.toml を一切読まず stateless (`KoltPaths.kt:15-32` の socket path `(projectHash, kotlinVersion)` が `[kotlin] compiler` 変更の唯一の検知路)。 lockfile v4 + `[test.sys_props]` / `[run.sys_props]` / `[classpaths.*]` schema は #318 で main に landed 済。

**(c) 何を変えるべきか**: `(section × edit-source × runtime-state) → required-action` matrix を ADR (新規 0033) で確定させ、 per-invocation 経路と watch 経路の 2 表で挙動を明文化する。 matrix は **kolt.toml の現行 schema 全 section を網羅**し、 各 section が seam (例: 内部完結 / resolve 必要 / compiler-state-affecting / runtime-only / 観測のみ) のどれに属するかを requirements 段階で確定させる。 seam は性質が異なる — `[build.sources]` の編集と `[dependencies]` の編集と `[kotlin] compiler` の編集は本質的に違う action を要求する — が、 **どの section をどの seam に置くかは PD では先取りせず、 requirements で全 section について決める** (kotlin 系の確定分は下記 design call 参照)。 watch 中は全 section 変更を検知して観測可能にし (silent failure を排除)、 各 seam に応じて auto reload / 通知のみを使い分ける。 per-invocation 経路は現状の eager 同等挙動を ADR で公式化。

**確定済み design call** (discovery で合意):

- **α1 (daemon stateless)**: daemon は kolt.toml を読まない stateless を維持。 protocol に config-changed message を追加しない。 daemon-side observation の代わりに watch 経路で section diff + 通知 (β3) で対処する
- **β3 + 検知通知**: watch は通知役、 resolve / daemon-lifecycle に触る変更は明示操作 (CLI 再起動 / `kolt deps install`) に委ねる。 暗黙の Maven Central アクセスや daemon 再起動は行わない
- **Q1=B + γ2**: per-invocation は完全 eager に揃え (現状追認)、 `kolt deps remove` は対称性確保のため別 issue で follow-up (本 spec scope 外)
- **Build-serialize**: config reload は in-flight build invocation の完了まで保留する。 build cancel 戦略は採らない。 reload latency が build 1 周分伸びる代償で race 不在を保証し、 予測可能性を優先する
- **`[kotlin]` セクション全体 = 通知のみ**: `[kotlin] compiler` (socket path 変更) / `[kotlin] version` / `[kotlin.plugins]` の watch 中変更はすべて通知のみで rebuild skip。 BTA の internal IC invalidation を kolt 側で保証できないため、 silent な BTA-trust より explicit な user 操作を優先する。 推奨アクションは `kolt daemon stop --all` 後の watch 再起動 (daemon 再起動で BTA IC を完全 reset)。 将来 BTA の invalidation 保証が個別に確認できた sub-section は matrix 改訂で auto-reload 側に移動可能。 ADR 0033 rationale に「silent な BTA-trust より explicit な user 操作を予測可能性として優先」 を明記する

**本 spec の requirements が満たすべき要件** (`/kiro-spec-requirements` で各 EARS requirement に展開):

- **R-1 (全 section 網羅性)**: matrix は kolt.toml の現行 schema 全 section (`name` / `version` / `kind` 等の top-level scalar、 `[kotlin]` / `[build]` / `[build.targets.*]` / `[fmt]` / `[dependencies]` / `[test-dependencies]` / `[repositories]` / `[[cinterop]]` / `[classpaths.*]` / `[test]` / `[run]`) を漏れなく扱う。 deferred セルは明示的に "deferred / out of scope" とマークする。 後続で section を追加する際は matrix への追記を必須とする
- **R-2 (通知 contract)**: 1 debounce window に対し 1 group の通知。 通知メッセージには変更 section 名と推奨アクション (e.g. `Run kolt deps install`、 `Restart watch`) を含める。 group 内に複数 section の変更が含まれる場合は **各 section を 1 行ずつ列挙** (各行に section 名と推奨アクション)。 同一 section の連続編集 (debounce window 内) は 1 行に集約。 group 内で auto-reload 系と通知のみ系が混在した場合の action 分岐は design 段階で確定する
- **R-3 (matrix セル単位の acceptance criteria)**: matrix の各セル (section × edit-source × runtime-state) が 1 つ以上の acceptance criterion を持つ。 watch 経路は section-diff → expected action の table-driven test、 per-invocation 経路は hand-edit → next build による post-state 検証で覆う

**Scope 境界** (詳細は `brief.md` 参照):

- **In**: ADR 0033、 WatchLoop の config reload + section diff + action dispatch + 通知出力、 per-invocation matrix の docs 化、 matrix が kolt.toml 現行 schema 全 section を網羅 (R-1)、 関連テスト
- **Out**: `kolt deps remove` 実装 (γ2 follow-up)、 daemon-side config observation (α1 で恒久 out)、 IDE/LSP 連携 auto-resync (build tool 責務外)、 `[kotlin.plugins]` / `[kotlin] version` 変更時の明示的 IC wipe signal (BTA 任せ、 watch 中は通知のみで対処)

**Constraints**:

- `product.md`: predictable / fewer features / no startup tax — clever auto-sync より predictable behavior 優先
- "kolt is build tool only" — auto-resync は IDE/LSP 責務
- Network I/O は明示操作のみ (watch が暗黙に Maven Central を叩かない)
- ADR 0001 準拠 (`Result<V, E>` 統一、 throw 禁止)
- ADR / code / commits は英語、 spec docs (本ファイル含む) は日本語

**関連 spec / ADR**:

- Upstream: #318 / `jvm-sys-props` (sys_props schema)、 #322 (`[run.sys_props]` watch threading 部分実装)、 ADR 0019 (BTA-driven IC、 α1 の前提)、 ADR 0024 (Native daemon)
- Adjacent: `concurrent-build-safety` (build invocation lifecycle で隣接)、 `daemon-self-host` (α1 = daemon stateless 原則を再確認する文脈)
- Downstream: #297 Q6 = `kolt deps remove` follow-up、 将来の config schema 拡張 (`[publish]` 等) は本 matrix を継承

## Boundary Context

- **In scope**:
  - `(section × edit-source × runtime-state) → required-action` matrix の確定 (ADR 0033)
  - watch loop の change detection、 section diff、 action dispatch、 通知出力の実装
  - per-invocation 経路の現状挙動を ADR で公式化 (matrix で全 entry point を表現)
  - kolt.toml 現行 schema 全 section の matrix 網羅 (R-1)
  - matrix セル単位の acceptance criteria を test suite で覆う (R-3)
- **Out of scope**:
  - `kolt deps remove` 実装 (#297 Q6 follow-up issue として別途切る; γ2)
  - daemon protocol への config-changed message 追加 (α1 で恒久 out)
  - IDE/LSP 連携 auto-resync (build tool 責務外)
  - watch 中の暗黙 Maven Central アクセス (β3 で恒久 out)
  - `[kotlin.plugins]` / `[kotlin] version` 変更時の daemon-side 明示的 IC wipe signal (BTA 任せ; watch 中は通知のみで対処)
  - watch loop の inotify integration test 基盤 (mock inotify / fs fixture) は本 spec scope 外。 ChangeMatrix の unit test (table-driven) と手動 smoke test で覆う
- **Adjacent expectations**:
  - 既存の `kolt deps {add, install, update}` command 挙動は不変 (matrix で公式化のみ、 commands 実装に変更なし)
  - 各 build/test/run command の per-invocation fresh-load 挙動は不変 (現状追認)
  - kolt JVM / native compiler daemon は引き続き kolt.toml を読まない (α1)
  - `kolt run --watch` で `[run.sys_props]` 等 runtime-only セクション変更時、 既に走っている app process の即時 respawn を行うかは design 段階で確定 (matrix 上は AutoReload + no rebuild、 process lifecycle は run loop 固有の design)

## Requirements

### Requirement 1: Per-invocation change handling matrix

**Objective:** As a kolt contributor, I want every per-invocation entry point (hand-edit followed by `kolt build` / `kolt test` / `kolt run`, or `kolt deps {add, install, update}`) to converge on the same post-state for a given kolt.toml edit, so that I can reason about what happens after I edit kolt.toml without consulting the source for each command.

#### Acceptance Criteria

1. When the user invokes `kolt build`, `kolt test`, or `kolt run`, the kolt CLI shall load the latest `kolt.toml` from disk before resolving any subsequent state.
2. When `kolt deps add <gav>` completes successfully, the resulting `kolt.toml`, `kolt.lock`, and JAR cache shall be in the same state as if the user had hand-edited `kolt.toml` and then run `kolt deps install`.
3. When `kolt deps install` is invoked, the kolt CLI shall re-read `kolt.toml`, re-resolve dependencies against the current configuration, and rewrite `kolt.lock` only when the resolution result differs from the existing lockfile.
4. When `kolt deps update` is invoked, the kolt CLI shall delete cached JARs, re-read `kolt.toml`, force-resolve dependencies, and rewrite `kolt.lock`.
5. The kolt CLI shall not retain a kolt.toml that was loaded by a prior process invocation; each invocation begins with a fresh disk read.

### Requirement 2: Watch detection of kolt.toml changes

**Objective:** As a kolt user running `kolt build --watch` / `kolt run --watch` / `kolt test --watch`, I want every kolt.toml edit to be observable, so that no change silently produces an outdated build.

#### Acceptance Criteria

1. While `kolt {build,run,test} --watch` is running, when the user modifies `kolt.toml`, the kolt watch loop shall detect the modification within its existing change-detection debounce window.
2. While `kolt {build,run,test} --watch` is running, when `kolt.toml` is replaced atomically (for example via editor save-by-rename), the kolt watch loop shall still detect the change.
3. When the kolt watch loop detects a `kolt.toml` change, the kolt watch loop shall classify the change by enumerating which top-level sections differ between the previously loaded configuration and a freshly parsed configuration.
4. The kolt watch loop shall classify changes against every section listed in the kolt.toml schema matrix defined by ADR 0033 (see Requirement 8); a section absent from the matrix shall be reported as deferred rather than silently ignored.
5. If parsing the new `kolt.toml` fails, the kolt watch loop shall emit a notification reporting the parse error, retain the previously loaded configuration, and skip any rebuild for that change event.

### Requirement 3: Watch action dispatch — auto-reload sections

**Objective:** As a kolt user, I want kolt.toml edits in sections that are fully internal to kolt to take effect on the next build without restarting watch, so that iteration on `[build.sources]`, `[run.sys_props]`, project metadata, and similar sections is friction-free.

#### Acceptance Criteria

1. When the kolt watch loop detects a change confined to auto-reload sections (per the matrix in ADR 0033), the kolt watch loop shall reload its in-memory configuration from the new `kolt.toml`.
2. The kolt watch loop shall classify auto-reload sections into two sub-categories: rebuild-required (changes that affect compile inputs or artifact identity, including `[build.sources]`, `[build.resources]`, `[build] main`, `name`, and `version`) and rebuild-not-required (runtime-only changes, including `[test.sys_props]` and `[run.sys_props]`).
3. When the reloaded configuration's `[build.sources]` or `[build.resources]` differ from the previously loaded values, the kolt watch loop shall reconstruct its watched path set to match the reloaded values before triggering any subsequent rebuild.
4. When the reloaded configuration's changed sections include any rebuild-required auto-reload section, the kolt watch loop shall trigger a rebuild using the reloaded configuration.
5. When the reloaded configuration's changed sections are confined to rebuild-not-required auto-reload sections, the kolt watch loop shall not trigger a rebuild; the reloaded configuration shall be used for any subsequent source-driven rebuild and for the next spawn of the test or run JVM.
6. The kolt watch loop shall not perform dependency resolution as part of an auto-reload; the existing `kolt.lock` and JAR cache shall be reused unchanged.

### Requirement 4: Watch action dispatch — notify-only sections

**Objective:** As a kolt user, I want kolt.toml edits that require explicit operations (Maven Central access, daemon restart, or watch restart) to be surfaced as notifications during watch instead of silently producing wrong builds, so that the boundary between automatic and explicit actions is observable.

#### Acceptance Criteria

1. When the kolt watch loop detects a change in any of `[dependencies]`, `[test-dependencies]`, `[classpaths.<name>]`, or `[repositories]`, the kolt watch loop shall emit a notification recommending `kolt deps install` and shall skip the rebuild.
2. When the kolt watch loop detects a change in `[kotlin] compiler`, `[kotlin] version`, or `[kotlin.plugins]`, the kolt watch loop shall emit a notification recommending `kolt daemon stop --all` followed by restarting watch, and shall skip the rebuild.
3. When the kolt watch loop detects a change in `kind` or `[build] target`, the kolt watch loop shall emit a notification recommending restarting watch (these changes alter the fundamental build pipeline), and shall skip the rebuild.
4. The kolt watch loop shall not perform dependency resolution, daemon shutdown, or Maven Central network access in response to any kolt.toml change event.
5. When a notify-only section change is detected, the kolt watch loop shall keep its previously loaded configuration in effect for any subsequent source-file-driven rebuild until the user takes the recommended action and restarts the relevant process.
6. When a debounce window contains both notify-only and auto-reload section changes, the kolt watch loop shall treat the entire window as notify-only: it shall emit notifications for the notify-only sections, shall not auto-reload its configuration for the auto-reload sections in that window, and shall skip any rebuild that would otherwise have been triggered. The auto-reload sections become effective only when the user takes the recommended notify-only action and restarts the relevant process.

### Requirement 5: Watch action dispatch — no-op sections

**Objective:** As a kolt user, I want kolt.toml edits in sections that have no effect on watch (e.g., `[fmt]`) to be ignored without producing rebuilds or notifications, so that watch output stays focused on changes that materially affect the build.

#### Acceptance Criteria

1. When the kolt watch loop detects a change confined to no-op sections (per the matrix in ADR 0033, including `[fmt]`), the kolt watch loop shall not reload its configuration, shall not trigger a rebuild, and shall not emit a notification.
2. The kolt project shall classify a section as no-op only when the section's effect is fully external to watch's responsibilities (e.g., `[fmt]` is consumed by `kolt fmt`, not by build/run/test).

### Requirement 6: Notification contract

**Objective:** As a kolt user, I want notifications for kolt.toml changes to be predictable and unambiguous, so that I can act on them without guessing which sections changed or whether multiple changes were collapsed.

#### Acceptance Criteria

1. When the kolt watch loop emits a notification for a kolt.toml change event, the kolt watch loop shall produce exactly one notification group per debounce window.
2. The kolt watch loop shall print one line per changed section within a notification group, where each line contains the section name and the recommended action for that section.
3. When the same section is edited multiple times within a single debounce window, the kolt watch loop shall emit exactly one line for that section in the resulting group.
4. When a debounce window contains changes to multiple distinct sections, the kolt watch loop shall list each changed section as its own line within the same notification group, without splitting them into separate groups.
5. The kolt watch loop shall write notification output to standard error.
6. The kolt watch loop shall prefix each notification line with a marker that distinguishes notification output from rebuild status output.
7. The kolt watch loop shall not suppress, re-order, or batch notifications across debounce windows; each window's group shall be emitted independently.

### Requirement 7: Build serialization

**Objective:** As a kolt user, I want config reload to never race with an in-flight build, so that no rebuild runs against partially reloaded state.

#### Acceptance Criteria

1. While a build invocation is in flight inside the kolt watch loop, when a kolt.toml change is detected, the kolt watch loop shall hold the reload until the in-flight build completes.
2. After the in-flight build completes, the kolt watch loop shall process the held reload as if the change had been detected at that moment.
3. The kolt watch loop shall not cancel an in-flight build to apply a pending reload.
4. While a held reload is being processed, when an additional kolt.toml change is detected, the kolt watch loop shall coalesce it into the same reload sequence rather than starting a parallel reload.

### Requirement 8: Matrix coverage, ADR publication, and SectionAction taxonomy

**Objective:** As a kolt maintainer, I want the change-handling matrix to be authoritatively documented under a fixed taxonomy and to cover every current kolt.toml section, so that future schema additions inherit explicit handling rather than implicit fallback.

#### Acceptance Criteria

1. The kolt project shall publish ADR 0033 under `docs/adr/` titled "kolt.toml change-handling model".
2. ADR 0033 shall define a SectionAction taxonomy with exactly three top-level values: AutoReload, NotifyOnly, and NoOp.
3. ADR 0033 shall specify that AutoReload distinguishes two sub-categories: rebuild-required and rebuild-not-required.
4. ADR 0033 shall specify that NotifyOnly carries a per-section user-recommended action string.
5. ADR 0033 shall include a per-invocation matrix table covering every top-level section of the current kolt.toml schema (`name`, `version`, `kind`, `[kotlin]` and its sub-sections, `[build]` and `[build.targets.<target>]`, `[fmt]`, `[dependencies]`, `[test-dependencies]`, `[repositories]`, `[[cinterop]]`, `[classpaths.<name>]`, `[test]` and `[test.sys_props]`, `[run]` and `[run.sys_props]`).
6. ADR 0033 shall include a watch matrix table covering the same sections, classifying each section into one of the SectionAction values defined in criterion 2 (with the AutoReload sub-category from criterion 3 where applicable, or marked as deferred when explicitly out of scope).
7. ADR 0033 shall record the rationale for placing `[kotlin] compiler`, `[kotlin] version`, and `[kotlin.plugins]` in the NotifyOnly category, citing the project's preference for explicit user operations over implicit compiler-internal cache invalidation.
8. ADR 0033 shall record the rationale for build-serialization (hold over cancel) and for the daemon stateless-with-respect-to-kolt.toml decision (α1).
9. ADR 0033 shall record the rationale for the mixed-window dispatch policy (notify-only prevails when a debounce window contains both notify-only and auto-reload changes).
10. ADR 0033 shall name `kolt deps remove` (#297 Q6) as the documented follow-up for per-invocation symmetry.
11. ADR 0033 shall include a maintenance clause requiring future kolt.toml schema additions to be classified in both matrix tables before merging.
12. The kolt project shall reference ADR 0033 from `docs/architecture.md`.
13. The kolt project test suite shall include at least one acceptance test per matrix cell, where a cell is a (section × edit-source × runtime-state) tuple defined by ADR 0033.

### Requirement 9: Backward compatibility

**Objective:** As an existing kolt user who does not edit kolt.toml during watch, I want my workflow to be unchanged, so that adopting this change is silent.

#### Acceptance Criteria

1. While `kolt {build,run,test} --watch` is running and `kolt.toml` is not modified, the kolt watch loop shall behave identically to its prior behavior with respect to source-file-driven rebuilds.
2. The kolt CLI shall not change the user-observable behavior of `kolt build`, `kolt run`, `kolt test`, `kolt deps add`, `kolt deps install`, `kolt deps update`, or any other non-watch command; this requirement formalizes the existing behavior in ADR 0033 without altering it.
3. The kolt JVM compiler daemon and the kolt native compiler daemon shall remain stateless with respect to `kolt.toml`.
