# Research & Design Decisions

## Summary
- **Feature**: `426-native-resolver-pom-fallback`
- **Discovery Scope**: Extension (light discovery)
- **Key Findings**:
  - 既存の native resolver (`NativeResolver.kt:271 fetchAndRead`) は `.module` の HTTP 404 を含むすべての download 失敗を `ResolveError.DownloadFailed` で hard fail する。`.pom` フォールバックの分岐は無い。
  - `RepositoryDownloadFailure.AllAttemptsFailed` は per-repository の `RepositoryAttempt(url, DownloadError)` を保持しており、すべての attempt が `DownloadError.HttpFailed(404)` かどうかを構造的に判定できる。 transient (5xx / network) と 404 の区別は既存の error 型で十分。
  - 必要な primitive はすべて既存: `buildPomCachePath`, `buildPomDownloadUrl` (`Dependency.kt:55-58`), `downloadFromRepositories` (`TransitiveResolver.kt:84`), `parsePom` / `PomInfo` (`PomParser.kt:20-32`)。 新規外部ライブラリ不要。
  - `DependencyNode.direct: Boolean` フラグが kernel から materialization まで保持されているので、direct vs transitive の区別は materialization loop で素直に取れる。
  - ADR 0011 §4 が解消した `kotlin-stdlib-common` のケースは、本仕様の構造的検出によって論理的に subsume される (`.module` 404 → `.pom` 200 → JVM 専用扱い)。 ADR 0011 の `kotlin-stdlib` skip は同型ではない (link-time bundling 問題) ので残す。

## Research Log

### `fetchAndRead` の error 表現と 404 検出
- **Context**: 構造的に "all repos returned 404" を判定したい。 既存 error 型でできるか?
- **Sources Consulted**: `src/nativeMain/kotlin/kolt/resolve/Resolver.kt:13-65`, `TransitiveResolver.kt:84-107`, `kolt.infra.DownloadError`.
- **Findings**:
  - `downloadFromRepositories` は 404 のみ次の repo へ fall-through し、 非-404 (HttpFailed status != 404, NetworkError, WriteFailed) は即時 `AllAttemptsFailed(attempts)` で停止する。
  - すべての attempt が `DownloadError.HttpFailed(statusCode = 404)` であれば「真に未公開」と判定できる。 1 つでも非-404 が混ざれば transient と扱うべき。
  - `ResolveError.DownloadFailed` は `groupArtifact` と `RepositoryDownloadFailure` を保持し、構造を保ったまま伝播する。
- **Implications**: 構造的検出は inline helper で書ける。 新規 error 型は不要。

### `.pom` URL / cache path の既存 builder
- **Context**: native resolver が `.pom` フォールバックを発行できるか?
- **Sources Consulted**: `Dependency.kt:54-58`, `TransitiveResolver.kt:122-138 (createPomLookup)`.
- **Findings**:
  - `buildPomDownloadUrl(coord, baseUrl)` と `buildPomCachePath(coord)` は既存 API として export 済み。
  - `createPomLookup` が POM-based JVM transitive で既にこの primitive を使っており、 native 側もそのまま借用できる。
- **Implications**: native resolver から URL / cache path を再合成する必要は無い。

### `.pom` の中身を読むか、 存在確認のみで済ますか
- **Context**: フォールバックで `.pom` の deps を BFS に流すか、 存在確認だけで JVM-only と断定するか。
- **Sources Consulted**: 要件 (Layer 1 only)、 ADR 0011 §4 の構造的根拠、 ADR 0010 (gradle metadata for native)。
- **Findings**:
  - `.pom` を parse して deps を BFS に流すと、 POM-listed deps の中に native variant が無いものも紛れ込み、 連鎖的に同じ 404 を踏む可能性がある。 これは Layer 2 (variant 解釈の audit) のスコープ。
  - 構造的観察: `.module` が 404 で `.pom` が 200 = 「Gradle Module Metadata が公開されていない artifact」= konanc がリンクできる klib を持たない = native 文脈では skip して安全。
  - kotlin-stdlib-common (ADR 0011 §4) も同じ判断で扱われている。
- **Implications**: 本仕様では `.pom` の存在確認のみで判定する。 deps の descent は行わない (空の children を返す)。

