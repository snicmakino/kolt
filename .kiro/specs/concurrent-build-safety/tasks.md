# Implementation Plan

- [ ] 1. Foundation: exit code と ADR
- [x] 1.1 EXIT_LOCK_TIMEOUT の exit code 定数を追加
  - `ExitCode.kt` に `EXIT_LOCK_TIMEOUT = 6` (既存最大値 5 の次) を新設
  - 既存の `EXIT_*` 命名・順序規則 (config / dependency / test / format の系列) と整合する位置に置く
  - **完了状態**: `kolt/cli/ExitCode.kt` に `EXIT_LOCK_TIMEOUT` 定数が公開され、import で参照可能になっている
  - _Requirements: 1.4, 1.7_

- [x] 1.2 (P) ADR 0029 を起草し並行性契約を記録
  - `docs/adr/0029-concurrent-build-safety-model.md` を新設、既存 ADR (0016 / 0024 / 0027 / 0028) に倣い `## Summary` (5-7 bullet) → Status → Context → Decision → Consequences の順で構成
  - Decision 節に: project-local flock の対象 critical section、`build/.kolt-build.lock` のパス、待機メッセージ、30 秒上限、global cache の `.tmp.<pid>` + `rename(2)` 規約、ADR 0016 §3-§5 で既述の daemon socket bind の OS-level 排他への依存、cross-machine (NFS) 共有の非サポート、を明記
  - Consequences 節で `cleanupStaleTemps` の 24h 判定と pre-v1 / no migration shim 方針を記述
  - **完了状態**: `docs/adr/0029-...md` が存在し、Requirement 3.1 / 3.2 / 3.3 すべての記載を含む
  - _Requirements: 3.1, 3.2, 3.3_
  - _Boundary: docs/adr_

- [ ] 2. Core: 同期プリミティブと atomic 書き出し
- [x] 2.1 (P) ProjectLock を新設し排他ロックの取得・タイムアウト・解放を実装
  - 新規ディレクトリ `src/nativeMain/kotlin/kolt/concurrency/` に `ProjectLock.kt` を作成
  - `LockHandle`(AutoCloseable) と `sealed class LockError { TimedOut(waitedMs) ; IoError(errno, message) }` を定義、`acquire(buildDir, timeoutMs = 30_000L, onWait)` は `Result<LockHandle, LockError>` を返す
  - 実装は `platform.posix.open(O_CREAT|O_RDWR)` → `platform.linux.flock(LOCK_EX|LOCK_NB)` の 100ms polling、最初の peer 検出時のみ `onWait` を 1 度呼ぶ、上限到達で `LockError.TimedOut`
  - `LockHandle.close()` は `flock(LOCK_UN)` + `close(fd)`、二重 close 安全
  - `src/nativeTest/kotlin/kolt/concurrency/ProjectLockTest.kt` を新設、in-process で同 path を別 fd で 2 回 open し以下を assert: (a) 1 回目即時 Ok、(b) 2 回目 (timeoutMs=200) `Err(TimedOut)` で `onWait` が 1 度だけ呼ばれる、(c) 1 回目 close 後の 3 回目即時 Ok、(d) 存在しない buildDir で `Err(IoError)`、(e) `timeoutMs=0` + peer 保持で即時 `Err(TimedOut)` かつ `onWait` 不発
  - **完了状態**: `./gradlew linuxX64Test` で `ProjectLockTest` の 5 ケースが GREEN
  - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6_
  - _Boundary: ProjectLock_

- [x] 2.2 (P) Downloader を temp+rename 化し global cache 競合を排除
  - `Downloader.kt` の `download()` を改修: `fopen(destPath, "wb")` を `fopen("$destPath.tmp.${getpid()}", "wb")` に変更、curl 完了 → `fclose` → SHA-256 検証 (既存ロジック維持) の後で `platform.posix.rename(tempPath, destPath)` を呼ぶ
  - 失敗時の `finally { remove(destPath) }` を `finally { remove(tempPath) }` に切替、SHA mismatch 時は rename せず temp を消す
  - `private fun cleanupStaleTemps(cacheDir: String, olderThanSeconds: Long = 86_400L)` を同ファイルに追加、`download()` 冒頭で対象 destPath の親ディレクトリのみ sweep (mtime が 24h 以上前の `*.tmp.*` を delete)
  - 既存の `DownloaderTest` を更新し以下を追加: (a) 成功時に `*.tmp.*` が残らず destPath に valid jar が出来ている、(b) curl 失敗時 destPath が変更されず temp も残らない、(c) SHA mismatch 時 destPath が変更されない、(d) 既存の 24h 以上前の `.tmp.<pid>` が初回 download で sweep される
  - **完了状態**: `./gradlew linuxX64Test` で `DownloaderTest` の 4 追加ケースが GREEN、既存ケースも引き続き GREEN
  - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5_
  - _Boundary: Downloader_

