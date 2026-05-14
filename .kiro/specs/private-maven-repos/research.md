# Gap Analysis: private-maven-repos

**Date**: 2026-05-13
**Inputs**: requirements.md (10 件), ADR 0034, codebase survey (Downloader / TransitiveResolver / Config / LocalOverlay / Resolver)

## 1. Current State Investigation

### Domain assets relevant to this feature

| Asset | File | Current shape |
|---|---|---|
| `RawRepository` (ktoml decode) | `src/nativeMain/kotlin/kolt/config/Config.kt:173` | `data class RawRepository(val url: String? = null)` — no auth fields. |
| `Repository` (typed) | `Config.kt:108` | `data class Repository(val url: String)` — no auth fields. |
| `liftRepositoriesMap` (validator) | `Config.kt:256-269` | Strips quotes, rejects null/empty URL, trims trailing slash. No userinfo check. |
| `mergeRepositories` (overlay merge) | `LocalOverlay.kt:82-105` | Field-level merge (line 102: `baseRepo.copy(url = overlayRepo.url ?: baseRepo.url)`). Rejects overlay-only names. No source-attribution map yet. |
| Implicit `central` fallback | `Config.kt:185-186` (raw) + `Config.kt:120` (typed) | Populated by ktoml decoding the `RawKoltConfig.repositories` data-class field default `mapOf("central" to RawRepository(MAVEN_CENTRAL_BASE))` when the TOML omits `[repositories]`. The lifted `KoltConfig.repositories` has the same default for callers that bypass `parseConfig`. The `Resolver.kt:153` `NoRepositoriesConfigured` branch is a defensive surface for internal callers that construct `KoltConfig` with an explicit empty map. |
| `Downloader.downloadFile` (HTTP) | `infra/Downloader.kt:47-109` | `downloadFile(url, destPath): Result<Unit, DownloadError>`. libcurl easy-handle. No header parameter. No `curl_slist_append` use anywhere in codebase. |
| `DownloadError` | `infra/Downloader.kt:37-41` | `HttpFailed(url, statusCode)`, `NetworkError(url, message)`. All non-2xx flatten to `HttpFailed`. |
| `downloadFromRepositories` (iteration) | `resolve/TransitiveResolver.kt:84-107` | Iterates `repos: List<String>` (URL-only). Falls through on 404 (line 99); hard-stops on any other error. Returns `RepositoryDownloadFailure.AllAttemptsFailed`. |
| `formatResolveError` (diagnostic) | `resolve/Resolver.kt:75-138` | Emits headline + per-attempt context lines (`url -> status`). `repositoryDownloadFailureContext` helper at lines 151-159 renders `NoRepositoriesConfigured` and `AllAttemptsFailed` variants. |
| `RawSysPropValue` polymorphism (precedent) | `config/SysPropValue.kt:27-31` + `Config.kt:220-250` | All-nullable-fields trick: `literal / classpath / project_dir` decoded as nullable, validator requires exactly one. **Reference pattern for `{env = "..."}` recognition.** |
| `sysPropSourceMap` (per-key source attribution) | `Config.kt:398-414` | Maps each sys_prop key to its contributing file (base / overlay). **Reference pattern for repository field-level source attribution.** |
| `RepositoryAttempt` (error context) | `resolve/Resolver.kt:18` | `RepositoryAttempt(url, error)` — URL only, no repository name. |

### HTTP traffic call sites (relevant to Req 5 universal auth attachment)

All seven `downloadFile` entry points converge on the single `Downloader.downloadFile`. Note: this table counts direct `Downloader.downloadFile` callers (i.e., HTTP entry points). The propagation layer above is `downloadFromRepositories`, which is itself called from 10 sites; see design.md File Structure Plan for that enumeration. The two layers are different and should not be conflated.

