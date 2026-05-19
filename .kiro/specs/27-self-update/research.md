# Gap Analysis — 27-self-update

`kolt self update` 実装に向けて既存コードベースと requirements のギャップを整理する。design 着手前の事実集約。

## 1. 現状調査

### 1.1 アーキテクチャの該当領域

- **CLI dispatch**: `src/nativeMain/kotlin/kolt/cli/Main.kt:39–148`
  - `when (filteredArgs[0])` による 1 階層の dispatch + `KNOWN_SUBCOMMANDS_SORTED` (lines 126–148) の二重管理。
  - 2 階層のネスト例: `doTool` (`ToolCommands.kt:35–104`) / `doToolchain` (`ToolchainCommands.kt:16–29`)。どちらも `args.drop(1)` してから `args[0]` で再 dispatch。
  - `self` namespace は **未存在**。
  - usage 表示は `usageLines()` + `printUsage()` (`Main.kt:234–271`)。`--help` の routing は明示的にはない。
- **HTTPS ダウンロード**: `src/nativeMain/kotlin/kolt/infra/Downloader.kt:41–164`
  - `fun downloadFile(url, destPath, headers): Result<Unit, DownloadError>` がファイル書き込み専用。Followlocation / 30s connect / 300s total / cross-origin Authorization strip 済み。
  - **`User-Agent` ヘッダは付かない** (Downloader 内では未送出)。GitHub API は UA 必須。
  - **GET-to-memory 関数は存在しない**。JSON を取得するにはテンポラリファイルを書いて読み直すか、新規に in-memory 取得を実装する必要あり。
- **SHA-256**: `src/nativeMain/kotlin/kolt/infra/Sha256.kt:19–36`
  - `fun computeSha256(filePath): Result<String, Sha256Error>` 既存。
  - `.sha256` ファイルの比較ロジックは `ToolchainManager.kt` に類似の前例あり (tarball + 隣接 `.sha256` を取得し parse して比較)。
- **アーカイブ展開**: `src/nativeMain/kotlin/kolt/infra/ArchiveExtraction.kt:75–133`
  - `internal fun extractArchive(archivePath, destDir): Result<Unit, ExtractError>` 既存。
  - `ARCHIVE_EXTRACT_PERM` フラグで実行ビット保持。symlink escape 検査済み (`ArchiveExtraction.kt:161–169`、 memory `reference_libarchive_cinterop` 参照)。
- **Version / SemVer**:
  - `src/nativeMain/kotlin/kolt/config/Version.kt`: `KOLT_VERSION = "0.20.0"` / `versionString()`。
  - `src/nativeMain/kotlin/kolt/resolve/VersionCompare.kt:27–39`: `compareVersions(a, b): Int` / `isStableVersion(version): Boolean` 既存。
- **自己バイナリパス**: `src/nativeMain/kotlin/kolt/infra/SelfExe.kt:25–39`
  - `fun readSelfExe(): Result<String, SelfExeError>`、`/proc/self/exe` 経由。Linux 専用。
- **JSON シリアライズ**: `kotlinx-serialization-json 1.7.3` (`kolt.toml:17`)。 nativeMain 内には `Json.decodeFromString` の load-bearing な前例が複数存在: `kolt.resolve.GradleMetadata` (Maven 取得 JSON のデコード)、 `kolt.resolve.Lockfile` (lockfile デコード)、 `kolt.build.BuildCache` / `kolt.cli.OutdatedFormatter` / `kolt.cli.InfoCommand` 等。 GitHub Releases JSON モデルも同じパターンを踏襲できる。

### 1.2 既存パターン

- **Result chain**: `kotlin-result` 2.x value class、 `is Ok / is Err` 禁止 (memory `feedback_kotlin_result_value_class`)。
- **sealed error ADT**: `DownloadError`、 `Sha256Error`、 `ExtractError` などのパターンを踏襲。
- **テスト**: `nativeTest/kotlin/` 配下、 mirror 構造。 ネットワーク stub は `testfixture/LoopbackHttpServer.kt`、 単一回応答のループバック HTTP/1.1。`DownloaderAuthHeaderTest.kt` がレファレンス。
- **subprocess CLI テスト**: `.kexe` を直接呼ぶ integration test は未確認。 内部 API を直接叩く unit test が中心。

