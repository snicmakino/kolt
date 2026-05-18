# Implementation Plan

> 圧縮版 spec (install.sh 同等スコープ)。kolt は TDD: 各実装サブタスクは対応する test を同一ユニットで green にする (reviewer の mechanical test-pass check が RED-only commit を拒否するため)。

- [ ] 1. Foundation: 共有型・OS プリミティブ・test fixture
- [x] 1.1 (P) `SelfUpdateError` sealed ADT を定義
  - Network / Metadata / Asset / Extract / Layout / Platform / Home の 7 トップ variant、各 variant は detail / path / name を String で保持 (粗粒度方針)
  - 観察: 全 variant がコンパイル可能で、design の Error Handling 表に対応する引数を持つ
  - _Requirements: 7.1, 7.2, 7.3_
  - _Boundary: SelfUpdateError_

- [x] 1.2 (P) `replaceSymlinkAtomically` と `SymlinkError` を実装 + `SymlinkTest`
  - `SymlinkError` sealed ADT (CreateFailed / RenameFailed)、`symlink(2)` で一時 symlink を作り `rename(2)` で linkPath を上書き、rename 失敗時は一時 symlink を unlink
  - 観察: SymlinkTest が green — (a) linkPath 不在で新規作成 (b) 既存 symlink を atomic 置換 (前後を lstat+readlink で観測) (c) newTarget 不在でも symlink(2) 成功 (d) regular file 上書き挙動を pin
  - _Requirements: 3.6_
  - _Boundary: infra Symlink_

- [x] 1.3 (P) `FileSystem.canWrite(path)` を追加 + test
  - `access(path, W_OK) == 0` の薄いラッパ、`@OptIn(ExperimentalForeignApi::class)` を function-level に、既存 `fileExists` の隣
  - 観察: 書き込み可能 dir で true、0500 dir で false を assert する test が green
  - _Requirements: 5.3_
  - _Boundary: infra FileSystem_

- [x] 1.4 (P) `GithubReleasesFixture` test fixture を作成
  - LoopbackHttpServer に載せる canned `releases/latest` JSON、および `kolt-<ver>-linux-x64.tar.gz` + 同 `.sha256` を生成する builder (3.3/3.4 の integration test が依存する test-infra)
  - 観察: fixture を使う最小 test が green — 生成 tarball が `extractArchive` で展開でき、生成 `.sha256` が `computeSha256` 結果と一致する
  - _Requirements: 4.1, 4.2_
  - _Boundary: testfixture_

- [ ] 2. Core: GitHub Releases クライアント
- [ ] 2.1 `GithubReleasesClient` を実装 + `GithubReleasesClientTest`
  - `downloadFile` を一時パスへ呼び `readFileAsString` → `Json.decodeFromString` (ignoreUnknownKeys)、`User-Agent: kolt/<ver>` を headers で送出、`validateTag` は `^v(\d+)\.(\d+)\.(\d+)$` で `X.Y.Z` を返す、asset 名 lookup で欠落は Asset(name)。既存 LoopbackHttpServer + awaitAccessLog を再利用し新規 server infra は作らない、fixture は 1.4 を使用
  - 観察: GithubReleasesClientTest が green — 正常 JSON decode / `tag_name` 不在→Metadata / tag 形式違反→Metadata / UA が `kolt/<ver>` で送出 (awaitAccessLog) / asset 欠落→Asset(name)
  - _Requirements: 2.1, 4.1, 4.4, 7.1, 7.2_
  - _Depends: 1.1, 1.4_
  - _Boundary: GithubReleasesClient_

- [ ] 3. Core: SelfUpdater 本体 (同一ファイル `SelfUpdater.kt`、順次)
- [ ] 3.1 ゲート群 `ensureLinuxX64` / `detectLayout` / `verifyWritable` + `SelfUpdaterLayoutTest`
  - `ensureLinuxX64`: uname で sysname=Linux && machine ∈ {x86_64, amd64}、`detectLayout`: lstat→readlink→target が `<shareRoot><X.Y.Z>/bin/kolt` の文字列 prefix にマッチ、非一致は単一 `Layout`、`verifyWritable` は `canWrite`
  - 観察: SelfUpdaterLayoutTest が green — installer 通り通過 / regular file・dangling・外部 target は単一 `Layout` / `canWrite=false` は writable 系 `Layout` / 非 linuxX64→Platform
  - _Requirements: 5.1, 5.2, 5.3, 6.1_
  - _Depends: 1.1, 1.3_
  - _Boundary: SelfUpdater_

