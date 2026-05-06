# Implementation Plan

## 1. Foundation: WireMismatch error variant

- [x] 1.1 (P) JVM 側エラー sealed 階層に WireMismatch 変位を追加
  - `BackendUnavailable` sealed に `WireMismatch(detail: String)` を sibling として追加し、 既存の `Other` 等とは別 case として扱えるようにする
  - `formatCompileError` で WireMismatch を `"error: $context wire mismatch: ${detail}"` 形式に整形する分岐を追加
  - `isFallbackEligible` は `BackendUnavailable` 一括で true を返すため変更不要だが、 `when` 網羅性が新 variant でも維持されることをコンパイル通過で確認
  - 観測可能な完了状態：JVM 側 unit test で `WireMismatch("any-detail")` が `BackendUnavailable` 系として扱われ、 `formatCompileError` から期待文字列が返ること
  - _Requirements: 1.1, 1.2, 1.3, 1.5_
  - _Boundary: kolt.build.CompilerBackend (JVM error sealed)_

- [x] 1.2 (P) Native 側エラー sealed 階層に WireMismatch 変位を追加
  - `NativeCompileError.BackendUnavailable` sealed に `WireMismatch(detail: String)` を追加
  - `formatNativeCompileError` で同形の分岐を追加
  - `isNativeFallbackEligible` は変更不要、 網羅性のみ確認
  - 観測可能な完了状態：native 側 unit test で `WireMismatch` が JVM 側と対称な振る舞いを示すこと
  - _Requirements: 1.4_
  - _Boundary: kolt.build.NativeCompilerBackend (native error sealed)_

## 2. Core: detection, Shutdown send, notification module

- [x] 2.1 (P) JVM compiler daemon backend で wire 不整合を検出し Shutdown を best-effort 送信
  - `mapFrameErrorToReceiveError` で受信側 `FrameError.{Eof, Truncated, Malformed, Transport}` を WireMismatch に振替（送信側 mapping は不変）
  - `mapReplyToOutcome` で期待外 reply variant（`Compile` / `Ping` / `Pong` / `Shutdown` 受信）を WireMismatch に振替
  - `compile()` 内で「戻り値が WireMismatch の場合のみ、 同じ open connection 上で `Message.Shutdown` を送信」する経路を追加。 `connection.use { ... }` を抜ける前に行い、 send が失敗したら `eprintln` で warn-line を 1 行出して続行
  - 戻り値の早期 return は維持しつつ、 戻り値計算を一旦変数に束ねてから Shutdown 送信→return の順で組み立てる（design.md の pseudocode を踏襲）
  - 観測可能な完了状態：FakeConnection を使った unit test で、 (a) FrameError.* 全 4 変位が `Err(BackendUnavailable.WireMismatch)` を返す、 (b) 期待外 variant が同じく WireMismatch を返す、 (c) WireMismatch 経路で `lastSent` 末尾が `Message.Shutdown` になっている、 (d) Shutdown 送信が失敗するスタブで stderr に warn-line が出て build が続行する、 の 4 項目すべて green
  - _Requirements: 1.1, 1.2, 1.3, 1.5, 2.1, 2.2, 2.3_
  - _Boundary: kolt.build.daemon.DaemonCompilerBackend_
  - _Depends: 1.1_

- [x] 2.2 (P) Native compiler daemon backend で同様の検出と Shutdown 送信
  - `kolt.build.nativedaemon.NativeDaemonBackend` で 2.1 と同形の改修を施す（wire 型は `kolt.nativedaemon.wire.Message.Shutdown`）
  - 観測可能な完了状態：native 版 unit test で 2.1 と同 4 項目が green
  - _Requirements: 1.4, 2.1, 2.2, 2.3_
  - _Boundary: kolt.build.nativedaemon.NativeDaemonBackend_
  - _Depends: 1.2_

- [x] 2.3 (P) StaleDaemonNotice モジュールで compile-pass スコープ 1 回限り通知を実装
  - 新規 `kolt.build.StaleDaemonNotice` を `object` として作成。 `emit(label, detail, sink)` と `reset()` を public API として公開（`reset` は test だけでなく caller integration からも呼ばれるため public）
  - `emit` は flag が false のとき stderr に `"warning: stale {label} detected ({detail}); recycling — this build runs as subprocess, the next build will spawn a fresh daemon"` を 1 行書き、 flag を立てて true を返す。 すでに true なら何も書かず false を返す
  - `reset()` は flag を false に戻す
  - 観測可能な完了状態：unit test で (a) reset 直後の最初の emit が true を返し sink に 1 行届く、 (b) 同 cycle の 2 回目 emit は false を返し sink には何も追加されない、 (c) reset を挟むと再び true で 1 行届く、 が green
  - _Requirements: 3.1, 3.2, 3.3, 4.3_
  - _Boundary: kolt.build.StaleDaemonNotice_

