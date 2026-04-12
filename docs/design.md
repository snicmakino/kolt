# kolt — Kotlin向け軽量ビルドツール

## コンセプト

Gradleを介さずに `kotlinc` / `konanc` を直接呼び出す、Kotlin Native製の軽量ビルドツール。
宣言的な設定ファイルだけでKotlinプロジェクトのビルド・実行・依存管理を行う。

**目指す体験:**

```
kolt init      # プロジェクト雛形を生成
kolt build     # コンパイル
kolt run       # 実行（kolt run -- args でアプリ引数も渡せる）
kolt test      # テスト実行（kolt test -- args でJUnit Platform引数も渡せる）
kolt clean     # ビルド成果物を削除
kolt --version # バージョン表示
```

## なぜ作るのか

- Gradleは汎用的すぎて、単純なKotlinプロジェクトに対してオーバーヘッドが大きい
  - JVM起動コスト
  - ビルドスクリプト自体のコンパイル
  - タスクグラフの構築・設定フェーズなど重い抽象化
- 「ちょっとしたKotlin CLIツールを作りたい」だけなのにGradleプロジェクト一式が必要になる
- Go の `go build`、Rust の `cargo build` に相当するものがKotlinにはない
- Kotlin公式フォーラムでもビルドツーリングへの不満は多いが、実際に代替を作ったプロジェクトはほぼ存在しない

## なぜKotlin Nativeで書くのか

- ビルドツール自体がJVMに依存しないため、起動が速い
- シングルバイナリで配布できる

## 設定ファイル（宣言的）

TOML形式を採用する（ktoml 0.7.1でパース）。

- コメントが書ける、可読性が高い
- Cargoの `Cargo.toml`、Denoの `deno.json` と同様のアプローチ
- 未知のフィールドは無視する（前方互換性のため）

```toml
name = "my-app"
version = "0.1.0"
kotlin = "2.1.0"
target = "jvm"
jvm_target = "17"
main = "com.example.MainKt"
sources = ["src"]

[dependencies]
"org.jetbrains.kotlinx:kotlinx-coroutines-core" = "1.9.0"
"com.squareup.okhttp3:okhttp" = "4.12.0"
```

- `name`: プロジェクト名
- `version`: プロジェクトバージョン
- `target`: `"jvm"` または `"native"`（将来的に拡張）
- `jvm_target`: JVMバイトコードのターゲットバージョン（`target: "jvm"` 時）
- `main`: エントリポイントクラス
- `sources`: ソースディレクトリ（デフォルトは `["src"]`）
- `dependencies`: Maven座標形式で宣言

## ロックファイル（`kolt.lock`）

依存解決の結果を記録し、環境間でのビルド再現性を保証する。`kolt build` 時に自動生成・更新。リポジトリにコミットする想定。

**フォーマット**: JSONで実装。出力時にキーをソートし安定した出力を保証する。将来的にdiff-friendlyな独自形式への移行も検討する（Cargoの `Cargo.lock` やGoの `go.sum` のように）。v1からv2へのマイグレーションは自動で行われる（v1は`transitive`フィールドなし、v2で追加）。

```json
{
  "version": 2,
  "kotlin": "2.1.0",
  "jvm_target": "17",
  "dependencies": {
    "org.jetbrains.kotlinx:kotlinx-coroutines-core": {
      "version": "1.9.0",
      "sha256": "abc123...",
      "transitive": false
    },
    "com.squareup.okhttp3:okhttp": {
      "version": "4.12.0",
      "sha256": "def456...",
      "transitive": false
    },
    "com.squareup.okio:okio": {
      "version": "3.9.0",
      "sha256": "ghi789...",
      "transitive": true
    }
  }
}
```

**記録する情報:**

