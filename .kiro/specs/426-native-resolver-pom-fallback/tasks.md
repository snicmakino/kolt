# Implementation Plan

> 進行方針: 各 sub-task は TDD (Red → Green → Refactor) を 1 実装者の 1 commit 単位で完結させる (memory `feedback_tdd_red_green_atomic.md`)。
>
> `(P)` マーカーは付けない: すべての production 変更は `NativeResolver.kt` 1 ファイルに集中し、 各 sub-task のテストも同一テストクラス群を触るため、 並行実行で merge conflict / interleaving のメリットが無い。 単独実装者でシーケンシャルに進める。

- [ ] 1. Foundation: `NativeResolved` を sealed class へ refactor
- [x] 1.1 既存の `data class NativeResolved(redirect, artifact)` を `private sealed class NativeResolved` に変換し、 既存内容を `Klib(redirect, artifact)` variant に移植する
  - 変換は behaviour-preserving。 新規 variant (`JvmOnly`) はこの task では追加しない。
  - すべての file-local 参照 (`processed: MutableMap<String, NativeResolved>`, `makeNativeChildLookup`, `resolveNative` の materialization loop, `createNativeLookup`) を `Klib` variant に対する分岐に書き換える。
  - 観測: 既存の `NativeResolverTest`, `ResolveNativeProgressTest`, `NativeResolverJvmOnlyFallbackTest` 以外のテスト群すべてが緑 (`kolt test`)、 既存挙動に変化なし。
  - _Requirements: 1.2, 2.2, 3.1_

- [ ] 2. Core: 構造的 404 判定 helper を追加
- [x] 2.1 `is404OnAllAttempts` (file-local) を実装し、 `ResolveError.DownloadFailed` が「全 attempt が 404」状態かを判定する
  - 真理表: (a) `AllAttemptsFailed` で全 `HttpFailed(statusCode=404)` → true、 (b) 1 つ以上の `HttpFailed(statusCode != 404)` 混入 → false、 (c) 1 つ以上の `NetworkError` / `WriteFailed` 混入 → false、 (d) `NoRepositoriesConfigured` → false、 (e) `attempts` が空 → false、 (f) `DownloadFailed` 以外の `ResolveError` → false。
  - 観測: helper の真理表 6 ケースすべてが unit test で緑。 既存テストすべて緑。
  - _Requirements: 1.4_

- [ ] 3. Core: `fetchNativeMetadata` の root `.module` 404 → `.pom` フォールバック
- [x] 3.1 root `.module` の fetch failure が `is404OnAllAttempts` を満たすとき、 同一 coordinate の `.pom` を取得して `Ok(NativeResolved.JvmOnly(coord))` を返す経路を追加する
  - `.pom` の URL / cache path は既存 `buildPomDownloadUrl` / `buildPomCachePath` を再利用。 `.pom` の中身は parse しない (存在確認のみ)。
  - mock `ResolverDeps` で `.module` 404 / `.pom` 200 のシナリオを再現する unit test を追加し、 戻り値が `NativeResolved.JvmOnly` であることを assert。
  - 観測: unit test が `Ok(JvmOnly(coord))` を assert し緑。 既存の Klib 経路テストすべて緑。
  - _Requirements: 1.1, 1.2_

- [x] 3.2 `.module` 404 + `.pom` も失敗 (404 / 5xx 等) のとき、 **元の `.module` の `DownloadFailed`** を返す
  - 返す error は `.module` 取得時の `RepositoryAttempt` 一覧を保持していること (ユーザーに `.module` の attempts dump が見える)。 `.pom` の attempts は捨てる。
  - 観測: `.module` 404 + `.pom` 404 の unit test で error が `.module` の attempts を保持していることを assert。 typo / 真の missing artifact を masking しない。
  - _Requirements: 1.3_

- [x] 3.3 root `.module` の failure が `is404OnAllAttempts` を満たさないとき (5xx / NetworkError / WriteFailed) は `.pom` fallback を発火させず、 既存の `DownloadFailed` 経路で error する
  - mock `ResolverDeps` の `downloadFile` 呼び出し回数を recorder で記録し、 `.pom` URL に対する download 試行が無いことを assert。
  - 観測: 5xx / NetworkError シナリオで `.pom` fetch が 0 回、 戻り値が既存通り `Err(DownloadFailed(..., attempts containing 5xx/network))`。
  - _Requirements: 1.4_

