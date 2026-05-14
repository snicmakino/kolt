# Implementation Plan

> **進め方**: TDD (Red→Green→Refactor) で各 sub-task を進める。 commit 列は読みやすさで切り、 Red/Green/Refactor の履歴を残す必要はない ([[feedback_tdd_commit_granularity]])。
> **Self-host**: kolt の 4 つの `kolt.toml` (`/kolt.toml`, `/kolt-jvm-compiler-daemon/kolt.toml`, `/kolt-native-compiler-daemon/kolt.toml`, `/spike/native-ktor-cinterop-smoke/kolt.toml`) はすべて `[repositories.central]` sub-table 形で、 新 validator のホットパスを通る。 タスク完了の最終ゲートとして CI `self-host-post` job の green を要求する (7.3)。

## 1. Foundation: new types and utilities

- [x] 1.1 (P) `RepositoryAuth` ADT と `AuthStateProjection` を追加
  - `src/nativeMain/kotlin/kolt/config/RepositoryAuth.kt` を新規作成: sealed class `RepositoryAuth`、 `Bearer(token: String)` / `Basic(user: String, password: String)` を data class で持ち、 各 variant に `toString()` override を実装して secret を `<redacted>` に置換
  - `RepositoryAuth.toHeaders(): Map<String, String>` を実装し、 Bearer は `mapOf("Authorization" to "Bearer $token")`、 Basic は `mapOf("Authorization" to "Basic ${base64Encode("$user:$password")}")` を返す
  - `src/nativeMain/kotlin/kolt/resolve/AuthStateProjection.kt` を新規作成: sealed class + 3 data object (`NotConfigured` / `ConfiguredToken` / `ConfiguredBasic`)、 `toDisplayString()` で要件 7.3 の 3 文字列を所有 (`kolt.resolve` 配置で renderer 側からの import が自然に閉じる)
  - `RepositoryAuth?.toStateProjection()` 拡張関数で nullable RepositoryAuth から AuthStateProjection への射影を行う (両ファイルのいずれかで定義)
  - 観察可能な完了: `RepositoryAuth.Basic("alice","s3cret").toString()` が `"<redacted>"` を含み `"s3cret"` を含まないことを assert する単体 test が green
  - _Requirements: 1.5, 5.1, 5.2, 7.3, 8.1, 8.2_
  - _Boundary: kolt.config.RepositoryAuth, kolt.resolve.AuthStateProjection_

- [x] 1.2 (P) `RawCredentialField` と uniform inline-table custom serializer を追加
  - `RawCredentialField` を sealed class + `Literal(value)` / `Env(varName)` 2 variants で導入
  - `RawCredentialFieldShape(literal: String? = null, env: String? = null)` を all-nullable fields trick で構築、 `RawCredentialFieldSerializer` を `SysPropValueSerializer` と同型の custom KSerializer で実装し、 `setFields.size != 1` のとき `SerializationException` を throw
  - `serialize` も `SysPropValueSerializer.kt:36-44` と同型の真面目な実装にする (error("...") にしない)
  - 観察可能な完了: 単体 test で `token = { literal = "abc" }` が `Literal("abc")` に decode、 `token = { env = "X" }` が `Env("X")` に decode、 `token = { literal = "a", env = "X" }` と `token = {}` の双方で SerializationException が出る
  - _Requirements: 1.1, 1.4, 1.6, 1.7, 2.3_
  - _Boundary: kolt.config.RawCredentialField_

- [x] 1.3 (P) `redactUrlUserinfo` shared utility と `UrlRedactionTest` を追加
  - `kolt.infra.redactUrlUserinfo(url: String): String` を実装、 `://` と `@` の位置関係 (`@` がパス区切り `/?#` より前にあること) で userinfo を検出して除去
  - 5 ケース matrix test: (a) `https://host/path` 不変、 (b) `https://u:p@host/path` → userinfo 除去、 (c) `https://u@host/path` → userinfo 除去、 (d) `https://host/foo@bar` 不変 (path 内 `@`)、 (e) `not-a-url-at-all` 不変
  - 観察可能な完了: `UrlRedactionTest` 5 ケース全 green
  - _Requirements: 3.3, 8.3, 9.3_
  - _Boundary: kolt.infra.UrlRedaction_

## 2. Config layer