- `version`: lockフォーマットバージョン（将来のフォーマット変更時のマイグレーション用）
- `kotlin`: コンパイラバージョン（異なるバージョンでのビルド時に警告を出せる）
- `jvm_target`: バイトコードターゲット（JDKメジャーバージョンの不一致を検知）
- 各依存の `sha256`: ダウンロードしたアーティファクトの整合性検証
- dependenciesのキーは `group:artifact`（kolt.tomlのdependenciesキーと一致させる。バージョン変更時のdiffが読みやすい）
- `transitive`: 推移的依存かどうかのフラグ（v2で追加済み）

**sha256検証ポリシー:**

- `kolt.lock` が単一の信頼ソース（キャッシュ側に `.sha256` ファイルは置かない）
- 検証フロー: jarのsha256を計算 → lockの値と照合
- 不一致時はエラー停止（サプライチェーン攻撃対策。自動再ダウンロードしない）
- 明示的な `kolt update` コマンドで再取得＋ハッシュ更新

**JDKバージョンに関する注意:**

- `kotlinc` は `-jvm-target` オプションでバイトコードバージョンを制御する
- 依存ライブラリのバイトコードバージョンが `jvm_target` より高い場合、実行時エラーになりうる
- lockファイルにJDKメジャーバージョンを記録することで、CI等の別環境での不一致を早期検知する
- JDKマイナーバージョンの違い（17.0.1 vs 17.0.12）はjarの中身にほぼ影響しないため、メジャーバージョンのみ追跡する

## ビルドターゲット

| target | コンパイラ | 出力 | 依存形式 |
|--------|-----------|------|---------|
| `jvm` | `kotlinc` | 実行可能JAR（fat JAR、`-include-runtime`） | `.jar`（Maven Central） |
| `native` | `konanc` | ネイティブバイナリ | `.klib`（KMP対応ライブラリのみ） |

## 依存管理の方針

- **グローバルキャッシュ**: `~/.kolt/cache/` にダウンロードしたアーティファクトを保存。複数プロジェクトで共有しディスクを節約（Cargo方式）。キャッシュにはjarファイルのみ保存（sha256ファイルは置かない）
- **lockファイル**: プロジェクトには `kolt.lock` のみ配置。バージョン・ハッシュを記録し再現性を保証。sha256の単一の信頼ソース
- **プロジェクトローカルにjarはコピーしない**: ビルド時にキャッシュからクラスパスを組み立てる
- **sha256不一致時はエラー停止**: 自動再ダウンロードしない（サプライチェーン攻撃対策）。`kolt update` で明示的に再取得

## 内部動作（概要）

1. `kolt.toml` を読み込む
2. `kolt.lock` が存在すればそれに従い、なければ依存解決を実行して `kolt.lock` を生成
3. 依存ライブラリを `~/.kolt/cache/` にダウンロード（キャッシュ済みならスキップ、sha256で整合性検証）
4. クラスパス（JVM）またはライブラリパス（Native）を組み立てる
5. `kotlinc` または `konanc` を適切なオプション付きで呼び出す
6. 成果物を `build/` に出力

## 開発ロードマップ

### Phase 1 — 最小限のビルド ✅

- `kolt.toml` を読んで `kotlinc` に渡すだけ
- 単一ソースディレクトリ、依存なし
- JVMターゲットのみ
- `kolt build` → `kotlinc -include-runtime -d build/app.jar` で実行可能JAR（fat JAR）を生成
- `kolt run` → `java -jar build/app.jar` で実行
- PATH上の `kotlinc` のバージョンと `kolt.toml` の `kotlin` フィールドの不一致を検知・警告
- **注意**: `-include-runtime` はkotlin-stdlibをJARに同梱する。依存なしPhaseでもkotlin-stdlib自体はランタイムに必要なため、この方式で対応する

### Phase 2 — 依存解決（直接依存） ✅

- Maven Centralからjarをフェッチ（libcurl cinterop — [ADR 0006](adr/0006-use-libcurl-cinterop-instead-of-ktor.md)）
- `~/.kolt/cache/` にキャッシュ（sha256で検証、kotlincrypto sha2-256）
- `kolt.lock` の生成・読み込み
- クラスパスを自動組み立て

**キャッシュディレクトリ構造（Maven互換）:**