- [ ] 4. Core: childLookup の variant 分岐
- [x] 4.1 `makeNativeChildLookup` を `NativeResolved` の when 分岐に書き換え、 `Klib` は既存通り `artifact.dependencies` (minus stdlib*) を返し、 `JvmOnly` は `Ok(emptyList())` を返す
  - JvmOnly node の `processed` キャッシュは Klib と同じく GA:version キーで記録される (kernel 側からは透過)。
  - 観測: childLookup を直接叩く unit test、 もしくは fetchNativeMetadata 経由の integration で JvmOnly node から children が出ないことを assert。 既存 Klib のテストすべて緑。
  - _Requirements: 1.2, 3.1_

- [ ] 5. Core: materialization loop の variant 分岐
- [x] 5.1 transitive JvmOnly node が非 stdlib のとき、 stderr に 1 行 note (`note: <ga>:<v> has no Gradle Module Metadata; skipping for native target`) を出して `resolvedDeps` に含めずスキップする
  - eprintln 経路は既存の `kolt.infra.output` の stderr 出口を踏襲。
  - ADR 0011 §4 を引く short anchor comment を skip 直前に 1 行入れる (`structure.md` の "ADR citations in code" に従う)。
  - 観測: 非 stdlib coordinate (例: `org.example:fake-jvm-only`) を transitive に含む resolve で stderr に該当 note 1 行が出力され、 `resolvedDeps` に含まれていないこと。
  - _Requirements: 4.1_

- [x] 5.2 transitive JvmOnly node が `kotlin-stdlib` / `kotlin-stdlib-common` のとき、 silent skip (note を出さない) を維持する
  - 既存 `isKotlinStdlib` predicate を skip 直前で short-circuit に使う。
  - 観測: transitive 上に `kotlin-stdlib-common` がある resolve で `resolvedDeps` に含まれず、 stderr に note 行が出ない (capture して空であること) こと。
  - _Requirements: 4.2_

- [x] 5.3 direct dep が JvmOnly に倒れた場合、 `ResolveError.NoNativeVariant(groupArtifact, nativeTarget)` を返して非ゼロ終了させる
  - materialization loop の最初の分岐で `node.direct && resolved is JvmOnly` を判定。 stderr note は出さない (direct dep skip は既存挙動と整合)。
  - direct `kotlin-stdlib` / `kotlin-stdlib-common` の既存 silent skip 経路 (`directDeps = filterKeys { !isKotlinStdlib(it) }`, `NativeResolver.kt:54`) は触らない。
  - 観測: `[dependencies]` に `.module` 404 + `.pom` 200 の coordinate を直接書いた integration test で `Err(NoNativeVariant)` が返り、 既存の formatter (`Resolver.kt:93-98`) のメッセージが stderr に出ること。
  - _Requirements: 2.1, 2.2, 4.3_

- [x] 5.4 klib pre-count (`total`) および per-artifact progress (`onArtifactStart`) から JvmOnly node を除外する
  - 既存の `total` 計算 (`NativeResolver.kt:71-77`) のフィルタ条件に variant チェックを追加。 主 loop の `index += 1` も Klib に限定。
  - 観測: transitive JvmOnly 1 個 + Klib 2 個の resolve で、 `RecordingResolverProgressSink` が `onArtifactStart` 通知を 2 回しか受けないこと。 `M/N` 表示が JvmOnly を膨らませないこと。
  - _Requirements: 1.2, 4.3_

- [ ] 6. Core: deps tree lookup の JvmOnly 対応
- [x] 6.1 `createNativeLookup` (deps tree 用) を JvmOnly variant に対応させる
  - JvmOnly node に対して `NativeNodeInfo(displayGroupArtifact = "${rootCoord.group}:${rootCoord.artifact}", displayVersion = rootCoord.version, dependencies = emptyList())` を返す。
  - tree 表示で JvmOnly が leaf として現れるが、 UI の "JVM 専用" ラベル付与は本仕様の対象外 (out of boundary)。
  - 観測: createNativeLookup の unit test で JvmOnly node が空 dependencies と root coordinate を持つ `NativeNodeInfo` を返すこと。
  - _Requirements: 1.2_

