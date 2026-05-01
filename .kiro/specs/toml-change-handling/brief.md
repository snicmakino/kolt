# Brief: toml-change-handling

Tracks GitHub issue #297. Milestone: v1.0 readiness (DX). Size: M〜L.

## Problem

`kolt.toml` の編集経路ごとに挙動が暗黙に異なり、 contributor が「いつ何が反映されるのか」を予測できない。 具体例:

- `kolt deps add` は full sync (rewrite + resolve + lockfile + JAR cache) で完結する
- 同じことを hand-edit でやると次の build まで何も起きない
- watch 中に `kolt.toml` を編集しても rebuild は走るが、 startup 時の stale config で動く (`[build.sources]` を増やしても watch range は古いまま、 `[run.sys_props]` を増やしても古い env で run)
- `[kotlin] compiler` を変えると socket path が変わるが、 watch 中に編集しても通知すらされず気付けない
- `kolt deps remove` が存在しない (add だけある非対称)

`(section × edit-source × runtime-state) → required-action` の matrix が未確定なまま新しい entry point (#319 CLI `-D`、 #320 `kolt.local.toml`、 将来の IDE/LSP) を増やすと、 各実装が独自にルールを発明して矛盾が広がる。

## Current State

検証済 (Explore agent, 2026-05-01):

- **per-invocation 経路は実は揃っている**: `BuildCommands.kt:141 loadProjectConfig()` が `doBuild` / `doTest` / `doRun` 各呼び出しで毎回 fresh load しているので、 hand-edit でも次の CLI 起動で post-state は eager 経路と一致する。 ただし暗黙ルールで、 文書化されていない
- **`kolt deps {add, install, update}` の挙動**: add は eager full sync、 install は re-read + resolve、 update は JAR cache 削除 + force resolve。 一貫した「edit → resolve → lockfile」モデル
- **`kolt deps remove` は存在しない** (`DependencyCommands.kt:279` は `doTree()` の終端)
- **watch loop は config を reload しない** (`WatchLoop.kt:181`, `:285`): kolt.toml 変更で rebuild trigger は走るが、 startup 時の `KoltConfig` を使い続ける。 `[build.sources]` / `[build.resources]` / `[run.sys_props]` 等の編集は watch を再起動しないと効かない
- **daemon は kolt.toml を読まない**: socket path `(projectHash, kotlinVersion)` (`KoltPaths.kt:15-32`) が `[kotlin] compiler` 変更の唯一の検知路。 `[kotlin.plugins]` / `[kotlin] version` の IC 反映は BTA の内部挙動に委ねている (`BtaIncrementalCompiler.kt:183`)。 lockfile 変更単独では IC は触らない

## Desired Outcome

- `(section × edit-source × runtime-state) → required-action` matrix が ADR (新規 0033) で確定し、 per-invocation 経路と watch 経路の 2 表で示される
- watch 中の kolt.toml 編集が **常に観測可能**: 内部完結 section は auto reload、 resolve / daemon-lifecycle に触る section は通知のみ (rebuild skip)。 「何も起きないのに古い config で動く」 silent failure を排除
- per-invocation 経路は現状挙動を ADR で公式化 (`loadProjectConfig` が毎回 fresh load する責務を文書化)
- `kolt deps remove` は別 issue で follow-up (本 spec scope 外)

通知メッセージの暫定例:

```
[watch] kolt.toml changed
[watch]   [build.sources] changed → reloading watcher
[watch]   ⚠ [dependencies] changed. Run `kolt deps install` to apply.
[watch]   ⚠ [kotlin] compiler changed. Restart watch to pick up.
```

## Approach

確定済み 3 つの design call:

- **α1 (daemon stateless 維持)**: daemon は kolt.toml を読まない。 protocol に config-changed message を追加しない。 `[kotlin.plugins]` / `[kotlin] version` の IC 反映は BTA 任せ。 daemon-side observation は BTA に問題が観測されてから再考
- **β3 + 検知通知 (watch 通知モデル)**: watch は kolt.toml の全 section 変更を検知。 内部完結 section (`[build.sources]` / `[build.resources]` / `[kotlin]` の compiler 以外) は config reload + watcher 再構築 + rebuild。 resolve を伴う section (`[dependencies]` / `[test-dependencies]` / `[classpaths.*]`) は通知のみで rebuild skip。 `[kotlin] compiler` は socket path が変わるので通知のみ + 「restart watch」 を促す
- **Q1=B + γ2**: per-invocation 経路は完全 eager に揃える (現状そうなっているのを ADR で固定)。 hand-edit は次の build/test/run で同じ post-state に収束。 `kolt deps remove` は対称性確保のため別 issue として切り出し

実装方針:

1. **ADR 0033** "kolt.toml change-handling model" — α1 / β3+通知 / Q1=B を 1 本にまとめる。 matrix 表を per-invocation / watch の 2 表で記述
2. **WatchLoop.kt** に config reload + section-level diff + action dispatch を実装。 `KoltConfig` の section 単位 equality (もしくは TOML レベル diff) が判定基盤
3. **通知出力**: 既存の rebuild 状態出力に prefix 行を追加。 標準 stderr に `[watch] ⚠ ...` 形式
4. **テスト**: section-diff → action 期待値の table-driven test、 per-invocation matrix の文書化を裏付ける既存挙動の characterization test
5. **docs/architecture.md** と `kolt --help` の watch 関連記述を更新

## Scope

- **In**:
  - ADR 0033 (kolt.toml change-handling model)
  - WatchLoop の config reload + section diff + action dispatch + 通知出力
  - per-invocation matrix の文書化 (ADR + docs/architecture.md の追加節)
  - 上記の test 一式

- **Out**:
  - `kolt deps remove` 実装 → #297 Q6 follow-up issue として別途切る (γ2)
  - daemon-side config observation / protocol 変更 → α1 で恒久 out
  - IDE/LSP 連携の auto-resync (Gradle Sync 風) → build tool 責務外、 永続的 out
  - `[kotlin.plugins]` / `[kotlin] version` 変更時の daemon への明示的 IC wipe signal → BTA に委ね、 問題観測されたら別 spec
  - watch 内での暗黙 Maven Central アクセス (auto-resolve) → β3 で恒久 out

## Boundary Candidates

- **Per-invocation seam**: `BuildCommands.kt` / `DependencyCommands.kt` の config load と matrix application。 ほぼ現状コードの文書化で済む見込み
- **Watch seam**: `WatchLoop.kt` の config reload + section diff + action dispatch。 実装の中心。 `KoltConfig` 構造体の section-level 比較が必要
- **ADR seam**: 文書 (matrix 表 + rationale)。 単独で land 可能、 実装と decoupled
- **Notification surface seam**: stderr 出力 format。 既存 watch 出力との一貫性を保つ薄い層

## Out of Boundary

- daemon protocol 変更
- `kolt deps remove` 実装
- IDE/LSP 統合
- BTA 任せの IC 挙動への介入

## Upstream / Downstream

- **Upstream**:
  - #318 / `jvm-sys-props` spec: `[test.sys_props]` / `[run.sys_props]` / `[classpaths.*]` schema が matrix の対象
  - #322: `[run.sys_props]` を watch run loop に通したが reload は未対応 (本 spec で general 化)
  - ADR 0019 (BTA IC): α1 の前提
  - ADR 0024 (Native daemon): α1 を Native 側にも適用
- **Downstream**:
  - #297 Q6: `kolt deps remove` follow-up issue (γ2)
  - 将来の config schema 拡張 (`[publish]` 等) は本 matrix を継承
  - IDE/LSP 統合は「明示的な `kolt deps install` を呼ぶ」契約になる

## Existing Spec Touchpoints

- **Extends**: なし (新規 spec)
- **Adjacent**:
  - `jvm-sys-props` (本 spec が watch 経路で sys_props を扱う)
  - `concurrent-build-safety` (build invocation lifecycle で隣接、 lock 取得経路は独立)
  - `daemon-self-host` (α1 = daemon stateless 原則を再確認する文脈)

## Constraints

- **product.md**: predictable / fewer features / no startup tax — clever auto-sync より predictable behavior 優先
- **「kolt is build tool only」**: auto-resync は IDE/LSP 責務、 build tool は明示操作主義
- **Network I/O は明示操作のみ**: watch が暗黙に Maven Central を叩かない
- **Daemon の stateless 原則維持** (α1)
- **ADR 0001**: `Result<V, E>` 統一、 throw 禁止
- **English-only on disk for ADR / code / commits** (CLAUDE.md): brief / requirements / design は ja で書くが、 ADR 0033 と code / comments / commits は英語