| Caller | File:line | Repository context passed |
|---|---|---|
| POM fetch | `TransitiveResolver.kt:128-134` | `repos: List<String>` |
| Module metadata | `TransitiveResolver.kt:186-191` | `repos: List<String>` |
| Binary / artifact materialization | `TransitiveResolver.kt` (materialization) | `repos: List<String>` |
| Sources jar fallback | `TransitiveResolver.kt:69-75` | `repos: List<String>` |
| klib (native) | `NativeResolver.kt:139-145` | `repos: List<String>` |
| Plugin jar | `resolve/PluginJarFetcher.kt:147` | URL-only (hardcoded Maven Central) |
| BTA impl jar | `build/daemon/BtaImplFetcher.kt:47-55` | Synthetic config, funnels through `resolveTransitive` |

**Repository name is lost after `parseConfig`**: every resolver call passes `config.repositories.values.map { it.url }` (TransitiveResolver.kt:20, NativeResolver.kt:67). The map key (= repository name) is dropped. This matters for Req 7 (`repository: <name>` line in 401/403 diagnostic).

### Redaction surface (relevant to Req 8)

Confirmed by `grep -rn "Authorization\|userinfo\|@.*://\|://.*@" src/nativeMain/` — zero hits.

| Output channel | Emits URL today? | Emits repo name? | Notes |
|---|---|---|---|
| `formatResolveError` stderr | Yes (per-attempt `url ->`) | No | The only current URL emission path. |
| `kolt.lock` | No | No | `Lockfile.kt` stores `version`, `kotlin`, `jvmTarget`, `dependencies: Map<String, LockEntry>` (each entry: `version`, `sha256`, `transitive`, `test`, `redirectTarget`), and `classpathBundles: Map<String, Map<String, LockEntry>>`. No repository URL or auth field is persisted in any of these. |
| `kolt info` JSON | No | No | `cli/InfoCommand.kt` `InfoSnapshot` data class (lines 72-84); `parseError` field at line 82 carries config error string but not URLs. |
| Watch-mode marker | No | No | `cli/WatchChangeDispatch.kt:77` and `cli/InfoCommand.kt:382` are the two call sites that invoke `renderConfigErrorAsLine`. The line emits the rendered string (which contains no URL by construction); no separate URL path. |
| libcurl request log | No | No | Verbose libcurl is not enabled in `Downloader.kt`. |

URL inline credentials (`https://user:pass@host/...`) would currently flow verbatim into `DownloadError.HttpFailed(url, ...)` → `RepositoryAttempt.url` → stderr if the parser failed to reject them (Req 3 catches at parse, Req 8.3 catches defensively if a URL slips through).

### kolt's own kolt.toml files (relevant to Req 9 migration)

| Path | `[repositories]` form |
|---|---|
| `/kolt.toml:30` | `[repositories.central]` sub-table |
| `/kolt-jvm-compiler-daemon/kolt.toml:28` | `[repositories.central]` sub-table |
| `/kolt-native-compiler-daemon/kolt.toml:27` | `[repositories.central]` sub-table |
| `/spike/native-ktor-cinterop-smoke/kolt.toml:14` | `[repositories.maven_central]` sub-table |

All four are already on the new sub-table form. Issue #416 body's claim ("kolt's own three `kolt.toml` files do not declare `[repositories]`") is incorrect, but the conclusion (no internal migration needed) stands.

## 2. Requirements Feasibility Analysis

### Requirement-to-asset map

