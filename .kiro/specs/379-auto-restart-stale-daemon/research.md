# Gap Analysis — 379-auto-restart-stale-daemon

要件と既存コードベースの突き合わせ。 設計判断のための情報整理であり最終決定ではない。

## 1. Current State Investigation

### 1.1 関連モジュールとレイヤ

```
kolt.cli.BuildCommands
    └─ resolveCompilerBackend / resolveNativeCompilerBackend
        └─ FallbackCompilerBackend(primary = daemonBackend, fallback = subprocessBackend, onFallback)
            ├─ primary: DaemonCompilerBackend / NativeDaemonBackend
            │     └─ DaemonConnection / NativeDaemonConnection
            │           └─ FrameCodec.{writeFrame, readFrame}
            │                 └─ FrameError(Eof|Truncated|Malformed|Transport)
            └─ fallback: KotlincSubprocessBackend / NativeSubprocessBackend
```

JVM 側と native 側はパッケージごと **完全な並行構造**：

| 役割 | JVM 側 | Native 側 |
|---|---|---|
| Backend | `kolt.build.daemon.DaemonCompilerBackend` | `kolt.build.nativedaemon.NativeDaemonBackend` |
| Connection iface | `DaemonConnection` | `NativeDaemonConnection` |
| Wire | `kolt.daemon.wire.{Message, FrameCodec, FrameError}` | `kolt.nativedaemon.wire.{Message, FrameCodec, FrameError}` |
| Fallback wrapper | `FallbackCompilerBackend` | `FallbackNativeCompilerBackend` |
| Error type | `CompileError` | `NativeCompileError` |
| Reporter | `reportFallback` | `reportNativeFallback` |
| Shutdown helper | `sendShutdown` (DaemonCommands.kt:139) | `sendNativeShutdown` (DaemonCommands.kt:153) |

### 1.2 Compile flow（両 backend 共通）

`DaemonCompilerBackend.compile()` (`src/nativeMain/kotlin/kolt/build/daemon/DaemonCompilerBackend.kt:72-89`)：

```kotlin
val connection = connectOrSpawn().getOrElse { return Err(it) }
connection.use {
  val sendErr = it.sendRequest(wire).getError()
  if (sendErr != null) return Err(mapFrameErrorToSendError(sendErr))
  val reply = it.receiveReply()
  val replyErr = reply.getError()
  if (replyErr != null) return Err(mapFrameErrorToReceiveError(replyErr))
  return mapReplyToOutcome(reply.get()!!)
}
```

`NativeDaemonBackend.compile()` (`src/nativeMain/kotlin/kolt/build/nativedaemon/NativeDaemonBackend.kt:76-93`) は構造が同一で、 wire 型が `kolt.nativedaemon.wire.Message` (`NativeCompile` / `NativeCompileResult` / `Ping` / `Pong` / `Shutdown`)。

### 1.3 既存のエラーマッピング

`mapFrameErrorToReceiveError(err: FrameError)` (`DaemonCompilerBackend.kt:225-237` および `NativeDaemonBackend.kt:235-247`)：

| FrameError 変体 | 現行マッピング |
|---|---|
| `Eof` | `BackendUnavailable.Other("daemon closed connection before replying")` |
| `Truncated(w, g)` | `BackendUnavailable.Other("truncated reply: wanted=$w got=$g")` |
| `Malformed(reason)` | `BackendUnavailable.Other("malformed reply: $reason")` |
| `Transport(cause)` | `BackendUnavailable.Other("receive failed: ${describe(cause)}")` |

`mapReplyToOutcome(reply: Message)` (`DaemonCompilerBackend.kt:239-263` / `NativeDaemonBackend.kt:249-270`) は `Message.Compile|Ping|Pong|Shutdown`（期待外の variant）を `BackendUnavailable.Other("unexpected reply type: ${reply::class.simpleName}")` に落とす。

`FrameCodec.readFrame()` (`src/nativeMain/kotlin/kolt/daemon/wire/FrameCodec.kt:56-88`) は内部で `kotlinx.serialization` の `decodeFromString` を呼び、 `SerializationException` を catch して `Malformed` に正規化する。 つまり「未知の variant」「フィールド不整合」もすべて `Malformed` として client に届く。

