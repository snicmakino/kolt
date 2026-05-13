---
status: proposed
date: 2026-05-13
---

# ADR 0034: Private Maven repository credentials in kolt.local.toml

> **Skeleton**: shape-complete, content-pending. Auth-field specifics
> (`token` / `user` / `password`, mutual-exclusion semantics) land with
> #416. Until then this ADR commits to the *home* of credentials and the
> framing that constrains #416's design space.

## Summary

- Repository credentials live in `kolt.local.toml` under `[repositories.<name>]`, not in `kolt.toml` and not in environment variables. `kolt.local.toml` is gitignored by default so credentials never reach the shared tree (§1).
- The overlay's field-level merge (#415 / spec `local-toml-overlay`) lets `kolt.toml` declare a repository's identity and `url` while `kolt.local.toml` supplies per-developer credential fields against the same `<name>` (§2).
- `${env.X}` indirection inside `kolt.toml` or `kolt.local.toml` is deferred to v1.1+ per ADR 0032 §2. v1.0 credentials are plaintext on disk (§3).
- Auth-field shapes are not fixed in this ADR (#416 owns them); §4 lists the constraints any choice must satisfy.
- Lockfile hash verification remains the integrity guarantee against mirror tampering; credentials authenticate the fetch but do not strengthen artifact trust (§5).
- A repository name declared in `kolt.local.toml` but not in `kolt.toml` is rejected at parse time. Overlay can only override fields on repositories the shared `kolt.toml` already names (§6 — already implemented in #415).

## Context and Problem Statement

Maven Central is open; private corporate repositories (Artifactory, Sonatype Nexus, GitHub Packages, GitLab Maven, etc.) require authentication. kolt v0.20.0 introduces a per-project gitignored overlay file `kolt.local.toml` to hold per-machine values; it is the obvious home for credentials that must not enter the shared `kolt.toml`. But several decisions need to be locked before #416 can introduce the auth fields:

- Where credentials live (overlay file vs env vars vs external secret store).
- Whether `kolt.toml` may carry credential fields at all (it must not, per ADR 0032 — `kolt.toml` is env-agnostic).
- Whether v1.0 needs `${env.X}` indirection (no, deferred per ADR 0032 §2).
- How field-level merge interacts with credential overrides (the overlay's field-merge already handles per-field replace, so the credential subset reuses that mechanism for free).

This ADR fixes those decisions so #416 can iterate on field-shape alternatives without re-litigating the framing.

## Decision Drivers

- ADR 0032: `kolt.toml` is env-agnostic. Credentials are env-specific by definition, so `kolt.toml` cannot host them.
- The overlay is already gitignored by `kolt init` / `kolt new` (#417). Credentials in `kolt.local.toml` get the gitignore guarantee for free.
- Credential indirection (env var, OS keychain, ad-hoc secret store) multiplies the configuration surface. v1.0 stays plaintext; v1.1+ may add indirection if a concrete user need surfaces.
- Field-level merge in #415 makes the credential override mechanism trivial: declare repository identity in `kolt.toml`, override credential fields in `kolt.local.toml`. No new override mechanism is needed.

## Considered Options

- **Plaintext credentials in `kolt.local.toml` (chosen)** — simplest, gitignored, no new mechanism. `${env.X}` deferral is per ADR 0032.
- **OS-keychain integration** — too platform-specific for v1.0 and adds a runtime dependency for read-out. Defer.
- **External secret store (Vault, AWS Secrets Manager)** — out of scope for v1.0; would need a plugin mechanism we have already decided against (ADR 0021).
- **Env-var indirection only** — duplicates the `${env.X}` decision deferred by ADR 0032; would still require some on-disk pointer.

## Decision Outcome

Chosen: **Plaintext credentials in `kolt.local.toml` `[repositories.<name>]`**, layered on top of #415's field-level overlay merge. #416 lands the concrete auth field set on top of this framing.

### §1 Credentials live in `kolt.local.toml`

`kolt.toml` MUST remain env-agnostic per ADR 0032. Any `token`, `user`, `password`, or equivalent credential field is therefore confined to `kolt.local.toml`. The overlay file is gitignored by default after #417, so the on-disk plaintext is not exposed to the shared tree.

### §2 Field-level merge reuses #415

`kolt.toml` declares each repository's identity:

```toml
[repositories.internal]
url = "https://artifacts.example.com/maven"
```

`kolt.local.toml` overrides credential fields against the same name:

```toml
[repositories.internal]
# auth field shape TBD by #416 — for illustration only
token = "abc123"
```

The merge mechanism is the field-level merge introduced in #415 (`mergeRepositories` in `LocalOverlay.kt`); credential fields are just additional fields on `RawRepository` that #416 will add. No new merge logic is needed.

### §3 No `${env.X}` indirection in v1.0

ADR 0032 §2 commits to verbatim literal pass-through. Credentials follow the same rule: `token = "${env.GITHUB_TOKEN}"` evaluates to the literal string `${env.GITHUB_TOKEN}`, not the environment variable's value. v1.1+ may revisit this; v1.0 ships without env interpolation across the board for consistency.

### §4 Auth field set — to be filled by #416

This ADR does not fix the auth field names, the mutual-exclusion rule between `token` and `user`+`password`, or the missing-credentials behavior (anonymous-fetch vs error). Constraints any choice in #416 must satisfy:

- The field set must extend `RawRepository` (`Config.kt`) such that #415's `mergeRepositories` continues to work without modification — i.e., new fields are added with `null` defaults and field-merged by name.
- Validation (mutual exclusion, presence) MUST run post-merge per design.md's two-phase validation rule; a credential override in `kolt.local.toml` could clear or replace a base value.
- Error messages MUST identify `kolt.local.toml` as the source when the offending field comes from the overlay; see follow-up #436 (validateBundleReferences source-file attribution) for the validator infrastructure needed.

### §5 Integrity is lockfile-hash-only

Credentials authenticate the *fetch* from a private mirror, not the artifact. SHA-256 hashes in `kolt.lock` remain the only integrity guarantee against mirror compromise or man-in-the-middle. A repository pointing at a hostile mirror that serves correct artifacts is indistinguishable from a benign mirror at the auth layer; lock-file verification catches the artifact substitution attack.

### §6 Overlay-only repository names are rejected

Per #415's `mergeRepositories`: a `[repositories.<name>]` entry in `kolt.local.toml` for a name not in `kolt.toml` is rejected with `ConfigError.ParseFailed` naming `kolt.local.toml`. The overlay can only override fields on repositories the shared file already names. This keeps the public set of repository identities visible in the shared `kolt.toml`; a developer cannot silently introduce a private repository for themselves.

## Consequences

### Positive

- Credentials are confined to a gitignored file by default; the shared `kolt.toml` stays committable.
- The overlay's field-level merge handles credentials with zero new merge logic.
- ADR 0032's env-agnostic rule remains a hard invariant.
- #416's design space is bounded — only the auth field shape and validation rules remain open.

### Negative

- Credentials are plaintext on disk. Users on shared workstations or with broad backup scope must compensate at the filesystem layer.
- No env-var indirection means CI pipelines that inject credentials via env vars need a wrapper that writes a transient `kolt.local.toml` from those env vars. This is acceptable for v1.0; a `${env.X}` mode in v1.1+ would eliminate the wrapper.
- Repository identity must be declared in `kolt.toml` even when only credentials differ per developer. This is a deliberate trade-off — see §6.

### Neutral

- Lockfile hash verification is unchanged. Credentials do not strengthen artifact trust; they authenticate the network fetch only.
- The error-attribution work in #436 is a prerequisite for clean credential-validation diagnostics but does not block this ADR; the framing here stands either way.

## Related ADRs

- ADR 0032 — kolt.toml env-agnostic rule (the constraint this ADR layers on top of).
- ADR 0028 — v1 release policy (informs the v1.0 / v1.1+ scope cut).

## References

- #415 — `[repositories.<name>]` overlay merge (the mechanism this ADR uses).
- #416 — Auth field shapes (the work this ADR enables).
- #417 — `kolt init` `.gitignore` auto-append (ensures `kolt.local.toml` is gitignored by default).
- #436 — `validateBundleReferences` source-file attribution (prerequisite for clean overlay-attributed credential errors).