| Req | Asset extension needed | Status |
|---|---|---|
| 1 (schema + mutex) | `RawRepository` += {token, user, password, env}; new `RepositoryAuth` sealed type on `Repository`; new validator in `liftRepositoriesMap` | **Missing** |
| 2 (placement policy) | New base-validator (reject literals in `kolt.toml`); reuse `#436` source-attribution model (`repositorySourceMap`) for overlay attribution | **Missing** (model exists for sys_props, not for repositories) |
| 3 (URL userinfo) | New URL-userinfo check inside `liftRepositoriesMap`; redaction in error message | **Missing** |
| 4 (central fallback + iteration order) | Preserve `Config.kt:120` and `TransitiveResolver.kt:20` | **No change** |
| 5 (universal auth attachment) | Extend `Downloader.downloadFile` signature with headers; carry per-repo auth context through 7 call sites | **Missing** (libcurl header attach not used anywhere yet) |
| 6 (401/403 hard error) | Add 401/403 branch alongside the existing 404 fall-through in `downloadFromRepositories:99`; new `RepositoryDownloadFailure.AuthFailed` variant | **Missing** (status code flattens to HttpFailed today) |
| 7 (401/403 diagnostic format) | New `formatResolveError` branch; needs `repository name` (currently dropped) and `credentials state` (new enum) | **Missing** |
| 8 (redaction guarantee) | Defensive scrub of URL userinfo in `DownloadError.HttpFailed`; never log Authorization; lockfile / kolt.lock confirmed clean today | **Partial** (lockfile clean, but URL-userinfo defensive scrub is missing) |
| 9 (legacy flat-form rejection) | Already in place via `KtomlMessageParse` + `ConfigParseMessageFormatTest` pinning. Verify migration hint text matches issue body. | **Partial** (rejection exists; message text needs check) |
| 10 (lockfile hash unchanged) | `Lockfile.kt` write path unchanged | **No change** |

### Complexity signals

- **External integration**: libcurl header attachment (new for kolt — no prior `curl_slist_append` use).
- **Type / schema polymorphism**: `token` / `user` / `password` must accept literal string OR inline-table `{ env = "..." }` (recognize-and-reject for v1.0). ktoml 0.7.1 cannot do string-OR-table polymorphism on a standard `KSerializer`; the precedent is the custom `SysPropValueSerializer` (`SysPropValue.kt:46-59`). [[reference_ktoml_decode_quirks]]
- **Cross-cutting redaction**: Req 8 cuts across every code path that emits a URL or auth value. Mostly clean slate, but the URL-userinfo defensive scrub is the one ongoing obligation.
- **Repository-name propagation**: Either thread `Repository` (name + url + auth) through the resolver chain or build a URL → auth side table at the resolver boundary. Affects 7 call sites either way.

### Constraints from existing architecture

- **ADR 0001** — All new error paths must return `Result<V, E>` (no exceptions). Applies to the custom serializer for credential fields: deserialize must not throw; if ktoml forces an exception, we wrap.
- **ADR 0006** — libcurl is the chosen native HTTP path. No ktor-client introduction.
- **ADR 0032 §2** — `${env.X}` indirection deferred. Req 2.3 recognizes the inline-table form but rejects it.
- **#415 overlay contract** — A repository name in `kolt.local.toml` but not in `kolt.toml` is already rejected. We do not re-litigate this.
- **kotlin-result 2.x is value class** — Use `getOrElse` / `getError` / `isErr`; never `is Ok` / `is Err`. [[feedback_kotlin_result_value_class]]

## 3. Implementation Approach Options

### Option A: URL-keyed side table at resolver boundary

**Shape**: Keep `Downloader.downloadFile(url, destPath)` unchanged in surface but add an internal `headers: Map<String, String>? = null` parameter. The resolver builds a `Map<URL, RepositoryAuth>` from the config and looks it up per request. Repository name lookup for diagnostics uses a parallel `Map<URL, RepositoryName>`.

**Files touched**:
- `Downloader.kt` — header attachment via `curl_slist_append`.
- `TransitiveResolver.kt`, `NativeResolver.kt`, `PluginJarFetcher.kt`, `BtaImplFetcher.kt` — construct the side table and pass headers per call.
- `Config.kt` — extend `RawRepository` + `Repository` with auth fields.
- `LocalOverlay.kt` — extend `mergeRepositories` to merge auth fields.

**Trade-offs**:
- ✅ Smallest signature churn at resolver layer (`List<String>` stays).
- ✅ Single-point libcurl change.
- ❌ URL-keyed lookup is brittle if URLs normalize differently between config and request (trailing-slash, port, etc.).
- ❌ Reverse-mapping URL → repository name for diagnostics is fragile if two repositories share a URL (rare but possible).

### Option B: `Repository`-typed propagation

**Shape**: Resolver functions take `repos: List<Repository>` instead of `repos: List<String>`. `Repository` becomes `data class Repository(val name: String, val url: String, val auth: RepositoryAuth?)`. Auth is a sealed type (`Bearer(token)` / `Basic(user, password)`). Each call site passes the repository, not the URL.