### 1.4 `FallbackCompilerBackend` 経路

`src/nativeMain/kotlin/kolt/build/FallbackCompilerBackend.kt:14-20`：

```kotlin
override fun compile(request): Result<CompileOutcome, CompileError> {
  val primaryResult = primary.compile(request)
  val primaryError = primaryResult.getError() ?: return primaryResult
  if (!isFallbackEligible(primaryError)) return Err(primaryError)
  onFallback(primaryError)
  return fallback.compile(request)
}
```

`isFallbackEligible` は `BackendUnavailable.*` と `InternalMisuse` を true、 `CompilationFailed` / `NoCommand` を false。 すなわち上記の「malformed reply」「unexpected reply type」全部が **fallback-eligible** で、 現状は subprocess に静かに fallback している。

`onFallback = ::reportFallback` (`BuildCommands.kt:1462,1504`)。 `reportFallback` (`FallbackReporter.kt:6-24`) は variant 別に "warning: compiler daemon unavailable (..), falling back to subprocess compile" を 1 行 stderr に出す。 つまり **`Malformed` を含む wire 系の不整合でもユーザは generic な "daemon unavailable" 警告しか見ない**。

### 1.5 既存の Shutdown 送信経路

`kolt daemon stop` 用：

- `sendShutdown(socketPath)` (`DaemonCommands.kt:139-147`) — **新規** に `UnixSocket.connect(path)` してから `JvmFrameCodec.writeFrame(socket, JvmMessage.Shutdown)`、close
- `sendNativeShutdown(socketPath)` (`DaemonCommands.kt:153-161`) — 同じ shape

これらは **socket path から再接続** する設計。 #379 の要件 (R2.1: "on the still-open connection") は **すでに開いている `DaemonConnection` 上で** Shutdown を送る必要があり、 既存ヘルパーはそのまま流用できない。

ただし `DaemonConnection.sendRequest(message)` は `Message` を任意に投げられるので、 `DaemonConnection` 上に薄い `sendShutdown()` を生やすか、 backend 内で既存の `sendRequest(Message.Shutdown)` を直に呼ぶだけで足りる。

### 1.6 Backend 構築点

`BuildCommands.kt:1459-1463 / 1501-1505`：

```kotlin
return FallbackCompilerBackend(
  primary = daemonBackendFactory(setup, pluginJars),
  fallback = subprocessBackend,
  onFallback = ::reportFallback,
)
```

新しい "auto-recycle" のシグナルは `onFallback` の引数を経由して `reportFallback` に届ける、 もしくは `FallbackCompilerBackend` の外側に新たな decorator を被せる、 どちらの選択肢もここから差し込める。

## 2. Requirement-to-Asset Map

