# Requirements Document

## Project Description (Input)

**Source**: issue #416 — Private Maven repository support (literal credentials, v1.0). Milestone v0.20.0. Replaces #33 (closed). Architectural baseline: `docs/adr/0034-private-maven-repos.md` (already written; treat as input to design.md, do not re-derive).

**Problem owner**: kolt users who need to fetch dependencies from authenticated Maven repositories — GitHub Packages, corporate Nexus, Cloudsmith, GitLab Package Registry — and cannot today.

**Current situation**: `[repositories.<name>]` sub-table is anonymous-only (`url` only; no auth fields). Any 401/403 response is unusable: no way to declare credentials, no way to attach `Authorization` headers, no specialized diagnostic for credential failures. The overlay merge mechanism (#415) is in place; kolt.local.toml is gitignored by default (#417).

**What should change**: Add the minimum schema, runtime, and diagnostic surface to fetch from authenticated repos in v1.0, with env-var-based credentials intentionally deferred to v1.1+.

### Prerequisites (landed)

- #415 (kolt.local.toml overlay scope extended to `[repositories]`) — closed 2026-05-13.
- #417 (`kolt init` `.gitignore` automation for `kolt.local.toml`) — closed 2026-05-13.

### Out of scope (deferred)

`{ env = "..." }` env-var indirection (v1.1+, additive); `${env.X}` interpolation inside string field values (v1.1+; v1.0 treats `${env.X}` as a literal string per ADR 0034 §3); credential helpers / `.netrc` / OAuth / device flow; HTTP proxy / mTLS / custom CA bundles / client certificates; per-repository retry / backoff / timeout overrides; credential storage outside `kolt.toml` / `kolt.local.toml`; Sonatype Portal publishing auth (publish-side).

## Boundary Context

- **In scope**: schema and field validation for `token` / `user` / `password` on `[repositories.<name>]` (uniform inline-table form, see Schema commitment below); per-request `Authorization` header attachment to repository HTTP traffic; 401/403 diagnostic shape and no-fall-through semantics; redaction of credential material from any kolt output; rejection of legacy flat-string-map `[repositories]` form with a migration directive.
- **Out of scope**: any credential source other than literal values in `kolt.local.toml`; any auth scheme other than HTTP Bearer / HTTP Basic; any change to 404 fall-through behavior or aggregated-error formatting; any change to lockfile integrity (SHA-256 hash verification remains the sole artifact-trust mechanism per ADR 0034 §5).
- **Schema commitment**: auth fields use a uniform inline-table form (`token = { literal = "..." }` / `{ env = "..." }`). The bare-string form (`token = "..."`) is rejected at parse time. ADR 0034 §4 leaves the field shape to #416; this spec commits to inline-table for the same reason kolt already uses inline-table polymorphism for `[*.sys_props]` values (the TOML parser surface in use cannot decode "bare-string OR inline-table" polymorphism on a single field). Detailed rationale lives in design.md.
- **Adjacent expectations**:
  - The `[repositories]` overlay merge from #415 is the only mechanism by which `kolt.local.toml` supplies credential fields against a repository named in `kolt.toml`. A repository name appearing only in `kolt.local.toml` is rejected by the existing overlay validator and is not re-litigated here.
  - The `kolt.local.toml` `.gitignore` guarantee (#417) is assumed for the placement-policy rationale but is not enforced by this feature.
  - Existing `[repositories.<name>]` sub-tables in `kolt.toml` and `kolt.local.toml` that pre-date this feature (no `token` / `user` / `password` fields) must continue to parse cleanly after upgrade. The new credential fields default to absent (`null` at the raw-decode level).

## Requirements

### Requirement 1: 認証フィールド付き `[repositories.<name>]` schema

**Objective:** kolt 利用者として、 各リポジトリに対して Bearer または Basic 認証情報を宣言的に指定したい。 そうすれば認証付き Maven リポジトリから依存関係を取得できる。

> Schema commitment lives in the Boundary Context section; this section's ACs describe testable behaviors only.

#### Acceptance Criteria

1. The kolt config parser shall accept `[repositories.<name>]` sub-tables with an optional `token` field, an optional `user` field, and an optional `password` field, in addition to the required `url` field, where each auth field is an inline-table (`{ literal = "<value>" }` or `{ env = "<env-var-name>" }`).
2. If a `[repositories.<name>]` entry sets both `token` and at least one of `user` / `password`, the kolt config parser shall reject the configuration with a message naming the repository and explaining that `token` is mutually exclusive with `user` / `password`.
3. If a `[repositories.<name>]` entry sets `user` without `password` or `password` without `user`, the kolt config parser shall reject the configuration with a message naming the repository and explaining that `user` and `password` must be declared together.
4. If a `[repositories.<name>]` entry sets `token`, `user`, or `password` with a `literal` value that is empty or whose characters are all Unicode whitespace codepoints, the kolt config parser shall reject the configuration with a message naming the offending field and the repository.
5. When a `[repositories.<name>]` entry sets only `url` (no auth fields), the kolt resolver shall treat the repository as anonymous and shall send no `Authorization` header to that repository.
6. If a `[repositories.<name>]` auth field's inline-table sets neither `literal` nor `env`, the kolt config parser shall reject the configuration with a message naming the offending field and the repository.
7. If a `[repositories.<name>]` auth field's inline-table sets both `literal` and `env`, the kolt config parser shall reject the configuration with a message naming the offending field and the repository.
8. When `kolt.toml` or `kolt.local.toml` declares a `[repositories.<name>]` sub-table without any of `token` / `user` / `password` (the pre-v0.20.0 shape), the kolt config parser shall parse it without producing an error and the kolt resolver shall treat that repository as anonymous.

### Requirement 2: 認証情報の配置ポリシー

**Objective:** kolt 利用者として、 共有される `kolt.toml` には機密値を絶対に書かない仕組みを持ちたい。 そうすれば誤コミットを防げる。

#### Acceptance Criteria

1. If `kolt.toml` declares any of `token`, `user`, or `password` as a literal value under `[repositories.<name>]`, the kolt config parser shall reject the configuration with a message identifying `kolt.toml` as the source file, naming the repository and the offending field, and directing the user to declare the field in `kolt.local.toml` instead.
2. The kolt config parser shall accept literal `token`, `user`, and `password` values when they appear under `[repositories.<name>]` in `kolt.local.toml` and the repository name also appears in `kolt.toml`.
3. If a `[repositories.<name>]` entry in either file sets any auth field to the inline-table form `{ env = "<env-var-name>" }`, the kolt config parser shall reject the configuration with a message stating that env-reference is not supported in v1.0 and directing the user to use the `{ literal = "..." }` form in `kolt.local.toml`.
4. When the placement-policy validator rejects a literal credential in `kolt.toml`, the error message shall not include the credential value itself.

### Requirement 3: URL に埋め込まれた認証情報の拒否

**Objective:** kolt 利用者として、 URL に user:password を埋め込む形を使えないようにし、 認証情報の管理を専用フィールドに集約したい。

#### Acceptance Criteria

1. If a `[repositories.<name>]` entry's `url` field contains a userinfo component (an `@` character before the host portion of the URL), the kolt config parser shall reject the configuration with a message naming the repository and directing the user to declare credentials in the `token` field or the `user` / `password` fields.
2. The kolt config parser shall apply the userinfo check regardless of whether the userinfo component includes a password (`user@host` and `user:pass@host` are both rejected).
3. When the URL-userinfo validator rejects a URL, the error message shall not include any character of the userinfo component (no `user`, no `password`, no `:`-separated combination).

### Requirement 4: 暗黙 `central` フォールバックと宣言順序の保存

**Objective:** kolt 既存利用者として、 認証機能の追加によって `central` の暗黙フォールバックと TOML 宣言順序のセマンティクスが変わらないことを保証したい。

#### Acceptance Criteria

1. When `kolt.toml` declares no `[repositories]` sub-tables at all, the kolt config parser shall populate the repository map with a single `central → https://repo1.maven.org/maven2` entry, so the kolt resolver iterates a single anonymous Maven Central entry as if it had been declared explicitly.
2. When `kolt.toml` declares `[repositories.central]` explicitly, the kolt resolver shall treat it as any other repository entry (no special reservation of the `central` name, no merging with the implicit fallback).
3. When `kolt.toml` declares two or more `[repositories.<name>]` sub-tables, the kolt resolver shall iterate them in TOML declaration order during dependency resolution.

### Requirement 5: 全 resolver 入口・全 artifact 種別への認証適用

**Objective:** kolt 利用者として、 どの kolt サブコマンドを使っても、 どの artifact を取得するときでも、 設定した認証情報が一貫して使われたい。

#### Acceptance Criteria

1. When the kolt resolver issues an HTTP request to a `[repositories.<name>]` whose `token` field is set, the kolt resolver shall attach the header `Authorization: Bearer <token>` to that request.
2. When the kolt resolver issues an HTTP request to a `[repositories.<name>]` whose `user` and `password` fields are set, the kolt resolver shall attach the header `Authorization: Basic <base64(user:password)>` to that request.
3. The kolt resolver shall apply the Authorization-attachment rules from acceptance criteria 1 and 2 to every HTTP request it issues to a repository, regardless of which CLI subcommand initiated the resolution (`build`, `run`, `test`, `check`, `fetch`, `update`, `tree`, `deps`, `add`, `remove`, `outdated`) and regardless of which artifact class is being fetched (declared dependencies, test dependencies, `[classpaths.<name>]` bundle entries, tools, compiler plugin jars, incremental-compilation support libraries).

### Requirement 6: 401 / 403 のハードエラー化

**Objective:** kolt 利用者として、 認証エラーが他のリポジトリへの fall-through で隠蔽されることなく、 即座に診断できるようにしたい。

#### Acceptance Criteria

1. If a repository responds with HTTP status 401 or 403 to any kolt-issued request, the kolt resolver shall stop iterating subsequent repositories for that coordinate and shall surface the failure as a hard error.
2. The kolt resolver shall apply the 401 / 403 hard-error rule regardless of whether the repository had any auth fields configured.
3. When a repository responds with HTTP status 404, the kolt resolver shall continue to fall through to the next repository in declaration order (existing behavior, unchanged).
4. When the kolt resolver aggregates multi-repository failures into a single error (existing v0.18.1 format), the aggregation shall include only 404 attempts; 401 / 403 attempts shall surface as a stand-alone diagnostic per Requirement 7.

### Requirement 7: 401 / 403 用の専用エラー診断

**Objective:** kolt 利用者として、 認証失敗時に何が起きているか・どこに何を設定すべきかを最小限の出力で把握したい。

#### Acceptance Criteria

1. When the kolt resolver emits a 401 / 403 diagnostic, the headline shall identify the requested coordinate (`group:artifact[:version]`) using the existing `ResolveError`-variant phrasing for the operation that triggered the fetch. The enumerable variants for v0.20.0 are: `failed to download <coordinate>` (`ResolveError.DownloadFailed`, used by POM / artifact / sources / klib fetch) and `could not fetch metadata for <coordinate>` (`ResolveError.MetadataDownloadFailed`, used by Gradle module metadata fetch).
2. The kolt resolver shall include the following context lines in declaration order: `repository: <name>`, `url: <url>`, `status: <code> <reason>`, `credentials: <state>`, `hint: <state-specific>`.
3. The `<state>` value shall be exactly one of: `not configured`, `configured (token, from kolt.local.toml)`, `configured (basic, from kolt.local.toml)`.
4. When the kolt resolver emits a 401 or 403 diagnostic, the `<state-specific>` hint line shall be the entry in the following table matching the `(status, credentials state)` pair:

| status | credentials state | hint text |
|--------|-------------------|-----------|
| 401 | `not configured` | the repository requires authentication; add credentials to `kolt.local.toml` |
| 401 | configured (token or basic) | the credentials may be invalid or expired |
| 403 | `not configured` | authentication is required |
| 403 | configured (token or basic) | the credentials are valid but lack permission for this repository |

### Requirement 8: 認証情報の redaction 保証

**Objective:** kolt 利用者として、 設定した認証情報が誤って出力経路に漏れることがないと保証されたい。 そうすれば CI ログやエラー出力を安心して共有できる。

#### Acceptance Criteria

1. The kolt resolver shall not include any `Authorization` header value (Bearer token, Basic credential, or any future scheme) in any error message, log line, or stderr output.
2. The kolt config parser and resolver shall not include any value of the `token`, `user`, or `password` fields in any error message, log line, or stderr output.
3. As a defense-in-depth measure (Requirement 3 catches userinfo URLs at parse time, but a credential value supplied via a different code path must not leak), the kolt resolver shall not include any userinfo component (text between `://` and `@` of a URL) in any error message, log line, or stderr output.
4. The kolt resolver shall not write any value of `token`, `user`, `password`, `Authorization`, or any URL userinfo component to `kolt.lock`.
5. The kolt resolver shall enforce the redaction guarantees in acceptance criteria 1 through 4 regardless of current or future verbosity flags or debug modes.

### Requirement 9: 既存 flat-string-map 形式の拒否

**Objective:** kolt 利用者として、 古い `[repositories]` flat-string-map 形式を残したまま 0.20.0 にアップグレードしたとき、 黙って壊れるのではなく明確なエラーと移行先を示してほしい。

#### Acceptance Criteria

1. If `kolt.toml` declares a flat-string-map `[repositories]` entry of the form `<name> = "<url>"`, the kolt config parser shall reject the configuration with a message identifying `kolt.toml` as the source file.
2. The kolt config parser's rejection message for a flat-string-map entry shall include a directive to use the sub-table form `[repositories.<name>] url = "<url>"`.
3. When the kolt config parser rejects a flat-string-map entry that contained credentials via URL userinfo (e.g. `central = "https://user:pass@..."`), the error message shall not include any character of the userinfo component.

### Requirement 10: lockfile-hash による artifact 整合性は不変

**Objective:** kolt 利用者として、 認証は fetch の認可手段にすぎず、 artifact 内容の改ざん検知は引き続き lockfile の SHA-256 が担うことを期待したい。

#### Acceptance Criteria

1. When the kolt resolver successfully fetches an artifact from any repository (authenticated or anonymous), the kolt resolver shall verify the artifact's SHA-256 hash against `kolt.lock` using the same mechanism as before this feature.
2. The kolt resolver shall not weaken or skip lockfile hash verification based on whether the source repository was authenticated.