### 1.3 統合ポイント

- 既存 release 成果物の命名: `kolt-<ver>-linux-x64.tar.gz` + `kolt-<ver>-linux-x64.tar.gz.sha256` (installer spec で確定)。
- インストール先: `~/.local/share/kolt/<ver>/bin/kolt` + `~/.local/bin/kolt` symlink (installer spec 所有)。
- 既存 `kolt update` (依存解決の lockfile 更新) と命令名空間は衝突しない (こちらは top-level、 `kolt self update` は nested)。

## 2. Requirement → Asset Map

| Req | 必要な能力 | 既存資産 | ギャップ |
|---|---|---|---|
| 1: `self` サブコマンド surface | dispatch / usage / 未知フラグ拒否 | `Main.kt` dispatcher, `doTool`/`doToolchain` ネスト前例 | **Missing**: `self` namespace ハンドラ + usage 文。`KNOWN_SUBCOMMANDS_SORTED` への追加 |
| 2: `--check` 動作 | GitHub Releases API GET + semver 比較 + 副作用ゼロ | `compareVersions` / `isStableVersion` / `KOLT_VERSION` / `Downloader.headers` param 経由でのカスタムヘッダ送出 (UA は既存 API で対応可、 `DownloaderAuthHeaderTest` が前例) | **Missing**: GitHub API GET の JSON 取得経路 (Downloader が file-write 専用なので一時ファイル経由 OR in-memory variant 追加)、 `tag_name` の `@Serializable` モデル |
| 3: `update` 実行 | 取得→検証→展開→symlink 切替→表示 | `downloadFile` / `computeSha256` / `extractArchive` | **Missing**: オーケストレーション ("SelfUpdater" 相当)。 atomic symlink 切替 |
| 4: ダウンロード + checksum | tarball + `.sha256` 同時取得、 一時パス、 ハッシュ比較 | `Downloader`、 `ToolchainManager` の前例パターン | **Constraint**: `.sha256` パースは ad-hoc (共通ヘルパなし)。 流用可だが小さな抽出が必要かは判断 |
| 5: Atomic symlink 切替 | tmp symlink 生成 → `rename(2)` で原子置換 | なし — `FileSystem.kt` に `symlinkat`/`readlink`/`renameat` のラッパーなし | **Missing**: `kolt.infra` に新規プリミティブ |
| 6: installer layout 検出 | `readSelfExe()` の解決結果が `~/.local/share/kolt/<ver>/bin/kolt` 形か判定 + `~/.local/bin/kolt` が一致 symlink か照合 | `SelfExe.readSelfExe`、 `FileSystem.lstat` | **Missing**: layout マッチャ。 書き込み権限プローブ (`access(W_OK)`) も未存在 |
| 7: Platform support | linuxX64 限定で API 接触前に早期 abort | Kotlin/Native の compile-time target 判定 (`Platform.osFamily` / `expect-actual`)、ただし root プロジェクトは nativeMain 単独で `linuxX64` 固定。 macOS 部分 (memory `project_macos_selfhost_v1`) は未着 | **Constraint**: 現状 linuxX64 ビルドのみ。 ランタイム platform 判定の仕組みを設けるかは要検討 (compile-time でも要件は満たせる) |
| 8: エラー識別性 | 5 経路 (network / metadata / asset 不在 / 展開 / その他) 別の sealed ADT | `DownloadError`、 `Sha256Error`、 `ExtractError` を再利用しつつ self-update 専用 envelope ADT が必要 | **Missing**: `SelfUpdateError` 相当の sealed ADT と CLI 側のメッセージマッピング |

## 3. 実装アプローチ案

### Option A: 既存ファイルに直接追加 (MVP minimum)

`Main.kt` に `"self" -> doSelf(...)` を追加、 `kolt.cli` に `SelfCommands.kt` を 1 ファイル増やしてその中に取得・検証・展開・切替まで詰める、 必要な infra ヘルパ (symlink 置換 / write probe) だけ `kolt.infra` に最小追加、 という配置。