- [ ] 7. Integration: 再現ケース regression test
- [x] 7.1 `NativeResolverJvmOnlyFallbackTest` を新規追加し、 報告された repro (transitive 上に `.module` 404 + `.pom` 200 の artifact が現れる) を決定的 fixture で再現する
  - mock `ResolverDeps` を組み、 parent 相当の coordinate は `.module` 200 を返して native variant + transitive children を持ち、 transitive 子の 1 つは `.module` 404 + `.pom` 200 を返す構造を用意する。
  - 検証 1: resolve 全体が `Ok` を返す (abort しない)。
  - 検証 2: JvmOnly 子の coordinate が `resolvedDeps` に含まれない。
  - 検証 3: stderr capture に該当 coordinate の note 行が 1 行ある。
  - 検証 4: テストコードが `isKotlinStdlib` の予約名リストや新規 skip リストに該当 coordinate を追加していないこと (構造的 fallback のみで通ること)。
  - 検証 5: fixture は live Maven Central / `repo1.maven.org` を踏まないこと (offline で run できる)。
  - _Requirements: 3.3, 6.1, 6.2, 6.3_

- [ ] 8. Validation: 既存テストの regression と JVM 経路の不変確認
- [x] 8.1 既存の `NativeResolverTest` 内で `kotlin-stdlib-common` が transitive 上で扱われる経路を構造的 fallback (新分岐) で通すように調整し、 silent skip 維持を assert する
  - 既存テストが「`.module` 404 を fatal として assert」していた場合は「fallback 後の Ok」に書き換える。 既存の `isKotlinStdlib` 名前 short-circuit が silent path として残ること (stderr に note が出ないこと) を assert に追加する。
  - direct `kotlin-stdlib` 経路 (`directDeps = filterKeys { !isKotlinStdlib(it) }`) が無傷であること (Req 2.3) も既存テストで担保されている前提を確認、 もし無ければ 1 ケース追加する。
  - 観測: `NativeResolverTest` の全ケースが緑、 `kotlin-stdlib-common` transitive シナリオで stderr 空。
  - _Requirements: 2.3, 3.1, 3.2_

- [x] 8.2 JVM 経路 (`TransitiveResolver` 系) を含む全テストスイートを `kolt test` で実行し、 緑であることを確認する
  - 検証: ルートで `kolt test`、 `kolt-jvm-compiler-daemon` で `kolt test`、 `kolt-native-compiler-daemon` で `kolt test` の 3 つすべて exit 0。
  - 検証: `TransitiveResolver.kt` / `Resolver.kt` の diff が 0 行 (`git diff src/nativeMain/kotlin/kolt/resolve/TransitiveResolver.kt` / `Resolver.kt`)。
  - 観測: スイート全体グリーン + 該当ファイル diff 空。
  - _Requirements: 5.1, 5.2, 5.3_

- [x] 8.3 target `.module` (redirect 後) の 404 は Layer 1 の対象外であり、 既存通り `DownloadFailed` で fail することを golden test で固定する
  - 目的: design.md の Out of Boundary "target `.module` (redirect 先) の 404 ハンドリング" を test で明示的に押さえ、 Layer 2 着手時に root と target の挙動差が意図的であることを差分として可視化する。
  - 構築: mock `ResolverDeps` で root `.module` 200 (linux_x64 variant が `available-at` redirect を宣言) → redirect 解析 OK → target `.module` 404 のシナリオ。 `.pom` (target coord) はテスト側で download されないことを recorder で確認 (fallback が発火していないこと)。
  - 検証 1: 戻り値が `Err(ResolveError.DownloadFailed)` であり、 attempts が target coordinate の URL を保持していること。
  - 検証 2: mock の `downloadFile` 呼び出し履歴に target `.module` の URL は記録されるが、 target `.pom` の URL は記録されないこと (Layer 1 の fallback は root `.module` 限定 = boundary の確認)。
  - 観測: test が `Err(DownloadFailed)` を assert し緑、 target `.pom` への download 試行が 0 回。
  - _Requirements: 1.1, 1.4_

## Implementation Notes

- Pre-commit hook runs `kolt fmt --check`. Implementer must run `kolt fmt` before declaring READY_FOR_REVIEW or the parent's `git commit` will fail. Discovered on task 1.1.
- Task 3.1 introduced three placeholder comments with task references (`task 5.x`, `task 4.1`, `task 6.1`) at `NativeResolver.kt` lines 101, 162, 250. Memory `feedback_no_task_refs_in_comments.md` forbids these. Task 4.1 removed the `task 4.1` reference; remaining two are expected to be naturally replaced by tasks 5.x and 6.1 when the real implementation lands. If any remain after task 6.1, do a final sweep before task 8.x.
