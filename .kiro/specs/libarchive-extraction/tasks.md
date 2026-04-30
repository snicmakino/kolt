# Implementation Plan

- [ ] 1. Foundation: build / CI wiring for libarchive

- [x] 1.1 (P) Add libarchive cinterop binding and register in build configuration
  - `src/nativeInterop/cinterop/libarchive.def` を新規作成 (`headers = archive.h archive_entry.h`、`compilerOpts.linux = -I/usr/include`、`linkerOpts.linux = -L/usr/lib/x86_64-linux-gnu -larchive`、`linkerOpts.osx = -larchive` を予約として含める)
  - `build.gradle.kts` の `cinterops { val libcurl by creating }` ブロックに並べて `val libarchive by creating` を追加
  - `kolt.toml` に `[[cinterop]] name = "libarchive"` ブロックを追加して self-host も新 cinterop を認識できるようにする
  - 観測条件: `./gradlew compileKotlinLinuxX64` が libarchive cinterop 生成を含めて成功し、`build/classes/kotlin/linuxX64/main/cinterop` 配下に `libarchive.klib` が生成される
  - _Requirements: 1.1, 1.2, 1.3, 1.4_
  - _Boundary: build configuration_

- [x] 1.2 (P) Add libarchive-dev install steps to CI workflows
  - `.github/workflows/unit-tests.yml` 既存「Install libcurl dev headers」ステップに並べて `sudo apt-get install -y --no-install-recommends libarchive-dev` を実行する step を追加
  - `.github/workflows/release.yml`、`.github/workflows/self-host-smoke.yml` も同様に追加
  - 観測条件: 各 yaml の libcurl install step 直後に libarchive install step が並んでおり、ブランチ push 時に libarchive-dev install ステップが pass する
  - _Requirements: 1.4_
  - _Boundary: CI workflows_

- [ ] 2. Core: ArchiveExtraction implementation with tests

- [x] 2.1 Prepare archive test fixtures
  - `src/nativeTest/resources/archive-fixtures/` を作成し、最小サイズの zip 1 個と tar.gz 1 個 (通常ファイル + 実行ビット付きファイル + サブディレクトリ + 内部 symlink を含む) を配置
  - セキュリティ系フィクスチャを別に用意: パストラバーサル (`../escape.txt`) を含む zip、絶対パス (`/etc/passwd`) を含む zip、外部 symlink (ターゲット `../outside`) を含む tar.gz、途中切り詰めの破損 zip
  - 再生成手順を `scripts/regen-archive-fixtures.sh` (POSIX sh) に残し、`zip` / `tar` / `ln` で生成する
  - 観測条件: フィクスチャ群が repo にコミットされ、`scripts/regen-archive-fixtures.sh` を再実行するとフィクスチャを上書き再生成して exit 0 で終了する (バイト一致は `SOURCE_DATE_EPOCH` / `tar --mtime` で固定するか、不要なら諦めるかをスクリプト内で明示)
  - _Requirements: 2.1, 2.2, 4.6, 4.7_
  - _Boundary: nativeTest resources_

- [x] 2.2 Implement extractArchive happy path with unit tests
  - `kolt.infra.ArchiveExtraction.kt` (新規) に `extractArchive(archivePath, destDir): Result<Unit, ExtractError>` と sealed `ExtractError` (`ArchiveNotFound` / `OpenFailed` / `ReadFailed` / `WriteFailed` / `SecurityViolation`) を実装
  - libarchive lifecycle を `memScoped` 内で完結、必須フラグ `ARCHIVE_EXTRACT_PERM | _TIME | _SECURE_SYMLINKS | _SECURE_NODOTDOT | _SECURE_NOABSOLUTEPATHS` を `archive_write_disk_set_options` に設定、`archive_read_extract2` でエントリ書き出し
  - 関数レベルで `@OptIn(ExperimentalForeignApi::class)` を付与
  - `ArchiveExtractionTest.kt` (新規) に zip / tar.gz happy-path テストを追加: 通常ファイル展開、実行ビット保持、内部 symlink 復元
  - 観測条件: `./gradlew linuxX64Test --tests "*ArchiveExtractionTest"` で happy-path 群が pass し、展開ファイルの perm bit と symlink ターゲットがフィクスチャと一致する
  - _Requirements: 1.1, 1.2, 1.3, 2.1, 2.2, 4.4, 4.5_
  - _Boundary: kolt.infra.ArchiveExtraction_

