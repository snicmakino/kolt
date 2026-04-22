# Implementation Plan

- [ ] 1. Manifest emit pipeline
- [x] 1.1 `JvmResolutionOutcome` data class 導入と `resolveDependencies` の戻り値型移行
  - `resolveDependencies` の戻り値を `Result<String?, Int>` から `Result<JvmResolutionOutcome, Int>` に変更し、`classpath` と `resolvedJars` を同時に保持する。
  - 既存 callers (`BuildCommands.kt` の JVM 経路および関連する test harness) を `.classpath` 参照に追従させる。`resolveNativeDependencies` は変更しない。
  - `resolveDependencies` のユニットテストを JvmResolutionOutcome 形で更新し、既存 callers がコンパイル可能な状態でテストスイートが全緑。
  - _Requirements: 2.1, 2.6_
  - _Boundary: kolt.cli.DependencyResolution_

- [x] 1.2 `outputRuntimeClasspathPath` ヘルパーと `writeRuntimeClasspathManifest` emit 関数を追加
  - `Builder.kt` に既存 `output*Path` helper と同じ internal visibility で `outputRuntimeClasspathPath(config): String` を追加し `"$BUILD_DIR/${config.name}-runtime.classpath"` を返す。
  - `writeRuntimeClasspathManifest(config, resolvedJars)` を Builder.kt に実装する。resolvedJars を file-name (path 最終要素) でアルファベット sort、同一ファイル名の場合は `group:artifact:version` 文字列でタイブレークする。self jar (`outputJarPath(config)`) は出力から除外。
  - UTF-8 / LF / 末尾空行なしで write し、failure は `ManifestWriteError.WriteFailed` で返す (例外 throw 禁止)。
  - `RuntimeClasspathManifestTest.kt` で (a) path 構築、(b) sort + self 除外、(c) GAV tiebreak、(d) UTF-8/LF/no trailing newline の 4 ユニットテストを追加し全て緑。
  - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6_
  - _Boundary: kolt.build.Builder_

- [x] 1.3 JVM `kind = "app"` 経路に manifest emit 呼び出しと stale cleanup を組み込む
  - `BuildCommands.kt` の JVM 経路で jar 生成直後に `kind == "app" && target == "jvm"` の分岐を追加し、`writeRuntimeClasspathManifest` を呼び出す。
  - 同分岐の else 側 (lib / native) で、`outputRuntimeClasspathPath(config)` に対応する既存ファイルがあれば削除する (kind 変更による stale manifest 防止)。
  - `JvmAppBuildTest.kt` で emit マトリクス (jvm+app → 出る / jvm+lib → 出ない / native → 出ない / kind を app→lib に変えて rebuild → 削除される) をフィクスチャベースで pin。
  - schema 違反 (`kind = "app"` で `main` なし等) は既存 parser のエラー表示がそのまま効くため、追加処理なし。1 項目のテストで既存エラーが build 前に出ることを確認。
  - テストスイートが全緑、fixture で `kolt build` すると manifest が条件通り出現/非出現/削除される。
  - _Requirements: 1.5, 2.1, 2.7_
  - _Boundary: kolt.cli.BuildCommands_

- [x] 1.4 `kolt run` が manifest を読まないことの回帰テスト
  - `KoltRunManifestIndependenceTest.kt` を追加し、(a) `kolt build` 後に `build/<name>-runtime.classpath` を削除、(b) `kolt run` を起動、(c) 正常終了することを検証する。
  - テストが緑 (manifest 削除が `kolt run` の経路に影響しないことが CI で保証される)。
  - _Requirements: 2.8_
  - _Boundary: kolt.cli.BuildCommands tests_

- [x] 1.5 (P) ADR 0027 §1 の helper 参照先を訂正
  - `docs/adr/0027-runtime-classpath-manifest.md` §1 末尾の「Helper `outputRuntimeClasspathPath(config): String` in `kolt/config/Config.kt`」を `kolt/build/Builder.kt` に 1 行修正。
  - ADR テキストと実装配置が一致し、diff を読めば修正意図が明確な状態。
  - _Requirements: 2.1_
  - _Boundary: docs/adr_
  - _Depends: 1.2_