### 既存 `isKotlinStdlib` の扱い
- **Context**: ADR 0011 の hardcoded skip リストとどう共存するか。
- **Sources Consulted**: `NativeResolver.kt:18-20`, ADR 0011 §1-§4.
- **Findings**:
  - `kotlin-stdlib` は `.module` を公開しているが konanc が同梱 stdlib と double-link するため skip する。 構造的検出では救えない (リンクが衝突するのを構造から知る手段が無い)。
  - `kotlin-stdlib-common` は `.module` 未公開のため、 構造的検出で同じ動作になる。 ただし ADR 0011 §4 と silent skip ポリシー (毎回ビルドで発火するため noise を出さない) を守るために、 名前ベースの先行 short-circuit を維持し、 diagnostic を抑制する。
  - 構造的検出によって、 将来 JVM 専用の kotlin artifact (`kotlin-reflect`, `kotlin-stdlib-jdk8` 等) が transitive に現れても hardcoded リスト追加無しで処理できる。
- **Implications**: `isKotlinStdlib` predicate は予約しつつ silent skip + diagnostic 抑制の anchor として再用する。

### 直接依存と transitive の区別
- **Context**: 直接依存に JVM 専用 lib が書かれた場合は error を維持したい (Req 2)。
- **Sources Consulted**: `Resolution.kt:8-13 (DependencyNode)`, `NativeResolver.kt:80-127 (materialization loop)`.
- **Findings**:
  - `DependencyNode.direct: Boolean` が kernel 内で保持され、 materialization loop でも `node.direct` として参照可能。
  - direct dep の場合は `NoNativeVariant` 系の error を返せばユーザーに「この lib は native に置けない」と伝えられる。
- **Implications**: direct 判定は materialization loop で行う。 fetch 経路には direct/transitive コンテキストを渡さなくて済む。

## Architecture Pattern Evaluation

| Option | Description | Strengths | Risks / Limitations | Notes |
|--------|-------------|-----------|---------------------|-------|
| A. Hardcoded skip 拡張 (`kotlin-reflect` を `isKotlinStdlib` に追加) | 2-name リストを 3-name にする | 最小コード変更 | 次の JVM 専用 artifact が transitive に現れる度にリスト更新が必要、 ADR 0011 の "blanket skip 拒否" alternative と矛盾しない範囲で済むが場当たり | 採用しない |
| B. 構造的検出 + JVM-only marker | `.module` 404 を検出して `NativeResolved` の sealed variant `JvmOnly` を導入 | 一般化されており未来の JVM 専用 artifact もカバー、 ADR 0011 §4 を subsume | sealed class 追加で resolver の internal data 構造を 1 段変える | **採用** |
| C. `.pom` を parse して deps を BFS に流す | Maven 互換 fallback | POM-only artifact の deps を native target に取り込める...かもしれない | POM には variant が無い、 deps の中の JVM-only artifact が再帰的に同じ問題を踏む、 Layer 2 のスコープに踏み込む | 採用しない |
| D. 上位で kotlin namespace を blanket skip | `org.jetbrains.kotlin:*` 全部 skip | 単純 | ADR 0011 で既に却下、 kotlin-test 等 native variant のある artifact を取りこぼす | 採用しない |

## Design Decisions

### Decision: `.module` 404 を JVM-only marker への構造的分岐とする
- **Context**: 要件 1 / 3 — transitive の `.module` 404 に対する fallback と hardcoded リストの subsume。
- **Alternatives Considered**:
  1. Option A — 名前 skip リストを拡張
  2. Option B — 構造的検出 (`.module` 404 + `.pom` 200 → JvmOnly)
  3. Option C — `.pom` を parse して deps descent
- **Selected Approach**: Option B。 `fetchNativeMetadata` の root `.module` フェッチが 404 で完全失敗 (`AllAttemptsFailed` のすべての attempt が `DownloadError.HttpFailed(404)`) した場合、 同じ coordinate の `.pom` フェッチを試行。 `.pom` が成功すれば `NativeResolved.JvmOnly(coord)` を返す。 `.pom` も失敗すれば元の `DownloadFailed` をそのまま返す。
- **Rationale**: ADR 0011 §4 の根拠 ("POM-only artifact は native variant を持たない") を一般化したもの。 hardcoded リストの肥大化を避け、 既存 `isKotlinStdlib` の役割を「link-time bundling 問題」に絞れる。
- **Trade-offs**:
  - (+) 一般化 — 未来の JVM 専用 kotlin artifact がリスト追加無しで通る。
  - (+) ADR 0011 と整合 — 同じ構造的根拠を共有。
  - (−) sealed variant の追加で resolver internal の data 表現が広がる。
  - (−) `.pom` が transient 5xx を返した場合は `DownloadFailed` を返すが、 数秒後に retry すれば成功するかもしれないというユーザー体験は変わらない。