**Files touched**:
- `Downloader.kt` — accept optional `headers` parameter (same as Option A).
- `TransitiveResolver.kt`, `NativeResolver.kt`, `downloadFromRepositories`, all 7 call sites — switch from `String` to `Repository`.
- `Config.kt` — extend `Repository` typed class.
- `Resolver.kt` (`RepositoryAttempt`, `formatResolveError`) — capture repository name in attempt records.

**Trade-offs**:
- ✅ Type-safe: repository name and auth always travel together.
- ✅ Req 7 (`repository: <name>` line) falls out naturally.
- ✅ Future credential schemes (mTLS, OAuth) extend `RepositoryAuth` cleanly.
- ❌ Wider blast radius: every resolver-function signature shifts from `String` to `Repository`.
- ❌ Lockfile and other downstream code that reads `repos: List<String>` (if any) needs adjustment.

### Option C: Hybrid — typed propagation through resolve layer + Downloader signature change

**Shape**: Resolver carries `List<Repository>`; at the boundary to `Downloader.downloadFile`, the resolver builds the headers and passes URL + headers. `Downloader` keeps a simple signature (`url, destPath, headers?`). The resolver also captures the repository name into `RepositoryAttempt`.

**Files touched**: Same set as Option B, but `Downloader.kt` stays minimally extended (no `Repository` type at infra layer — keeps `kolt.infra` independent of `kolt.config`).

**Trade-offs**:
- ✅ Type-safe at the resolver layer where the information matters.
- ✅ `kolt.infra` stays as a thin OS-primitive layer (no domain types leak in).
- ✅ Diagnostic carries repository name without reverse-mapping.
- ✅ Symmetric with the structure-md downward dependency rule (cli → build → resolve → infra).
- ❌ Same blast radius as Option B at the resolver layer.
- ⚠️ `Map<String, Repository>` with `Repository(name, url, auth)` introduces a `repository.name == map_key` redundancy. **Resolved in design.md §Domain Model: `liftRepositoriesMap` enforces the invariant at construction**, and a dedicated `RepositoryNameInvariantTest` pins it.
- ❌ The `kolt.config` side grows (`RepositoryAuth` ADT, `RawCredentialField` + serializer, `repositorySourceMap`) while `kolt.infra` only gains a headers parameter. The asymmetry is intentional but worth naming so reviewers don't read it as "thin infra change".

## 4. Effort and Risk

- **Effort: M (3-7 days)**. Schema + parse validation (~1 day) + libcurl header attachment + Downloader extension (~1 day) + resolver propagation + new error variant (~1-2 days) + error format + redaction (~1 day) + comprehensive tests (~1-2 days).
- **Risk: Medium**.
  - libcurl `curl_slist_append` / `CURLOPT_HTTPHEADER` is new to kolt (no prior use). Need a small spike to confirm cleanup ordering with the existing finally-block pattern.
  - Custom ktoml serializer for credential-field polymorphism (literal vs `{env = "..."}`) follows the `SysPropValueSerializer` precedent — known feasible but adds review surface.
  - Redaction is a non-functional invariant; getting `Req 8.5` ("regardless of current or future verbosity flags") right needs explicit tests now and a documented contract for any future logger.

## 5. Recommendations for Design Phase

### Preferred approach: **Option C (hybrid: typed resolver + thin Downloader extension)**

Rationale:
- Cleanest fit with the `cli → build → resolve → infra` dependency direction (structure.md). `kolt.infra` does not learn about `Repository`; the resolver layer owns auth attachment.
- Repository name flows for free through `RepositoryAttempt` — Req 7's `repository: <name>` line is direct, not a reverse lookup.
- libcurl change is a single header-attachment call site, the same as Option A.
- Future credential schemes (OAuth, mTLS) extend `RepositoryAuth` without touching infra.

### Key decisions to commit in design.md

