# アーキテクチャ

kolt は単一の Kotlin/Native バイナリとして配布される Kotlin ビルドツールです。宣言的な `kolt.toml` を読み込み、Maven リポジトリから依存を解決し、Unix ソケット経由のウォーム JVM コンパイラデーモンでコンパイルします。デーモンが利用できない場合はサブプロセス呼び出しにフォールバックします。JVM ターゲットとネイティブターゲットはそれぞれ専用のデーモンを持ち、ワイヤプロトコルとスポーン方式を共有しつつ独立した JVM プロセスとして動作します（ADR 0024）。

## コンポーネント

```
┌─────────────────────────────────────────────┐
│              kolt (Kotlin/Native)            │
│                                             │
│  cli/          コマンドディスパッチ、--watch │
│  config/       TOML パース、KoltPaths       │
│  build/        コンパイルパイプライン、キャッシュ│
│  resolve/      Maven/POM/Gradle メタデータ   │
│  daemon/       JVM デーモンクライアント      │
│  nativedaemon/ ネイティブデーモンワイヤコーデック│
│  tool/         ツールチェイン管理(kotlinc/JDK)│
│  infra/        プロセス、ファイルシステム     │
└───┬─────────────────────────────────────┬───┘
    │ Unix ソケット（長さプレフィクス付き JSON）│
┌───▼───────────────────┐   ┌─────────────▼─────────────┐
│  kolt-compiler-daemon │   │   kolt-native-daemon       │
│        (JVM)          │   │        (JVM)               │
│                       │   │                            │
│  server/   ライフサイクル│   │  server/    ライフサイクル  │
│  protocol/ ワイヤ      │   │  protocol/  ワイヤ         │
│  ic/       BTA IC     │   │  compiler/  K2Native リフレクション│
│  reaper/   IC クリーンアップ│   │                         │
└───────────────────────┘   └────────────────────────────┘
```

### ネイティブクライアント (`src/nativeMain/kotlin/kolt/`)

| パッケージ | 責務 |
|-----------|------|
| `cli` | 引数をパースし、コマンドをディスパッチ（`build`、`run`、`test`、`check`、`init`、`clean`、`fmt`、`deps`、`daemon`、`toolchain`）。ウォッチモードループ。 |
| `config` | `kolt.toml` を `KoltConfig` にパース。`~/.kolt/` パスの管理（両デーモンのソケットパスを含む）。 |
| `build` | JVM および Native ターゲット向けのコンパイラコマンド構築。デーモンとサブプロセス実装を持つ `CompilerBackend` / `NativeCompilerBackend` 抽象化。ビルドキャッシュ（mtime）。テストコンパイルと JUnit Platform 実行。 |
| `build/daemon` | `DaemonCompilerBackend` — 指数バックオフ付きの接続またはスポーン。デーモン JAR および BTA impl JAR の解決。ブートストラップ JDK のプロビジョニング。 |
| `build/nativedaemon` | `NativeDaemonBackend` — ネイティブデーモン用の同じ接続またはスポーンパターン。konanc JAR の検出とデーモン前提条件の確認。 |
| `nativedaemon/wire` | ネイティブデーモン用のフレームコーデックとメッセージ型（`NativeCompile` / `NativeCompileResult`）。JVM デーモンのプロトコルをミラーするが、構造化された `Compile` フィールドではなく konanc 引数のフラットなリストを搬送する。 |
| `resolve` | POM/Gradle モジュールメタデータによる推移的依存解決。ロックファイル（v2、SHA-256 ハッシュ）。プラグイン JAR の取得。 |
| `daemon` | JVM デーモン用のワイヤコーデックとクライアント側フレーミング（4 バイト長 + JSON）。 |
| `tool` | `~/.kolt/toolchains/` 配下で kotlinc、konanc、JDK をダウンロード・管理。 |
| `infra` | `fork`/`execvp` プロセス実行、`spawnDetached`（デーモン用ダブルフォーク）、Unix ソケットクライアント、inotify、SHA-256、HTTP ダウンロード（libcurl cinterop）。 |

### コンパイラデーモン (`kolt-compiler-daemon/`)

JVM ターゲットのコンパイルに対しビルド間でウォーム状態を維持する JVM プロセス。ネイティブクライアントからダブルフォークで起動され、Unix ドメインソケットで通信します。

