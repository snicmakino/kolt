# Design Document: private-maven-repos

## Overview

**Purpose**: kolt が認証付き Maven リポジトリ (GitHub Packages, Nexus, Cloudsmith, GitLab Package Registry など) から依存関係を取得できるようにする。 認証情報は `kolt.local.toml` の `[repositories.<name>]` に literal で配置し、 HTTP リクエストごとに `Authorization: Bearer` / `Authorization: Basic` を付与する。

**Users**: 企業内 Maven リポジトリや GitHub Packages を必要とする kolt 利用者。 v1.0 では single-machine の plaintext 配置のみをサポートし、 env-var indirection や OS keychain は v1.1+ に持ち越す。

**Impact**: 既存の resolver chain (`TransitiveResolver` / `NativeResolver` / `PluginJarFetcher` / `BtaImplFetcher`) が `Repository` 型を伝播するように切り替わる。 `Downloader.downloadFile` は optional headers パラメータを取る。 401/403 は新しい `RepositoryDownloadFailure.AuthFailed` variant としてハードエラー化する。 lockfile 整合性 (SHA-256) は不変。

### Goals

- `[repositories.<name>]` に `token` / `user` / `password` 認証フィールドを追加し、 相互排他と空値拒否を施行する
- 認証情報は `kolt.local.toml` 限定とし、 `kolt.toml` の literal は parse-time に拒否する
- 全 11 CLI 入口 × 全 artifact 種別に均一に `Authorization` ヘッダを付与する
- 401/403 をハードエラーとし、 専用診断フォーマットで `credentials: <state>` と state-specific hint を提示する
- error / log / stderr / `kolt.lock` のいずれにも認証情報を漏らさない (redaction guarantee)
- `{ env = "..." }` 形式は parse-time に認識して reject する (v1.1+ の additive な flip-to-accept を可能にするため)

### Non-Goals

- `{ env = "..." }` の実装 (v1.1+ 持ち越し、 ADR 0032 §2 / ADR 0034 §3)
- credential helpers / `.netrc` / OAuth / device flow
- HTTP proxy, mTLS, custom CA bundles, client certificates
- per-repository retry / backoff / timeout overrides
- `~/.kolt/credentials.toml` などの外部 credential store
- Sonatype Portal publish-side auth
- 404 fall-through 動作の変更 (既存通り保持)
- 既存 aggregated multi-repo error フォーマット (404 のみを束ねる動作) の変更

## Boundary Commitments

### This Spec Owns

- `[repositories.<name>]` に `token` / `user` / `password` フィールドを追加する schema
- 認証フィールドの mutex / empty / userinfo / `{env="..."}` recognize-and-reject 検証
- `kolt.toml` 上の literal credential 拒否 (配置ポリシー)
- `Repository` typed の `auth: RepositoryAuth?` field と、 resolver chain への伝播
- `Downloader.downloadFile` への optional `headers` parameter と libcurl `curl_slist_append` 接続
- `downloadFromRepositories` における 401/403 ハードエラー分岐 (404 fall-through は不変)
- `RepositoryDownloadFailure.AuthFailed` variant と専用診断レンダリング (`credentials: <state>` + hint)
- URL userinfo の defensive scrub utility (`redactUrlUserinfo`) と、 全 URL 出力経路への適用
- legacy flat-string-map `[repositories]` 拒否のテキストが新スキーマを案内すること

### Out of Boundary