- ✅ 新規ファイル最小 (`SelfCommands.kt` + infra ヘルパ 1〜2 個)、 ローカルなパターン継承
- ✅ v0.21.0 MVP の scope に対しては domain pollution が小さい (新規実装の中身はそもそもオーケストレーション 1 本)
- ❌ `kolt self uninstall` 等の将来追加が来た時点で 1 ファイルが膨らみ、 後追いで Option C に refactor する負債になる
- ❌ `SelfUpdateError` の sealed ADT を `kolt.cli` 配下に置くと package 構造の方向 (cli → build → resolve) と整合しない

### Option B: 新規 package を切る (推奨ベース)

`kolt.cli.self` (CLI ハンドラ) と `kolt.selfupdate` (オーケストレーション + `SelfUpdater` + `SelfUpdateError` + `GithubReleasesClient` + `InstallerLayout`) を新設。 infra 側には**最小限の汎用プリミティブ**だけ追加 (`Downloader` への User-Agent 受け渡しは既存 `headers` param で対応可能、 symlink swap と write probe は `kolt.infra.FileSystem` か新規 `kolt.infra.Symlink` に小ヘルパを増やす)。

- ✅ self-update のドメインが 1 package で読める
- ✅ `installerLayout` / `selfUpdater` などの将来サブコマンド (`kolt self uninstall` 等) を同 package で受けられる
- ✅ infra 追加分は他コマンドからも再利用可能 (libarchive / Downloader が辿った経路と同じ)
- ❌ 新規ファイルがそれなりに増える (~6 ファイル想定)

### Option C: Hybrid (推奨)

Option B を基本としつつ、以下は明示的に **既存パターン継承**:

- HTTPS GET は `Downloader.downloadFile` をテンポラリパスに対して呼び、 `readFileAsString` で取り直す (JSON は小さい)。 新規 GET-to-memory API を作らない。
- `.sha256` 解析は `ToolchainManager` のパターンを `SelfUpdater` 内で踏襲し、 共通ヘルパには切り出さない (load-bearing でない抽象は作らない、 memory `feedback_sdd_test_inflation` 参照)。
- symlink swap は `kolt.infra` に薄いラッパ (`replaceSymlinkAtomically(linkPath, newTarget)`) を追加し、 内部で `symlink(2)` で一時 symlink を作って `rename(2)` で linkPath を上書きする (Linux man-page 上 `rename(2)` は newpath が既存 symlink でも atomic に置換、 `renameat2` や `*at` 系は不要)。
- 書き込みプローブは `kolt.infra.FileSystem` に 1 関数だけ追加 (`canWrite(path): Boolean`)。
- installer layout 検出は `kolt.selfupdate.InstallerLayout` (data class + companion validator)。

Option C は Option B からファイル数を最小化しつつ、 ドメイン境界を 1 package に閉じる。

| 観点 | A (MVP min) | B | C (推奨) |
|---|---|---|---|
| ドメイン凝集 | 低 | 高 | 高 |
| 新規ファイル | 2–3 | 6+ | 4–5 |
| 既存テスト前例の再利用 | 高 | 中 | 高 |
| 将来 `self uninstall` 拡張 | 弱 | 強 | 強 |
| MVP 着地までの距離 | 最短 | 中 | 中 |

Option A も「`self uninstall` がロードマップ入りした時点で Option C へ refactor」 という evolution path として現実的で、 v0.21.0 MVP では legitimate な選択肢。 ただし `SelfUpdateError` 等のドメイン ADT が `kolt.cli` に居座る違和感は残るため、 design 着手時に Option C が最終ターゲットだと共有しておく。

## 4. 複雑度・リスク

