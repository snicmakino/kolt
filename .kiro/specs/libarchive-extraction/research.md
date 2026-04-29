# Research & Design Decisions

## Summary
- **Feature**: `libarchive-extraction`
- **Discovery Scope**: Extension (既存 `ToolchainManager.kt` のシェルアウトを native 実装に置き換える)
- **Key Findings**:
  - kotlinc / JDK / konanc 3 系統のインストールは「ダウンロード → checksum → 一時展開 → `mv` で最終配置」の同型構造で、`extractArchive(archivePath, destDir)` 1 関数で 3 callsite を置換可能。
  - libarchive は zip / tar / gzip すべてを 1 つの API (`archive_read_support_format_all` + `archive_read_support_filter_all`) で扱える。形式判定は libarchive 側で自動実施されるため、kolt 側に format 分岐は不要。
  - libarchive の disk writer (`archive_write_disk_*`) はパーミッション保持・symlink 復元・ディレクトリ作成・パストラバーサル防御をフラグ指定で一括処理する。Requirement 2 (展開忠実性) と Requirement 4.6/4.7 (パス検証) の実装はこのフラグ群への委譲で済む。

## Research Log

### libarchive C API for streaming extraction
- **Context**: zip と tar.gz の両方をストリーミング展開する API を確認する (Requirement 4.5)
- **Sources Consulted**:
  - libarchive 公式 wiki / man pages (`archive_read`, `archive_write_disk`)
  - 既存 `src/nativeInterop/cinterop/libcurl.def` のパターン
- **Findings**:
  - 読み出し: `archive_read_new` → `archive_read_support_format_all` + `archive_read_support_filter_all` → `archive_read_open_filename(a, path, blockSize)` → `archive_read_next_header` ループ。
  - 書き出し: `archive_write_disk_new` + `archive_write_disk_set_options(flags)` を経由してエントリを `archive_read_extract2` に渡すと、libarchive 側がデータブロックループを内包する。
  - エラー文字列: `archive_error_string(a)` で取得。`archive_errno(a)` で errno。
  - セキュリティフラグ:
    - `ARCHIVE_EXTRACT_SECURE_SYMLINKS` (展開中の symlink follow 拒否)
    - `ARCHIVE_EXTRACT_SECURE_NODOTDOT` (`..` を含むパス拒否)
    - `ARCHIVE_EXTRACT_SECURE_NOABSOLUTEPATHS` (絶対パス拒否)
    - `ARCHIVE_EXTRACT_PERM` (実行ビット等パーミッション保持)
    - `ARCHIVE_EXTRACT_TIME` (mtime 保持)
- **Implications**:
  - extraction の実装は「open → next_header ループ + extract2 → close + free」の 30〜50 行程度に収束する。
  - パストラバーサル拒否は libarchive のフラグ指定で達成される (kolt 側で path 文字列を検証する必要なし)。
  - cinterop ヘッダは `archive.h` と `archive_entry.h` の 2 ファイル。

### Build / runtime dependency surface
- **Context**: libarchive を build/runtime に載せる影響範囲を確認 (libcurl の前例と比較)
- **Sources Consulted**:
  - `.github/workflows/{unit-tests,release,self-host-smoke}.yml` の libcurl install ステップ
  - `docs/adr/0006-use-libcurl-cinterop-instead-of-ktor.md` (libcurl 採用時の影響整理)
  - `kolt.toml` / `build.gradle.kts` の cinterop 登録形式
- **Findings**:
  - Ubuntu パッケージ: `libarchive-dev` (build), `libarchive13` (runtime)。`libcurl4-openssl-dev` と同型の追加。
  - CI 3 workflow すべてに「Install libcurl dev headers」ステップが既にあり、同位置に libarchive 用ステップを並べる。
  - kolt.kexe は libarchive.so.13 を動的リンクするため、エンドユーザーも runtime に libarchive13 が必要。これは libcurl4 と同じ運用。
- **Implications**:
  - 開発・CI・エンドユーザーで影響範囲は libcurl と完全に並行。新規の運用パターンは発生しない。
  - 後続の ADR (0006 と並行) で採用根拠を残す。

### Existing toolchain install structure
- **Context**: 置換対象 3 箇所の構造同型性を確認 (Generalization 判断)
- **Sources Consulted**:
  - `src/nativeMain/kotlin/kolt/tool/ToolchainManager.kt` L63-L178 (`installJdkToolchain`), L223-L316 (`installKotlincToolchain`), L339-L416 (`installKonancToolchain`)
- **Findings**:
  - 3 関数とも同じステップ列: `ensureDirectoryRecursive(baseDir)` → `downloadFile(url, archivePath)` → checksum 取得 → checksum 検証 → `ensureDirectoryRecursive(extractTempDir)` → **`executeCommand([unzip|tar], ...)`** ← 置換対象 → `executeCommand([mv], extractTempDir/<topDir>, finalPath)` → `removeDirectoryRecursive(extractTempDir)`。
  - 置換対象は 1 行のシェルアウト呼び出し。前後のディレクトリ準備・クリーンアップロジックは不変。
- **Implications**:
  - 1 つの `extractArchive(archivePath, destDir)` で 3 callsite を完全に賄える。format 引数も不要 (libarchive 側で自動判定)。
  - エラー型は `ExtractError` 1 種で十分。`ToolchainError` への変換は callsite 側で行う。

## Architecture Pattern Evaluation