1. **`RepositoryAuth` ADT shape** — `sealed class RepositoryAuth { object None; data class Bearer(token); data class Basic(user, password) }` vs a flat `(token?, user?, password?)`. Sealed ADT preferred (ADR 0001 idiom; lets `formatResolveError` switch exhaustively for `credentials: <state>`).
2. **Credential-field polymorphism mechanism** — custom serializer (literal-vs-inline-table, mirrors `SysPropValueSerializer`) vs plain `String?` + ktoml-error remap. The custom serializer is forward-compat for v1.1+; the plain-string approach is simpler for v1.0 but adds remap fragility.
3. **Placement-policy validator location** — pre-merge base-only validator (rejects literals in `kolt.toml` before merge) vs post-merge validator with `repositorySourceMap` (mirroring `sysPropSourceMap` from #436). Pre-merge is simpler; post-merge unifies with the source-attribution pattern.
4. **Repository name in `RepositoryAttempt`** — extend the existing data class with `name: String` (breaks no contract; serialized only into stderr).
5. **`DownloadError` extension vs new `RepositoryDownloadFailure.AuthFailed`** — extend `DownloadError` with `Unauthorized` / `Forbidden` variants and reuse the existing aggregation path, OR introduce `RepositoryDownloadFailure.AuthFailed` that bypasses aggregation (Req 6.4). Issue #416 implies the latter shape; design should commit.

### Research items to carry forward

- **libcurl spike**: Confirm `curl_slist_append` ownership and free-order with the existing `curl_easy_cleanup` pattern. ~30 minute spike.
- **Custom serializer for credential fields**: Decide between the SysPropValue-style serializer and the plain-string remap; design.md should pick one with rationale.
- **Multi-repo 401 + 404 interaction**: When repository A returns 401 (hard error) and repository B (declared later) would have returned 404 — what does the final aggregate error look like? Issue body says "401/403 hard error, no fall-through" so iteration stops at A. Design should explicitly state that subsequent repositories are not contacted.
- **`token = { env = "..." }` recognition surface**: Should it also be recognized (and rejected in v1.0) for `url`, or only for credential fields? Issue body restricts it to `token` / `user` / `password`; design should confirm.
- **Empty-credentials test matrix**: tabulate the rejection cases (Req 1.4) — `token = ""`, `token = "   "`, `user = ""` (with valid password), etc. Helps `/kiro-spec-tasks` size the test surface and avoid SDD test inflation. [[feedback_sdd_test_inflation]]

---

## 6. Design Synthesis Outcomes (2026-05-13)

### Generalization decisions committed in design.md

- **`RepositoryAuth` ADT is the source of truth for header attachment only.** It carries the secret material (token / user / password) and is consumed by the `kolt.resolve` loop to produce HTTP `Authorization` headers. It is NEVER passed to the renderer — Req 8.1 / 8.2 are upheld by type, not by runtime check.
- **`AuthStateProjection`** is the renderer-facing secret-free counterpart (`kolt.resolve`, co-located with `RepositoryDownloadFailure`). `RepositoryDownloadFailure.AuthFailed` carries `AuthStateProjection`, not `RepositoryAuth`. Architectural redaction: the renderer cannot see secrets even if asked to. The display-string mapping (Req 7.3) is owned by `AuthStateProjection.toDisplayString()`, not the renderer.
- **No `RepositoryAuth.None` variant** — `null` represents anonymous (simpler, fewer pattern-match branches).
- **Source attribution via per-field map** generalizes the `sysPropSourceMap` pattern from #436 to repositories. New helper `repositorySourceMap(base, overlay, basePath, overlayPath): Map<repoName, Map<fieldName, file?>>`.

### Build-vs-adopt decisions committed in design.md

- **Adopt** libcurl `curl_slist_append` + `CURLOPT_HTTPHEADER` for header attachment. First use in kolt; cleanup via `curl_slist_free_all` in finally block (the order vs `curl_easy_cleanup` is mutually safe per libcurl docs — string copies are internal — but kolt's convention is slist-first followed by easy, opposite the common upstream example; documented as an anchored gotcha).
- **Adopt** the `SysPropValueSerializer` pattern for `RawCredentialField`, but constrained to **uniform inline-table polymorphism**, not string-OR-table. The `SysPropValue.kt:14-16` comment documents that ktoml 0.7.1 rejects string-OR-table on a custom serializer (would require reaching into `TomlNode` internals). v1.0 auth fields therefore take the shape `token = { literal = "..." }` / `token = { env = "..." }`; v1.1+ flips the env-reject validator additively without schema change.
- **Adopt** kotlin platform `Base64` for `Authorization: Basic` encoding. Same dependency already used for `kolt.lock` SHA-256 path (kotlincrypto.hash is sha2-only; base64 is platform stdlib).
- **Adopt** `Map<String, String>` as the `Downloader.downloadFile` headers parameter type, with the boundary contract that the Map key is the bare header name (no colon) and the Map value is the bare header value. The Downloader assembles each libcurl slist line as `"$name: $value"` internally. This is the contract between `RepositoryAuth.toHeaders()` and the infra layer (`Downloader`).

### Simplification decisions committed in design.md

- **No `RepositoryAuth.None`** — `null` is sufficient and reduces pattern-match noise everywhere.
- **No URL-keyed side table** (Option A from §3 rejected) — `Repository` is propagated as a typed value through the resolver chain (Option C).
- **Req 2.1 (kolt.toml literal reject) uses `path` parameter directly**, not the source map. Base raw originates entirely from `kolt.toml`; no per-field attribution needed for this case. Source map is only invoked for overlay-attributed errors (Req 2.3 env-form, Req 1.x mutex / empty when the violating field came from overlay).
- **`DownloadError` is not extended** with `Unauthorized` / `Forbidden`. 401/403 stay flat at `HttpFailed` in `kolt.infra`. The 401/403 → hard-error transformation happens at `downloadFromRepositories` in `kolt.resolve`. Keeps `kolt.infra` status-code-agnostic.
- **No new diagnostic types for legacy flat-form rejection** (Req 9). Existing `KtomlMessageParse` path stays; only the hint text changes to reference the new sub-table-plus-credentials shape.

### Carry-forward research items (resolved in design.md)

| Item | Resolution |
|---|---|
| libcurl spike (slist cleanup order) | Resolved by reading libcurl docs: `curl_slist_*` strings are copied internally; either order is safe. kolt picks slist-first-then-easy, opposite the common upstream example; documented as anchored gotcha in `Downloader` Implementation Notes. |
| libcurl redirect Authorization stripping | Cross-origin redirect strips `Authorization` by default (`CURLOPT_UNRESTRICTED_AUTH = 0L`). Critical for GitHub Packages / Cloudsmith / CDN-fronted repos. design.md commits to `CURLOPT_UNRESTRICTED_AUTH = 0L` (drop on cross-origin) with explicit test coverage. |
| Custom serializer shape | Uniform inline-table only (`{ literal = "..." }` / `{ env = "..." }`), not string-OR-table. Driven by `SysPropValue.kt:14-16` ktoml-0.7.1 constraint. v1.1+ flip-to-accept-env stays additive. |
| Multi-repo 401 + 404 interaction | `AuthFailed` returned before iteration continues. Subsequent repositories not contacted. Documented in `downloadFromRepositories` Implementation Notes and `System Flows` diagram. |
| `{env="..."}` for `url` field too? | Restricted to `token` / `user` / `password` per issue body. `url` polymorphism out of scope; URL accepts only string. |
| Empty-credentials test matrix | Sized as a single parametrized test (`RepositoryAuthConfigTest` with data table), not 1 file per case. |
| `Repository.toString()` / `RepositoryAuth.toString()` leak | The `RepositoryAuth` parent is a non-data `sealed class`; its variants (`Bearer`, `Basic`) are `data class` with **explicit `toString()` overrides** that emit `<redacted>` for secret fields. `Repository` stays as a `data class` (no custom `toString`); its auto-generated `toString` delegates per-field, so the `auth` field surfaces through the overridden variant `toString` and the password / token never reach output. design.md §Domain Model invariants documents the chain explicitly. |