- **Effort: S–M (2–4 日 baseline / 3–7 日 並行・中断耐性込み)**
  - **2–4 日 (S–M 境界)** の根拠: 既存プリミティブ (`Downloader` / `Sha256` / `extractArchive` / `compareVersions` / `readSelfExe`) を再利用でき、 nativeMain での JSON デコード前例も既に複数あるため「ロジック量より配線量が支配的」。 新規記述は (1) GitHub Releases JSON モデル ~30 行、 (2) `SelfUpdater` orchestration ~150 行、 (3) symlink swap ラッパ ~40 行、 (4) layout 検出器 ~80 行、 (5) CLI 配線 + usage ~50 行、 (6) テスト (LoopbackHttpServer + on-disk テスト) ~300 行、 合計 ~700 行。
  - **3–7 日 (M)** の根拠: Requirement 5 (並行実行 lock + ステージング dir + 中断回復) を真面目に実装すると lock primitive 設計と SIGINT-aware なクリーンアップが追加される。 既存に `flock` ベースの advisory lock 前例があれば短縮可能。
- **Risk: Medium**
  - **Medium 要因**: `rename(2)` over 既存 symlink の atomic 置換は Linux man-page で確認済みだが、 ファイルシステム実装 (ext4 / btrfs / 9p WSL) ごとの挙動差は実機で 1 度走らせて確認する価値がある。 9p (WSL2) は memory `feedback_bench_mtime_granularity` / `feedback_mtime_unittest_gotcha` の例があり想定外の挙動を持つことが既知。
  - **Medium 要因**: GitHub API 非認証レート制限 (60/hr/IP)。 `--check` を CI で繰り返す使い方が出てくると詰まる可能性。
  - **Medium 要因 (Requirement 5 関連)**: 2 つの `kolt self update` 並行実行で同一ステージング dir を半端に上書きするリスク。 advisory lock (`flock` 等) を `~/.local/share/kolt/.update.lock` 等に取るのが定石だが、 lock の取得経路を design で決める。
  - **Medium 要因 (Requirement 5 関連)**: 展開・検証中の SIGINT / kill。 `extractArchive` は libarchive 経由で chdir を伴うため (memory `reference_libarchive_cinterop`)、 中断時の savedCwd 復帰が間に合わないと外部から見て kolt の cwd が壊れた状態が観測されうる。 ステージング → atomic rename への切替で殆ど吸収できるが、 abort path で savedCwd を `atexit` 的に restore する仕組みは design で確認。
  - **Medium 要因**: 切替直前まで生きていた古い `kolt` が daemon JVM を spawn 中に symlink が差し替わると、 「親 kolt (新版) と既存 daemon プロセス (旧版 jar) が混在」する状態が発生しうる。 daemon 側の handshake (バージョン照合) で reject される想定だが、 design で観測経路を確認。
  - **Low 要因**: その他 (semver 比較、 tarball 展開、 SHA256 検証、 UA ヘッダ送出) はすべて load-bearing な前例が存在。

## 5. design に持ち越す Research Items