```
~/.kolt/cache/
  org/jetbrains/kotlinx/
    kotlinx-coroutines-core/
      1.9.0/
        kotlinx-coroutines-core-1.9.0.jar
```

変換ルール: `groupId` の `.` → `/`、続いて `artifactId/version/artifact-version.jar`

**Maven Central URL構成:**

```
https://repo1.maven.org/maven2/{group}/{artifact}/{version}/{artifact}-{version}.jar
```

例: `org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0`
→ `https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core/1.9.0/kotlinx-coroutines-core-1.9.0.jar`

**ビルドフロー（依存あり）:**

1. `kolt.toml` の `dependencies` を読み込む
2. `kolt.lock` が存在する場合:
   - lockのdependenciesと照合
   - 一致 → キャッシュからjarを取得、sha256検証
   - kolt.tomlに追加/削除/バージョン変更あり → 再解決してlock更新
3. `kolt.lock` が存在しない場合:
   - 全依存をダウンロード、sha256を計算、`kolt.lock` を生成
4. クラスパスを組み立て: `kotlinc -cp jar1:jar2:... [sources] -include-runtime -d build/{name}.jar`

### Phase 3 — 推移的依存解決 ✅

- pom.xmlのパース（自前XMLパーサー、プロパティ補間対応）
- BFS依存グラフ探索による推移的依存の解決
- バージョン競合の解決（最新バージョン優先）
- Mavenバージョンインターバル（`[1.0,2.0)` 等）の解析・比較
- `<exclusions>` のサポート
- POMメタデータのキャッシュ
- I/Oと純粋ロジックの分離（Resolution.kt = 純粋関数、TransitiveResolver.kt = I/Oオーケストレーション）
- lockfileをv2に更新（`transitive` フラグ追加）

### Phase 4 — Kotlin/Nativeサポート（将来検討）

- `target: "native"` 時に `konanc` を呼び出し
- `.klib` 形式の依存解決
- Maven上のプラットフォーム別アーティファクト（`-native-linux` 等）の取得
- **注意**: konanc + .klibのエコシステムは狭く実用的なユースケースが限定的。優先度は低め

### Phase 5 — DX改善 🚧 進行中

**実装済み:**
- `kolt init [project-name]` によるプロジェクト雛形生成（kolt.toml + src/Main.kt + test/MainTest.kt）
- `kolt clean` によるビルド成果物の削除
- `kolt --version` / `kolt version` によるバージョン表示
- `kolt run -- args` でアプリへの引数渡し
- ビルド所要時間の表示（例: "2.3s"、"1m 5.0s"）
- 標準化された終了コード（0=成功, 1=ビルドエラー, 2=設定エラー, 3=依存エラー, 4=テストエラー, 127=コマンド未発見）
- プロジェクト名のバリデーション（`[a-zA-Z0-9][a-zA-Z0-9._-]*`）

### Phase 6 — テスト実行 ✅

- `kolt test` でテストのコンパイル・実行
- `kolt test -- <args>` でJUnit Platformへの引数渡し
- JUnit 5、kotlin-test、Kotest をサポート（JUnit Platform Console Standalone経由）
- `kotlin-test-junit5` を自動注入（JVMターゲット時、Kotlinバージョンに合わせて）
- `[test-dependencies]` セクションで追加のテスト依存を宣言可能（Kotest等）
- `test_sources` フィールド（デフォルト: `["test"]`）
- `kolt init` でテスト雛形（test/MainTest.kt）を生成。テスト依存の宣言は不要
- JUnit Platform Console Standalone JARは `~/.kolt/tools/` に自動キャッシュ

**テスト実行フロー:**

1. メインソースをビルド（`build/{name}.jar`）
2. 依存を一括解決（main + 自動注入テスト依存 + ユーザーテスト依存、推移的依存含む）
3. JUnit Platform Console Standalone JARを確保（キャッシュ or ダウンロード）
4. テストソースをコンパイル（`build/{name}-test.jar`）
5. `java -jar junit-platform-console-standalone.jar --class-path ... --scan-class-path` で実行