- `kolt.local.toml` overlay merge の仕組み自体 (#415 で実装済み、 本 spec は新フィールドを `mergeRepositories` の field-level merge に乗せるだけ)
- `kolt.local.toml` の gitignore 自動付与 (#417 で実装済み)
- 認証以外の HTTP layer 設定 (timeout / connect / redirect — 不変)
- lockfile schema (`Lockfile.kt` の `dependencies` map shape — 不変)
- daemon 経由経路の HTTP — kolt の daemon は HTTP を発行しない (local socket + file I/O のみ)

### Allowed Dependencies

- `kolt.config` (RawRepository / Repository / SysPropValueSerializer pattern)
- `kolt.config.LocalOverlay` (mergeRepositories field-merge contract from #415)
- `kolt.infra.Downloader` (libcurl easy-handle API)
- `kolt.resolve.*` (TransitiveResolver, NativeResolver, downloadFromRepositories の iteration loop)
- `kolt.tool.PluginJarFetcher` / `kolt.build.daemon.BtaImplFetcher` (これらは resolver chain の consumer)
- `libcurl` cinterop の `curl_slist_append` / `CURLOPT_HTTPHEADER` / `curl_slist_free_all`
- ktoml の custom KSerializer (precedent: `SysPropValueSerializer`)
- kotlin-result `Result<V, E>` (ADR 0001)

### Revalidation Triggers

以下が変更されたら依存先 spec / 利用者は再検証する:

- `Repository` typed の field 構成変更 (現状 `name` / `url` / `auth`; 新フィールド追加は formatResolveError 出力に影響しうる)
- `RepositoryDownloadFailure` sealed 階層の追加 / 削除 / リネーム (renderer 側で網羅 when を持つため)
- `Downloader.downloadFile` のシグネチャ変更 (現状 `url, destPath, headers?`)
- `RawCredentialField` の polymorphism shape (literal / env の disjoint) — v1.1+ で env 認容に flip する際に再検証
- libcurl header attach の cleanup ordering (slist free と easy_cleanup の順序契約)

## Architecture

### Existing Architecture Analysis

kolt の依存方向は `cli → build → resolve → infra` (structure.md)。 本 spec は以下を守る:

- `kolt.infra.Downloader` は domain types (`Repository`, `RepositoryAuth`) を import しない — 文字列 URL + headers map のみを受け取る
- `kolt.resolve` 層が auth attachment の境界 (header の組み立てと libcurl 呼び出しへの引き渡し) を所有
- `kolt.config` は raw decode + lift + 配置ポリシー検証のみを担当
- `kolt.config.LocalOverlay` の `mergeRepositories` (#415) は field-level merge を既に提供しており、 新フィールドは null-default で追加するだけで既存契約を満たす

既存の error-handling 規律 (ADR 0001) と value-class kotlin-result の利用慣行 (`getOrElse` / `getError` / `isErr`) を踏襲する。

### Architecture Pattern & Boundary Map

```mermaid
graph TB
    subgraph cli[kolt.cli]
        Commands[BuildCommands / DependencyCommands]
    end
    subgraph resolve[kolt.resolve]
        TransResolver[TransitiveResolver]
        NativeResolver[NativeResolver]
        DownloadLoop[downloadFromRepositories]
        Renderer[formatResolveError]
        AuthFailure[RepositoryDownloadFailure.AuthFailed]
    end
    subgraph config[kolt.config]
        Parser[parseConfig]
        RawRepo[RawRepository]
        LiftRepo[liftRepositoriesMap]
        AuthADT[RepositoryAuth ADT]
        CredField[RawCredentialField + serializer]
        Overlay[mergeRepositories from issue 415]
    end
    subgraph infra[kolt.infra]
        Downloader[Downloader.downloadFile]
        UrlRedact[redactUrlUserinfo]
    end
    subgraph external[external]
        Libcurl[libcurl curl_slist_append]
    end

    Commands --> TransResolver
    Commands --> NativeResolver
    TransResolver --> DownloadLoop
    NativeResolver --> DownloadLoop
    DownloadLoop --> Downloader
    DownloadLoop --> AuthFailure
    AuthFailure --> Renderer
    Renderer --> UrlRedact
    Downloader --> Libcurl
    Downloader --> UrlRedact
    Parser --> RawRepo
    RawRepo --> CredField
    Parser --> LiftRepo
    LiftRepo --> AuthADT
    Parser --> Overlay
    Commands --> Parser
```

**Architecture Integration**:

- **Selected pattern**: layered with strict downward dependency (cli → resolve → infra). `kolt.config` is a sibling of `kolt.resolve` consumed by cli and resolve. New types live in `kolt.config` (domain) and `kolt.infra` (URL utility).
- **Existing patterns preserved**: ADT-based `Result<V, E>` flow (ADR 0001), custom KSerializer pattern for polymorphic TOML values (`SysPropValueSerializer`), per-key source attribution model (`sysPropSourceMap` from #436), field-level overlay merge (`mergeRepositories` from #415).
- **New components rationale**: `RepositoryAuth` ADT (typed projection over Bearer/Basic, drives both header attach and diagnostic state); `RawCredentialField` (recognize-and-reject `{env="..."}` forward-compat); `redactUrlUserinfo` (defense-in-depth utility shared by Downloader and Renderer).
- **Steering compliance**: language (English code/comments per tech.md §Language Policy), no exceptions (ADR 0001), libcurl over ktor (ADR 0006), kotlin-result idioms (`isErr` / `getOrElse`).

### Technology Stack

| Layer | Choice / Version | Role in Feature | Notes |
|-------|------------------|-----------------|-------|
| Native HTTP | libcurl (system, already pinned) | `curl_slist_append` for `Authorization` header attach | First use of header-list API in kolt; cleanup via `curl_slist_free_all` in finally |
| TOML parsing | ktoml-core (existing) | Custom KSerializer for `RawCredentialField` polymorphism | Same pattern as `SysPropValueSerializer`; ktoml's KSerializer surface cannot do string-OR-table polymorphism directly ([[reference_ktoml_decode_quirks]]) |
| Encoding | platform `Base64` (already used in lockfile sha2-256 path) | `Authorization: Basic <base64(user:password)>` encoding | RFC 7617; ASCII-only credentials assumed for v1.0 |
| Error type | kotlin-result 2.x (existing) | `Result<Repository, ConfigError>` / `Result<Unit, RepositoryDownloadFailure>` | value class; use `getError` / `isErr` |

## File Structure Plan

### New files

```
src/nativeMain/kotlin/kolt/config/
├── RepositoryAuth.kt              # RepositoryAuth sealed class (Bearer/Basic data classes with overridden toString)
└── RawCredentialField.kt          # Uniform-inline-table literal/env discriminator + KSerializer

src/nativeMain/kotlin/kolt/resolve/
└── AuthStateProjection.kt         # AuthStateProjection sealed class + toDisplayString() (renderer-facing, secret-free)

src/nativeMain/kotlin/kolt/infra/
└── UrlRedaction.kt                # redactUrlUserinfo() shared utility

src/nativeTest/kotlin/kolt/config/
├── RepositoryAuthConfigTest.kt    # Req 1 + Req 2 + Req 2.3 schema / mutex / empty / placement / env-reject (parametrized, kolt.toml + kolt.local.toml fixtures shared)
└── RepositoryUrlUserinfoTest.kt   # Req 3: URL @-rejection + redaction in message

src/nativeTest/kotlin/kolt/infra/
├── DownloaderAuthHeaderTest.kt    # Req 5: header attach + redirect Authorization drop (loopback HTTP stub)
└── UrlRedactionTest.kt            # Req 8.3: userinfo scrub

src/nativeTest/kotlin/kolt/resolve/
├── RepositoryAuthFailureTest.kt   # Req 6, 7: 401/403 hard-error + 4-row parametrized hint matrix
└── RepositoryAuthRedactionTest.kt # Req 8.1-8.5: cross-cutting redaction matrix
```

### Modified files

- `src/nativeMain/kotlin/kolt/config/Config.kt` — extend `RawRepository` with `token` / `user` / `password` fields of type `RawCredentialField?`; extend `Repository` (currently `@Serializable data class Repository(val url: String)` at `:108`) with `name: String` and `auth: RepositoryAuth?`; mark `auth` as `@Transient` so the existing `@Serializable` annotation continues to compile (`RepositoryAuth` is non-serializable; secrets must never reach the JSON wire); update the default value of `KoltConfig.repositories` at `:120` to `mapOf("central" to Repository(name = "central", url = MAVEN_CENTRAL_BASE, auth = null))`; extend `liftRepositoriesMap` with mutex / empty / userinfo / env-reject validators and the `Repository.name == map_key` invariant enforcement; add base-only literal-credential reject (Req 2.1) inside `parseConfig` before merge; add `repositorySourceMap` helper next to the existing `sysPropSourceMap`.
- `src/nativeMain/kotlin/kolt/config/LocalOverlay.kt` — extend `mergeRepositories` to field-merge the new credential fields. The shape mirrors the existing `url` merge: `merged[name] = baseRepo.copy(url = overlayRepo.url ?: baseRepo.url, token = overlayRepo.token ?: baseRepo.token, user = overlayRepo.user ?: baseRepo.user, password = overlayRepo.password ?: baseRepo.password)`. Null overlay field → keep base; non-null overlay → override (last-write-wins, consistent with `mergeSysProps`). The overlay-only-name rejection (#415) is unchanged. Note: if base has a credential field (forbidden by `rejectBaseCredentialLiterals`), the merge would propagate it forward, but `rejectBaseCredentialLiterals` runs before merge so this case is unreachable in production.
- `src/nativeMain/kotlin/kolt/infra/Downloader.kt` — add `headers: Map<String, String>? = null` to `downloadFile`; apply via `curl_slist_append` → `CURLOPT_HTTPHEADER`; free via `curl_slist_free_all` in finally; set `CURLOPT_UNRESTRICTED_AUTH = 0L` so `Authorization` is dropped on cross-origin redirects; scrub URL via `redactUrlUserinfo` when constructing `DownloadError.HttpFailed`.
- `src/nativeMain/kotlin/kolt/resolve/TransitiveResolver.kt` — switch the `repos` accumulator at `:20` from `List<String>` to `List<Repository>` (drop the `.map { it.url }.toList()` projection; pass `config.repositories.values.toList()` directly); bump `downloadFromRepositories` signature to take `List<Repository>` and the new 3-arg `download` lambda type `(String, String, Map<String, String>?) -> Result<Unit, DownloadError>`; at each of the 4 fetch sites (`:69`, `:128`, `:186`, `:252`) compute headers from `repo.auth.toHeaders()` inside the new `download` lambda and pass through; add the 401/403 branch in `downloadFromRepositories` (~line 99) that returns `AuthFailed` immediately and stops iteration.
- `src/nativeMain/kotlin/kolt/resolve/NativeResolver.kt` — switch the `repos` accumulator at `:67` to `List<Repository>` (same projection drop); update both `downloadFromRepositories` call sites (`:139`, `:395`) to pass `Repository`-typed `repos` and the new `download` lambda.
- `src/nativeMain/kotlin/kolt/resolve/BundleResolver.kt` — switch the `repos` accumulator at `:180` to `List<Repository>`; update both `downloadFromRepositories` call sites (`:124`, `:197`).
- `src/nativeMain/kotlin/kolt/resolve/PluginJarFetcher.kt` — bypasses `downloadFromRepositories` (calls `deps.downloadFile` directly at `:147`). Update the call site to pass the new `headers` parameter as `null` (compiler plugin jars are fetched from Maven Central anonymously; no auth context). No `Repository`-typed propagation here.
- `src/nativeMain/kotlin/kolt/build/daemon/BtaImplFetcher.kt` — update the synthetic `Repository(MAVEN_CENTRAL_BASE)` constructor call (`:54`) to `Repository(name = "central", url = MAVEN_CENTRAL_BASE, auth = null)` to match the extended `Repository` shape.
- `src/nativeMain/kotlin/kolt/cli/OutdatedCommand.kt` — switch the `repos` accumulator at `:42` to `List<Repository>`; update the single `downloadFromRepositories` call site (`:82`).
- `src/nativeMain/kotlin/kolt/cli/DependencyCommands.kt` — switch four `config.repositories.values.map { it.url }.toList()` projection sites (`:57`, `:300`, `:392`, `:412`) to `List<Repository>`; update the single `downloadFromRepositories` call site (`:165`) to pass `Repository`-typed `repos`.
- `src/nativeMain/kotlin/kolt/cli/ToolCommands.kt` — switch the `repos` accumulator at `:163` to `List<Repository>`. This file does not call `downloadFromRepositories` directly; the `List<Repository>` flows downstream into `ensureTool` (`src/nativeMain/kotlin/kolt/usertool/ToolResolution.kt:53`), whose `repos: List<String>` parameter is bumped to `List<Repository>` in the same change.
- `src/nativeMain/kotlin/kolt/usertool/ToolResolution.kt` — bump `ensureTool`'s `repos` parameter type at `:53` from `List<String>` to `List<Repository>`. This function forwards `repos` into `resolveSingleArtifact` in `BundleResolver.kt`.
- `src/nativeMain/kotlin/kolt/resolve/BundleResolver.kt` — in addition to the projection-site switch at `:180` and the two `downloadFromRepositories` call sites (`:124`, `:197`), bump `resolveSingleArtifact`'s `repos: List<String>` parameter at `:106` to `List<Repository>`. This function is called from `ToolResolution.kt:125` (`ensureTool`) and `DependencyCommands.kt:297` (the update-command tool-resolution path); both call sites flow `List<Repository>` directly after the projection-site switch.
- `src/nativeMain/kotlin/kolt/resolve/Resolver.kt` — add `repositoryName: String` to `RepositoryAttempt`; add `RepositoryDownloadFailure.AuthFailed(repositoryName, url, statusCode, authState)`; extend `formatResolveError` with the new diagnostic shape (multi-line context with `credentials: <state>` + hint); apply `redactUrlUserinfo` in `repositoryDownloadFailureContext`.
- `src/nativeTest/kotlin/kolt/resolve/ResolveErrorFormatTest.kt` — extend with 401/403 diagnostic format pins (4-row parametrized matrix for state-specific hints).
- `src/nativeTest/kotlin/kolt/config/RepositorySchemaMigrationTest.kt` — keep existing flat-form rejection assertions; add cross-check that the migration hint now references the credential-bearing sub-table form.

> Each new file has a single clear responsibility. Existing files are extended along the field-level overlay pattern that already exists for `[*.sys_props]`.

## System Flows

### Authentication header attachment (per-request, all entry points)

```mermaid
sequenceDiagram
    participant CLI as kolt cli
    participant Resolver as TransitiveResolver
    participant Loop as downloadFromRepositories
    participant Downloader as Downloader.downloadFile
    participant Curl as libcurl

    CLI->>Resolver: resolveTransitive(config)
    Resolver->>Loop: downloadFromRepositories(repos: List<Repository>, coord)
    loop per repo
        Loop->>Loop: headers = repo.auth?.toHeader()
        Loop->>Downloader: downloadFile(repo.url + path, dest, headers)
        Downloader->>Curl: curl_slist_append(Authorization)
        Downloader->>Curl: CURLOPT_HTTPHEADER + perform
        alt 2xx
            Downloader-->>Loop: Ok
        else 404
            Downloader-->>Loop: HttpFailed(404)
            Note over Loop: fall through to next repo
        else 401 or 403
            Downloader-->>Loop: HttpFailed(401 or 403)
            Loop-->>Resolver: AuthFailed(name, url, code, authState)
            Note over Loop: break — stop iteration immediately
        end
    end
```

**Key decisions** (not obvious from diagram):

- `downloadFromRepositories` is the sole place that distinguishes 401/403 vs 404. The `Downloader` layer remains status-code-agnostic (flat `HttpFailed`).
- `repo.auth?.toHeader()` returns `null` for anonymous; the `Downloader` receives `headers: null` and skips `curl_slist_append` entirely.
- The 401/403 branch stops iteration immediately. No subsequent repository is contacted. This is observable in tests as: a sequence of `[private-401, central-would-have-200]` resolves to a hard 401 error, not a successful fetch from central.

### Config validation chain

```mermaid
graph TB
    TOML[kolt.toml + kolt.local.toml strings]
    Parse[parseConfig]
    BaseRaw[rawBase: RawKoltConfig]
    OverlayRaw[rawOverlay nullable]
    BasePolicy[reject base credential literals]
    Merge[mergeOverlay + mergeRepositories]
    MergedRaw[merged raw]
    Lift[liftRepositoriesMap]
    Validators[mutex / empty / userinfo / env-reject]
    Final[Map name to Repository]

    TOML --> Parse
    Parse --> BaseRaw
    Parse --> OverlayRaw
    BaseRaw --> BasePolicy
    BasePolicy --> Merge
    OverlayRaw --> Merge
    Merge --> MergedRaw
    MergedRaw --> Lift
    Lift --> Validators
    Validators --> Final
```

**Key decisions**:

- The "kolt.toml carries literal credential" check (Req 2.1) runs on `rawBase` **before** merge. The file path attached to the rejection is the known `path` parameter (no source map needed — base raw originated entirely from `kolt.toml`).
- Mutex / empty / userinfo / env-reject (Req 1.2, 1.3, 1.4, 2.3, 3.x) run on the **merged** raw inside `liftRepositoriesMap`. For overlay-sourced violations, attribution uses a new `repositorySourceMap` (mirroring `sysPropSourceMap` from #436) that maps each credential field's contributing file.

## Requirements Traceability

| Requirement | Summary | Components | Interfaces | Flows |
|-------------|---------|------------|------------|-------|
| 1.1 | Optional `token` / `user` / `password` schema | RawRepository, RawCredentialField | `RawRepository` decode | Config validation chain |
| 1.2 | token vs user/password mutex | liftRepositoriesMap validators | `validateAuthMutex(name, raw)` | Config validation chain |
| 1.3 | user-password pair completeness | liftRepositoriesMap validators | `validateAuthMutex(name, raw)` | Config validation chain |
| 1.4 | Empty/whitespace `literal` value reject | liftRepositoriesMap validators | `validateAuthNonEmpty(name, raw)` | Config validation chain |
| 1.5 | URL-only entry → anonymous, no header | TransitiveResolver loop, Downloader | `repo.auth?.toHeaders()` returns null | Authentication header attachment |
| 1.6 | Auth inline-table with neither `literal` nor `env` rejected | RawCredentialFieldSerializer.deserialize | `setFields.size == 0` → SerializationException → ParseFailed | Config validation chain |
| 1.7 | Auth inline-table with both `literal` and `env` rejected | RawCredentialFieldSerializer.deserialize | `setFields.size == 2` → SerializationException → ParseFailed | Config validation chain |
| 1.8 | Pre-v0.20.0 sub-table (no auth fields) parses cleanly, treated as anonymous | `RawRepository` nullable default fields + `liftRepositoriesMap` | `token/user/password = RawCredentialField? = null` → `Repository.auth = null` | Config validation chain |
| 2.1 | kolt.toml literal credential reject | parseConfig pre-merge | `rejectBaseCredentialLiterals(rawBase, path)` | Config validation chain |
| 2.2 | kolt.local.toml accepts when name in kolt.toml | mergeRepositories (existing from #415) | `mergeRepositories(base, overlay, overlayPath)` | Config validation chain |
| 2.3 | `{env="..."}` recognize-and-reject | RawCredentialField KSerializer | `RawCredentialField.kind == Env` → ParseFailed | Config validation chain |
| 2.4 | Reject message scrubs credential value | rejectBaseCredentialLiterals + ParseFailed renderer | message constructed without value | Config validation chain |
| 3.1 | URL userinfo `@` reject | liftRepositoriesMap validators | `validateUrlNoUserinfo(name, url)` | Config validation chain |
| 3.2 | userinfo with or without password both rejected | validateUrlNoUserinfo | regex/parse on `@` before host | Config validation chain |
| 3.3 | Reject message scrubs userinfo | validateUrlNoUserinfo + redactUrlUserinfo | message includes redacted URL only | Config validation chain |
| 4.1 | Implicit central fallback unchanged | `RawKoltConfig.repositories` data-class field default (decoded by ktoml) | (no change) | n/a |
| 4.2 | Explicit `[repositories.central]` treated as any other | (no change) | (no change) | n/a |
| 4.3 | TOML declaration order preserved | LinkedHashMap iteration (no change) | `config.repositories.values` | n/a |
| 5.1 | Bearer header attach when token set | TransitiveResolver loop, RepositoryAuth.Bearer | `auth.toHeader() == "Authorization: Bearer <token>"` | Authentication header attachment |
| 5.2 | Basic header attach when user+password set | TransitiveResolver loop, RepositoryAuth.Basic | `auth.toHeader() == "Authorization: Basic <base64(user:password)>"` | Authentication header attachment |
| 5.3 | Every repository HTTP request, all subcommands and all artifact classes | All resolver entry points funnel into the same `downloadFile` site; auth attaches at the loop boundary | Resolver chain shared | Authentication header attachment |
| 6.1 | 401/403 hard error, no fall-through | downloadFromRepositories loop branch | `if statusCode in 401..403 → return AuthFailed` | Authentication header attachment |
| 6.2 | Hard error regardless of auth configured | downloadFromRepositories branch (no auth check on hard-error gate) | branch is status-code only | Authentication header attachment |
| 6.3 | 404 continues fall-through | downloadFromRepositories loop (unchanged) | (no change) | Authentication header attachment |
| 6.4 | Aggregated multi-repo errors only group 404 | downloadFromRepositories branch returns AuthFailed before AllAttemptsFailed | sealed dispatch in formatResolveError | n/a |
| 7.1 | Headline via existing `ResolveError`-variant phrasing (`failed to download <X>` / `could not fetch metadata for <X>`) | outer `formatResolveError` `ResolveError` branches (unchanged) | RenderedDiagnostic.headline | n/a |
| 7.2 | Context lines in declaration order | formatResolveError AuthFailed branch | RenderedDiagnostic.context | n/a |
| 7.3 | `<state>` enum projection | RepositoryAuth.toStateString() | string projection | n/a |
| 7.4 | Four state-specific hint texts (status × configuration-state matrix) | formatAuthHint(statusCode, state) | hint table | n/a |
| 8.1 | No Authorization in output | Renderer never receives `RepositoryAuth` body, only projection | (architectural invariant) | n/a |
| 8.2 | No token/user/password values in output | Same as 8.1 | (architectural invariant) | n/a |
| 8.3 | No userinfo in output (defensive) | redactUrlUserinfo applied at all URL emission sites | `redactUrlUserinfo(url): String` | n/a |
| 8.4 | No credentials in kolt.lock | Lockfile schema unchanged (no credential field) | (no change) | n/a |
| 8.5 | Invariant holds regardless of verbosity | Architectural: Renderer / Lockfile receive only projections, not raw auth | (design discipline) | n/a |
| 9.1 | Legacy flat form reject | KtomlMessageParse existing path | (no change in logic) | n/a |
| 9.2 | Migration hint to sub-table form | KtomlMessageParse hint text | hint text update | n/a |
| 9.3 | Legacy flat with URL userinfo scrubs | rejection message uses redactUrlUserinfo | redactUrlUserinfo | n/a |
| 10.1 | SHA-256 verify unchanged | Lockfile verification path unchanged | (no change) | n/a |
| 10.2 | No weakening based on auth status | Lockfile verification unaware of auth | (architectural invariant) | n/a |

## Components and Interfaces

| Component | Domain/Layer | Intent | Req Coverage | Key Dependencies | Contracts |
|-----------|--------------|--------|--------------|------------------|-----------|
| `RepositoryAuth` ADT | kolt.config | Typed Bearer / Basic projection used by both header attach and diagnostic state | 1.5, 5.1, 5.2, 7.3 | None (data only) | State |
| `RawCredentialField` + serializer | kolt.config | Uniform inline-table literal-vs-env discriminator; shape validator | 1.1, 1.4, 1.6, 1.7, 2.3 | ktoml KSerializer (P0) | State |
| `liftRepositoriesMap` (extended) | kolt.config | Schema validators: mutex, non-empty, URL userinfo, env-reject; populates Repository.name == map_key; pre-v0.20.0 sub-tables (no auth fields) lift to anonymous | 1.1, 1.2, 1.3, 1.4, 1.8, 2.3, 3.1, 3.2, 3.3 | None | Service |
| `rejectBaseCredentialLiterals` | kolt.config | Reject literal credentials in kolt.toml base before merge | 2.1, 2.4 | parseConfig (P0) | Service |
| `mergeRepositories` (extended, from #415) | kolt.config.LocalOverlay | Field-merge new credential fields; existing overlay-only-name rejection unchanged | 2.2 | RawRepository (P0) | Service |
| `Repository` (extended) | kolt.config | Typed repository carrying name, url, auth across resolver chain | 4.1, 4.2, 4.3, 5.1, 5.2, 5.3, 7.2 | RepositoryAuth (P0) | State |
| `Downloader.downloadFile` (extended) | kolt.infra | HTTP fetch with optional Authorization headers via libcurl slist | 5.1, 5.2, 8.3 | libcurl (P0), redactUrlUserinfo (P1) | Service |
| `downloadFromRepositories` (extended) | kolt.resolve | Repository iteration with 401/403 hard-error branch | 6.1, 6.2, 6.3, 6.4 | Downloader (P0), Repository (P0) | Service |
| `RepositoryDownloadFailure.AuthFailed` | kolt.resolve | Sealed-ADT variant carrying name, url, status, authState | 6.4, 7.1, 7.2, 7.3 | RepositoryAuth (P0) | State |
| `formatResolveError` (extended) | kolt.resolve | New AuthFailed branch rendering credentials-state diagnostic | 7.1, 7.2, 7.3, 7.4, 8.3 | redactUrlUserinfo (P1) | Service |
| `redactUrlUserinfo` | kolt.infra | URL userinfo scrub utility, used by Downloader and Renderer | 3.3, 8.3, 9.3 | None | Service |

### kolt.config

#### RepositoryAuth

| Field | Detail |
|-------|--------|
| Intent | Typed projection over Bearer / Basic that drives both Authorization header construction and `credentials: <state>` diagnostic line |
| Requirements | 1.5, 5.1, 5.2, 7.3 |

**Responsibilities & Constraints**

- Single source of truth for "is this auth Bearer / Basic / anonymous"
- Absence (`null`) means anonymous; no `RepositoryAuth.None` variant (simplification)
- Projects to header string (`toHeader()`) and to diagnostic state string (`toStateString()`)
- Immutable value class semantics; carries `password` / `token` but is never `equals`-leaked through stringification (no `data class toString()` override needed; `Renderer` only ever calls `toStateString()`)

**Contracts**: State [x]

##### State

```kotlin
// Non-data sealed class with explicit toString so secret fields cannot
// reach println / eprintln / stringtemplate-based logging by accident.
// Bearer / Basic remain data classes for equals / hashCode / copy
// convenience, BUT they override toString to redact secrets.
sealed class RepositoryAuth {
  data class Bearer(val token: String) : RepositoryAuth() {
    override fun toString(): String = "RepositoryAuth.Bearer(token=<redacted>)"
  }
  data class Basic(val user: String, val password: String) : RepositoryAuth() {
    override fun toString(): String =
      "RepositoryAuth.Basic(user=$user, password=<redacted>)"
  }
}

fun RepositoryAuth.toHeaders(): Map<String, String> =
  when (this) {
    is RepositoryAuth.Bearer -> mapOf("Authorization" to "Bearer $token")
    is RepositoryAuth.Basic ->
      mapOf("Authorization" to "Basic ${base64Encode("$user:$password")}")
  }

fun RepositoryAuth?.toStateProjection(): AuthStateProjection =
  when (this) {
    null -> AuthStateProjection.NotConfigured
    is RepositoryAuth.Bearer -> AuthStateProjection.ConfiguredToken
    is RepositoryAuth.Basic -> AuthStateProjection.ConfiguredBasic
  }
```

The `toHeaders()` return type is `Map<String, String>` (matches `Downloader.downloadFile`'s `headers` parameter, splitting `Authorization` key from `Bearer <token>` / `Basic <...>` value). The state projection returns `AuthStateProjection` (defined in `kolt.resolve.AuthStateProjection.kt`, co-located with `RepositoryDownloadFailure`) rather than a string, so the renderer alone owns the display-string mapping (Req 7.3 fixed text lives in one place).

- **Preconditions**: For `Basic`, `user` and `password` are both non-empty (validators upstream enforce this).
- **Postconditions**: `toHeaders()` always returns a single-entry map keyed by `"Authorization"`.
- **Invariants**: `RepositoryAuth` is never serialized (no `@Serializable`); only the parsed-and-validated form constructed by `liftRepositoriesMap` exists. `toString` on every variant is overridden to redact secret fields, so `eprintln("retrying $repo")` style stringification cannot leak credentials.

**Implementation Notes**

- Integration: Constructed only by `liftRepositoriesMap`; consumed by `TransitiveResolver` (header attach) and `formatResolveError` (state projection)
- Validation: Mutex enforced at construction site (validator rejects before constructor reached)
- Risks: `data class` `equals` / `hashCode` still compare secret fields, but this is desirable (two `Bearer("X")` values being equal is correct). The leak surface is `toString`, which is overridden above. Test `RepositoryAuthRedactionTest` asserts the literal `RepositoryAuth.Basic("u","p").toString()` output contains the `<redacted>` placeholder and not the password.

#### RawCredentialField

| Field | Detail |
|-------|--------|
| Intent | Uniform inline-table discriminated value for `token` / `user` / `password`, accepting `{ literal = "..." }` in v1.0 and recognizing `{ env = "..." }` for forward-compat rejection per Req 2.3 |
| Requirements | 1.1, 1.6, 1.4, 2.3 |

**Responsibilities & Constraints**

- ktoml 0.7.1 cannot decode "bare string OR inline-table" on a single field through the standard `KSerializer` surface (`SysPropValue.kt:14-16` documents the same constraint for `SysPropValue`). v1.0 therefore commits to **uniform inline-table**: every credential field is always written as an inline-table.
- v1.0 schema: `token = { literal = "abc123" }` (accepted) or `token = { env = "GITHUB_TOKEN" }` (recognized but rejected per Req 2.3).
- v1.1+ flip: the env-reject validator removes its check; no schema change needed.
- The all-nullable-fields trick from `RawSysPropValue` directly applies: `RawCredentialField` has two nullable fields and a validator requires exactly one to be set (Req 1.6).

**Contracts**: State [x], Service [x]

##### State + Service

```kotlin
@Serializable(with = RawCredentialFieldSerializer::class)
internal sealed class RawCredentialField {
  data class Literal(val value: String) : RawCredentialField()
  data class Env(val varName: String) : RawCredentialField()

  // toString is overridden to never expose Literal.value
  override fun toString(): String = when (this) {
    is Literal -> "RawCredentialField.Literal(<redacted>)"
    is Env -> "RawCredentialField.Env(varName=$varName)"
  }
}

@Serializable
internal data class RawCredentialFieldShape(
  val literal: String? = null,
  val env: String? = null,
)

internal object RawCredentialFieldSerializer : KSerializer<RawCredentialField> {
  override val descriptor: SerialDescriptor =
    RawCredentialFieldShape.serializer().descriptor

  override fun serialize(encoder: Encoder, value: RawCredentialField) {
    val raw = when (value) {
      is RawCredentialField.Literal -> RawCredentialFieldShape(literal = value.value)
      is RawCredentialField.Env -> RawCredentialFieldShape(env = value.varName)
    }
    encoder.encodeSerializableValue(RawCredentialFieldShape.serializer(), raw)
  }

  override fun deserialize(decoder: Decoder): RawCredentialField {
    val raw = decoder.decodeSerializableValue(RawCredentialFieldShape.serializer())
    val setFields = listOfNotNull(
      raw.literal?.let { "literal" },
      raw.env?.let { "env" },
    )
    if (setFields.size != 1) {
      throw SerializationException(
        "credential field value must set exactly one of { literal, env }, " +
          "but ${setFields.size} were set: $setFields"
      )
    }
    return when (setFields.single()) {
      "literal" -> RawCredentialField.Literal(raw.literal!!)
      "env" -> RawCredentialField.Env(raw.env!!)
      else -> error("unreachable")
    }
  }
}
```

- **Preconditions**: Called only by ktoml at decode time on `RawRepository.{token,user,password}`.
- **Postconditions**: Returns `Literal` or `Env`; never null (the parent uses `RawCredentialField? = null` for "absent").
- **Invariants**: The custom `serialize` is a real implementation (not `error(...)`), mirroring `SysPropValueSerializer` (`SysPropValue.kt:36-44`). Decode failures throw `SerializationException` per the `KSerializer` contract; this is allowed (the contract requires it) and the existing `parseConfig` `catch (e: SerializationException)` at `Config.kt:651` and `LocalOverlay.kt:27-30` converts these into `ConfigError.ParseFailed`. ADR 0001 ("no exceptions") applies to kolt's own error model, not to framework-mandated throws on a serializer boundary.

**Implementation Notes**

- Integration: Mirrors `SysPropValueSerializer` (`config/SysPropValue.kt:33-67`). The all-nullable-fields trick + post-decode validator is the canonical kolt pattern for ktoml 0.7.1 inline-table discrimination.
- Validation: Env-shape rejection text is fixed in `liftRepositoriesMap` so tests can pin the exact wording. Shape-error (`Req 1.6`) comes from this serializer's `setFields.size != 1` check and reaches the user via `Config.kt:651` catch.
- Risks: ktoml 0.7.x version drift could change the decoder API; pin to current version and keep the serializer adjacent to its sibling `SysPropValueSerializer` for consistency. If a future ktoml version supports string-OR-table polymorphism, the schema can additively accept the short `token = "abc"` form without breaking the existing inline-table syntax.

#### liftRepositoriesMap (extended)

| Field | Detail |
|-------|--------|
| Intent | Validate the merged `Map<String, RawRepository>` and lift to `Map<String, Repository>` with authentication populated |
| Requirements | 1.1, 1.2, 1.3, 1.4, 2.3, 3.1, 3.2, 3.3 |

**Responsibilities & Constraints**

- Validation order (fail fast, first failure wins):
  1. URL non-null / non-empty (existing)
  2. URL no userinfo (`@` before host) — Req 3.1 / 3.2; rejection message uses `redactUrlUserinfo(url)` (Req 3.3)
  3. Auth-field mutex: `token` set together with `user` or `password` → reject (Req 1.2)
  4. Auth-field completeness: `user` without `password` or vice versa → reject (Req 1.3)
  5. Auth-field non-empty: any of `token` / `user` / `password` Literal with empty-or-whitespace → reject (Req 1.4)
  6. Auth-field env-form: any `RawCredentialField.Env(_)` → reject (Req 2.3)
- Resulting `Repository.auth` is `null`, `Bearer(token)`, or `Basic(user, password)`. Constructor enforces invariants implicitly (validators must have rejected impossible states before reaching it).

**Contracts**: Service [x]

##### Service Interface

```kotlin
internal fun liftRepositoriesMap(
  raw: Map<String, RawRepository>,
  repositorySourceMap: Map<String, Map<String, String?>>,  // name → field → file
): Result<Map<String, Repository>, ConfigError>
```

`repositorySourceMap` follows the `sysPropSourceMap` (`Config.kt:398-414`) shape and semantics exactly:
- **Outer key** is the repository name (post quote-stripping).
- **Inner key** is one of the fixed strings `"url" / "token" / "user" / "password"`. Any other key is a programmer error.
- **Inner key absent** means that field is unset on this repository (no base nor overlay contribution).
- **Inner value is a `String?`**: the contributing file path. Set to `basePath` when only base contributes, to `overlayPath` when overlay contributes (overlay wins on conflict — last-write-wins, matching `mergeRepositories` from #415). The value is `null` only when the contributing source provided no path (internal-test scenario where `parseConfig` was called without `path` / `overlayPath`).

- **Preconditions**: `raw` has been through `mergeRepositories` (#415 contract); `repositorySourceMap` covers every name in `raw` and every set field on each.
- **Postconditions**: Returns `Map<String, Repository>` in insertion order (TOML declaration order, Req 4.3) on success; or `ConfigError.ParseFailed` with `path` attribution (looked up via `repositorySourceMap[name][field]`) on first failure.
- **Invariants**: Successful return implies every value of the map has valid auth (`null` or fully-populated Bearer / Basic); `Repository.name == outer map key` (see Data Models §Domain Model for the full invariant; enforced at construction here).

**Implementation Notes**

- Integration: Receives the source map from a new `repositorySourceMap(base, overlay, basePath, overlayPath)` helper inside `parseConfig`, mirroring `sysPropSourceMap` from #436
- Validation: All validator subfunctions return `Result<Unit, ConfigError>` per ADR 0001
- Risks: Validator order matters for error message clarity. Document the order in a `// validation order: ...` comment with no rationale spread — colocated and short

#### rejectBaseCredentialLiterals

| Field | Detail |
|-------|--------|
| Intent | Reject `kolt.toml` containing literal `token` / `user` / `password` before merge, so the error path stays on the base file |
| Requirements | 2.1, 2.4 |

**Contracts**: Service [x]

##### Service Interface

```kotlin
internal fun rejectBaseCredentialLiterals(
  rawBase: RawKoltConfig,
  basePath: String?,
): Result<Unit, ConfigError.ParseFailed>
```

- **Preconditions**: Called from `parseConfig` after base decode and before overlay merge
- **Postconditions**: Returns `Ok` if no credential literal appears in `rawBase.repositories`; otherwise `ParseFailed(path = basePath, message = ...)` naming the repository, the offending field, and the directive to move to `kolt.local.toml`. The message does not include the credential value (Req 2.4)
- **Invariants**:
  - Only inspects `rawBase.repositories[*].token / user / password`.
  - The base-placement check is **shape-blind**: any non-null `token` / `user` / `password` on a `kolt.toml` repository is rejected, regardless of whether the inner shape is `Literal` or `Env`. The rationale is policy-first: `kolt.toml` must not host credential-related fields at all.
  - The env-form-specific reject (Req 2.3) runs in `liftRepositoriesMap` after merge, so it only fires for `kolt.local.toml`-sourced env-forms (or, after v1.1+ flips the validator, additively accepts them).
  - Order: base-placement check first, then merge, then `liftRepositoriesMap` validators. A user who tries `token = { env = "X" }` in `kolt.toml` sees the placement message; if they move it to `kolt.local.toml` (the directed action), they then see the env-not-supported message. This is a 2-hop in v1.0; v1.1+ collapses to 1-hop when env is accepted.

**Implementation Notes**

- Integration: New private function in `Config.kt`; one call site in `parseConfig` between base decode and `mergeOverlay`
- Validation: Iterates `rawBase.repositories` in declaration order; first violation wins
- Risks: If a user puts `token = { env = "X" }` in `kolt.toml`, we want them to see the placement-policy message first ("declare in kolt.local.toml"), then if they move it to `kolt.local.toml` they'll hit the env-not-supported-in-v1.0 message. This two-step is intentional — both messages teach the right rule

### kolt.infra

#### Downloader.downloadFile (extended)

| Field | Detail |
|-------|--------|
| Intent | Single-point HTTP GET with optional `Authorization` header injection |
| Requirements | 5.1, 5.2, 8.3 |

**Responsibilities & Constraints**

- Optional `headers: Map<String, String>?` parameter, defaulting to `null` (no header list set when null). The default is retained as a DI seam for test callers and for `PluginJarFetcher` (which fetches compiler plugin jars from Maven Central anonymously and passes `null` explicitly).
- libcurl resource management: `curl_slist_append` in setup, `curl_slist_free_all` in `finally` block alongside existing `curl_easy_cleanup`
- URL userinfo scrub: when constructing `DownloadError.HttpFailed(url, code)`, apply `redactUrlUserinfo(url)` so even URLs that slip past the config parser don't leak into the error path

**Contracts**: Service [x]

##### Service Interface

```kotlin
fun downloadFile(
  url: String,
  destPath: String,
  headers: Map<String, String>? = null,
): Result<Unit, DownloadError>
```

- **Headers Map contract**: `headers: Map<String, String>?`. The Map key is the **bare header name** (no colon, no trailing whitespace; e.g., `"Authorization"`). The Map value is the **bare header value** (no leading name, no leading colon; e.g., `"Bearer abc123"`). The Downloader internally builds each libcurl slist line as `"$name: $value"`. Callers (currently only `RepositoryAuth.toHeaders()`) must respect this split.
- **Preconditions**: `url` is a complete URL; if `headers` is non-null, every (name, value) pair is ASCII and contains no CR / LF (callers responsible — `RepositoryAuth.toHeaders()` only emits ASCII).
- **Postconditions**: Same as today (`Ok(Unit)` on 2xx, `Err(HttpFailed)` for non-2xx, `Err(NetworkError)` for libcurl-level failures); `DownloadError.HttpFailed.url` is the redacted form.
- **Invariants**: libcurl slist memory is always freed regardless of success / failure (finally block); no header value ever appears in the returned error type.

**Implementation Notes**

- Integration: Pattern follows existing libcurl cinterop setup (`CURLOPT_URL`, `CURLOPT_WRITEFUNCTION` etc.). `curl_slist_append` is new for the codebase but documented as the standard libcurl pattern. Add a comment naming this as the canonical kolt pattern for future libcurl option lists (cookies, etc.).
- **Redirect Authorization policy** (Req 5.x for redirected requests): libcurl strips `Authorization` on cross-origin redirect when `CURLOPT_UNRESTRICTED_AUTH` is `0L` (the default), and forwards it when `1L`. kolt commits to **`CURLOPT_UNRESTRICTED_AUTH = 0L`** (the default, explicitly set for documentation purposes). Rationale: forwarding credentials to an unintended host (e.g. a typo in `url` that 302s elsewhere) is a worse failure mode than a 401 from a CDN-fronted mirror. Repository operators who need cross-origin auth (e.g. GitHub Packages → AWS CloudFront → 401) should declare the final host directly, not rely on redirect-forwarding. `DownloaderAuthHeaderTest` includes a cross-origin redirect test case asserting the `Authorization` header is NOT seen by the redirect target.
- **slist cleanup order**: `curl_slist_free_all` and `curl_easy_cleanup` are mutually safe in either order per libcurl docs (the slist's string copies are internal to libcurl). kolt's convention is **slist first, then easy** in the `finally` block, which is the opposite of most upstream sample code (`easy_cleanup → slist_free_all`). The order is documented inline as an anchored gotcha so a reader does not "fix" it to match upstream samples.
- Validation: `DownloaderAuthHeaderTest` uses a loopback HTTP listener to assert (a) the server saw the expected `Authorization` header for Bearer / Basic; (b) no header for anonymous; (c) cross-origin redirect drops the header; (d) libcurl slist freed on success and failure (no leak under repeated calls).
- Risks: First use of `curl_slist_append` in kolt. Pattern reuse is documented above so future header / cookie / option lists follow the same finally-block discipline.
- **Per-call locality**: each `downloadFile` invocation calls `curl_easy_init` + `curl_easy_cleanup` and (when headers are present) `curl_slist_append` + `curl_slist_free_all` within the same scope. There is no shared `curl_easy_handle` or `curl_slist` state across calls. Concurrent invocations (e.g., a future parallel POM fetch) are therefore safe with respect to libcurl handle lifetime; each call owns its handle from init through finally-cleanup.
- **Status-code routing**: only HTTP 401 and HTTP 403 trigger the `AuthFailed` hard-error branch in the calling `downloadFromRepositories`. Other non-2xx codes (including HTTP 402 / 407 / 5xx) continue to flow through the existing `DownloadError.HttpFailed` path: 404 falls through to the next repository per Req 6.3, and the rest aggregate into `AllAttemptsFailed`. This preserves the existing multi-repo aggregation behavior for everything except auth failures, and Req 6.4 (aggregated multi-repo errors only group 404s) holds because 401/403 are intercepted upstream before they can join the attempts list.

#### redactUrlUserinfo

| Field | Detail |
|-------|--------|
| Intent | Single utility that strips the userinfo component from a URL string (`https://user:pass@host/x` → `https://host/x`) |
| Requirements | 3.3, 8.3, 9.3 |

**Contracts**: Service [x]

##### Service Interface

```kotlin
fun redactUrlUserinfo(url: String): String
```

- **Preconditions**: `url` is any string; non-URL inputs return the input unchanged
- **Postconditions**: If `url` contains a userinfo component, the returned string omits everything between `scheme://` and the `@` (exclusive of `@`); otherwise `url` is returned unchanged
- **Invariants**: The function is pure, depends on nothing, and is idempotent

**Implementation Notes**

- Integration: Called by `Downloader.downloadFile` when constructing `HttpFailed`, by `liftRepositoriesMap` when rejecting userinfo URLs, by `formatResolveError` defensively in `repositoryDownloadFailureContext`
- Validation: Detection by simple substring scan: find `://`, then find the next `@` before `/`, `?`, `#`. If both present, slice
- Risks: Edge case: URL containing `@` inside the path (e.g. `https://host/foo@bar/baz`). The detector must check that `@` appears before the first path separator. Test matrix covers: (a) `https://host/path` — unchanged; (b) `https://u:p@host/path` — userinfo removed; (c) `https://u@host/path` — userinfo removed; (d) `https://host/foo@bar` — unchanged; (e) `not-a-url-at-all` — unchanged

### kolt.resolve

#### RepositoryDownloadFailure.AuthFailed

| Field | Detail |
|-------|--------|
| Intent | Sealed-ADT variant signalling 401/403 from a specific repository, carrying the typed state for diagnostic rendering |
| Requirements | 6.4, 7.1, 7.2, 7.3 |

**Contracts**: State [x]

##### State

```kotlin
sealed class RepositoryDownloadFailure {
  data object NoRepositoriesConfigured : RepositoryDownloadFailure()
  data class AllAttemptsFailed(val attempts: List<RepositoryAttempt>) : RepositoryDownloadFailure()
  data class AuthFailed(
    val repositoryName: String,
    val url: String,            // already-redacted form
    val statusCode: Int,        // 401 or 403
    val authState: AuthStateProjection,
  ) : RepositoryDownloadFailure()
}

sealed class AuthStateProjection {
  data object NotConfigured : AuthStateProjection()
  data object ConfiguredToken : AuthStateProjection()
  data object ConfiguredBasic : AuthStateProjection()

  // Owned by AuthStateProjection so the renderer reads the canonical
  // text without re-deriving it per call site. Req 7.3 fixes these
  // exact strings; tests pin them. The "from kolt.local.toml" suffix
  // is hardcoded because Req 2.1 rejects credential literals from
  // kolt.toml at parse time, so any configured credential that reaches
  // the resolver provably originated in kolt.local.toml.
  fun toDisplayString(): String = when (this) {
    NotConfigured -> "not configured"
    ConfiguredToken -> "configured (token, from kolt.local.toml)"
    ConfiguredBasic -> "configured (basic, from kolt.local.toml)"
  }
}
```

`AuthFailed` does **not** carry a `coordinate` field. The coordinate lives in the outer `ResolveError.DownloadFailed.groupArtifact` (or `MetadataDownloadFailed.groupArtifact` etc.), which is already what produces the headline today. The renderer reads coordinate from the outer error and context from `repositoryDownloadFailureContext` — same split as existing `AllAttemptsFailed`. See "Headline text reconciliation" below.

- **Preconditions**: Constructed only in `downloadFromRepositories` after a 401/403 response. `url` is passed through `redactUrlUserinfo` before storage.
- **Postconditions**: Carries enough state for `repositoryDownloadFailureContext` to produce the 5-line context list without re-fetching anything.
- **Invariants**: `AuthStateProjection` is fully decoupled from `RepositoryAuth` (the secrets-carrying type). Renderer switches on `AuthStateProjection` exhaustively via `toDisplayString()`; secrets never reach the renderer.

#### downloadFromRepositories (extended)

| Field | Detail |
|-------|--------|
| Intent | Iterate `List<Repository>` and dispatch downloads with auth headers; branch on 401/403 to hard-stop |
| Requirements | 5.1, 5.2, 5.3, 6.1, 6.2, 6.3, 6.4 |

**Contracts**: Service [x]

##### Service Interface

```kotlin
internal fun downloadFromRepositories(
  repos: List<Repository>,
  destPath: String,
  urlBuilder: (Repository) -> String,
  download: (String, String, Map<String, String>?) -> Result<Unit, DownloadError>,
  onRetry: (Repository) -> Unit = {},
): Result<Unit, RepositoryDownloadFailure>
```

The signature mirrors the current 5-parameter shape (`TransitiveResolver.kt:84-90`) and changes only the parameter types: `String → Repository` in `repos` / `urlBuilder` / `onRetry`, and `(String, String) → (String, String, Map<String, String>?)` in `download` so the per-repository headers can flow through to the infra layer. The DI seam (the `download` lambda, used by `ResolverDeps` for test injection) is preserved.

- **Preconditions**: `repos` non-empty (otherwise caller returns `NoRepositoriesConfigured` upstream); each `Repository` has valid url and (optional) auth.
- **Postconditions**: Returns `Ok` on first successful 2xx; `AuthFailed` on first 401/403 (no further iteration); `AllAttemptsFailed` if all repositories return 404 or network errors.
- **Invariants**: The order of iteration is the order of `repos` (TOML declaration order, Req 4.3). Auth header is computed per-repository (`repo.auth?.toHeaders()`), not shared across iterations.

**Implementation Notes**

- Integration: One change site at the existing branch (`TransitiveResolver.kt:99` today); add `else if (httpFailed.statusCode == 401 || httpFailed.statusCode == 403) → return AuthFailed(repositoryName = repo.name, ...)`.
- Callers (10 sites): `TransitiveResolver.kt:69` (sources jar fallback), `:128` (POM), `:186` (module metadata), `:252` (binary artifact); `NativeResolver.kt:139, 395` (klib + secondary path); `BundleResolver.kt:124, 197` (classpath bundle fetch); `OutdatedCommand.kt:82` (HEAD probes for newer versions); `DependencyCommands.kt:165` (add-command probing). Every caller's `urlBuilder` migrates from `(String) -> String` to `(Repository) -> String`, and the `download` lambda swap goes through `ResolverDeps.downloadFile`.

**`urlBuilder` lambda discipline**: every lambda body must extract the URL via `repo.url`, never `repo.toString()` or `"$repo"`. The `Repository.toString()` returned by the auto-generated data-class `toString` includes the `auth` field (which is itself redacted-safe, see §RepositoryAuth), but it is NOT a URL — using it in string concatenation produces a malformed download URL and a silent regression. Lambdas SHOULD be written as `{ "${it.url}/$groupPath/..." }` rather than `{ "$it/..." }`. The implementer is responsible for grepping every migrated lambda for `"$it/"` / `"$it."` substrings before merge.
- Validation: Test matrix includes `[private-401, central-200]` to confirm iteration stops at the 401 and central is NOT contacted; `[mirror-404, central-200]` to confirm 404 fall-through unchanged. The 4-call-site fan-out in `TransitiveResolver` itself is covered by the existing `TransitiveResolverTest`; the off-`TransitiveResolver` callers (`BundleResolver`, `OutdatedCommand`, `DependencyCommands`) each need a smoke test confirming the `Repository` typed input does not break their existing behavior.
- Risks: A 401 from a misconfigured central is now a hard error. This is intentional but reverses prior behavior where central effectively had no auth surface. Migration note in release notes documents the change.

#### formatResolveError (extended)

| Field | Detail |
|-------|--------|
| Intent | Render `RepositoryDownloadFailure.AuthFailed` into the 6-line diagnostic |
| Requirements | 7.1, 7.2, 7.3, 7.4, 8.3, 9.3 |

**Responsibilities & Constraints**

- Sealed `when` over `RepositoryDownloadFailure`; the AuthFailed branch emits the headline plus the 5 context lines
- State-and-status to hint table:

| status \ state | NotConfigured | ConfiguredToken | ConfiguredBasic |
|---|---|---|---|
| 401 | repository requires authentication; add credentials to `kolt.local.toml` | credentials may be invalid or expired | credentials may be invalid or expired |
| 403 | authentication required | credentials are valid but lack permission for this repository | credentials are valid but lack permission for this repository |

- All `<url>` fields go through `redactUrlUserinfo` defensively even though they already are at construction time. The double-redaction is idempotent

**Contracts**: Service [x]

##### Service Interface

```kotlin
internal fun formatResolveError(error: ResolveError): RenderedDiagnostic
// extended branch inside repositoryDownloadFailureContext:
internal fun repositoryDownloadFailureContext(failure: RepositoryDownloadFailure): List<String> =
  when (failure) {
    is NoRepositoriesConfigured -> listOf(/* existing hint, unchanged */)
    is AllAttemptsFailed -> failure.attempts.map { "${it.url} -> ${formatAttemptStatus(it.error)}" }
    is AuthFailed -> listOf(
      "repository: ${failure.repositoryName}",
      "url: ${redactUrlUserinfo(failure.url)}",
      "status: ${failure.statusCode} ${reasonPhrase(failure.statusCode)}",
      "credentials: ${failure.authState.toDisplayString()}",
      "hint: ${formatAuthHint(failure.statusCode, failure.authState)}",
    )
  }
```

- **Preconditions**: `failure.statusCode` is 401 or 403 (constructor enforces); `failure.authState` is one of three discriminants.
- **Postconditions**: Returns a 5-entry context list in the declared order. Headline is owned by the outer `ResolveError` variant ("failed to download X" for `DownloadFailed`, "could not fetch metadata for X" for `MetadataDownloadFailed`, etc.).
- **Invariants**: No raw credential value (`token` / `user` / `password` / base64 encoded `user:password`) appears in any returned string.

**Headline text reconciliation**: Req 7.1 specifies the headline via existing `ResolveError`-variant phrasing. The two relevant variants in v0.20.0 are `failed to download <coordinate>` (`ResolveError.DownloadFailed`, used by POM / artifact / sources / klib fetch) and `could not fetch metadata for <coordinate>` (`ResolveError.MetadataDownloadFailed`, used by Gradle module metadata fetch). The 401/403 diagnostic therefore appears under whichever outer headline naturally describes the operation that triggered the fetch. Test pins assert the full headline+context shape per scenario (POM 401 → "failed to download G:A" + 5 context lines; metadata 401 → "could not fetch metadata for G:A" + 5 context lines). This is consistent with the existing `AllAttemptsFailed` rendering split.

The outer `ResolveError` `when` in `formatResolveError` (`Resolver.kt:75-138`) is **not** modified: `AuthFailed` is a new variant of `RepositoryDownloadFailure`, not of `ResolveError`, so the new branch is added to `repositoryDownloadFailureContext` (`Resolver.kt:151-159`) rather than the top-level renderer. The existing `AllAttemptsFailed` aggregation path is untouched, which is why Req 6.4 (aggregated multi-repo errors only group 404s) falls out for free.

## Data Models

### Domain Model

```mermaid
graph LR
    KoltConfig --> Repositories[Map name to Repository]
    Repositories --> Repository
    Repository --> Url
    Repository --> Auth[RepositoryAuth nullable]
    Auth --> Bearer[Bearer token]
    Auth --> Basic[Basic user password]
```

**Invariants**:

- `Repository.auth` is `null` ⇔ the repository is anonymous; never an empty `Bearer("")` or `Basic("","")`
- `Repository.name` matches the TOML map key (after quote-stripping per existing `liftRepositoriesMap` convention). This is a structural redundancy (the name lives both as map key and inside the value), enforced at construction in `liftRepositoriesMap`. Callers that hold a `Repository` outside its enclosing map can rely on `.name` directly; callers iterating the map must not construct a `Repository` with a name different from its key
- The map preserves TOML declaration order (LinkedHashMap)
- `Repository.toString()` is the Kotlin auto-generated data-class `toString` (we do not override it). It produces `"Repository(name=<n>, url=<u>, auth=null)"` for anonymous repositories and `"Repository(name=<n>, url=<u>, auth=RepositoryAuth.Basic(user=<u2>, password=<redacted>))"` (or `Bearer(token=<redacted>)`) for authenticated ones. Safety is delegated to `RepositoryAuth.Bearer.toString()` and `RepositoryAuth.Basic.toString()` (both explicitly override `toString` to redact secret fields, see §RepositoryAuth State). This chain is asserted by `RepositoryAuthRedactionTest`.

### Logical Data Model

The new fields on `RawRepository`:

```kotlin
@Serializable
internal data class RawRepository(
  val url: String? = null,
  val token: RawCredentialField? = null,
  val user: RawCredentialField? = null,
  val password: RawCredentialField? = null,
)
```

The lifted `Repository`:

```kotlin
data class Repository(
  val name: String,
  val url: String,
  val auth: RepositoryAuth? = null,
)
```

### Data Contracts & Integration

- **kolt.toml schema**: `[repositories.<name>] url = "..."` plus optional `token` / `user` / `password` (always rejected at base placement validator if present in `kolt.toml`)
- **kolt.local.toml schema**: same shape; literal credentials accepted only here
- **No schema change to `kolt.lock`** (Req 8.4, Req 10.x) — verified by an explicit test pinning the lockfile JSON shape

## Error Handling

### Error Strategy

All new error paths return `Result<V, E>` per ADR 0001. The two new error surfaces:

- **Config-time**: `ConfigError.ParseFailed` (existing), with `path` set to the originating file (`kolt.toml` for base, `kolt.local.toml` for overlay-sourced via `repositorySourceMap`)
- **Resolve-time**: `RepositoryDownloadFailure.AuthFailed` (new), routed through the existing `formatResolveError` renderer

### Error Categories and Responses

- **Schema validation errors** (config): mutex / empty / userinfo / env-reject / placement policy → `ParseFailed` with field-named message and (where relevant) source file attribution
- **Network errors** (resolve): 401/403 → `AuthFailed` hard error with state-specific hint; 404 → unchanged fall-through; libcurl-level failures → existing `NetworkError` (no change)
- **Credential leak prevention**: Architectural invariant rather than runtime check. The renderer never receives the secret-carrying `RepositoryAuth` type; it receives `AuthStateProjection` only. Tests assert this end-to-end

### Monitoring

No new logging is introduced. If future verbose-mode logging is added, the dev must call `redactUrlUserinfo` before emitting URLs. This contract is documented adjacent to `redactUrlUserinfo` in code.

## Testing Strategy

### Unit Tests (config layer)

1. **`RepositoryAuthConfigTest`** — parametrized Req 1 + Req 2 + Req 2.3 fixture matrix:
   - schema accept/reject: (a) `token = { literal = "abc" }` accepted as Bearer; (b) `user = { literal = "u" }` + `password = { literal = "p" }` accepted as Basic; (c) `token` + `user` mutex rejected; (d) `user` alone rejected; (e) empty / whitespace `literal` rejected; (f) inline-table with neither `literal` nor `env`, or both, rejected (Req 1.6).
   - placement: (g) literal `token` in `kolt.toml` rejected, message names `kolt.toml`; (h) literal `token` in `kolt.local.toml` accepted; (i) overlay-sourced env-reject names `kolt.local.toml` via `repositorySourceMap`; (j) reject message contains no credential value.
   - env-form recognize-and-reject: (k) `token = { env = "X" }` rejected with the canonical v1.0 message; (l) same for `user` / `password`.
   - The 2-hop UX (kolt.toml `{env = "..."}` → placement message → move to kolt.local.toml → env-not-supported message) is not pinned with a separate test case. The message difference between (g) base-placement and (k) env-reject is sufficient to verify that the validator order produces the right teaching sequence ([[feedback_sdd_test_inflation]]).
2. **`RepositoryUrlUserinfoTest`** — Req 3: (a) `https://u:p@host/x` rejected; (b) `https://u@host/x` rejected; (c) `https://host/foo@bar/baz` accepted (no userinfo); (d) rejection message contains no userinfo character.

### Unit Tests (infra layer)

3. **`UrlRedactionTest`** — Req 3.3, 8.3, 9.3: 5-case redaction matrix.
4. **`DownloaderAuthHeaderTest`** — Req 5.1, 5.2, and the redirect policy: loopback HTTP fixture server confirms (a) Bearer header attached for `RepositoryAuth.Bearer`; (b) Basic header attached with correct base64 for `RepositoryAuth.Basic`; (c) no Authorization header in the loopback server access log when `headers == null` (no empty `"Authorization: "` line either); (d) cross-origin 302 redirect does NOT forward the Authorization header (`CURLOPT_UNRESTRICTED_AUTH = 0L`). The libcurl slist `free_all` discipline in the `finally` block is enforced by code review, not by a separate observable test (Kotlin/Native has no in-test heap accounting).

### Integration Tests (resolve layer)

5. **`RepositoryAuthFailureTest`** — Req 6, 7: (a) `[401-repo, central-200]` returns 401 hard error, central is NOT contacted; (b) `[404-repo, 401-repo, central-200]` returns 401 hard error from the second repo; (c) 4-row parametrized matrix (`(401, 403) × (not configured, configured)`) asserts the hint text exactly.
6. **`RepositoryAuthRedactionTest`** — Req 8: (a) stderr from a 401 fetch contains no Bearer token; (b) stderr from a Basic-auth 401 contains no base64 / no user / no password; (c) `RepositoryAuth.Basic("u","p").toString()` returns the `<redacted>` placeholder; (d) `Repository(name="x", url="y", auth=RepositoryAuth.Basic("alice","s3cret")).toString()` contains the literal substring `"<redacted>"` and does NOT contain `"s3cret"` (Repository → RepositoryAuth toString chain); (e) `kolt.lock` after a successful authenticated fetch contains no credential field; (f) `kolt info` JSON contains no credential field.

### Implementation invariants (additional pin tests)

7. **`PlacementOrderTest`** (inside `RepositoryAuthConfigTest`'s fixture pool, parametrized) — Req 2.1 ordering: a `kolt.toml` carrying a literal `token` plus a `kolt.local.toml` that "overrides" the same field to `null` shall still fail with the placement-policy error (the base-validator runs before merge, so the overlay cannot "rescue" the violation).
8. **`RepositoryNameInvariantTest`** (inside `RepositoryAuthConfigTest`) — Req 1.x post-lift: for every entry in the returned `Map<String, Repository>`, `entry.key == entry.value.name`.
9. **`AllCallersTypedTest`** — compile-time smoke: every one of the 10 `downloadFromRepositories` call sites and every one of the 9 `config.repositories.values.toList()` sites compiles with `List<Repository>` and the `(Repository) -> String` urlBuilder lambda type. This is implicitly covered by `kolt build` succeeding after migration, but tasks must verify the compile gate explicitly before claiming completion. The anonymous-no-slist assertion lives inside `DownloaderAuthHeaderTest` (c), not as a separate entry here.

### Cross-cutting Tests

10. **`RepositorySchemaMigrationTest`** (existing, extended) — Req 9: flat-form rejection now suggests `[repositories.<name>] url = "..."` (the new sub-table form); flat with URL userinfo still scrubs.

### Self-host smoke gate

Independent of the unit / integration tests above, the implementation tasks must include a self-host bootstrap check: `kolt build` against kolt's own four `kolt.toml` files (`/kolt.toml`, `/kolt-jvm-compiler-daemon/kolt.toml`, `/kolt-native-compiler-daemon/kolt.toml`, `/spike/native-ktor-cinterop-smoke/kolt.toml`) must succeed after the new `liftRepositoriesMap` validators land. All four already use `[repositories.central]` sub-table form, but the new userinfo / mutex / shape checks enter the hot path for every kolt parse, and a regression here breaks kolt's bootstrap. CI's `self-host-post` job covers this; tasks must not be marked complete without it green. [[feedback_subagent_smoke_misses_ci]]

### Test discipline

- Tests are sized 1 per acceptance criterion family, not 1 per AC. Schema / placement / env-reject share fixtures inside `RepositoryAuthConfigTest` (parametrized) rather than three separate files. The 4-row hint matrix in `RepositoryAuthFailureTest` is parametrized similarly. [[feedback_sdd_test_inflation]]
- Loopback HTTP fixture (`testfixture/LoopbackHttpServer.kt`) is the testbed for `DownloaderAuthHeaderTest` and `RepositoryAuthFailureTest`; pattern follows `UnixEchoServer` (structure.md §Test fixtures).

## Security Considerations

- **Credential plaintext on disk**: ADR 0034 §3 commits to plaintext in `kolt.local.toml`; this is gitignored by default (#417). Filesystem-level protection (mode 600, encrypted home, etc.) is out of scope and left to the user
- **Credential-in-process memory**: `RepositoryAuth` is held in `Repository` while the resolver runs. No process-isolation step; this is consistent with v1.0 scope
- **`data class` `toString()` leakage**: `RepositoryAuth.Basic("u","p").toString()` exposes the password. Mitigation: `AuthFailed` never contains `RepositoryAuth`; the renderer receives `AuthStateProjection`. Test `RepositoryAuthRedactionTest` asserts the password string is absent from any output even when the test deliberately constructs a `RepositoryAuth.Basic` and walks it through the full error path
- **TLS / certificate validation**: libcurl defaults (system CA bundle) — unchanged. Custom CA support is out of scope (issue #416 non-goal)
- **Lockfile integrity**: Unchanged. Credentials authenticate the fetch, not the artifact; SHA-256 in `kolt.lock` remains the artifact-trust mechanism (ADR 0034 §5)

## Supporting References

See `.kiro/specs/private-maven-repos/research.md` for the codebase survey and the key-decision rationale that informed each commitment in this design.