- **Follow-up**: 実装後に ADR 0011 を更新 (`kotlin-stdlib-common` 部分が構造的に subsume されたことを記録)。 本仕様では必須範囲外。

### Decision: 直接依存は `NoNativeVariant` で error を維持
- **Context**: 要件 2 — 直接依存に JVM 専用 lib を書いた場合は silent skip しない。
- **Alternatives Considered**:
  1. Direct dep でも silent skip
  2. Direct dep は `NoNativeVariant` で error (既存 error 型を再利用)
  3. 新規 error 型 `JvmOnlyDirectDep` を導入
- **Selected Approach**: Option 2。 materialization loop で `node.direct && resolved is JvmOnly` の場合 `ResolveError.NoNativeVariant(groupArtifact, nativeTarget)` を返す。
- **Rationale**: `NoNativeVariant` のセマンティクスは「この artifact は target に対応する native variant を持たない」で、 JVM-only artifact は構造的にその条件を満たす。 新規 error 型は冗長。
- **Trade-offs**:
  - (+) 既存 error 型 / formatter / message を再利用、 ユーザーには既存の言い回しで届く。
  - (−) `NoNativeVariant` の発火条件が 2 つ (root `.module` で linux_x64 variant 無し / `.module` 自体が 404) になるので、 内部 invariant の整理が必要。 ただしユーザー観測は同じ。

### Decision: Diagnostic 出力は ProgressSink を拡張せず stderr 直書き
- **Context**: 要件 4 — transitive skip の観測可能性。
- **Alternatives Considered**:
  1. `ResolverProgressSink` に `onJvmOnlySkip` を追加
  2. `eprintln` で resolver 内から直接 stderr に書く
  3. `ResolveResult` に skipped 一覧フィールドを追加して CLI 側でレンダリング
- **Selected Approach**: Option 2。 `NativeResolver.kt` 内で `eprintln("note: ...")` を skip 直前に発火。 `kotlin-stdlib` / `kotlin-stdlib-common` は名前で先行 short-circuit (silent)。
- **Rationale**: ProgressSink は per-artifact の進行ベース (start/retry) で skip は意味的に異なる。 `ResolveResult` 拡張は API 表面が大きく、 単発 note の伝送だけのために重い。 既存の eprintln 経路 (`infra/output` 配下) と整合。
- **Trade-offs**:
  - (+) 最小実装、 既存の stderr 経路を踏襲。
  - (−) diagnostic を抑制したいテストでは stderr を capture する必要がある (NativeResolverTest の既存 fixture で対応可)。

## Risks & Mitigations
- **Risk**: `.pom` が transient 5xx を返したケースで「JVM 専用 artifact ではないが downstream に進めない」状態になる。 ⇒ **Mitigation**: 5xx / NetworkError は構造的検出の前提条件 (`all 404`) に含まれないので、 既存の `DownloadFailed` 経路で報告される。 ユーザーは retry すれば通常通り解決できる。
- **Risk**: `.module` 404 + `.pom` 200 となるが実際には Gradle metadata 公開を忘れただけの "真の native artifact" を skip してしまう。 ⇒ **Mitigation**: 直接依存は `NoNativeVariant` で error になるためユーザーが気づく。 transitive で skip された場合は stderr の note で観測可能。 後段のリンクで unresolved 参照が出れば link error として顕在化する。
- **Risk**: 既存テストで `.module` 404 を fatal として assert しているケースが壊れる。 ⇒ **Mitigation**: 既存 `NativeResolverTest` の該当ケースを「`.pom` 同時 404 / `.pom` 200 でフォールバック成功」両方の variant でリプレースする。

## References
- ADR 0010 — Gradle Module Metadata for native resolution (本仕様が拡張する経路)
- ADR 0011 — Skip kotlin-stdlib for native resolution (本仕様が一般化する論理の起点)
- issue #426 — 報告された再現ケース
- `src/nativeMain/kotlin/kolt/resolve/NativeResolver.kt:18-297` — 改修対象
- `src/nativeMain/kotlin/kolt/resolve/TransitiveResolver.kt:84-107` — `downloadFromRepositories` の 404 fall-through ポリシー (共有挙動)
- `src/nativeMain/kotlin/kolt/resolve/PomParser.kt` — `.pom` 解析 (本仕様では存在確認のみ使用)
