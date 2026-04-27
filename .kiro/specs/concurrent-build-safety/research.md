# Gap Analysis — concurrent-build-safety

## Current State Snapshot

### Lock / 同期プリミティブ
- **flock(2) / fcntl(2) は未使用**。codebase 全体で grep してもヒットなし。新規 cinterop が必要。
- `Downloader.kt:37-79` は `platform.posix.fopen / fwrite / fclose / remove` で I/O、排他制御なし。
- `FileSystem.kt:15-66` の `writeFileAsString` は単純な `fopen("w")` 上書き、原子性なし。
- 既存「自己修復」パターンは `PluginJarFetcher.kt:82-161` の TOFU stamp (`<jar>.sha256` で検証済みハッシュを記録、不一致で再 download) — 真の mutex ではないが ADR への参考実装にはなる。
- `WatchLoop.kt:42-50` は `AtomicInt` + SIGINT ハンドラで exit flag を扱う既存例。flock の lifetime 管理 (Process 終了で OS が解放する性質) と組み合わせる前提で参考になる。
- `DaemonReaper.kt:21-76` の socket probe → 失敗時 reap パターンは「stale 検出 + 自動掃除」の参考になる。

### Critical Section の seam
- `BuildCommands.kt:195-375` の `doBuild()` が JVM build フローの直列チェーン: 依存解決 → `writeFileAsString(LOCK_FILE, ...)` (DependencyCommands 経由) → `backend.compile()` で `build/classes/` 出力 → `executeCommand(jarCmd.args)` で `build/<name>.jar` 出力 → `handleRuntimeClasspathManifest()` で `build/<name>-runtime.classpath` 出力。
- `BuildCommands.kt:377-523` の `doNativeBuild()` も同プロセス内で 2 段階 (library → link) 直列。
- `Main.kt:27-82` 経由の `build` / `check` / `run` / `run --watch` / `test` / `test --watch` がすべて `doBuild` / `doTest` / `doRun` を呼ぶ → ロック獲得は **`doBuild` / `doNativeBuild` / `doTest` の入口で 1 か所**で済む。
- `WatchLoop.kt` は inotify トリガで `doBuild/doCheck/doTest` を再起動する → ロック獲得・解放はループ内側で per-rebuild 単位、watch プロセス全体で保持しない (Req 1 #7 と整合)。

### Downloader retrofit ポイント
- `Downloader.kt:41` `fopen(destPath, "wb")` を一時パスに切替。
- `Downloader.kt:52-72` の curl 完了 + HTTP code 検証 後に rename(2) 呼び出しを挿入 (現在 cleanup の `remove(destPath)` だけ存在)。
- `Downloader.kt:74-78` の `finally { fclose; if (!success) remove(destPath) }` を `remove(tempPath)` に切り替え。
- **POSIX `rename` の cinterop 経路は未確認** — `platform.posix.rename` が公開されているか要 spike (1 行検証)。なければ既存 cinterop def の拡張が要る。
- 起動時 `.tmp.<pid>` sweep は `~/.kolt/cache/` 配下を初期化する場所がない (lazy 作成) ため、`Downloader` 冒頭で対象ディレクトリ単位 sweep を仕込むのが自然。

### Multi-process native test の素地
- `ProcessTest.kt:11-59` で `executeCommand(listOf("true" / "false"))` の成功/失敗 assert は既にある — fork/execvp + waitpid のテスト経路が確立。
- **タイムアウト assert を行う既存テストは無い** — `waitpid` は無期限待機で、30s 上限ロジックを単体テストでカバーするには新パターンが要る。
- `Process.kt:70-124` の `spawnDetached` (double-fork + setsid、daemon 起動用) は detached 子プロセス制御の参考だが、本 spec のテスト用途は同期的に 2 プロセス回す方が単純。
- kolt.kexe 自身を test fixture から起動する e2e テストは現存しない。テストでは `ProjectLock` の単体 (1 プロセス内で 2 つの fd を取得) と「実 2 プロセス」 (`posix_spawn` で kolt.kexe または小さな専用バイナリを起動) の両方が要りうる。

### ADR 既存資産
- `docs/adr/0028-...` (v1.0 release policy)、`0027-...` (runtime classpath manifest)、`0024-...` (native compiler daemon)、`0016-...` (warm JVM compiler daemon) など、最近の ADR はすべて冒頭に `## Summary` (5-7 bullet) を置く規約が定着。
- 既存 ADR は **lock 戦略を扱っていない** — daemon socket は ADR 0016 §3-§5 で「one-connection-at-time + bind 排他は OS 任せ」と記載済み。本 spec の ADR は新規テーマとして座る。
- 命名は `docs/adr/00NN-<kebab-name>.md` 形式。本 spec では 0029 が次番号 (28 の次)。

## Requirement-to-Asset Map

| Requirement | 既存資産 | Gap |
|---|---|---|
| R1.1 lock 取得試行 | なし | **Missing** — 新規 flock cinterop |
| R1.2 別プロセス保持中の writes 抑止 | なし | **Missing** — flock 経由 |
| R1.3 stderr 待機メッセージ | 既存の logging utility (BuildCommands で stderr 出力例多数) | **Existing pattern** |
| R1.4 30s 上限 + clean error | タイムアウト assert する test なし、time-based wait なし | **Missing** — `flock(LOCK_EX|LOCK_NB)` polling か `alarm(2)+flock` |
| R1.5 終了時 release | flock は FD close (プロセス終了) で OS が自動解放 | **Trivial with flock** |
| R1.6 クラッシュ時自動回復 | 同上 | **Trivial with flock** |
| R1.7 critical section 限定 | doBuild/doNativeBuild/doTest の入口に seam あり | **Constraint** — wrap 位置の特定 |
| R2.1 中間バイト列を最終パスに置かない | Downloader.kt:41 直書き | **Missing** |
| R2.2 atomic な出現 | POSIX rename 未確認 | **Missing + Research Needed** |
| R2.3 失敗時 残骸なし | 現行の `remove(destPath)` 経路あり | **Adapts** (path 切替のみ) |
| R2.4 並走 download safe | なし | **Inherent in temp+rename** |
| R2.5 SHA-256 検証維持 | TransitiveResolver.kt:236 | **No change** |
| R3.1 ADR 1 本 | docs/adr/ + Summary 規約 | **New** — 0029 |
| R3.2 lock path + temp naming 記述 | なし | **New** |
| R3.3 NFS unsupported 明示 | なし | **New** |

## Implementation Approach Options

### Option A: 既存コンポーネントの拡張のみ
- flock cinterop は `kolt/infra/` 直下にユーティリティとして同居 (FileSystem.kt と並列)。ロック取得は `BuildCommands.kt` 内で `tryAcquireLock(buildDir) → ... → release` を inline で書く。Downloader は 5 行の retrofit。
- ✅ 新規ファイル最小、既存パターンに馴染む。
- ❌ BuildCommands.kt がさらに膨らむ。lock の lifecycle 管理が build flow と混在し、テストのしにくさが残る。

### Option B: 新規コンポーネント中心
- `kolt/concurrency/ProjectLock.kt` (flock wrapper、`acquire(path, timeoutMs): Result<Lock, LockError>`、`use { ... }` パターン)。
- `kolt/concurrency/AtomicWrite.kt` (temp path 生成 + rename ヘルパ、Downloader 以外でも将来使い回し可能)。
- BuildCommands.kt は `projectLock.use { doBuild(...) }` で wrap、Downloader は AtomicWrite.atomicWrite { ... } を呼ぶ。
- ✅ 単体テスト容易、責任境界が明確、再利用可能。
- ❌ 新規ファイル 2 本 + cinterop def 拡張。pre-v1 の "削除 > 抽象" 方針 (CLAUDE.md) に対しやや重い。

### Option C: ハイブリッド (推奨)
- **新規 `kolt/concurrency/ProjectLock.kt`** — flock は既存パターンに収まらない真の新規プリミティブで、kotlin-result ベースのエラー型 (`LockError.HeldByPeer / TimedOut / IoError`) と timeout 制御を 1 ファイルに閉じ込めると単体テストがクリーン。
- **`Downloader.kt` を inline retrofit** — temp path 生成 + rename 切替は同一関数内の 5-10 行差分で済み、新規ファイル化はオーバーキル。`cleanupStaleTemps(cacheDir)` だけを Downloader 末尾に private で同居。
- **`BuildCommands.kt` の 3 エントリ (`doBuild` / `doNativeBuild` / `doTest`) で lock 獲得 / 解放を wrap**。ロック対象パスは `build/.kolt.lock` (後述の Research Needed)。
- ✅ flock の lifecycle テストを isolate しつつ、Downloader 改修は最小差分。
- ❌ ファイル増は 1 本のみ。allow.

**推奨: Option C**

## Research Needed (design phase に持ち越し)

1. **POSIX `rename(2)` の cinterop 公開確認** — `platform.posix.rename` が呼べるか、または `cinterop.def` 拡張が要るか。10 分の spike で確定。
2. **`flock(2)` の cinterop 経路** — `platform.posix.flock` の有無を確認、無ければ追加。`LOCK_EX | LOCK_NB` 定数も同様。
3. **タイムアウト実装方式** — `flock(LOCK_EX|LOCK_NB)` を 100ms 間隔で polling して上限到達で TimedOut を返す方式 vs `alarm(2) + signal handler + flock(LOCK_EX)` 方式。前者は実装単純で fairness を損なうが、本 spec の用途では peer 完了が短期 (秒オーダー) のはずなので前者推奨。設計時に決定。
4. **lock ファイルパス** — `build/.kolt.lock` か `build/.lock` か。`build/` は `.gitignore` 対象前提だが、衝突する既存ファイル名がないか design 時に確認 (`build/<name>.jar` / `build/<name>-runtime.classpath` などとぶつからないこと)。
5. **temp ファイル命名** — `*.jar.tmp.<pid>` 案だが `<pid>` 衝突 (PID reuse) 対策で `<pid>-<random>` などに拡張する余地あり。設計時に決定。
6. **multi-process native テスト戦略** — `posix_spawn` で kolt.kexe を子起動する方式 vs テスト内で `fork(2)` + 子プロセス内で flock 取得を再現する方式。後者なら Process.kt 既存パターンを流用しやすい。design phase で test 戦略を確定。
7. **`kolt deps install` も lock 取得対象に含めるか** — Req 1 #2 の AC で `kolt.lock` rewrite を保護対象としているが、`deps install` が `doBuild` を経由しないなら独立に wrap が要る。BuildCommands.kt 周辺の確認待ち。

## Effort & Risk

- **Effort: M (3-7 日)**
  - Day 1: cinterop def 拡張 + ProjectLock.kt + 単体テスト (1 プロセス内 fd 競合)
  - Day 2-3: BuildCommands wrap + 統合テスト (実 2 プロセス、fork ベース)
  - Day 4: Downloader retrofit + cleanup sweep + 単体テスト
  - Day 5: ADR 0029 起草 + dogfood (kolt 自身の build で 2 端末併走)
  - Day 6-7: バッファ (Research Needed の解消、レビュー差し戻し)
- **Risk: Medium**
  - 真に新規の POSIX 同期プリミティブ導入 (flock cinterop + timeout 設計) で EINTR / EAGAIN ハンドリングを丁寧にやる必要。kotlin-result ベースで例外を throw しない縛りも踏まえて Result マッピングが地味に煩雑。
  - multi-process native テストが既存になく、安定的にフレークしないテストハーネスを書く部分が見えづらい (timing-sensitive)。
  - rename(2) の cinterop 確認結果次第で 1 日の差分が出うる。

## 設計フェーズへの持ち送り

- **推奨アプローチ: Option C (Hybrid)** — `kolt/concurrency/ProjectLock.kt` 新設 + `Downloader.kt` inline retrofit + `BuildCommands.kt` 3 エントリ wrap + ADR 0029。
- **設計で確定すべき決定事項**:
  - lock ファイルパスと temp 命名規約 (上記 Research 4, 5)
  - timeout 実装方式 (上記 Research 3)
  - multi-process test 戦略 (上記 Research 6)
  - `kolt deps install` を critical section 内に含めるか (上記 Research 7)
- **継続テーマ**: ADR 0016 (warm JVM daemon) §3-§5 で既述の「daemon socket bind 排他は OS 任せ」を ADR 0029 から参照するクロスリンクを残す。

---

## Design Phase 追加調査と決定 (2026-04-26)

### cinterop 確認結果

konan 2.3.20 同梱の `platformDef/linux_x64/` を直接検証した結果:

- **`platform.posix.rename`** ✓ — `posix.def` の headers に `stdio.h` が含まれ、`rename(2)` シンボルは excludedFunctions リストにない。新規 cinterop 不要。
- **`platform.linux.flock`** ✓ — `linux.def` の headers に `sys/file.h` が含まれ、`flock(2)` シンボルおよび `LOCK_EX` / `LOCK_NB` / `LOCK_UN` 定数が利用可能。`platform.linux` 名前空間からのインポートが必要 (`platform.posix` ではない)。
- **`platform.posix.getpid`** ✓ — `unistd.h` 経由で利用可能、temp path 命名に使用。

linuxX64-only target という前提での確認。macOS / linuxArm64 追加時 (#82 / #83) は `platform.linux` → `platform.darwin` 等の分岐が必要だが、本 spec では Out of Boundary。

### Research Needed の決着

| # | 項目 | 決定 |
|---|---|---|
| 1 | rename(2) cinterop | `platform.posix.rename` 利用 (上記確認済) |
| 2 | flock(2) cinterop | `platform.linux.flock` 利用 (上記確認済) |
| 3 | timeout 実装 | `flock(LOCK_EX\|LOCK_NB)` を 100ms 間隔で polling し 30s 上限。`alarm(2) + signal handler` 方式は Kotlin/Native runtime の signal 取り扱いとの干渉リスクで採らない |
| 4 | lock ファイルパス | `build/.kolt-build.lock`。`build/` は kolt しか書かない領域、ドット prefix で隠しファイル化、`-build` で将来の他 lock との衝突を予防 |
| 5 | temp 命名 | `<destPath>.tmp.<pid>` (PID 単独)。PID は OS が current run 中に一意保証するため衝突なし。reuse は別世代の run でしか起きず、その時点で前 run の残骸は `cleanupStaleTemps` の 24h 判定で除去される |
| 6 | multi-process test 戦略 | 単体テストは in-process 二重 fd で flock semantics を検証 (Linux の flock は per-OFD なので別 open() で別 lock owner)。統合テスト `ConcurrentBuildIT.kt` は既存 `executeCommand` (fork+execvp+waitpid) パターンで 2 プロセス spawn |
| 7 | deps install の wrap | `DependencyResolution.kt:107` の LOCK_FILE rewrite を踏むのは `doAdd` / `doInstall` / `doUpdate` (`DependencyCommands.kt:124-365`)。各 entry で `ProjectLock.acquire(...).use { ... }` で wrap する |

### Synthesis 決定

- **Option C (Hybrid) を確定採用**。設計に反映済み: `concurrency/ProjectLock.kt` 新設 + `Downloader.kt` inline retrofit + `BuildCommands.kt` / `DependencyCommands.kt` の 7 entry wrap + ADR 0029。
- **Build-vs-adopt**: flock wrapper は self-build (Kotlin/Native の標準ライブラリには POSIX file lock の高水準抽象がない)。kotlin-result の AutoCloseable 連携は既存 `UnixSocket.kt` のパターンを踏襲。
- **Simplification**: ADR 構造を ADR 0016 (warm daemon) と並行構造で書く — Summary 5-7 bullet → Status → Context → Decision → Consequences。新規 ADR フォーマットの導入はしない。

### Open Risk

- ConcurrentBuildIT.kt の timing-sensitive flake — 先発プロセスが lock を握る前に後発が起動するレースを排するため、先発に "lock acquired" を stdout 出力させて後発はそれを待ってから launch する handshake が必要。design ではテスト戦略を残し、実装時に handshake を追加。