- [x] 2.3 Add security and failure-path tests for extractArchive
  - パストラバーサル / 絶対パス / 外部 symlink フィクスチャに対して `extractArchive` が `Err(SecurityViolation)` を返し、destDir に該当エントリが書き出されないことを検証
  - 存在しないアーカイブで `Err(OpenFailed)`、破損アーカイブで `Err(ReadFailed)` が返ることを検証
  - エラーメッセージに libarchive の `archive_error_string` 由来の文字列が含まれることを検証
  - 観測条件: `ArchiveExtractionTest` のセキュリティ・失敗系テスト全件 pass、destDir 配下に `escape.txt` / `/etc/passwd` 等の不正パスファイルが存在しないことを `fileExists` で確認
  - _Requirements: 4.1, 4.6, 4.7_
  - _Boundary: kolt.infra.ArchiveExtraction_

- [ ] 3. Integration: replace ToolchainManager shell-outs

- [x] 3.1 Replace kotlinc unzip callsite with extractArchive
  - `installKotlincToolchain` 内 `executeCommand(listOf("unzip", "-q", zipPath, "-d", extractTempDir))` を `extractArchive(zipPath, extractTempDir)` に置換
  - `formatExtractError(error: ExtractError, tool: String, version: String): ToolchainError` ローカルヘルパを `ToolchainManager.kt` に導入し、3 callsite で共有する
  - 失敗時 cleanup シーケンス (`deleteFile(zipPath)` + `removeDirectoryRecursive(extractTempDir)`) は維持
  - 観測条件: `./gradlew linuxX64Test --tests "*ToolchainManagerTest"` が変更なしに pass、`grep -n '"unzip"' src/nativeMain/kotlin/kolt/tool/ToolchainManager.kt` が空
  - _Requirements: 1.1, 3.1, 4.1, 4.2, 4.3_
  - _Depends: 2.2_

- [x] 3.2 Replace JDK tar callsite with extractArchive
  - `installJdkToolchain` 内 `executeCommand(listOf("tar", "xzf", tarPath, "-C", extractTempDir))` を `extractArchive(tarPath, extractTempDir)` に置換、`formatExtractError(error, "jdk", version)` を再利用
  - `findSingleEntry` + `executeCommand(listOf("mv", ...))` の top-level dir 検出ロジックは本 spec 対象外なので不変
  - 観測条件: `ToolchainManagerTest` が pass、JDK callsite から `"tar"` 文字列が消える
  - _Requirements: 1.2, 3.2, 4.1, 4.2, 4.3_
  - _Depends: 3.1_

- [x] 3.3 Replace konanc tar callsite with extractArchive
  - `installKonancToolchain` 内 `executeCommand(listOf("tar", "xzf", tarPath, "-C", extractTempDir))` を `extractArchive(tarPath, extractTempDir)` に置換、`formatExtractError(error, "konanc", version)` を再利用
  - 観測条件: `ToolchainManagerTest` が pass、`grep -nE 'executeCommand\(listOf\("(unzip|tar)"' src/nativeMain/kotlin/kolt/tool/ToolchainManager.kt` が空 (`mv` は対象外なので残る)
  - _Requirements: 1.3, 3.3, 4.1, 4.2, 4.3_
  - _Depends: 3.2_

- [ ] 4. Documentation: ADR and steering update