- [ ] 2. Daemon `kolt.toml` 導入
- [x] 2.1 (P) `kolt-jvm-compiler-daemon/kolt.toml` を追加
  - `target = "jvm"`, `kind = "app"`, `main = "kolt.daemon.main"`, `jvm_target = "21"`, `jdk = "21"` を設定し、`sources = ["src/main/kotlin", "ic/src/main/kotlin"]` で `:ic` ソースを merge する。
  - 依存は `kotlin-build-tools-api:2.3.20` / `kotlinx-serialization-json:1.7.3` / `kotlin-result:2.3.1` / `ktoml-core:0.7.1` の 4 本のみ (Req 5.3 により `-impl` / fixture classpath は入れない)。
  - `[kotlin.plugins] serialization = true` を付与。
  - 当該ディレクトリで `kolt.kexe build` が成功し `build/kolt-jvm-compiler-daemon.jar` + `build/kolt-jvm-compiler-daemon-runtime.classpath` が生成される。`./gradlew :kolt-jvm-compiler-daemon:build` は既存挙動通り成功し続ける。
  - _Requirements: 1.1, 1.3, 1.4, 5.1, 5.3_
  - _Boundary: kolt-jvm-compiler-daemon config_
  - _Depends: 1.3_

- [ ] 2.2 (P) `kolt-native-compiler-daemon/kolt.toml` を追加
  - `target = "jvm"`, `kind = "app"`, `main = "kolt.nativedaemon.main"`, `jvm_target = "21"`, `jdk = "21"`, `sources = ["src/main/kotlin"]`。
  - 依存は `kotlinx-serialization-json:1.7.3` / `kotlin-result:2.3.1` のみ。`kotlin-native-compiler-embeddable` は含めない (ADR 0024 §8)。
  - `[kotlin.plugins] serialization = true` を付与。
  - 当該ディレクトリで `kolt.kexe build` が成功し jar + manifest が生成される。`./gradlew :kolt-native-compiler-daemon:build` は既存挙動通り成功し続ける。
  - _Requirements: 1.2, 1.4, 5.1, 5.3_
  - _Boundary: kolt-native-compiler-daemon config_
  - _Depends: 1.3_

- [ ] 3. `scripts/assemble-dist.sh` stitcher
- [ ] 3.1 スクリプト骨格: 3 プロジェクトの直列 `kolt build` と `dist/` 準備
  - `#!/usr/bin/env bash`、`set -euo pipefail` で新規作成。
  - 起動時に既存 `dist/` を wipe し、ルート `kolt.toml` から `version` を grep で抜き出し、`dist/kolt-<version>-linux-x64/` を作成するのみ。配下サブディレクトリ (`bin/`, `libexec/`) の作成と内容書き込みは 3.2 / 3.3 の担当。
  - `${KOLT:-./build/bin/linuxX64/releaseExecutable/kolt.kexe} build` をルート / `kolt-jvm-compiler-daemon/` / `kolt-native-compiler-daemon/` の順で実行。
  - いずれかの build が非ゼロ終了したら以降のステップを実行せず非ゼロで終了する。
  - `dist/kolt-<version>-linux-x64/` が準備状態 (空ディレクトリとして存在) で、各プロジェクト配下に `build/<name>.jar` と `build/<name>-runtime.classpath` が揃う。途中 build をわざと失敗させると 3 番目が起動しない。
  - _Requirements: 3.1, 3.3, 3.5_
  - _Boundary: scripts/assemble-dist.sh_
  - _Depends: 2.1, 2.2_

- [ ] 3.2 `kotlin-build-tools-impl` の GAV/SHA pin 集合を決定し、curl で Maven Central から取得
  - pin 元資材の準備: 実装開始時に `./gradlew :kolt-jvm-compiler-daemon:stageBtaImplJars` を 1 回実行して `kolt-jvm-compiler-daemon/build/bta-impl-jars/` に配置される jar 集合を観測し、ファイル名 (GAV) と `sha256sum` をスクリプト内定数として転記する。pin 後は Gradle 成果物への依存はない。
  - スクリプト冒頭に GAV 配列と対応する SHA-256 定数を定義する。最低集合は `kotlin-build-tools-impl:2.3.20` + その transitive (daemon の `[dependencies]` で既に `deps/` に入るものは除外)。
  - `curl -fsSL` で `https://repo1.maven.org/maven2/...` から各 jar を `dist/kolt-<version>-linux-x64/libexec/kolt-bta-impl/` に配置し、`sha256sum` で定数と一致を検証する。不一致は非ゼロ終了。
  - `libexec/kolt-bta-impl/*.jar` が完全に配置され、sha 検証ログが GAV 毎に "OK" を出す。
  - _Requirements: 3.2, 3.3_
  - _Boundary: scripts/assemble-dist.sh_

