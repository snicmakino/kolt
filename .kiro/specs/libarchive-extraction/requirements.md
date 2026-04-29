# Requirements Document

## Introduction

`ToolchainManager.kt` がトールチェーン (kotlinc / JDK / konanc) のアーカイブを展開する際に、外部コマンド `unzip` (zip) と `tar` (tar.gz) をシェルアウトしている。これらを native 展開実装に置き換え、kolt CLI が外部アーカイブツールに依存せずに動作するようにする。Issue #43 が対象。

リファレンス実装としては libarchive cinterop を採用する想定だが、本要件書は「外部プロセス非依存」「展開忠実性」「失敗時クリーンアップ」を観測可能な振る舞いとして規定し、選択ライブラリの細部は design フェーズで確定する。

## Boundary Context

- **In scope**:
  - `installKotlincToolchain` の zip 展開
  - `installJdkToolchain` の tar.gz 展開
  - `installKonancToolchain` の tar.gz 展開
  - 展開後ディレクトリ構成 (実行ビット保持・symlink 復元) の忠実性
  - 失敗時のエラー伝搬と部分成果物クリーンアップ
- **Out of scope**:
  - macOS / linuxArm64 ターゲット対応 (kolt は現状 `linuxX64` のみで、当該対応は別 issue で扱う)
  - `mv` / `ls` シェルアウトの除去 (本 spec の対象外、将来別 issue)
  - 純 Kotlin による zip / tar / gzip 自前実装
  - 展開高速化やキャッシュ最適化
- **Adjacent expectations**:
  - 既存の `ToolchainManagerTest` は URL 構築・チェックサム検証等を verify する設計で、展開実装の単体テストは持たない。本 spec では展開ロジックの単体テストを追加するが、既存テストのフィクスチャ生成方法は変更しない。
  - kolt 開発環境および CI runner には libarchive のヘッダ・ランタイムが必要になる (libcurl と同じ位置付け)。tech.md と CI workflow への追記は design / tasks で扱う。
  - エラーハンドリング規約 (kolt-result `Result<V, ToolchainError>` / 例外禁止 / ADR 0001) は変更しない。

## Requirements

### Requirement 1: 外部プロセス非依存なアーカイブ展開

**Objective:** As a kolt ユーザー, I want kolt が `unzip` や `tar` を持たないシステムでも toolchain をインストールできること, so that kolt をブートストラップするためだけに追加のシステムツールを入れずに済む.

#### Acceptance Criteria
1. When the Toolchain Manager は kotlinc の zip アーカイブを展開する, the Toolchain Manager shall 外部の `unzip` プロセスを起動しない.
2. When the Toolchain Manager は JDK の tar.gz アーカイブを展開する, the Toolchain Manager shall 外部の `tar` プロセスを起動しない.
3. When the Toolchain Manager は konanc の tar.gz アーカイブを展開する, the Toolchain Manager shall 外部の `tar` プロセスを起動しない.
4. While `unzip` および `tar` が `PATH` 上に存在しない, the Toolchain Manager shall kotlinc / JDK / konanc のインストールを正常完了させる.

### Requirement 2: 展開忠実性 (実行ビットおよび symbolic link)

**Objective:** As a kolt ユーザー, I want 展開された toolchain がそのまま実行可能であること, so that `java` / `kotlinc` / `konanc` を手動 `chmod` や symlink 修復なしに起動できる.

#### Acceptance Criteria
1. When アーカイブ内のエントリに実行ビットが立っている, the Toolchain Manager shall 展開後のファイルにも実行ビットを保持する.
2. When アーカイブのエントリが symbolic link である, the Toolchain Manager shall 同じターゲットを指す symbolic link を展開先に再生成する.
3. When kotlinc / JDK / konanc のインストールが完了する, the Toolchain Manager shall それぞれの bin パス (例: `paths.kotlincBin(version)` / `paths.javaBin(version)` / `paths.konancBin(version)`) において実行可能なバイナリを提供する.

### Requirement 3: 既存レイアウトとの一致

**Objective:** As a kolt 開発者, I want 新展開実装が現行シェルアウト版と同一のオンディスク配置を作ること, so that 下流のパス解決・バージョン判定・既存テストが追加変更なく動作する.

#### Acceptance Criteria
1. When kotlinc のインストールが完了する, the Toolchain Manager shall アーカイブ内の `kotlinc/` ツリーを `paths.kotlincPath(version)` 配下に配置する.
2. When JDK のインストールが完了する, the Toolchain Manager shall アーカイブのトップレベル単一ディレクトリの中身を `paths.jdkPath(version)` 配下に配置する.
3. When konanc のインストールが完了する, the Toolchain Manager shall アーカイブ内の `kotlin-native-prebuilt-linux-x86_64-<version>/` ツリーを `paths.konancPath(version)` 配下に配置する.
4. The Toolchain Manager shall インストール完了後の `KoltPaths` API で参照されるパスを変更しない.

### Requirement 4: 失敗時エラーハンドリングおよびクリーンアップ

**Objective:** As a kolt ユーザー, I want 展開失敗時に明確なエラーが返り、ファイルシステムが汚れずに次回試行が成功しうる状態に戻ること, so that 中途半端なインストール状態に詰まらずに済む.

#### Acceptance Criteria
1. If アーカイブ展開が失敗する, the Toolchain Manager shall `Result.Err(ToolchainError(...))` を返し、メッセージにアーカイブの種別 (kotlinc / JDK / konanc) と失敗原因を含める.
2. If アーカイブ展開が失敗する, the Toolchain Manager shall 展開先の一時ディレクトリ (例: `<base>/<version>_extract`) を削除する.
3. If アーカイブ展開が失敗する, the Toolchain Manager shall ダウンロード済みアーカイブファイルを削除する.
4. The Toolchain Manager shall 展開失敗時に例外を throw しない.
5. While アーカイブを展開している, the Toolchain Manager shall アーカイブ全体をメモリにロードせず、エントリ単位でストリーミング処理する.
6. If アーカイブのエントリパスが展開先ディレクトリの外を指す、または絶対パスである, the Toolchain Manager shall そのエントリの書き出しを拒否し展開全体を失敗として扱う.
7. If symbolic link のターゲットが展開先ディレクトリの外を指す, the Toolchain Manager shall その link の書き出しを拒否し展開全体を失敗として扱う.