| パッケージ | 責務 |
|-----------|------|
| `server` | ソケット接続を受け付け、`Compile` → `CompileResult` をディスパッチ。ライフサイクル管理（アイドルタイムアウト、最大コンパイル数、ヒープウォーターマーク）。 |
| `protocol` | ネイティブ側とミラーするワイヤ型。Java NIO `SocketChannel` 用フレームコーデック。kotlinc stderr の診断パーサー。 |
| `ic` | `BtaIncrementalCompiler` — kotlin-build-tools-api 上のアダプター。プロジェクトごとのファイルロック、クラスパススナップショットキャッシュ、プラグイン変換。`SelfHealingIncrementalCompiler` が内部エラー時にキャッシュワイプ＆リトライでラップ。 |
| `reaper` | アクティブでなくなったプロジェクトの古い IC 状態を削除するバックグラウンドスレッド。 |

JVM デーモン内の ClassLoader 階層：

```
daemon classloader
  └─ SharedApiClassesClassLoader (org.jetbrains.kotlin.buildtools.api.*)
      └─ URLClassLoader (kotlin-build-tools-impl + プラグイン JAR)
```

### ネイティブコンパイラデーモン (`kolt-native-daemon/`)

ネイティブターゲットのコンパイルに対するピアの JVM プロセス（ADR 0024）。JVM デーモンと同じスポーン / ワイヤパターンを共有しつつ、`kotlin-native-compiler-embeddable.jar` をロードし `K2Native.exec(PrintStream, String[])` をリフレクションで呼び出します。ネイティブ側には Build Tools API が存在しないためです。

| パッケージ | 責務 |
|-----------|------|
| `server` | ソケット接続を受け付け、`NativeCompile` → `NativeCompileResult` をディスパッチ。ライフサイクル管理（アイドルタイムアウト、最大コンパイル数、ヒープウォーターマーク）。accept ループはシングルスレッド — K2Native はデーモンで再利用され、同時呼び出しに対して安全ではない。 |
| `protocol` | ワイヤ型とフレームコーデック（JVM デーモンと同じ 4 バイト長 + JSON フレーミング）。 |
| `compiler` | `ReflectiveK2NativeCompiler` — 専用の ClassLoader で konanc をロードし、`exec` をリフレクションで呼び出す。インスタンスはデーモンの寿命の間保持される。 |

ネイティブデーモンの ClassLoader トポロジ：

```
bootstrap classloader (JDK のみ)
  └─ URLClassLoader(arrayOf(konancUrl), null)   // konanc + 同梱 stdlib
daemon classloader (独立、kolt-native-daemon のコード + kotlin-result を保持)
```

konanc の ClassLoader は **親が null** です。デーモン自身のクラスパス（kotlin-result、kotlinx-serialization-json、デーモンコア）は konanc から見えず、konanc が同梱する Kotlin stdlib / kotlinx-serialization もデーモン側に漏れません。これは JVM デーモンの共有 API 階層とは対照的です。JVM デーモンは安定した API 面（`org.jetbrains.kotlin.buildtools.api.*`）を impl JAR と意図的に共有しますが、ネイティブには同等の API が存在しないため厳密に隔離します。

## ビルドフロー

JVM ターゲットでの `kolt build`：

1. `kolt.toml` をパース → `KoltConfig`
2. ビルドキャッシュをチェック（ソースの mtime と前回ビルドの比較）
3. 依存を解決 — ロックファイルを読むか推移的解決を実行、JAR を `~/.kolt/cache/` にダウンロード
4. コンパイラバックエンドを選択 — JVM デーモン（Unix ソケット）を試行、サブプロセスにフォールバック
5. ソースをコンパイル → `build/classes/`
6. パッケージング → `build/{name}.jar`（`-include-runtime` 付き fat JAR）

ネイティブターゲットでは、ステップ 4〜6 が同じ形を取りつつ `NativeCompilerBackend` を経由します。`resolveNativeCompilerBackend`（デーモンをプライマリ、`NativeSubprocessBackend` をフォールバック）でバックエンドを解決し、konanc を 2 回実行します — ライブラリステージ（`-p library -nopack`）とリンクステージで、いずれもバックエンドにディスパッチされます。konanc のプラグインに関する問題への回避策として 2 段階分割は維持しますが（ADR 0014）、デーモン経路では両ステージがウォーム JVM にヒットするため、呼び出しごとの約 3 秒の起動コストを支払わずに済みます（ADR 0024 §5）。