- [x] 2.1 `RawRepository` に credential field を追加
  - `RawRepository` data class に `token: RawCredentialField? = null`, `user: RawCredentialField? = null`, `password: RawCredentialField? = null` を追加
  - 既存の `RawRepository(url = MAVEN_CENTRAL_BASE)` 形 (`Config.kt:185-186` の default) との互換を維持
  - 観察可能な完了: 既存の `RepositorySchemaMigrationTest` がそのまま緑のままで、 credential field 込みの `[repositories.x] url = "..."; token = { literal = "abc" }` が decode 成功する
  - _Requirements: 1.1, 1.8_
  - _Boundary: kolt.config.RawRepository_
  - _Depends: 1.2_

- [x] 2.2 `Repository` (typed) を `name` + `auth` で拡張、 default と synthetic 構築サイトを更新
  - `Repository` data class に `val name: String` (required) と `@Transient val auth: RepositoryAuth? = null` を追加 (`@Transient` で `RepositoryAuth` の非 `@Serializable` 性と整合させる)
  - `KoltConfig.repositories` の default 値 (`Config.kt:120`) を `mapOf("central" to Repository(name = "central", url = MAVEN_CENTRAL_BASE, auth = null))` に更新
  - `BtaImplFetcher.kt:54` の synthetic `Repository(MAVEN_CENTRAL_BASE)` 呼び出しを `Repository(name = "central", url = MAVEN_CENTRAL_BASE, auth = null)` に更新
  - 観察可能な完了: `kolt build` が compile pass、 ktoml decode + 既存 lift の chain で `Repository` を組み立てる既存 test (例: `LiftRepositoriesMapTest`) が green
  - _Requirements: 4.1, 4.2_
  - _Boundary: kolt.config.Repository, kolt.build.daemon.BtaImplFetcher_
  - _Depends: 1.1_

