# kolt

[![Unit tests](https://github.com/snicmakino/kolt/actions/workflows/unit-tests.yml/badge.svg)](https://github.com/snicmakino/kolt/actions/workflows/unit-tests.yml)
[![Self-host smoke test](https://github.com/snicmakino/kolt/actions/workflows/self-host-smoke.yml/badge.svg)](https://github.com/snicmakino/kolt/actions/workflows/self-host-smoke.yml)

[English](README.md) | 日本語

> v0.16.1 — 初期段階のプロジェクトです。破壊的変更の可能性があります。

Kotlin 向けの軽量ビルドツールです。TOML 設定ファイルのみで動作し、Kotlin DSL のビルドスクリプト評価は不要です。単一の Kotlin/Native バイナリとして配布されるため、利用に Java のインストールは必要ありません。

ツール自体は瞬時に起動します。実際のコンパイルは `kotlinc` / `konanc` に委譲するため、ビルド時間は Kotlin コンパイラに直接依存します。mtime ベースのキャッシュによるインクリメンタルビルドで、変更のないソースは完全にスキップされます。ウォーム JVM コンパイラデーモンが JVM 起動コストを償却し、ウォームビルドの典型的なレイテンシは約 0.3 秒です。

## インストール

Linux x64 環境で最新リリースをインストール:

```sh
curl -fsSL https://raw.githubusercontent.com/snicmakino/kolt/main/install.sh | sh
```

インストーラーは GitHub Releases から該当 version の tarball を取得し、SHA-256 を検証した上で `~/.local/share/kolt/<version>/` 配下に展開し、`~/.local/bin/kolt` に symlink を作成します。`~/.local/bin` を `PATH` に追加する必要がある場合があります:

```sh
export PATH="$HOME/.local/bin:$PATH"
```

### 環境変数

- `KOLT_VERSION=<v>` — 最新版ではなく特定 version をインストール
- `KOLT_ALLOW_YANKED=1` — yank された version でも強制インストール (デフォルトでは推奨される replacement を表示して refuse)

### サポート対象 platform

初版は **linux-x64 のみ**。macOS は [#82](https://github.com/snicmakino/kolt/issues/82)、linuxArm64 は [#83](https://github.com/snicmakino/kolt/issues/83) で別 track。それ以外の platform は [ソースからビルド](#ソースからビルド) を参照。

## クイックスタート

```sh
mkdir my-app && cd my-app
kolt init
kolt build
kolt run
```

カレントディレクトリに以下の構造が生成されます：

```
kolt.toml
src/Main.kt
test/MainTest.kt
```

プロジェクト名はディレクトリ名から推測されます。別の名前を使う場合：

```sh
kolt init custom-name
```

## コマンド

```
kolt init [name]       新しいプロジェクトを作成
kolt build             プロジェクトをコンパイル（デフォルトは debug プロファイル）
kolt build --release   release プロファイルでコンパイル
kolt run               ビルドして実行（kolt run -- args でアプリ引数を渡す）
kolt test              ビルドしてテスト実行（kolt test -- args で JUnit Platform 引数を渡す）
kolt check             成果物を生成せずに型チェック
kolt add <dep>         依存を追加（deps add のエイリアス）

# --watch フラグ：ソースを監視し、変更時に再ビルド
kolt build --watch     監視して再ビルド
kolt test --watch      監視して再テスト
kolt run --watch       監視して再ビルド・アプリ再起動
kolt check --watch     監視して型チェック
kolt fmt               ktfmt でソースファイルをフォーマット
kolt fmt --check       フォーマットチェック（CI モード）
kolt clean             ビルド成果物を削除

kolt deps add <dep>    依存を追加
kolt deps install      依存を解決して JAR をダウンロード
kolt deps update       依存を再解決してロックファイルを更新
kolt deps tree         依存ツリーを表示

kolt toolchain install kolt.toml で定義された kotlinc バージョンをインストール

kolt daemon stop       このプロジェクトのコンパイラデーモンを停止
kolt daemon stop --all 全コンパイラデーモンを停止
kolt daemon reap       古いデーモンディレクトリと孤立ソケットを削除

kolt --version         バージョンを表示
```

`install`、`update`、`tree` はトップレベルのエイリアスとしても利用可能です（例：`kolt install`）。

### フラグ

| フラグ | 説明 |
|--------|------|
| `--watch` | ソースファイルを監視し、変更時にコマンドを再実行（build/check/test/run） |
| `--no-daemon` | この実行でウォームコンパイラデーモンをスキップ。daemon サポート対象外の Kotlin バージョン (ADR 0022) でも常に利用可能。 |
| `--release` | release プロファイルでビルドする。Native は `-opt` を有効化し `-g` を外して `build/release/` に出力。JVM では宣言上 no-op（kotlinc 引数も daemon IC パスも変わらない）だが、両プロファイル成果物を相互に上書きしないよう成果物パスは `build/release/<name>.jar` に切り替わる。デフォルトは `debug` プロファイルで、両プロファイルの成果物はディスク上に共存するためプロファイルを切り替えても他方の IC が無効化されることはない。詳細は [ADR 0030](docs/adr/0030-build-profiles.md) を参照。 |

## 設定

`kolt.toml` — 宣言的な TOML ベースの設定ファイル：

```toml
name = "my-app"
version = "0.1.0"

[kotlin]
version = "2.3.20"

[kotlin.plugins]
serialization = true

[build]
target = "jvm"
jvm_target = "25"
main = "main"
sources = ["src"]
resources = ["resources"]
test_resources = ["test-resources"]

[dependencies]
"org.jetbrains.kotlinx:kotlinx-coroutines-core" = "1.9.0"

[repositories]
central = "https://repo1.maven.org/maven2"
jitpack = "https://jitpack.io"
```

### フィールド

| キー | 説明 | デフォルト |
|------|------|-----------|
| `name` | プロジェクト名 | （必須） |
| `version` | プロジェクトバージョン | （必須） |
| `[kotlin] version` | Kotlin 言語／API バージョン（`compiler` 未指定時はコンパイラバージョンも兼ねる） | （必須） |
| `[kotlin] compiler` | `version` と独立して kotlinc / daemon のバージョンを固定（`>= version` 必須） | `version` |
| `[kotlin.plugins]` | コンパイラプラグイン（`serialization`、`allopen`、`noarg`） | `{}` |
| `[build] target` | `"jvm"` または KonanTarget（`"linuxX64"`, `"linuxArm64"`, `"macosX64"`, `"macosArm64"`, `"mingwX64"`） | （必須） |
| `[build] jvm_target` | JVM バイトコードターゲット | `"25"` |
| `[build] jdk` | daemon/runtime 用の JDK バージョン | （ホスト JDK） |
| `[build] main` | エントリポイント関数の FQN（例：`"main"` または `"com.example.main"`） | （必須） |
| `[build] sources` | ソースディレクトリ | （必須） |
| `[build] test_sources` | テストソースディレクトリ | `["test"]` |
| `[build] resources` | ビルド出力に含めるリソースディレクトリ | `[]` |
| `[build] test_resources` | テスト時のクラスパスに追加するリソースディレクトリ | `[]` |
| `[fmt] style` | ktfmt スタイル：`"google"`、`"kotlinlang"`、`"meta"` | `"google"` |
| `[[cinterop]]` | native target 用の C interop バインディング（`.def` ごとに 1 エントリ） | `[]` |
| `[repositories]` | Maven リポジトリ（名前 = URL） | Maven Central のみ |

### 依存関係

`kolt add` で依存を追加：

```sh
kolt add org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0
kolt add org.jetbrains.kotlinx:kotlinx-coroutines-core   # 最新安定バージョン
kolt add --test io.kotest:kotest-runner-junit5:5.8.0      # テスト依存
```

または `[dependencies]` に Maven 座標を直接記述：

```toml
[dependencies]
"org.jetbrains.kotlinx:kotlinx-coroutines-core" = "1.9.0"
"com.squareup.okhttp3:okhttp" = "4.12.0"
```

`kolt install` を実行すると、ビルドせずにすべての依存を解決・ダウンロードします。

推移的依存は POM メタデータを通じて自動解決されます。`kolt.lock` ファイルがバージョンと SHA-256 ハッシュを記録し、再現可能なビルドを保証します。

### カスタムリポジトリ

デフォルトでは Maven Central から依存を解決します。カスタムリポジトリ（例：JitPack）を追加するには `[repositories]` で宣言します：

```toml
[repositories]
central = "https://repo1.maven.org/maven2"
jitpack = "https://jitpack.io"
```

リポジトリは宣言順に試行されます。省略時は Maven Central が使用されます。

### コンパイラプラグイン

`[kotlin.plugins]` で Kotlin コンパイラプラグインを有効化：

```toml
[kotlin.plugins]
serialization = true
```

対応プラグイン：`serialization`、`allopen`、`noarg`。プラグイン JAR は Kotlin コンパイラディストリビューションから解決されます。

プラグインは `target = "jvm"` と native target（`"linuxX64"` など）の両方で動作します。Native では、konanc の 2 段階コンパイル（`-p library` → `-p program -Xinclude=...`）でプラグインレジストラをライブラリ段階で実行します。これは、konanc の単一ステップ `-p program` がコンパイラプラグインを暗黙にスキップする問題への回避策です。詳細は ADR 0014 を参照してください。Native プロジェクトでプラグインを有効にすると、現在は `<kotlincHome>/lib/` からプラグイン JAR を借用するために kotlinc ディストリビューションをサイドカーとしてプロビジョニングします。将来的に Maven Central からの直接解決に切り替え予定です。

### C Interop（native ターゲット）

C ライブラリを呼び出す native target プロジェクトでは、`.def` ファイルごとに `[[cinterop]]` エントリを宣言します。kolt は各エントリに対して konan の `cinterop` ツールを呼び出し、生成された `.klib` を `build/` にキャッシュして、ライブラリおよびリンク段階で `-l` を通じて `konanc` に渡します。

```toml
[[cinterop]]
name = "libcurl"
def = "src/nativeInterop/cinterop/libcurl.def"
package = "libcurl"
```

| フィールド | 説明 | デフォルト |
|-----------|------|-----------|
| `name` | 出力 klib のベース名（`build/<name>.klib`） | （必須） |
| `def` | バインディングを記述する `.def` ファイルのパス | （必須） |
| `package` | 生成バインディングの Kotlin パッケージ | （`.def` から導出） |

コンパイラおよびリンカオプションは `.def` ファイル内に、Kotlin/Native 標準の `compilerOpts.<platform>` / `linkerOpts.<platform>` キーで記述します。例：

```ini
headers = curl/curl.h
compilerOpts.linux = -I/usr/include -I/usr/include/x86_64-linux-gnu
linkerOpts.linux = -L/usr/lib/x86_64-linux-gnu -lcurl
```

`cinterop` klib は `.def` ファイルの mtime が変更された場合に再生成されます。ソースのみの編集ではキャッシュ済み klib を再利用します。複数の `[[cinterop]]` エントリが許可され、宣言順にリンクされます。

### リソースファイル

リソースファイル（設定ファイル、テンプレート、静的アセット）をビルド出力に含める：

```toml
[build]
resources = ["resources"]
test_resources = ["test-resources"]
```

`resources` ディレクトリのファイルはビルド出力にコピーされ、JAR に含まれます。`test_resources` ディレクトリのファイルはテスト実行時のクラスパスに追加されます。存在しないディレクトリは暗黙にスキップされます。

### テスト依存

JVM ターゲットでは、kolt が Kotlin バージョンに合わせて `kotlin-test-junit5` を自動注入します。`kotlin.test` を使ってテストを書くだけです：

```kotlin
import kotlin.test.Test
import kotlin.test.assertEquals

class MyTest {
    @Test
    fun testAdd() {
        assertEquals(3, add(1, 2))
    }
}
```

追加のテストフレームワーク（例：Kotest）を使う場合は `[test-dependencies]` で宣言：

```toml
[test-dependencies]
"io.kotest:kotest-runner-junit5" = "5.8.0"
```

## テスト

kolt は JUnit Platform Console Standalone を通じてテストを実行します。対応フレームワーク：

- **kotlin.test**（kotlin-test-junit5 経由、自動注入）
- **JUnit 5**（直接）
- **Kotest**（kotest-runner-junit5 経由）

```sh
kolt test
kolt test -- --include-classname ".*IntegrationTest"
```

## 依存管理

- **グローバルキャッシュ**：`~/.kolt/cache/` — プロジェクト間で共有（Maven 互換レイアウト）
- **ロックファイル**：`kolt.lock` がバージョンと SHA-256 ハッシュを記録
- **SHA-256 検証**：不一致時はエラー（暗黙の再ダウンロードなし）
- **明示的な更新**：`kolt update` で依存を再解決しハッシュを更新

## ツールチェイン管理

kolt は独自の kotlinc インストールを管理できるため、システムワイドにインストールする必要はありません。

```sh
kolt toolchain install   # kolt.toml で指定された kotlinc バージョンをダウンロード
```

ツールチェインは `~/.kolt/toolchains/kotlinc/{version}/` に保存されます。管理対象のツールチェインが利用可能な場合は自動的に使用され、なければ PATH 上のシステム `kotlinc` にフォールバックします。

### 未対応の機能

v1.0 で対応予定：

- ライブラリパッケージング（現在は `app` のみ）
- `kolt publish` / `kolt new`
- macOS および linuxArm64 ターゲット
- プライベート Maven リポジトリの認証

v1.0 以降で対応予定：

- マルチモジュールプロジェクト
- Kotlin/Java 混合ソースのコンパイル

## Kotlin バージョンサポート

kolt は **Kotlin 2.3.0 以上** を daemon のファーストクラス対象としてサポートします。`kolt.toml` の `[kotlin] version = "2.3.x"` は `[kotlin.plugins]` を使うプロジェクトも含め常に warm compiler daemon を通ります。Kotlin 2.3.20 はバンドル済みなので初回ビルドでダウンロードは発生しません。他の 2.3.x パッチは初回使用時に Maven Central から取得され `~/.kolt/cache/` にキャッシュされます。

2.3.0 未満は soft floor です：`kolt build` は subprocess で動作し、該当ビルドごとに 1 行の警告が出ます。`--no-daemon` を渡すと警告は消え、Kotlin バージョンによらず常に利用できます。

将来の Kotlin 言語リリース（2.4.0、2.5.0 …）のサポート範囲は事前に約束せず、各リリース時点で再評価します。ポリシーの詳細は [ADR 0022](docs/adr/0022-supported-kotlin-version-policy.md) を参照してください。

### language バージョンと切り離して compiler を固定する

古い言語バージョン（例：Kotlin 2.1）のままで daemon の恩恵を受けたい場合、
`compiler` を daemon サポート対象のバージョンに固定します：

```toml
[kotlin]
version = "2.1.0"      # 言語 / API バージョン
compiler = "2.3.20"    # kotlinc + daemon バージョン（未指定時は version と同じ）
```

`compiler > version` の場合、kolt は 2.3.20 の daemon / kotlinc を使いつつ
コンパイル時に `-language-version 2.1` / `-api-version 2.1` を渡します
（`major.minor` のみ — kotlinc は patch 付きの値を拒否します）。
`compiler < version` はパース時にエラーとなります。`version` のみを指定した
場合は従来どおりの挙動です。

## なぜ kolt？

Gradle は強力ですが、シンプルな Kotlin プロジェクトには重すぎます：

- JVM 起動コスト
- ビルドスクリプトのコンパイル
- タスクグラフの構築

kolt は Go における `go build`、Rust における `cargo build` に相当するものを目指しています。宣言的な設定ファイルとゼロセレモニーで Kotlin プロジェクトをビルドする、高速で集中したツールです。

## 終了コード

| コード | 意味 |
|--------|------|
| 0 | 成功 |
| 1 | ビルドエラー |
| 2 | 設定エラー |
| 3 | 依存エラー |
| 4 | テストエラー |
| 5 | フォーマットエラー |
| 127 | コマンドが見つからない |

## アーキテクチャ

内部設計（コンポーネント概要、ビルドフロー、デーモンライフサイクル、アーキテクチャ上の決定事項）については [docs/architecture.ja.md](docs/architecture.ja.md) を参照してください。

## ソースからビルド

contributor やインストーラー未対応 platform の利用者向け:

```sh
git clone https://github.com/snicmakino/kolt.git
cd kolt
./gradlew build
```

バイナリは `build/bin/linuxX64/debugExecutable/kolt.kexe` に生成されます。PATH 上のディレクトリにコピーしてください:

```sh
cp build/bin/linuxX64/debugExecutable/kolt.kexe ~/.local/bin/kolt
```

## Claude Code 連携

このプロジェクトには [Claude Code](https://claude.ai/code) のスキルが含まれています。Claude Code を使用する場合、`/kolt-usage` スキルで kolt のコマンド、設定、依存管理についてのインタラクティブなヘルプを利用できます。[直接参照](.claude/skills/kolt-usage/SKILL.md) することもできます。

## 名前

**kolt** = **Kot**lin + bo**lt** — 高速で軽量な Kotlin ツーリング。

## ライセンス

MIT