| Req | 必要な能力 | 既存資産 | Gap |
|---|---|---|---|
| R1.1 frame-layer 失敗検出 | `FrameError.*` の判定 | `FrameError.{Eof,Truncated,Malformed,Transport}` 既存（FrameCodec.kt:12-20） | ✅ 既存 |
| R1.2 payload deserialize 失敗 | 失敗時の通知 | `FrameCodec.readFrame` が `SerializationException` を catch して `Malformed` に正規化 | ✅ 既存（`Malformed` に集約） |
| R1.3 reply variant mismatch | request 送信種別と reply variant の対応検査 | `mapReplyToOutcome` で「期待外 variant」を `BackendUnavailable.Other("unexpected reply type: ...")` に落としている | 🔶 Constraint：今は string detail に埋もれていて型で識別できない |
| R1.4 JVM / native 両 backend で同一 | 並行構造あり | `kolt.build.{daemon,nativedaemon}` の二重実装 | ✅ ミラー対応で OK |
| R1.5 他 daemon error class は対象外 | precondition 失敗等は別経路 | `formatDaemonPreconditionWarning` 経路は `subprocessBackend` を直返し（BuildCommands.kt:1448-1450） | ✅ 既存（混線しない） |
| R2.1 開いた connection 上で `Shutdown` 送信 | live connection への `Message.Shutdown` write | `DaemonConnection.sendRequest(Message)` は任意の `Message` を投げられる | 🚧 Missing：使い方を backend `compile()` 内に追加 |
| R2.2 send 失敗時に warn-log し継続 | warn-level logger | `eprintln` のみ（dedicated logger なし） | 🔶 Constraint：log facility は eprintln + ステータスメッセージで十分 |
| R2.3 同期待ちしない | – | 既に `connection.use { ... }` で抜けるパターン | ✅ 既存 |
| R3.1 stderr 1 行通知 | dedicated message | `reportFallback` が generic warning を 1 行出している | 🚧 Missing：stale-daemon 専用メッセージと既存 generic warning の二重出力抑止 |
| R3.2 1 invocation 1 回 | 一度限りガード | invocation-スコープの状態は現在ない（CLI は 1 process / 1 invocation） | 🚧 Missing：process-global flag または higher-level scope が必要 |
| R3.3 verbosity に関わらず出す | – | `eprintln` は無条件出力 | ✅ 既存（verbosity システム自体未実装） |
| R4.1 in-flight build を fallback で完走 | `FallbackCompilerBackend` 経由 | 既存どおり | ✅ 既存 |
| R4.2 fresh daemon に対する再試行はしない | – | `FallbackCompilerBackend` は subprocess 1 回のみ | ✅ 既存 |
| R4.3 invocation 中 1 回限り | once-per-invocation ガード | R3.2 と同じ機構 | 🚧 Missing |
| R5.1 次回起動で fresh daemon | 既存 `connectOrSpawn` の再試行で実現 | `connectOrSpawn` は ENOENT/ECONNREFUSED を retry し socket がなければ spawn | ✅ 既存（古い daemon が socket 解放 → 自動 spawn） |
| R5.2 socket-occupied 時の挙動 | 既存挙動を維持 | `mapFatalConnectError` 経路 | ✅ 既存 |

### Complexity 信号

- **Algorithmic**: 単純（型ベースの分岐 + 1 回限りガード）
- **External integration**: なし（daemon side は不変）
- **Workflow**: 既存の compile flow に 1 ステップ（Shutdown 送信）追加 + 1 つの新エラー variant
- **Cross-cutting**: 「invocation 中 1 回限り」のガードがやや交差的

## 3. Implementation Approach Options

### Option A: 新エラー variant + backend 内インライン Shutdown

新しく `CompileError.BackendUnavailable.WireMismatch(reason)` と `NativeCompileError.BackendUnavailable.WireMismatch(reason)` を追加し、 `mapFrameErrorToReceiveError` と `mapReplyToOutcome` で wire 系の失敗（`FrameError.*` の全変体 + 期待外 variant）をこの新 variant に振り替える。 backend の `compile()` 内で当該 variant を返す **直前に** `connection.sendRequest(Message.Shutdown)` を best-effort 実行（戻り値は warn-log するだけ）。 `FallbackReporter` を WireMismatch だけ別メッセージ（stale daemon 通知）に分岐させる。 once-per-invocation ガードは `reportFallback` 側の process-global flag。

**変更ファイル想定**:
- `kolt/build/CompilerBackend.kt` / `kolt/build/NativeCompilerBackend.kt`（variant 追加 + `isFallbackEligible` map）
- `kolt/build/daemon/DaemonCompilerBackend.kt` / `kolt/build/nativedaemon/NativeDaemonBackend.kt`（mapFrameErrorToReceiveError / mapReplyToOutcome 拡張、 Shutdown 送信ステップ）
- `kolt/build/FallbackReporter.kt` / `kolt/build/NativeFallbackReporter.kt`（WireMismatch 分岐 + 1 回限りガード）
- 新規 test: `DaemonCompilerBackendWireMismatchTest`, `NativeDaemonBackendWireMismatchTest`, `FallbackReporterStaleDaemonTest` ほか