| Option | Description | Strengths | Risks / Limitations | Notes |
|--------|-------------|-----------|---------------------|-------|
| libarchive cinterop (selected) | C ライブラリの cinterop で zip + tar.gz を 1 API に統合 | format / filter の網羅、symlink・perm・パストラバーサル防御を libarchive flag に委譲、libcurl と同じパターン | 開発・CI・runtime に libarchive 系パッケージが必要、macOS は別途設計 (本 spec では非対応) | ADR 0006 と並行する形で採用 |
| 純 Kotlin 自前実装 | zip / tar / gzip パーサを Kotlin で書く | 外部システムライブラリ非依存 | DEFLATE/gzip 実装の労力大、symlink/perm の OS 呼び出しは結局必要、テスト負荷大 | コスト過大で却下 |
| zlib cinterop + 自前 zip/tar | gzip だけ zlib に委譲し zip/tar は自前 | 中庸 | 結局 cinterop が増える上、zip/tar コードを抱える | libarchive 1 つで済む A に劣る |

## Design Decisions

### Decision: 単一 `extractArchive` 関数で 3 callsite を統合
- **Context**: kotlinc.zip / JDK.tar.gz / konanc.tar.gz の 3 系統のシェルアウト置換
- **Alternatives Considered**:
  1. `extractZip` / `extractTarGz` の 2 関数を分ける
  2. 各 `installXxxToolchain` 内に展開ロジックを埋め込む
- **Selected Approach**: `kolt.infra.extractArchive(archivePath: String, destDir: String): Result<Unit, ExtractError>` 1 関数。format は libarchive の `support_format_all` / `support_filter_all` で自動判定。
- **Rationale**: 3 callsite が同型 (Research Log §Existing toolchain install structure) で format 分岐の必要がない。kolt.infra に他の OS primitive (Sha256 等) と並べて配置し、`kolt.tool` から呼ぶ。
- **Trade-offs**: format 自動判定は libarchive 内部で全 format / filter テーブルを引くため、特定 format 限定より僅かに遅い (μs オーダー、無視可能)。
- **Follow-up**: ToolchainManager 側の `formatProcessError(error, "unzip"|"tar")` は不要になる。`ExtractError` を直接 `ToolchainError(message)` に変換する callsite ローカルヘルパで十分。

### Decision: libarchive disk writer + security flag に委譲
- **Context**: Requirement 2 (perm/symlink) と Requirement 4.6/4.7 (パストラバーサル) の実装方針
- **Alternatives Considered**:
  1. エントリ単位で kolt 側がパス検証・perm 設定・symlink 作成
  2. libarchive disk writer + flag 指定で委譲
- **Selected Approach**: `archive_write_disk_new` + `archive_write_disk_set_options(EXTRACT_PERM | EXTRACT_TIME | EXTRACT_SECURE_SYMLINKS | EXTRACT_SECURE_NODOTDOT | EXTRACT_SECURE_NOABSOLUTEPATHS)` を経由して `archive_read_extract2` に委譲。
- **Rationale**: libarchive の disk writer は path 検証・perm/symlink/mtime をフラグで一括処理する。kolt 側に path 検証コードを書くよりライブラリ実装に乗る方が CVE 追従の観点でも安全。
- **Trade-offs**: libarchive の挙動に依存する (libarchive 自体の脆弱性が出れば同伴する)。これは libcurl 採用時と同じトレードオフ。
- **Follow-up**: cinterop バインディングで `ARCHIVE_EXTRACT_*` 定数が露出するか確認。露出しなければ `.def` の `---` セクションで C ヘッダを include して再宣言。

### Decision: ADR を新規作成 (ADR 0031) し採用根拠を残す
- **Context**: libcurl は ADR 0006 で採用根拠が残されている。libarchive も同等のトレードオフ判断なのでドキュメント化が必要。
- **Selected Approach**: `docs/adr/0031-use-libarchive-cinterop-for-toolchain-extraction.md` を新規作成。
- **Rationale**: 将来 alternative (純 Kotlin 自前) への戻し圧力が来たときに判断履歴があった方が良い。
- **Follow-up**: tasks に ADR 作成を含める。

## Risks & Mitigations
- **Risk: libarchive のリンクオプション差異** — distro により libarchive ヘッダの場所が異なる可能性。**Mitigation**: libcurl と同様、`linkerOpts.linux = -larchive` のみで `-L` パスは pkg-config 不在前提でデフォルト探索に任せる。CI では `apt install libarchive-dev` で `/usr/lib/x86_64-linux-gnu/libarchive.so` が配置される。
- **Risk: cinterop の K/N opt-in 注釈** — 既存 libcurl 利用箇所と同じく `@OptIn(ExperimentalForeignApi::class)` が必要。**Mitigation**: `ArchiveExtraction.kt` 関数レベルで注釈、CLAUDE.md ルールに合致。
- **Risk: archive_write_disk のディレクトリ自動作成は umask に依存** — 一時ディレクトリのモードが系列で異なる可能性。**Mitigation**: `extractArchive` 入口で `ensureDirectoryRecursive(destDir)` を呼んでから libarchive に渡す (現状の callsite 側コードと同じ前提を維持)。
- **Risk: エンドユーザー環境に libarchive13 が無い** — install.sh 経由で導入したユーザーが起動時に loader error。**Mitigation**: ADR で runtime 依存として明記。install.sh 側にチェックを足すかは別 issue。

## References
- [libarchive(3) man page](https://man7.org/linux/man-pages/man3/libarchive.3.html) — 公式 API リファレンス
- [archive_read_disk(3)](https://man7.org/linux/man-pages/man3/archive_read_disk.3.html) — disk writer flag 仕様
- `docs/adr/0006-use-libcurl-cinterop-instead-of-ktor.md` — 並行先例
- `src/nativeInterop/cinterop/libcurl.def` — 既存 cinterop の最小定義例
- Issue #43 — 親 issue