## 3. Wiring: reporter dispatch and entry-point reset

- [x] 3.1 (P) JVM fallback reporter から StaleDaemonNotice にディスパッチ
  - `reportFallback` の `when (err)` に `is CompileError.BackendUnavailable.WireMismatch` 分岐を **既存 `BackendUnavailable.Other` より前** に追加し、 `StaleDaemonNotice.emit("compiler daemon", err.detail, sink)` を呼ぶ
  - 既存の generic warning（`Other` 含む）は WireMismatch には流れないことを確認
  - 観測可能な完了状態：FallbackReporterTest に「WireMismatch を渡すと stale-daemon 通知が出て、 generic warning が出ない」「他の variant 渡し時は従来どおりの文言が出る」の 2 ケースを追加し green
  - _Requirements: 3.1_
  - _Boundary: kolt.build.FallbackReporter_
  - _Depends: 1.1, 2.3_

- [x] 3.2 (P) Native fallback reporter から StaleDaemonNotice にディスパッチ
  - `reportNativeFallback` に同形の分岐を追加（label は `"native compiler daemon"`）
  - 観測可能な完了状態：NativeFallbackReporterTest に対応 2 ケースを追加し green
  - _Requirements: 3.1_
  - _Boundary: kolt.build.NativeFallbackReporter_
  - _Depends: 1.2, 2.3_

- [x] 3.3 (P) BuildCommands の compile pass entry に StaleDaemonNotice.reset() を挿入
  - `doBuildInner` / `doTestInner` の compile invocation 直前で `StaleDaemonNotice.reset()` を 1 回呼ぶ。 watch 経路も同関数を再呼び出しする構造のため、 同関数冒頭に reset を置けば single-shot と watch 両方をカバーする想定。 実装時にコールパスを grep で確認し、 別経路があればそこにも reset を挿入する
  - reset は idempotent なので「念のため複数箇所に呼ぶ」コストは無視できる。 ただし対称性のため 1 関数につき 1 箇所に統一する
  - 観測可能な完了状態：`grep -n "StaleDaemonNotice.reset" src/nativeMain/kotlin/kolt/cli/BuildCommands.kt` で挿入箇所が確認でき、 各 compile invocation の前に reset が走ることが目視 + 後続 IT (4.1) で検証される
  - _Requirements: 3.2, 4.3_
  - _Boundary: kolt.cli.BuildCommands_
  - _Depends: 2.3_

## 4. Validation: end-to-end test and ADR

- [x] 4.1 古い daemon 模擬による end-to-end IT
  - `src/nativeTest/kotlin/kolt/cli/StaleDaemonRecycleIT.kt` を新規作成し、 既存 `MultiShapeDaemonTestCoverageIT` の env-gated パターン（false-RED 回避）と memory `feedback_bootstrap_gated_test.md` の bootstrap-gated パターンを踏襲
  - `kolt.infra.net` の UnixSocket primitive で、 受信した Compile に対し `Message.Pong` 等の期待外 reply を JSON 直書きで返すフェイク server を fixture として実装
  - シナリオ：(1) フェイク server を立てて socket path に bind、 (2) その path を daemon socket として `kolt build` を 1 回走らせる、 (3) フェイク server を停止し、 (4) もう 1 回 `kolt build` を走らせる
  - 観測可能な完了状態：assertion で (a) 1 回目の stderr に stale-daemon 通知が 1 行ある、 (b) 1 回目の build が exit code 0 で完了している（subprocess fallback で）、 (c) 2 回目の build が「fresh daemon を spawn した」または「subprocess に再 fallback した」のいずれか（フェイク server が解放した socket 状態に依存して `connectOrSpawn` の通常経路を通ることを確認）、 (d) 2 回目の stderr に stale-daemon 通知が出ない、 の 4 点が green
  - _Requirements: 4.1, 4.2, 5.1, 5.2_
  - _Boundary: kolt.cli (integration test only)_

- [x] 4.2 (P) ADR 0016 と ADR 0024 に Wire-mismatch auto-recycle 節を追記
  - ADR 0016（JVM daemon fallback policy）末尾に「Wire-mismatch auto-recycle」短節（5–7 行）を追加し、 #379 の動機・トリガー・once-per-compile-pass policy・Shutdown best-effort を 1 文ずつまとめる
  - ADR 0024（native daemon symmetry）にも同節を追加（JVM 版を参照する形で短く）
  - 観測可能な完了状態：両 ADR の末尾に該当節が存在し、 既存の「fallback policy」記述（§5 / §7）から 1 行で参照されていること
  - _Requirements: 1.5, 2.1, 3.1, 4.3_
  - _Boundary: docs/adr_