- [ ] 4.1 (P) Add ADR 0031 for libarchive cinterop adoption
  - `docs/adr/0031-use-libarchive-cinterop-for-toolchain-extraction.md` を新規作成、ADR 0006 と同じ Status / Context / Decision / Consequences 構造で書く
  - 含める内容: 採用根拠 (3 callsite 統合 + disk writer/security flag 委譲 + libcurl パターン踏襲)、build/runtime 影響範囲 (libarchive-dev / libarchive13)、却下案 (純 Kotlin 自前 / zlib + 自前 zip-tar) の理由
  - feedback_adr_summary_section.md に従い Status と Context の間に 5〜7 bullet の Summary を置く
  - 観測条件: `docs/adr/0031-*.md` がコミットされ、ADR 番号が連番に整合 (`ls docs/adr/ | sort` で抜け番なし)
  - _Requirements: 1.4_
  - _Boundary: docs/adr_

- [ ] 4.2 (P) Update tech.md steering with libarchive entries
  - `.kiro/steering/tech.md` の「Required Tools」セクションの libcurl 行に並べて libarchive (build) を追加
  - 「Key Libraries」セクションに「libarchive cinterop — toolchain archive extraction (ADR 0031)」を追加
  - 観測条件: tech.md の libcurl エントリと同じ表現粒度で libarchive エントリが並ぶ
  - _Requirements: 1.4_
  - _Boundary: .kiro/steering_

- [ ] 5. Validation: end-to-end verification

- [ ] 5.1 Verify full local build, test suite, and clean-toolchain smoke install
  - `./gradlew build linuxX64Test` 全 pass
  - `~/.kolt/toolchains/{kotlinc,jdk,konanc}/` を一時退避した上で、生成された `./build/bin/linuxX64/debugExecutable/kolt.kexe build` を kolt 自身の repo root で実行し、3 toolchain の自動インストールと最終 native binary 生成が完走することを確認
  - 観測条件: Gradle 全テスト pass + `kolt.kexe build` 完走 + `~/.kolt/toolchains/{kotlinc,jdk,konanc}/<version>/bin/<binary>` が `test -x` で実行可能、retry 用 `which unzip || true` を実行しても `command not found` 状態で `kolt build` が成功する PoC が手元で再現可能
  - _Requirements: 1.4, 2.3, 3.1, 3.2, 3.3, 3.4_

- [ ] 5.2 Verify CI workflows pass on PR
  - PR を作成し、`unit-tests` / `release` / `self-host-smoke` の 3 workflow が pass することを確認
  - 観測条件: PR 上の 3 CI workflow すべて success、特に `self-host-smoke` の installer-managed dir build とfixture smoke が緑
  - _Requirements: 1.4, 3.4_
  - _Depends: 5.1_

## Implementation Notes
- happy.zip は Info-ZIP 由来の bare `subdir/` ディレクトリエントリを含むので 5 件 (regular.txt / executable.sh / subdir/ / subdir/nested.txt / link-to-regular)。2.2 テストでエントリ件数を assert する場合は 5 を使う。
- `extractArchive` は process-global な `chdir(destDir)` を使う (libarchive の `ARCHIVE_EXTRACT_SECURE_NOABSOLUTEPATHS` が rewritten absolute path も拒否するため)。kolt の bootstrap install シーケンスは sequential なので現状安全だが、並行 toolchain install を導入する際は再設計が必要。
- ADR 0031 citation コメントは task 4.1 で ADR が書かれた後に `extractArchive` の cinterop lifecycle ブロックへ追加する。
- libarchive.def に Ubuntu 24.04 multiarch include path (`-I/usr/include/x86_64-linux-gnu`) が必要。`bits/timesize.h` 経路で libcurl.def と同じ修正パターン。
- libarchive の `ARCHIVE_EXTRACT_SECURE_SYMLINKS` は新規 symlink エントリの **target 文字列** を検証しない。`archive_write_disk_posix.c:2388` の `symlink(linkname, a->name)` は target 検証なしで呼ばれる。Req 4.7 を満たすために `extractArchive` 側で entry-depth-aware な `symlinkTargetEscapes` 検査を `archive_write_header` の前に行う実装を入れている (タスク 2.3 で追加)。design.md の "外部 symlink 拒否は libarchive のフラグに完全委譲" は不正確で、実際は kolt 側に手書きチェックが必要。