- [x] 2.3 `mergeRepositories` を credential field merge に拡張
  - `LocalOverlay.kt:82-105` の `mergeRepositories` を更新し、 overlay の credential field (token / user / password) を base に対して last-write-wins で merge する: `baseRepo.copy(url = overlayRepo.url ?: baseRepo.url, token = overlayRepo.token ?: baseRepo.token, user = overlayRepo.user ?: baseRepo.user, password = overlayRepo.password ?: baseRepo.password)`
  - 既存の overlay-only-name rejection 動作 (#415) は変更しない
  - 観察可能な完了: 単体 test で `kolt.toml` の `[repositories.x] url = "..."` と `kolt.local.toml` の `[repositories.x] token = { literal = "abc" }` が merge 後に `RawRepository(url = "...", token = Literal("abc"))` を生成する
  - _Requirements: 2.2_
  - _Boundary: kolt.config.LocalOverlay_
  - _Depends: 2.1_

- [x] 2.4 `rejectBaseCredentialLiterals` (新規) を実装し parseConfig pre-merge に挿入
  - `parseConfig` 内の base raw decode 直後・overlay merge 前に呼び出される shape-blind validator を新規実装
  - `rawBase.repositories` を走査し、 `token` / `user` / `password` のいずれかが non-null なら `ConfigError.ParseFailed(path = basePath, message = "kolt.toml [repositories.<name>]: literal <field> field. kolt.toml is intended to be committed; declare <field> in kolt.local.toml instead.")` を返す。 message には credential 値そのものは含めない
  - `env` form / `literal` form を区別せず両方 reject (placement-policy first)
  - 観察可能な完了: 単体 test で `kolt.toml` 内 `[repositories.x] token = { literal = "abc" }` が `kolt.toml` を名指す ParseFailed で reject される、 message に `"abc"` が含まれない
  - _Requirements: 2.1, 2.4_
  - _Boundary: kolt.config.Config (parseConfig)_
  - _Depends: 2.1_

- [x] 2.5 `repositorySourceMap` helper を追加
  - `sysPropSourceMap` (`Config.kt:398-414`) と同型の helper を新規実装、 戻り値型 `Map<repoName, Map<fieldName, String?>>`
  - inner key 集合は `"url" / "token" / "user" / "password"` の 4 種固定、 base 由来 = `basePath`、 overlay 由来 = `overlayPath` (overlay last-write-wins)、 未設定 fields は inner map に key absent
  - 観察可能な完了: 単体 test で base に `url`、 overlay に `token` がある repo について `map["repo"]["url"] == basePath`, `map["repo"]["token"] == overlayPath`, `map["repo"]["user"]` は absent
  - _Requirements: 2.2_
  - _Boundary: kolt.config.Config (repositorySourceMap)_
  - _Depends: 2.1_

- [x] 2.6 `liftRepositoriesMap` を auth 検証で拡張
  - 検証順 (fail-fast): URL 非空 (既存) → URL userinfo (`@` before path) → auth mutex (`token` ∧ (`user` ∨ `password`)) → auth pair 完全性 (`user` ⟺ `password`) → auth field 非空 (`literal` 値が empty / Unicode whitespace のみ → reject)
  - validator が field-level rejection を出すとき `repositorySourceMap` から該当 field の contributing file を引いて `ParseFailed.path` にセット
  - `Repository.name == map_key` invariant を構築時に enforce (`raw` の map key を `Repository.name` に代入)
  - 観察可能な完了: 単体 test で各 validator branch が想定された ParseFailed message を返し、 lifted map の `entry.value.name == entry.key` が全 entry で成立
  - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.8, 2.3, 3.1, 3.2, 3.3_
  - _Boundary: kolt.config.Config (liftRepositoriesMap)_
  - _Depends: 1.2, 2.1, 2.5_

- [x] 2.7 `RepositoryAuthConfigTest` (parametric Req 1 + Req 2 + Req 2.3 + name invariant) を追加
  - 単一 test ファイル内で fixture (kolt.toml + kolt.local.toml string) を共有、 data-table parametric で網羅
  - 検証マトリクス: schema accept/reject (Req 1.1-1.7、 空・whitespace `literal` reject 含む) / placement (Req 2.1 base reject、 2.2 overlay accept、 source attribution) / env-form recognize-and-reject (Req 2.3 token/user/password 各 1 ケース、 message が `kolt.local.toml` を指す) / pre-v0.20.0 backward-compat (Req 1.8: base side の `kolt.toml` `[repositories.central]` auth field なし、 overlay side の `kolt.local.toml` `[repositories.central]` auth field なしの双方で parse 成功し anonymous 扱い) / `Repository.name == map_key` invariant
  - 2-hop UX シナリオは個別 case として組まず: placement message と env-not-supported message は (g) base-placement / (k) env-reject の 2 ケースの message 差で間接的に検証される ([[feedback_sdd_test_inflation]])
  - 観察可能な完了: 上記マトリクス全 case green、 各 fixture が 1 line で記述された data-table 配列に組み込まれる
  - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7, 1.8, 2.1, 2.2, 2.3, 2.4_
  - _Boundary: kolt.config (test)_
  - _Depends: 2.4, 2.6_

- [x] 2.8 `RepositoryUrlUserinfoTest` を追加
  - 4 ケース: (a) `https://u:p@host/x` reject、 (b) `https://u@host/x` reject、 (c) `https://host/foo@bar/baz` accept (path 内 `@`)、 (d) rejection message が userinfo character (`u`, `p`, `:`, `@`) を含まない
  - 観察可能な完了: 4 ケース全 green
  - _Requirements: 3.1, 3.2, 3.3_
  - _Boundary: kolt.config (test)_
  - _Depends: 2.6_

## 3. Infra layer

- [x] 3.1 `Downloader.downloadFile` を `headers` parameter + libcurl slist + redirect policy で拡張
  - signature を `downloadFile(url: String, destPath: String, headers: Map<String, String>? = null): Result<Unit, DownloadError>` に変更
  - `headers` が non-null のとき `curl_slist_append` で `"$name: $value"` 形式のヘッダを追加し `CURLOPT_HTTPHEADER` にセット
  - `CURLOPT_UNRESTRICTED_AUTH = 0L` を明示的に設定し、 cross-origin redirect で `Authorization` ヘッダが forward されない動作を確定させる
  - `finally` block で `curl_slist_free_all` を `curl_easy_cleanup` より前に呼ぶ (anchored gotcha: upstream sample と逆の順序、 コメントで明示)
  - `DownloadError.HttpFailed(url, code)` 構築時に `redactUrlUserinfo(url)` を適用する defensive 経路
  - 観察可能な完了: `kolt build` compile pass、 既存の `DownloaderTest` 緑、 新たに `DownloaderAuthHeaderTest` が次タスクで作成可能な状態
  - _Requirements: 5.1, 5.2, 8.3_
  - _Boundary: kolt.infra.Downloader_
  - _Depends: 1.3_

- [x] 3.2 `DownloaderAuthHeaderTest` (loopback HTTP fixture server + redirect policy) を追加
  - `testfixture/LoopbackHttpServer.kt` を `UnixEchoServer` パターンで実装 (再利用可能、 anonymous + Bearer / Basic + 302 redirect target を提供する 2 ポート server)
  - 4 サブテスト: (a) `RepositoryAuth.Bearer` で `Authorization: Bearer <token>` が server に到達、 (b) `RepositoryAuth.Basic` で正しい base64 `Authorization: Basic ...` が server に到達、 (c) `headers == null` で server の access log に Authorization ヘッダの行が出現しない (空 `Authorization:` も無い、 slist 自体組まれない)、 (d) cross-origin 302 で redirect target の access log に Authorization ヘッダなし
  - libcurl resource leak の単体検査は Kotlin/Native ランタイムにヒープ計測手段がないため、 観察可能な test として組まない。 `finally` block で `curl_slist_free_all` が呼ばれる規律はコードレビューで担保 ([[feedback_sdd_test_inflation]])
  - 観察可能な完了: 4 サブテスト全 green、 loopback server access log の文字列照合で Authorization ヘッダの有無が確定する
  - _Requirements: 5.1, 5.2, 8.1, 8.3_
  - _Boundary: kolt.infra (test) + testfixture.LoopbackHttpServer_
  - _Depends: 1.1, 3.1_

## 4. Resolver layer (signature bump + Authorization propagation)

- [x] 4.1 `downloadFromRepositories` の signature 変更 + `AuthFailed` variant 追加 + 401/403 hard-error 分岐
  - signature を `(repos: List<Repository>, destPath: String, urlBuilder: (Repository) -> String, download: (String, String, Map<String, String>?) -> Result<Unit, DownloadError>, onRetry: (Repository) -> Unit = {})` に変更 (既存 5-arg shape を String → Repository に置換)
  - loop body で `repo.auth?.toHeaders()` を計算し `download(url, dest, headers)` に渡す
  - `RepositoryDownloadFailure` に `AuthFailed(repositoryName: String, url: String, statusCode: Int, authState: AuthStateProjection)` variant を追加。 `RepositoryAttempt` には `repositoryName: String` を追加 (既存 url-only から拡張)
  - `TransitiveResolver.kt:99` の既存 branch (`error.statusCode != 404`) を分岐拡張: `else if (httpFailed.statusCode == 401 || httpFailed.statusCode == 403) -> return Err(AuthFailed(repo.name, redactUrlUserinfo(httpFailed.url), httpFailed.statusCode, repo.auth.toStateProjection()))`
  - 404 fall-through と aggregated `AllAttemptsFailed` の既存動作は維持 (Req 6.3, 6.4)
  - 観察可能な完了: `kolt build` compile pass、 `downloadFromRepositories` 単体 test (loopback server fixture) で `[401-repo, 200-repo]` から 401 hard-error を返し iteration が 200-repo まで進まないことを assert
  - _Requirements: 5.1, 5.2, 5.3, 6.1, 6.2, 6.3, 6.4, 7.2, 7.3_
  - _Boundary: kolt.resolve.TransitiveResolver (downloadFromRepositories), kolt.resolve.Resolver (RepositoryDownloadFailure, RepositoryAttempt)_
  - _Depends: 1.1, 2.2, 3.1_

- [x] 4.2 `TransitiveResolver` の caller migration
  - `:20` の `repos = config.repositories.values.map { it.url }.toList()` を `config.repositories.values.toList()` に書き換え (型は `List<Repository>` になる)
  - 4 つの `downloadFromRepositories` 呼び出し (`:69` sources jar fallback, `:128` POM, `:186` module metadata, `:252` binary artifact) で `urlBuilder` lambda を `(Repository) -> String` に書き換え、 lambda body は `repo.url` を使う (`"$repo/"` 禁止規律)
  - 観察可能な完了: `kolt build` compile pass、 既存の `TransitiveResolverTest` が anonymous central 経路で green を維持
  - _Requirements: 5.3_
  - _Boundary: kolt.resolve.TransitiveResolver_
  - _Depends: 4.1_

- [x] 4.3 `NativeResolver` の caller migration
  - `:67` の projection と `:139` / `:395` の 2 caller を `Repository` typed に switch
  - `urlBuilder` lambda 内は `repo.url` のみを URL 構築に使う
  - 観察可能な完了: `kolt build` compile pass、 既存の `NativeResolverTest` が klib 経路で green を維持
  - _Requirements: 5.3_
  - _Boundary: kolt.resolve.NativeResolver_
  - _Depends: 4.1_

- [x] 4.4 `BundleResolver` の caller migration + `resolveSingleArtifact` signature bump
  - `:180` の projection と `:124` / `:197` の 2 caller を `Repository` typed に switch
  - `resolveSingleArtifact` (`:106`) の `repos: List<String>` parameter を `List<Repository>` に変更
  - `urlBuilder` lambda 内は `repo.url` のみを URL 構築に使う
  - 観察可能な完了: `kolt build` compile pass、 `BundleResolver` の既存 test が classpath bundle 経路で green を維持
  - _Requirements: 5.3_
  - _Boundary: kolt.resolve.BundleResolver_
  - _Depends: 4.1_

- [x] 4.5 CLI commands + ToolResolution + PluginJarFetcher の migration
  - `OutdatedCommand.kt:42` projection + `:82` caller、 `DependencyCommands.kt` の 4 projection (`:57`, `:300`, `:392`, `:412`) + `:165` の caller + `:297` の `resolveSingleArtifact` caller、 `ToolCommands.kt:163` projection、 `ToolResolution.kt:53` の `ensureTool` parameter `repos: List<String>` → `List<Repository>`、 `PluginJarFetcher.kt:147` の `deps.downloadFile(url, jarPath)` 呼び出しを 3-arg 形 `deps.downloadFile(url, jarPath, headers = null)` に変更
  - 観察可能な完了: `kolt build` exit 0、 `kolt test` で既存の `OutdatedCommandTest` / `DependencyCommandsTest` / `ToolResolutionTest` / `PluginJarFetcherTest` が green を維持。 後続 7.2 (`AllCallersTypedTest`) がこの task 完了で初めて compile 成功する
  - _Requirements: 5.3_
  - _Boundary: kolt.cli.{OutdatedCommand, DependencyCommands, ToolCommands}, kolt.usertool.ToolResolution, kolt.resolve.PluginJarFetcher_
  - _Depends: 4.1, 4.4_

## 5. Renderer layer

- [x] 5.1 `repositoryDownloadFailureContext` に `AuthFailed` 分岐 + hint matrix を追加
  - `Resolver.kt:151-159` の helper を sealed `when` で `AuthFailed` 分岐を追加、 5 行の context list (`repository: <name>` / `url: <redacted>` / `status: <code> <reason>` / `credentials: <state>` / `hint: <state-specific>`) を生成
  - outer `formatResolveError` の `ResolveError` 分岐 (`Resolver.kt:75-138`) には触れない — `AuthFailed` は `RepositoryDownloadFailure` の variant なので、 既存の `ResolveError.DownloadFailed.context = repositoryDownloadFailureContext(error.failure)` 経由で headline (`failed to download <coordinate>` / `could not fetch metadata for <coordinate>`) と組み合わさる
  - hint 文言の 4 行 matrix を `formatAuthHint(statusCode, authState): String` で実装 (要件 7.4 のテーブル)
  - `url` フィールドには `redactUrlUserinfo(url)` を defensively 再適用
  - 観察可能な完了: `ResolveErrorFormatTest` を拡張、 POM 401 → `failed to download G:A` + 5-line context、 metadata 401 → `could not fetch metadata for G:A` + 5-line context を pin
  - _Requirements: 7.1, 7.2, 7.3, 7.4, 8.3_
  - _Boundary: kolt.resolve.Resolver (formatResolveError, repositoryDownloadFailureContext)_
  - _Depends: 1.1, 4.1_

- [x] 5.2 `RepositoryAuthFailureTest` (4-row hint matrix + iteration stop) を追加
  - loopback fixture を `[401-repo, 200-repo]` / `[404-repo, 401-repo, 200-repo]` / 4 hint variants の scenarios で構成
  - 4 row parametric matrix で `(statusCode, AuthStateProjection)` × `(401/403, NotConfigured/ConfiguredToken/ConfiguredBasic)` の hint text を完全一致 assert
  - iteration stop assertion: `[401-repo, 200-repo]` で central (200-repo) に request が **届かない** ことを loopback server access log で確認
  - 観察可能な完了: 4 hint row 全 green、 200-repo に request が届かないことが access log で確認できる
  - _Requirements: 6.1, 6.2, 6.4, 7.1, 7.2, 7.3, 7.4_
  - _Boundary: kolt.resolve (test) + testfixture.LoopbackHttpServer_
  - _Depends: 3.2, 5.1_

## 6. Redaction cross-cutting tests

- [x] 6.1 `RepositoryAuthRedactionTest` を追加 (Req 8 完備マトリクス)
  - 6 アサーション: (a) 401 fetch の stderr に Bearer token literal が含まれない、 (b) Basic auth 401 の stderr に base64 / user / password literal が含まれない、 (c) `RepositoryAuth.Basic("u","p").toString()` が `<redacted>` placeholder を返し `"p"` を含まない、 (d) `Repository(name="x", url="y", auth=RepositoryAuth.Basic("alice","s3cret")).toString()` が `<redacted>` を含み `"s3cret"` を含まない、 (e) 認証 fetch 成功後の `kolt.lock` に credential field が無い (`Lockfile.kt` の serialized JSON を grep)、 (f) `kolt info` JSON に credential field が無い
  - 観察可能な完了: 6 アサーション全 green
  - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5, 10.1, 10.2_
  - _Boundary: cross-cutting (test)_
  - _Depends: 5.2_

## 7. Migration verification and self-host smoke

- [x] 7.1 `RepositorySchemaMigrationTest` を Req 9.x で拡張
  - 既存の flat-string-map rejection をそのまま、 migration hint の text を新 schema (`[repositories.<name>] url = "..."`) に追従する形で pin
  - 追加: `central = "https://u:p@host/x"` 形式の flat-form + URL userinfo が rejection されるとき message が userinfo character を含まないことを assert (Req 9.3)
  - 観察可能な完了: `RepositorySchemaMigrationTest` の全ケース green
  - _Requirements: 9.1, 9.2, 9.3_
  - _Boundary: kolt.config (test)_
  - _Depends: 2.6_

- [x] 7.2 `AllCallersTypedTest` (compile gate smoke) を追加
  - 10 `downloadFromRepositories` call sites (`TransitiveResolver` 4 + `NativeResolver` 2 + `BundleResolver` 2 + `OutdatedCommand` 1 + `DependencyCommands` 1) と 9 projection sites (`TransitiveResolver:20`, `NativeResolver:67`, `BundleResolver:180`, `OutdatedCommand:42`, `ToolCommands:163`, `DependencyCommands:57/:300/:392/:412`) と `ensureTool` (`ToolResolution:53`) / `resolveSingleArtifact` (`BundleResolver:106`) の各 signature bump が `kolt build` のコンパイルを通ることを確認する
  - test 自体は no-op assertion で、 `kolt build` succeed が観察可能な合格条件
  - 観察可能な完了: `kolt build` exit 0 + this test file compiles & loads
  - _Requirements: 5.1, 5.2, 5.3_
  - _Boundary: cross-cutting (compile gate)_
  - _Depends: 4.2, 4.3, 4.4, 4.5_

- [x] 7.3 Self-host smoke gate確認
  - `kolt build` を kolt 自身の 4 つの `kolt.toml` (`/kolt.toml`, `/kolt-jvm-compiler-daemon/kolt.toml`, `/kolt-native-compiler-daemon/kolt.toml`, `/spike/native-ktor-cinterop-smoke/kolt.toml`) に対して実行し、 新しい `liftRepositoriesMap` validators (userinfo / mutex / env-reject / name invariant) がいずれもホットパスで pass することを確認
  - CI `.github/workflows/self-host-smoke.yml` の `self-host-post` job が green であることを最終ゲートとする ([[feedback_subagent_smoke_misses_ci]])
  - 観察可能な完了: 4 ファイルすべてでローカル `kolt build` exit 0、 CI `self-host-post` job green
  - _Requirements: 1.8, 4.1, 4.2, 4.3_
  - _Boundary: cross-cutting (smoke)_
  - _Depends: 7.1, 7.2_