- [ ] 3.3 Tarball layout 組立て、argfile 生成、tar czf パッケージング
  - `dist/kolt-<version>-linux-x64/` 配下に `bin/` と `libexec/<daemon>/`、`libexec/<daemon>/deps/`、`libexec/classpath/` を作成。
  - ルート `kolt.kexe` を `bin/kolt` にコピー (ADR 0018 §1 naming)。
  - 各 daemon について、self jar を `libexec/<daemon>/<daemon>.jar`、manifest 記載の jar 列を `libexec/<daemon>/deps/` にファイル名保持でコピー。
  - 各 daemon の argfile を `libexec/classpath/<daemon>.argfile` に生成: `-cp`、`<self>:<deps...>:<impl...>` (separator `:`、linuxX64 前提)、main class FQN (`kolt.daemon.MainKt` / `kolt.nativedaemon.MainKt`)。
  - `VERSION` ファイルに semver 1 行を書き出す。
  - `tar czf dist/kolt-<version>-linux-x64.tar.gz -C dist kolt-<version>-linux-x64/` で生成。
  - 生成された `.tar.gz` を別ディレクトリに展開すると ADR 0018 §1 layout と完全一致し、argfile は `java @argfile` で実行可能な形式になっている。
  - _Requirements: 3.4, 3.5, 3.6_
  - _Boundary: scripts/assemble-dist.sh_

- [ ] 4. Self-host smoke CI companion job
- [ ] 4.1 (P) Companion 用の最小 JVM fixture プロジェクト
  - `spike/daemon-self-host-smoke/` に最小構成 (`kolt.toml` に `target = "jvm"`, `kind = "app"`, main 1 つ + `[dependencies]` なし or 最小)、`src/main/kotlin/Hello.kt` 等) を配置。
  - 既存 `kolt.kexe build` でこのディレクトリが build 成功し、`build/<name>.jar` が生成される (動作原理の smoke 用なので、テスト対象は通過)。
  - _Requirements: 4.4_
  - _Boundary: spike/daemon-self-host-smoke_

- [ ] 4.2 既存 native self-host job に `kolt.kexe` の upload-artifact step を追加
  - `.github/workflows/self-host-smoke.yml` の `self-host` job 末尾 (`--version` 検証の後) に `actions/upload-artifact@v4` step を追加し、`./build/bin/linuxX64/debugExecutable/kolt.kexe` を artifact 名 `kolt-kexe` で export する。
  - 既存の `--version` 検証 step は変更せず合格状態を維持する。
  - PR CI で native self-host job の完了時に `kolt-kexe` artifact がリストされる。既存の緑判定は崩れない。
  - _Requirements: 4.6, 5.2_
  - _Boundary: .github/workflows/self-host-smoke.yml (native job)_

- [ ] 4.3 Companion job (`self-host-post`) を追加し self-host path 全体を検証
  - `.github/workflows/self-host-smoke.yml` に `self-host-post` job を追加 (`needs: self-host`、ubuntu-latest)。
  - Steps: checkout → `actions/download-artifact@v4` で `kolt-kexe` 取得 → `chmod +x` → ルート + 2 daemon で `kolt build` 順次実行 → `scripts/assemble-dist.sh` を実行 → 生成 tarball を `/tmp/kolt-install/` 配下に展開 → fixture project (4.1) の dir で展開後の `bin/kolt build` を実行。
  - いずれの step も `set -euo pipefail` 相当で fail-fast。
  - PR 毎に `self-host-post` job が動き、fixture の `kolt build` 成功までを緑で通す。途中 step が fail したら job が fail し CI 全体が fail する。
  - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5_
  - _Boundary: .github/workflows/self-host-smoke.yml (companion job)_
  - _Depends: 3.3, 4.1, 4.2_