## デーモンライフサイクル

両デーモンはスポーンと接続のパターンを共有し、チューニングで差異があります。

- **ソケット**：`~/.kolt/daemon/<projectHash>/<kotlinVersion>/daemon.sock`（JVM）と `.../native-daemon.sock`（ネイティブ）。同じバージョンディレクトリに並置することで、`kolt daemon stop` を単一の列挙パスで済ませます（ADR 0020 §2、ADR 0024）。
- **スポーン**：ネイティブクライアントが対応するバックエンドから `spawnDetached()` を呼び出し（ダブルフォーク + setsid）。
- **接続**：クライアントが指数バックオフでリトライ（10〜200 ms、3 秒バジェット）。
- **シャットダウン**：アイドルタイムアウト、最大コンパイル数到達、ヒープウォーターマーク超過、または明示的な `kolt daemon stop`。ネイティブデーモンはデフォルト値がよりタイトです（アイドル 10 分 vs 30 分、ヒープウォーターマーク 2 GB）。ネイティブビルドの頻度が低いためです。
- **古いデータのクリーンアップ**：`DaemonReaper` が両デーモンの孤立したソケットとバージョンディレクトリを削除。`IcReaper` は JVM デーモンの未使用の BTA IC 状態のみを削除します — ネイティブデーモンは IC 状態を管理しません（konanc が自身で `-Xic-cache-dir` を扱います）。

## 依存解決

- POM メタデータ上の BFS で推移的依存を解決
- Maven リポジトリ、Gradle モジュールメタデータ、バージョンインターバル、exclusions に対応
- グローバルキャッシュ：`~/.kolt/cache/`（Maven 互換レイアウト）
- `kolt.lock` がバージョンと SHA-256 ハッシュの単一の信頼ソース
- ハッシュ不一致 → ハードエラー（暗黙の再ダウンロードなし）

## エラー処理

すべての関数は `Result<V, E>`（kotlin-result）を返します。例外のスローは禁止されています。

JVM コンパイルのフォールバックチェーン：デーモンバックエンド → サブプロセスバックエンド、`FallbackCompilerBackend` 経由。デーモンの `SelfHealingIncrementalCompiler` は内部エラー時に IC 状態をワイプして 1 回リトライします。

ネイティブコンパイルのフォールバックチェーン：`FallbackNativeCompilerBackend` が `NativeDaemonBackend` を `NativeSubprocessBackend` でラップします。エラーごとにサブプロセスへリトライするかどうかは `isNativeFallbackEligible` が判定します — インフラ起因の失敗（接続拒否、スポーン失敗、予期せぬ切断）はフォールバックし、真のコンパイルエラーはフォールバックしません。これによりユーザーはサブプロセスの重複実行なしに konanc の診断を目にします。

## スコープ外

- **タスクランナー** — npm scripts スタイルのカスタムコマンド定義は行わない。ビルドライフサイクルフックは別の議論（[#119](https://github.com/snicmakino/kolt/issues/119)）。
- **プラグインシステム** — インプロセス拡張 API は提供しない。kolt は Kotlin/Native バイナリであり JVM プラグインをロードできない。[ADR 0021](adr/0021-no-plugin-system.md) を参照。
- **Kotlin Multiplatform (KMP)** — 単一プロジェクトで複数ターゲットを管理する機能。複雑性が高く、Gradle との差別化ポイントではない。
- **Android** — AGP の再実装は現実的ではない。
- **IDE 統合** — IntelliJ/VSCode プラグイン。ツール自体をまず成立させることが先。

## ADR

アーキテクチャ上の決定事項は `docs/adr/` に記録されています。主要なもの：

- [0001](adr/0001-result-type-error-handling.md) — Result 型エラー処理
- [0004](adr/0004-pure-io-separation.md) — リゾルバの Pure/IO 分離
- [0016](adr/0016-warm-jvm-compiler-daemon.md) — ウォーム JVM コンパイラデーモン
- [0019](adr/0019-incremental-build-kotlin-build-tools-api.md) — BTA によるインクリメンタルビルド
- [0021](adr/0021-no-plugin-system.md) — プラグインシステム不採用
- [0024](adr/0024-native-compiler-daemon.md) — リフレクティブ K2Native によるネイティブコンパイラデーモン