1. **Atomic symlink replacement**: `symlink(2)` で一時 symlink を作って `rename(2)` で linkPath を上書きする経路 (Linux man-page 上は OK、 N-3 で確認済) を ext4 / 9p (WSL2) 両方で 1 度実機確認。
2. **GitHub API on libcurl**: User-Agent ヘッダ未指定で `api.github.com` に GET したときの 403 挙動を確認 (実機 1 度)。 既存 `Downloader.headers` パラメータで `User-Agent: kolt/<ver>` を渡せば足りる。
3. **macOS expectation hook**: linuxX64 以外を「明示エラー」で拒否する判定は、 現状 nativeMain が linuxX64 単独ビルドである以上、 compile-time でなく runtime に「常に linuxX64 でないと走らない」前提を assert する形になる。 macOS port 着手時に `expect-actual` で差し替えるルートを残すか、 design で判断。
4. **`--check` の `releases/latest` 解釈**: GitHub API は draft / prerelease を `releases/latest` から自動除外する (GitHub REST docs 確認済)。 これに乗ると requirements の「安定版のみ」が API 仕様 + release workflow の prerelease マーキングだけで満たせる。 design で「`tag_name` をそのまま `compareVersions` に渡す」 + Requirement 2.1 の regex で `vX.Y.Z` 形式を assert するアプローチに収束させる。
5. **テスト戦略の粒度**: subprocess CLI test (`kolt.kexe self update --check`) を追加するか、 `SelfUpdater` の unit test + `LoopbackHttpServer` 接続テストで十分か。 既存に subprocess CLI integration test の前例がないため、 後者を default にしたい。
6. **並行実行 lock 戦略**: advisory `flock` を `~/.local/share/kolt/.update.lock` 相当に取って後発を refuse するか、 `~/.local/share/kolt/<new>/` の存在チェックで「別プロセス進行中」を検出するかを design で決定。 ステージング dir の path も含めて確定。
7. **中断回復ポリシー**: ステージング dir が前回中断で残っているとき、 (a) 即破棄して再展開、 (b) 整合性を再検証して使い回す、 のどちらを既定にするか。 設計上は (a) の方が単純で safe restart を保証しやすい。
8. **`~/.local/bin/kolt` が regular file のケース**: installer 経由でない手動配置 (`cp kolt.kexe ~/.local/bin/kolt`) ユーザーが現実にいる場合の挙動。 Requirement 6.2 で「拒否」確定済みだが、 メッセージ内に「installer 経由で再インストールしてください」等のリカバリ案内を入れるかは design で決める。
9. **`XDG_DATA_HOME` 等の path override 対応**: `kolt.config.KoltPaths` (もしくは類似) が既に XDG_DATA_HOME / HOME 取得を抽象化しているか確認し、 self-update の path 解決もそれに乗せる。
10. **ダウンロード進捗表示**: tarball が数 MiB 規模になる前提で、 Requirement 3.4 の「`downloading tarball`」 1 行 のみで足りるか、 % 進捗を出すかを design で判断。 libcurl progress callback を引き回すと既存 Downloader API に拡張が必要。
11. **libarchive SECURE_NOABSOLUTEPATHS 整合性**: `extractArchive` は absolute path / `..` を含む symlink を弾く (memory `reference_libarchive_cinterop` 参照)。 self-update tarball が内部に absolute path や `..` を含むエントリを持たないことを release workflow が保証する旨を docs に明示し、 整合性を pin。
12. **daemon との版整合性**: 切替直後の `kolt` invocation が古い daemon JVM プロセス (旧 jar) に接続した場合の挙動を design で確認。 既存の handshake (memory `feedback_daemon_canary_pattern` / `feedback_daemon_launch_field_fingerprint` 参照) が version mismatch を reject するならそのまま依拠、 さもなければ `kolt daemon stop` を self-update が呼ぶ必要があるかを判断。

## 6. design phase への推奨

- **Preferred approach**: Option C (Hybrid) を最終ターゲットとしつつ、 v0.21.0 MVP のサイズ次第で Option A を中継点として通る選択肢を design 段階で評価。 どちらも `kolt.infra` への追加 (`replaceSymlinkAtomically`、 `canWrite`) は共通で最小限。
- **Key decisions to lock in design**:
  - `SelfUpdateError` の sealed ADT 構成と Requirement 8 の 4 経路 (Network / Metadata / Asset / Extract) + 包括 catch のマッピング。
  - GitHub Releases JSON モデルの最小フィールドセット (`tag_name`、 `assets[].name`、 `assets[].browser_download_url`)、 `@Serializable` で nativeMain にもう存在する `GradleMetadata.kt` / `Lockfile.kt` パターンを踏襲。
  - Requirement 6 のレイアウト検出ロジック (`readSelfExe` 結果と `~/.local/bin/kolt` の `lstat` + `readlink` + path-prefix 比較)。
  - Requirement 5 の lock 戦略 (`flock`-based vs 存在チェックベース)、 ステージング dir の path、 中断回復のデフォルト挙動。
  - Platform 判定の置き場所 (Requirement 7.2 で「最初の gate」 と明示済み)。
  - 出力文字列フォーマット (Requirement 2.3 / 2.4 / 3.3 / 3.4 を満たす最小書式、 i18n は対象外)。
- **Carry-forward research**: §5 の 12 項目を design.md の Research セクションに転記し、 実機確認結果を追記する。