- [ ] 3. Integration: lock を CLI entry に wire up
- [x] 3.1 (P) BuildCommands の build 系 entry を ProjectLock で wrap
  - `BuildCommands.kt` の `doBuild` / `doNativeBuild` / `doTest` / `doRun` の入口で `ProjectLock.acquire(paths.buildDir, timeoutMs).use { ... 既存 body ... }` パターンで wrap
  - timeoutMs は環境変数 `KOLT_LOCK_TIMEOUT_MS` を読み、未設定または parse 失敗時は `ProjectLock.DEFAULT_TIMEOUT_MS` (30_000L) を使う。env 解釈ヘルパは BuildCommands 内に閉じ込める (DependencyCommands と重複してよい — 抽象化はしない)
  - `LockError.TimedOut` は `EXIT_LOCK_TIMEOUT` で exit、stderr に "lock acquisition timed out after Nms; another kolt build may be stuck" 相当のメッセージを出力
  - `LockError.IoError` は既存 `EXIT_BUILD_ERROR` 経路に流し、errno + path を stderr に
  - `doRun` 内で `doBuild` を呼ぶ場合の二重 acquire は同一プロセス・同一 OFD であれば flock(2) 仕様上問題ないが、設計 §Integration 通り entry-only に統一して `doBuild` 等の内部からは acquire しない
  - **完了状態**: `BuildCommands.kt` の対象 4 entry 入口で lock 獲得済み、TimedOut 時の exit code が 6 (EXIT_LOCK_TIMEOUT)、`KOLT_LOCK_TIMEOUT_MS=200` を渡したテストが TimedOut 経路を 200ms 程度で踏める
  - _Depends: 1.1, 2.1_
  - _Requirements: 1.4, 1.7_
  - _Boundary: BuildCommands_

- [x] 3.2 (P) DependencyCommands の deps 系 entry を ProjectLock で wrap
  - `DependencyCommands.kt` の `doAdd` / `doInstall` / `doUpdate` の入口で同パターン (`ProjectLock.acquire(paths.buildDir, timeoutMs).use { ... }`) で wrap
  - timeoutMs の解釈は 3.1 と同方針 (`KOLT_LOCK_TIMEOUT_MS` env 優先、デフォルト 30_000L)
  - `LockError.TimedOut` は `EXIT_LOCK_TIMEOUT`、`LockError.IoError` は既存 `EXIT_DEPENDENCY_ERROR` 経路
  - `deps tree` (read-only) は wrap しない — 既存 dispatcher の `tree -> doTree(...)` 経路はそのまま
  - **完了状態**: `DependencyCommands.kt` の `doAdd` / `doInstall` / `doUpdate` 入口で lock 獲得済み、`deps tree` は lock を踏まずに動作
  - _Depends: 1.1, 2.1_
  - _Requirements: 1.4, 1.7_
  - _Boundary: DependencyCommands_

- [ ] 4. Validation: 多プロセス並走の e2e
- [x] 4.1 ConcurrentBuildIT で 2 プロセス間の直列化と atomic download を検証
  - `src/nativeTest/kotlin/kolt/cli/ConcurrentBuildIT.kt` を新設、`executeCommand` (既存 fork+execvp+waitpid) で `kolt.kexe` を 2 回 spawn
  - 直列化シナリオ: tmp dir に小さな fixture プロジェクトを生成 (`kolt.toml` + Main.kt) し、2 つの `kolt build` を ~50ms 差で起動。両方とも exit 0、合計実行時間が単独実行の概ね 2 倍程度になることで直列化を観察 (タイミング厳密 assert はせず、hard upper bound 60s 以内で終わることのみ assert して flake を抑える)
  - TimedOut パス: shell `flock(1)` で fixture の `build/.kolt-build.lock` を 1 秒保持する peer を spawn し、その間に環境変数 `KOLT_LOCK_TIMEOUT_MS=200` を渡した `kolt build` を起動 → exit code 6 (`EXIT_LOCK_TIMEOUT`) と stderr メッセージを assert (`KOLT_LOCK_TIMEOUT_MS` の env 解釈は 3.1 / 3.2 の CLI entry 側で行い、2.1 の acquire には解釈済みの timeoutMs を渡す — 本番運用上も短い CI timeout 用途に有用な knob)
  - deps × build シナリオ: `kolt deps install` と `kolt build` を同一 fixture で並走、両方 exit 0
  - Downloader 並走: 2 プロセスを共有 `~/.kolt/cache/` (test 用 tmp HOME) 配下で同一座標 fetch、最終 path に valid jar (SHA 一致) が 1 つ、`*.tmp.*` 残骸なし
  - **完了状態**: `./gradlew linuxX64Test` で `ConcurrentBuildIT` の 4 ケースが GREEN、ローカルで 3 連続 pass を確認
  - _Depends: 3.1, 3.2, 2.2_
  - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.7, 2.1, 2.2, 2.3, 2.4_
  - _Boundary: ConcurrentBuildIT_

## Implementation Notes

- Format check: project uses `./scripts/fmt.sh --check` (invoked by pre-commit hook). Format-and-fix: `./scripts/fmt.sh`. There is no Gradle `ktfmt` task; running `./gradlew ktfmtCheck` fails. Run the script before staging Kotlin changes.
- ConcurrentBuildIT is gated behind `KOLT_CONCURRENT_IT=1` because it spawns the real `kolt.kexe` and pulls live Maven Central deps. Default `./gradlew linuxX64Test` returns early without exercising the e2e cases. To run the gated suite: `./gradlew linkDebugExecutableLinuxX64 && KOLT_CONCURRENT_IT=1 ./gradlew linuxX64Test --tests "kolt.cli.ConcurrentBuildIT"`.
- Pre-existing TOCTOU race in `ensureDirectoryRecursive` (two procs racing to create the same `~/.kolt/cache/<g>/<a>/<v>/` tree) is **outside** this spec's boundary; scenario 4 sidesteps it with a serial warm-up. Worth a follow-up issue.
- Stale `kolt.kexe` (binary predating tasks 3.1/3.2) makes scenario 2 fail. The IT does not verify binary freshness; CI must ensure `linkDebugExecutableLinuxX64` runs after lock-related commits. Worth a follow-up to detect via mtime vs. lock-related source mtimes.
