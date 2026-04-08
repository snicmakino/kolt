# keel_kt

Kotlin/Native (linuxX64) 製の軽量ビルドツール。Zig版keelのKotlin移植。
keel.jsonを読み込み、kotlincによるコンパイルとjava -jarによる実行を行う。

## ビルド・テスト

```bash
./gradlew build              # ビルド + テスト + バイナリ生成
./gradlew linuxX64Test       # テストのみ
./gradlew compileKotlinLinuxX64  # コンパイルのみ
```

バイナリ: `build/bin/linuxX64/debugExecutable/keel_kt.kexe`

## 技術スタック

- Kotlin 2.3.20 / Kotlin/Native (linuxX64)
- kotlinx-serialization-json: keel.json パース
- kotlin-result (michael-bull) 2.3.1: エラー処理

## アーキテクチャ

| ファイル | 役割 |
|---|---|
| Config.kt | keel.json のパース、KeelConfig データクラス |
| FileSystem.kt | ファイル読み込み、ディレクトリ作成、stderr出力 |
| Process.kt | fork/execvp によるコマンド実行、popen によるキャプチャ |
| Builder.kt | kotlinc コマンド引数の組み立て（純粋関数） |
| Runner.kt | java -jar コマンド引数の組み立て（純粋関数） |
| VersionCheck.kt | kotlinc バージョン文字列のパース（純粋関数） |
| Main.kt | CLI エントリーポイント、各モジュールの統合 |

## エラー処理の方針

**原則として Exception の throw は禁止。** kotlin-result の `Result<V, E>` でエラーを表現する。

- 関数ごとに返しうるエラー型だけを戻り値の型パラメータに指定する
- sealed class は共通の親エラーが意味を持つ場合のみ使い、独立したエラーは個別の data class にする
- 消費側は `getOrElse` + `when` で全バリアントを網羅する

```
parseConfig()     → Result<KeelConfig, ConfigError>
readFileAsString() → Result<String, OpenFailed>
ensureDirectory()  → Result<Unit, MkdirFailed>
executeCommand()   → Result<Int, ProcessError>
executeAndCapture() → Result<String, ProcessError>
```

## コーディング規約

- TDD (Red → Green → Refactor) で進める
- テストファイルは `src/nativeTest/kotlin/keel/` に `XxxTest.kt` の命名で配置
- 純粋関数（Builder, Runner, VersionCheck）はResult不要。副作用のある関数にResultを適用する
- POSIX API は `@OptIn(ExperimentalForeignApi::class)` を関数レベルで付与
- コミットメッセージは英語で書く
