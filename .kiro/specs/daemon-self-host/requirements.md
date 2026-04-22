# Requirements Document

## Project Description (Input)

### 誰の問題か
kolt プロジェクト本体の開発・配布。kolt は自身の native binary (`kolt.kexe`) は
`kolt.toml` で self-host しているが、2 つの JVM daemon
(`kolt-jvm-compiler-daemon`, `kolt-native-compiler-daemon`) の JVM jar だけは
Gradle + shadowJar でしか build できず、self-host のループが閉じていない
(Issue #97)。

### 現状
ADR 0018 (#224/#225)、ADR 0025 (#222)、ADR 0026 (#227)、ADR 0027 が揃い、
2026-04-22 dogfood spike で 2 daemon とも `kolt build` が一発で thin jar を
emit することを確認済み。残る実装ギャップは runtime classpath manifest の
emit、`scripts/assemble-dist.sh` の stitcher 実装、self-host-smoke CI の
companion job。

### 何を変えるか
Issue #97 の DoD を閉じる。`install.sh` / tarball release CI / shadowJar 除去
/ daemon unit tests の kolt 移行 / multi-module `kolt.toml` はスコープ外。

## Introduction

本スペックは kolt が自分自身の 2 daemon JVM jar を build し、リリース
タルボールの layout を組み立て、CI で self-host 経路を検証できる状態に
することで、Issue #97 の self-host gap を閉じる。ADR 0018 / 0025 / 0027 で
既に pin されている決定を実装に落とし込む位置づけで、新規の設計選択は
含まない。

## Boundary Context

- **In scope**:
  - `kolt-jvm-compiler-daemon/kolt.toml` と `kolt-native-compiler-daemon/kolt.toml`
    の追加 (`:ic` ソース merge を含む)。
  - JVM `kind = "app"` build における runtime classpath manifest の emit
    (ADR 0027 準拠)。
  - `scripts/assemble-dist.sh` による 3 プロジェクト stitcher 実装
    (ADR 0018 §4)。
  - `self-host-smoke.yml` に self-host path を検証する companion job を
    追加。
- **Out of scope**:
  - `install.sh` / `curl | sh` 経路 / GitHub Release への tarball upload
    (ADR 0018 §4 first release cut 別トラック)。
  - `shadowJar` 除去と関連 Gradle task の削除 (ADR 0018 Confirmation の
    段階的撤去)。daemon の Gradle build は本スペック期間中は並走維持。
  - daemon unit tests の kolt 移行 (`:ic` の fixture classpath 3 本、
    BtaImplJarResolver 経路含む)。
  - `install.sh` 不在下での `~/.local/share/kolt/` layout / symlink 管理。
  - Multi-module `kolt.toml` schema (#4)。
- **Adjacent expectations**:
  - 依存 resolver: JVM 経路で Kotlin stdlib を transitive closure に含める
    現状挙動に依存する (ADR 0011 skip は native 限定)。本スペックは resolver
    を変更しない。
  - `kolt.toml` schema: `[build]` の `target = "jvm"` / `jvm_target` / `jdk` /
    `main` / `sources` は既に `BuildSection` に存在する前提で使用。
  - `kolt run` / `kolt build` の kind/target 分岐は既存 `BuildCommands.kt` の
    orchestration 層に追従する。

## Requirements

### Requirement 1: Daemon の kolt.toml による build 可能性

**Objective:** kolt メンテナとして、2 つの daemon を `kolt build` でビルド
できるようにし、Gradle なしでも self-host ループを閉じられる状態にしたい。

#### Acceptance Criteria

1. Where `kolt-jvm-compiler-daemon/kolt.toml` が存在する場合、the kolt CLI shall
   当該ディレクトリで `kolt build` を実行したときに `build/kolt-jvm-compiler-daemon.jar`
   を Gradle を起動せずに emit する。
2. Where `kolt-native-compiler-daemon/kolt.toml` が存在する場合、the kolt CLI
   shall 当該ディレクトリで `kolt build` を実行したときに
   `build/kolt-native-compiler-daemon.jar` を Gradle を起動せずに emit する。
3. When `kolt-jvm-compiler-daemon/` 配下で `kolt build` を実行した時、the
   kolt CLI shall daemon 本体のソースと `:ic` 相当のソース
   (`ic/src/main/kotlin`) を単一 build の入力として取り込む。
4. While daemon の Gradle build (`build.gradle.kts`) がリポジトリに残存して
   いる間、the kolt CLI shall Gradle の build path の成功を阻害せず両経路が
   正常終了する状態を保つ。
5. If daemon の `kolt.toml` に kind/target/main の schema 違反がある場合、
   the kolt CLI shall build 開始前にエラーで停止し、原因を示す。

### Requirement 2: Runtime classpath manifest の emit

**Objective:** `assemble-dist.sh` などの外部ランチャーとして、依存解決を
再実行せずに daemon 起動に必要な jar 一覧を取得したい。

#### Acceptance Criteria

1. When JVM `kind = "app"` の `kolt build` が成功した時、the kolt CLI shall
   `build/<name>-runtime.classpath` を emit する。
2. The manifest shall UTF-8 / LF line endings のプレーンテキストで、1 行に
   1 つの絶対パス jar を含み、末尾に空行を持たない。
3. The manifest shall `build/<name>.jar` (self jar) を含まず、解決済み依存
   jar のみを列挙する。
4. The manifest shall エントリをファイル名 (path の最終要素) でアルファベット
   順にソートして出力する。
5. If 複数のエントリが同一ファイル名を持つ場合、the kolt CLI shall
   `group:artifact:version` 文字列による辞書順で tie-break する。
6. The manifest shall JVM 経路で resolver が返す transitive closure
   (post-BFS / post-exclusion / post-version-interval) をそのまま反映し、
   Kotlin stdlib を含む。
7. Where `kind = "lib"` または target が native の場合、the kolt CLI shall
   manifest を emit してはならない。
8. While `kolt run` の JVM app 経路が実行されている間、the kolt CLI shall
   manifest ファイルを読み取らず、resolver の戻り値から in-process で
   classpath を組み立てる。

### Requirement 3: assemble-dist.sh stitcher

**Objective:** リリース担当者として、単一コマンドで 3 プロジェクトを
`kolt build` で build し、ADR 0018 §1 の tarball layout を組み立てたい。

#### Acceptance Criteria

1. When `scripts/assemble-dist.sh` が引数なしで実行された時、the stitcher
   shall `./`、`./kolt-jvm-compiler-daemon/`、`./kolt-native-compiler-daemon/`
   の 3 プロジェクトで `kolt build` を順次実行する。
2. When 3 プロジェクトの build が成功した時、the stitcher shall
   `kotlin-build-tools-impl:2.3.20` を Maven Central (`repo1.maven.org`) から
   HTTP GET で直接取得し、`libexec/kolt-bta-impl/` に配置する。取得はその
   transitive 依存を含めた最小集合とし、kolt の resolver を経由しない。
3. If いずれかのプロジェクト build または `-impl` 取得が非ゼロ終了した
   場合、the stitcher shall 以降のステップを実行せず、非ゼロで終了する。
4. When 3 プロジェクトの build および `-impl` 取得が成功した時、the
   stitcher shall 出力先ディレクトリ (`dist/kolt-<version>-linux-x64/`) に
   以下を配置する: `bin/kolt`、`libexec/<daemon>/<daemon>.jar`、
   `libexec/<daemon>/deps/*.jar` (manifest 記載順にコピー、ファイル名維持)、
   `libexec/kolt-bta-impl/*.jar`、`libexec/classpath/<daemon>.argfile`
   (platform separator 適用、最終行に main class FQN)、`VERSION`
   (semver 単行)。
5. The stitcher shall `~/.kolt/cache` の layout 知識を持たず、各プロジェクト
   の `build/<name>.jar` と `build/<name>-runtime.classpath` のみを kolt
   build 成果物の入力として動作する (`-impl` は独立した Maven 取得路)。
6. When 実行された時、the stitcher shall Req 2 が定める manifest を
   `libexec/<daemon>/deps/` の生成元として使用する。

### Requirement 4: Self-host smoke CI companion job

**Objective:** CI の運用者として、self-host 経路の regression を毎 PR で
検知したい。

#### Acceptance Criteria

1. When プルリクエストが `main` ブランチ向けに open または update された時、
   the self-host-smoke workflow shall self-host path (3 × `kolt build` +
   assemble-dist.sh + classpath launch) を検証する companion job を実行
   する。
2. The companion job shall ビルド済みの `kolt.kexe` を使って 3 プロジェクトを
   `kolt build` で順次 build し、`scripts/assemble-dist.sh` を実行する。
3. When assemble-dist.sh が成功した時、the companion job shall 生成された
   tarball を一時ディレクトリに展開し、`libexec/classpath/<daemon>.argfile`
   を用いて daemon を classpath launch で起動する。
4. While daemon が起動している間、the companion job shall `Ping` を送信し、
   `Pong` が返ることを確認する。
5. If companion job のいずれかのステップが非ゼロ終了した場合、the CI
   workflow shall 全体として fail する。
6. Where native self-host smoke job が同 workflow に存在する場合、the
   companion job shall 同一 `kolt.kexe` artifact を再利用し、重複する
   native build を行わない。

### Requirement 5: 並走運用中の Gradle 依存の健全性

**Objective:** 本スペック実装中に既存の Gradle build / CI が壊れない状態で
段階的に移行したい。

#### Acceptance Criteria

1. While 本スペックが実装中の任意時点で、the Gradle build
   (`./gradlew build`) shall 成功し、既存の daemon fat jar (`shadowJar`
   成果物) と `bta-impl-jars/` を従来通り生成する。
2. While 本スペックが実装中の任意時点で、the existing self-host smoke job
   (native 側) shall 合格状態を維持する。
3. The daemon `kolt.toml` shall Gradle build 側の configuration 分離
   (`buildToolsImpl` / fixture classpath 等) を覆さない。具体的には、
   `[dependencies]` には daemon 本体 runtime に必要な jar のみを列挙し、
   `kotlin-build-tools-impl` および test fixture 用 classpath は含めない。