- [ ] 3.2 `check()` + `SelfUpdaterCheckTest`
  - `ensureLinuxX64` → `fetchLatest` → `compareVersions` → CheckOutcome、`detectLayout`/`verifyWritable` は呼ばない、ファイル書き込みゼロ
  - 観察: SelfUpdaterCheckTest が green — update available / already latest(equal) / older / Platform 不一致で fetch しない / layout 不一致でも version を返す / 書き込み 0 行
  - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 6.2_
  - _Depends: 2.1, 3.1_
  - _Boundary: SelfUpdater_

- [ ] 3.3 `update()` + `runStaged` 本体 + `SelfUpdaterUpdateTest`
  - `ensureLinuxX64`→`detectLayout`→`verifyWritable`→`fetchLatest`→compare、新しければ自 pid `~/.local/share/kolt/.staging-<pid>/` を作り直し→tarball+`.sha256` download→`computeSha256` 検証(一致で通過)→`extractArchive`→`rename` で `<new-ver>` 確定→`replaceSymlinkAtomically`→progress 5 段表示→旧バージョン dir 非削除。死 pid 掃除はここに含めない (3.4)
  - 観察: SelfUpdaterUpdateTest が green — 5 ステージ進捗が順に出る / `<new>/bin/kolt` 作成 / symlink が新 target / 旧 dir 残存 / 自 pid staging が成功後に削除 / checksum 一致で extract に進む / latest≤current は no-op exit 0
  - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 4.1, 4.2, 4.5_
  - _Depends: 1.2, 2.1, 3.1_
  - _Boundary: SelfUpdater_

- [ ] 3.4 死 pid staging 掃除実装 + `SelfUpdaterStagingIsolationTest` + `SelfUpdaterUpdateChecksumMismatchTest`
  - 起動時に `.staging-*` を列挙し `kill(pid, 0)` で死 pid のみ best-effort 削除 (生存 pid 非干渉)。checksum mismatch 経路で `<new>` 未作成 + symlink 不変を保証
  - 観察: 両 test が green — 死 pid 分は掃除済 / 生存 pid 分は残存 / 自 pid は成功後削除、checksum mismatch で `<new>` 未作成 + symlink 不変
  - _Requirements: 4.3, 4.5, 7.2, 7.3_
  - _Depends: 3.3_
  - _Boundary: SelfUpdater_

- [ ] 4. Integration: CLI 配線
- [ ] 4.1 `SelfCommands.doSelf` + `SelfCommandsTest`
  - 引数 parse: `[]`→EmptyHelp exit0 / `--help`→Help exit0 / `update`→update / `update --check`→check / 未知フラグ→非ゼロ / 未知 subcommand→非ゼロ、全 `SelfUpdateError` variant を人間可読 1 行メッセージへマップ
  - 観察: SelfCommandsTest が green — 6 引数パターンの ExitCode と出力、全 error variant が空でないメッセージを返す
  - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 2.3, 2.4, 2.6, 3.3, 7.4_
  - _Depends: 1.1, 3.2, 3.3_
  - _Boundary: SelfCommands_

- [ ] 4.2 `Main.kt` 配線
  - `when` 句に `"self" -> doSelf(args.drop(1))` (アルファベット順で `run` の前)、`KNOWN_SUBCOMMANDS_SORTED` に `"self"` 挿入、`usageLines()` に `kolt self update` 行を追加
  - 観察: `kolt self update --check` が dispatch され、`kolt self` が未知コマンド扱いされず、usage 出力に self が含まれる (既存 Main の挙動/テストで確認)
  - _Requirements: 1.1, 1.3_
  - _Depends: 4.1_
  - _Boundary: cli Main_

- [ ] 5. Validation
- [ ] 5.1 全経路通し + self-host smoke 非影響 + workflows grep
  - `doSelf` 経由で check / update / 各 error 経路を通し確認、full `kolt test` を green に、`.github/workflows/` を grep して新 `self` subcommand と `KNOWN_SUBCOMMANDS_SORTED` 変更で破綻する path 仮定がないこと、`self-host-post` 経路に regression がないことを確認
  - 観察: full `kolt test` が green、`.github/workflows/` grep で新規 path 仮定の破綻なし、self-host smoke 経路に regression なし
  - _Requirements: 1.1, 2.1, 3.1, 4.1, 5.1, 6.1, 7.1_
  - _Depends: 4.2_

## Implementation Notes
- 実装後は必ず `kolt fmt` を実行すること。pre-commit の ktfmt フックが未整形ファイルの commit を拒否する (task 1.2 で発覚)。