**Trade-offs**:
- ✅ wire-mismatch シグナルが Result chain に乗るので test と将来の telemetry が容易
- ✅ JVM/native の対称性を保つ
- ✅ 既存 `FallbackCompilerBackend` を変更しない（onFallback 経由でメッセージは出る）
- ❌ sealed error 階層に新 variant を追加（小規模だが API surface 変更）
- ❌ once-per-invocation flag が process-global（ただし CLI は 1 process / 1 invocation なので自然）

### Option B: 外側 decorator `RecyclingDaemonBackend`

`DaemonCompilerBackend` をラップする `RecyclingDaemonBackend(inner, recycleSink)` を新設。 `inner.compile()` を呼び、 結果が `BackendUnavailable.Other` で detail が wire-mismatch を示すなら別途 socket を再接続し `Shutdown` を投げる。 `DaemonCompilerBackend` 自体は無改造。

**Trade-offs**:
- ✅ エラー階層を変えない
- ❌ live connection への access がない → 別接続を張るしかなく R2.1 の "still-open connection" 文言とずれる（古い daemon は read-side が壊れていても accept が生きている可能性が高いが保証はない）
- ❌ 「wire mismatch」を `BackendUnavailable.Other.detail` の文字列マッチで検出することになり脆弱
- ❌ 結局 once-per-invocation ガードを別途必要とする

### Option C（recommended）: Hybrid — backend 内に変位 variant + reporter 側に policy

Option A の core（新 variant + backend 内インライン Shutdown）に、 once-per-invocation ガードは `reportFallback` / `reportNativeFallback` の中で持つ（あるいは小さな `RecycleNotifier` モジュールに切り出す）。 backend は **検出と Shutdown 送信** だけを担い、 **ユーザ通知の重複抑止** は reporter 層が責任を持つ。 この分離は steering structure.md の "Explicit fallback policy" 原則とも整合する（fallback policy は明示的に上層に集約）。

**Trade-offs**:
- ✅ 検出（backend）／通知ポリシー（reporter）／状態（reporter or 専用モジュール）の責務分離
- ✅ unit test しやすい（backend は Result の variant、 reporter は flag を観測）
- ✅ Option A 比で追加するピースは小さい
- ❌ Option A より動く部品は 1 段多い

## 4. Effort & Risk

- **Effort**: **M (3–7 days)**
  - 6–8 ファイルへの並行変更（JVM/native）+ 同数の新規テスト
  - 新エラー variant の sealed 階層への追加とエラーマッピング 4 箇所の更新
  - `FallbackReporter` / `NativeFallbackReporter` の 1 回限りガード
  - end-to-end IT は既存 `MultiShapeDaemonTestCoverageIT` パターン流用で書けるが古い daemon を擬する fixture 整備が必要

- **Risk**: **Low**
  - 既存パターンの拡張のみ（sealed variant 追加、 並行ミラー、 fallback wrapper）
  - 新規依存なし、 daemon-side コード触らず、 socket protocol 不変
  - 最悪のリグレッション = 「auto-recycle が発火しない」だけ → 既存 fallback で通常 build 成功

## 5. Recommendations for Design Phase

### Preferred approach
**Option C** — backend 内で wire-mismatch を新エラー variant として表面化し、 reporter 層で 1 回限りガードと専用 stderr メッセージを担当する。

### Key decisions（design 段階で確定すべき）
1. **新 variant の名前と shape**：`CompileError.BackendUnavailable.WireMismatch(kind: Kind, detail: String)` か単純な `WireMismatch(detail: String)` か。 `kind = Frame|Payload|VariantMismatch` を持たせると test が見通し良くなる
2. **`DaemonConnection.sendShutdown()` を生やすか、 既存 `sendRequest(Message.Shutdown)` を直接呼ぶか**：前者は意図が明確、 後者は API surface が小さい
3. **Once-per-invocation ガードの置き場所**：
   - `reportFallback` 内に `private var emitted = false`（最も小さい）
   - 専用 `RecycleNotifier` シングルトン（テスト分離に有利）
   - `FallbackCompilerBackend` のコンストラクタ引数で渡す（DI 的、 でも policy が wrapper に漏れる）