**自動注入テスト依存:**

JVMターゲット時、`kotlin-test-junit5` を `kolt.toml` の `kotlin` バージョンに合わせて自動的にテストclasspathに追加する。ユーザーが `[test-dependencies]` で同じ依存を宣言した場合はユーザー指定のバージョンが優先される。`kotlin-test` は `kotlin-test-junit5` の推移的依存として自動的に解決される。

**kolt.tomlの拡張（テスト依存の宣言は通常不要）:**

```toml
test_sources = ["test"]   # optional, defaults to ["test"]

# Kotest等を使いたい場合のみ宣言
[test-dependencies]
"io.kotest:kotest-runner-junit5" = "5.8.0"
```

**未実装:**
- インクリメンタルビルド（変更ファイルのみ再コンパイル）
- マルチモジュール対応
- エラーメッセージの整形・カラー出力

## スコープ外（やらないこと）

以下は現時点でkoltのスコープに含めない。将来的に再検討する可能性はあるが、初期の判断基準としてここに明記する。

- **ソースフォーマッター/リンター**: ktlint, detekt等の既存ツールに委ねる。koltが内蔵する必要はない
- **タスクランナー**: `npm scripts` のようなカスタムコマンド定義。Cargo/Goの思想に従い、ビルドツールの責務外とする
- **Kotlin Multiplatform（KMP）プロジェクト**: 複数ターゲットを単一プロジェクトで管理する機能。Gradleとの差別化ポイントではなく複雑性が高い
- **Android開発**: AGP（Android Gradle Plugin）の再実装は非現実的
- **IDE統合**: IntelliJ/VSCodeプラグイン等。ツールとしてまず成立させることが先
- **プラグインシステム**: 拡張性は重要だが、安定したコア機能の確立が先

## 設計の参考ツール

| ツール | 言語 | 参考ポイント |
|--------|------|-------------|
| Cargo (Rust) | Rust | lockファイル設計、グローバルキャッシュ、`init/build/run` のCLI UX |
| Go | Go | 設定の最小主義、`go.mod`/`go.sum` のシンプルさ |
| Bun | Zig/C++ | 起動速度へのこだわり、既存エコシステム（npm）との互換 |
| uv (Python) | Rust | 非ネイティブ言語でツールを書く戦略、既存リポジトリ（PyPI）活用。koltと最もポジショニングが近い |
| Deno | Rust | ツールチェイン一体型、lockファイル自動管理、`deno.json` の設計 |
| pnpm | JS | コンテンツアドレッサブルなグローバルストア設計 |
| Zig build | Zig | 宣言的依存定義（`build.zig.zon`）のフォーマット設計 |
| [coursier](https://github.com/coursier/coursier) | Scala | **依存解決の設計参考（重要度: 高）**。状態マシン解決 (Done/Missing/Continue)、Exclusions (MinimizedExclusions)、VersionConstraint (interval + preferred)、不変な解決状態、POMメタデータキャッシュ |

**共通して学べる設計判断:**

- 設定ファイルは宣言的であるべき（Gradle/SBTのようなチューリング完全なビルドスクリプトは避ける）
- タスクランナーはビルドツールの責務に含めない（Cargo/Goの思想。koltでも当面はスコープ外）
- グローバルキャッシュ ＋ lockファイルが依存管理のモダンな標準形

## 競合・類似プロジェクト

| プロジェクト | 特徴 | koltとの違い |
|-------------|------|-------------|
| Gradle | デファクトスタンダード。汎用的だが重い | koltはKotlin専用で軽量 |
| Maven | XMLベース。予測可能だが冗長 | koltは宣言的TOMLで最小構成 |
| Mill | Scala製。透明性が高い | JVM上で動作。koltは非JVM |
| Bazel | Google製。大規模向け | セットアップが重い。koltは小〜中規模向け |

**「宣言的設定 + kotlinc直接呼び出し + 非JVMランタイム」の組み合わせは現時点で存在しない。**