---

# スコープ圧縮の判断 (2026-05-16)

design validation (GO 判定) 後、 ユーザーと「この機能を v0.21.0 でやるか / どこまでやるか」を再検討した結果、 **要件を圧縮した spec に書き直す (Option 1)** を選択。 install.sh wrapper 案 (Option 2) は、 macOS / linuxArm64 対応時に platform 分岐がネイティブ側で一元管理できなくなるため却下 (memory `project_macos_selfhost_v1` の方向と非整合)。

## なぜ圧縮したか

- #27 は priority: **nice**。 当初 Issue は「`rename(2)` で置換」程度のライトな想定だった。
- adversarial review で Requirement 5 系 (並行 `flock` lock / SIGINT 中断回復 / `cleanupAbandoned` / dangling・外部 target・regular file の 4 分類 / `SelfUpdateError` 11 variant) が積み上がり、 effort が M (3–7 日) に膨張。 技術的指摘は正しいが priority: nice に対する投資 bar として過大と判断。
- 基準は **install.sh が POSIX sh で持つスコープ**。 install.sh も並行排他しない / 中断回復専用機構を持たない / layout は単一判定。 self-update がそれを超える防衛機構を持つ必然性はない。
- daemon 増加は self-update の複雑度に **影響しない**ことを確認済み (release tarball の `libexec/` 同梱 + socket fingerprint 自動隔離)。 ユーザーの「daemon で難易度が上がった」直観は事実とズレており、 複雑度増は専ら review 由来だった。

## 何を切ったか

| 削除/縮小した項目 | 旧 | 新 |
|---|---|---|
| 並行 advisory lock (`UpdateLock`) | Requirement 5.2 + 専用クラス | 削除 (Out of Boundary に明記) |
| SIGINT/kill 中断回復 (`cleanupAbandoned`) | Requirement 5.4/5.5 + 専用機構 | 削除。 staging を処理開始時に無条件再作成するだけ (Requirement 4.5) |
| layout 細分類 | Requirement 6.2–6.4 (NotASymlink/Dangling/外部) | 単一判定 1 件に集約 (Requirement 5.1/5.2) |
| `SelfUpdateError` 粒度 | 11 variant (4 重 sealed) | 7 トップ variant (粗粒度、 detail は String) |
| package 分割 | 7+ ファイル (`UpdateLock`/`StagingArea`/`PlatformGate`/`InstallerLayout` 個別) | 3 ファイル (`SelfUpdater` に internal 関数で内包) |
| 新規ファイル総数 | 12 + 2 modified | 5 + 2 modified |
| effort | M (3–7 日) | S (1–3 日) |

## 何を残したか (core)

- `kolt self update` / `--check` の CLI surface
- `releases/latest` + `vX.Y.Z` regex 検証 + semver 比較
- SHA-256 検証
- linuxX64 以外の拒否 (gate 最前)
- installer layout の単一判定 + 書き込み権限プローブ
- `rename(2)` 1 回による symlink atomic 切替 (lock なしでも `rename` の atomic 性は無料で得られる — Requirement 3.6)
- 一時 dir 経由の配置 (lock も pid も cleanup 専用機構も持たない最小形)

## 圧縮後も残る Research Items (実装時に確認)

1. `symlink(2)` + `rename(2)` の atomic 置換を ext4 / 9p (WSL2) で実機確認 (圧縮後も core)。
2. User-Agent 未指定で `api.github.com` が 403 を返すことの実機確認 (`Downloader.headers` 経由送出で解決の想定)。
3. `releases/latest` の prerelease 自動除外 + `vX.Y.Z` regex で「安定版のみ」契約が成立することの確認 (GitHub docs 確認済、 実 release で 1 度確認)。
4. macOS port 時の `expect-actual` 差し替え seam (`ensureLinuxX64`) — 本 spec では runtime wide assertion のみ。

§5 の旧 12 項目のうち lock 戦略 / 中断回復ポリシー / daemon 版整合性 / 進捗 % / XDG 対応は scope 圧縮により不要化、 または Out of Boundary に移送済み。