4. **stale-daemon stderr 文言**：既存 `reportFallback` は "warning: compiler daemon unavailable, falling back to subprocess compile"。 新メッセージは「古い daemon を見つけたので restart する」「今回の build は subprocess」という 2 点を 1 行で。 例： `warning: stale compiler daemon detected (<reason>), recycled — this build runs as subprocess; the next build will spawn a fresh daemon` の前後で短縮検討
5. **既存 generic fallback warning の抑止**：`WireMismatch` のときは generic 警告を出さず stale-daemon メッセージだけ。 同 invocation の 2 回目以降の WireMismatch は両方とも silent
6. **テスト戦略**：
   - unit: backend が WireMismatch 時に `connection.sendRequest(Message.Shutdown)` を呼ぶこと、 send 失敗時も build を続行すること（FakeConnection 拡張）
   - unit: reporter が 1 invocation で 1 回しかメッセージを出さないこと（flag 注入）
   - IT: 既存 `MultiShapeDaemonTestCoverageIT` / `DaemonIntegrationTest` 系統に「古い wire を返す fake daemon を立てて kolt build → stderr 検査」を追加検討（ただし複雑化するなら unit + 限定的 IT で十分）

### Research items to carry forward
- **Once-per-invocation ガードを process-global flag で済ませる正当性**：kolt CLI が 1 process / 1 invocation である事実を ADR/コメントとして固定するか、 構造化されたスコープを導入するか
- **`MultiShapeDaemonTestCoverageIT` で stale-daemon 経路をどこまで再現できるか**：既存ハーネスは fresh daemon 前提で組まれているため、 古いバージョンの jar を差し込む or fake daemon server を別途立てる手立てが必要
- **`WireMismatch.kind` を持たせた場合、 `mapReplyToOutcome` の "unexpected reply type" を `Kind.VariantMismatch` に分類するか、 別変位として保つか**：要件 R1.3 はあくまで "treat as incompatibility" なので一括でよいが、 telemetry 余地は残しておく価値がある
- **ADR 0016 / ADR 0024 の更新範囲**：本変更は §7 "fallback" 周りの policy を踏み込むので、 ADR 末尾に "Wire-mismatch auto-recycle" 節を追記する価値がある

---

## Design Synthesis Outcomes (2026-05-05)

### 1. Generalization
- 5 つの requirement は「detection (R1) → action (R2) → notification (R3) → disposition (R4) → continuity (R5)」の同一 lifecycle として表現できる。 design では各 phase を 1 component が担い、 横断 state（once-per-invocation flag）だけ単独 module 化（`StaleDaemonNotice`）
- JVM/native 並行 backend は wire 型の sealed family が異なるため共通化せず、 既存の対称ミラーで一貫させる（ADR 0024 §1 の方針踏襲）

### 2. Build vs. Adopt
- **Adopt**：`Message.Shutdown` 送信は既存 `connection.sendRequest(Message)` を再利用、 path 経由再接続の `sendShutdown` ヘルパーは目的が異なるため流用しない
- **Adopt**：`isFallbackEligible(BackendUnavailable) -> true` は既存網羅で WireMismatch も自動カバー、 追加分岐不要
- **Build**：`StaleDaemonNotice` は最小 module（flag + emit + reset）として新設。 既存 reporter に flag 直書きする案も検討したが、 JVM/native 両 reporter から共有されるため独立 module の方が test/責務とも明快

### 3. Simplification
- `WireMismatch.Kind = Frame|Payload|VariantMismatch` の sealed sub-hierarchy は **採らず**、 単一 `WireMismatch(detail: String)` で十分。 detail 文字列は既存 `Other(detail)` と互換の人間可読形式でテスト時に substring 検証できる。 telemetry が必要になれば pre-v1 で variant 化可能
- `DaemonConnection.sendShutdown()` 専用メソッドは追加せず、 既存 `sendRequest(Message.Shutdown)` を直接呼ぶ。 API surface 最小化 + 意図はコメントで明示
- 新規 decorator class（`RecyclingDaemonBackend` など）は採らず、 backend 内インライン処理で完結。 sealed error 階層を通じて signal が伝わるので decorator の indirection は冗長
