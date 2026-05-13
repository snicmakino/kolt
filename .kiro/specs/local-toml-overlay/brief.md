# Brief: local-toml-overlay

## Problem

`kolt.toml` is checked in and shared across the team (ADR 0032, from `jvm-sys-props`), so it must remain environment-agnostic. Yet some values are legitimately per-machine: a contributor's local API endpoint, a sandbox path, a debug log level on for one developer but off in CI, and — looking ahead — credentials for a private Maven repository (#416). There is no built-in channel for these values today except `-D<key>=<value>` CLI flags, which are awkward for values that should stick across many invocations.

## Current State

- `kolt.toml` is parsed by `parseConfig()` (`src/nativeMain/kotlin/kolt/config/Config.kt:435`) into a `KoltConfig` model. Unknown sections are strictly rejected (`Toml(TomlInputConfig(ignoreUnknownNames = false))`), guarded by `KNOWN_TOP_LEVEL_SECTIONS` (`KtomlMessageParse.kt:29`).
- `[test.sys_props]` / `[run.sys_props]` are already declared schema, parsed into `Map<String, SysPropValue>` (`SysPropValue.kt:18`). Runtime delivery via `-D<key>=<value>` is wired (`Main.kt:264`).
- `[repositories]` is currently `Map<String, String>` (name → url) flat shape (`Config.kt:118`). No nested table form, no auth fields.
- `kolt init` / `kolt new` writes `kolt.toml` and `.gitignore` from `Init.kt:generateKoltToml()` / `generateGitignore()` via `Scaffold.kt:scaffoldProject()`. The current `.gitignore` template does not include `kolt.local.toml`.
- No precedent in the codebase for file-level layered config; the only existing "overlay" is the runtime `-D` CLI flag merge with `[test|run.sys_props]`.

## Desired Outcome

When `kolt.local.toml` exists alongside `kolt.toml` in the project root, kolt parses it as a partial overlay restricted to an explicit section allowlist (`[test.sys_props]`, `[run.sys_props]`, `[repositories.<name>]`). Sections outside the allowlist are rejected at parse time. The merged config is what every downstream consumer (resolver, test runner, run runner) sees. `kolt init` writes a `.gitignore` entry for `kolt.local.toml` automatically. `kolt.local.toml` is documented in `docs/architecture.md` and surfaced in `kolt --help` / `kolt init` output.

## Approach

**Approach B — section-specific merge functions with an allowlist.**

Introduce `RawLocalOverlayConfig` with every field nullable, mirroring `RawKoltConfig` but restricted to overlay-eligible sections. ktoml's existing `ignoreUnknownNames = false` automatically rejects `[build]`, `[dependencies]`, `[classpaths]`, etc., reusing the established `KtomlMessageParse` error-shape infrastructure.

Two merge functions cover the v0.20 overlay surface:

- `mergeSysProps(base, local)` — `Map<String, SysPropValue>` key-replace; local entries replace same-key base entries, new keys union in.
- `mergeRepositories(base, local)` — `Map<String, Repository>` name-merge; for each name, fields in `local` override fields in `base`. Names in `local` not present in `base` are a parse error with a named-bundle message (matches #415's required-existing-section rule).

To support field-merge for repositories, the `[repositories]` schema migrates from `Map<String, String>` to `Map<String, Repository(url)>` sub-table form. Pre-v1 breaking change; release-note documented. The current `central = "https://..."` flat form becomes `[repositories.central] url = "https://..."`.

After merge, the existing `KoltConfig` semantic validation (env-agnostic literal rule, `[classpaths]` reference resolution, `project_dir` containment check) runs on the merged result — overlay validation reuses the existing pipeline, no duplicate validation layer.

Why Approach B over a generic deep-merge engine: the overlay scope is small (2 sections in v0.20, ~1 added per release at most), and section-specific functions make merge rules visible at the call site rather than buried behind a generic helper. The allowlist nature of overlay-eligible sections is encoded in the type (`RawLocalOverlayConfig`'s field set) rather than enforced by separate guard code. Adding a new overlay-eligible section is one struct field + one merge function — generic doesn't pay off until ~5 sections.

## Scope

**In**:

- `kolt.local.toml` partial decode: `RawLocalOverlayConfig` with `repositories?`, `test?`, `run?` nullable fields; unknown sections rejected via existing `ignoreUnknownNames = false` path.
- Section-specific merge: `mergeSysProps` (key-replace) and `mergeRepositories` (name-merge + field-merge).
- `[repositories]` schema migration from `Map<String, String>` to `Map<String, Repository>` sub-table form, with `url` as the only field.
- `parseConfig` orchestrates: parse `kolt.toml` first, parse `kolt.local.toml` if present, merge, then run semantic validation on merged result.
- `kolt init` / `kolt new` `.gitignore` template appends `kolt.local.toml`.
- `docs/architecture.md` overlay chapter; `kolt --help` text update so the `-D` line correctly reads as a three-layer merge (kolt.toml ← kolt.local.toml ← `-D`).
- ADR 0034 (private Maven repos) skeleton, with the env-agnostic ↔ overlay relationship recorded; #416 fills in auth fields.

**Out**:

- `[repositories.<name>]` auth fields (`token` / `user` / `password`) themselves — that is #416.
- `${env.X}` / environment-variable interpolation inside `kolt.local.toml` values — deferred to v1.1+ per ADR 0032.
- Overlay for any other section (`[build]`, `[dependencies]`, `[classpaths]`, `[kotlin]`, …) — demand-driven follow-up.
- Per-user-home overlay files (`$XDG_CONFIG_HOME/kolt/local.toml` etc.) — permanently out per #320.
- Multi-file overlay chains (`kolt.local.toml` + `kolt.user.toml` etc.) — one overlay file.
- Encryption / secret management; the overlay is plaintext.

## Boundary Candidates

- **Overlay decode** vs **section merge** vs **schema migration**: parsing `kolt.local.toml` to `RawLocalOverlayConfig`, merging per-section into `KoltConfig`, and migrating `[repositories]` shape are three distinct seams. PR-1 (#320) owns decode + sys_props merge + schema migration. PR-2 (#415) owns repositories merge. The seam between decode and merge is the cleanest split point.
- **Validation timing**: merge-before-validate vs validate-each-then-merge. We chose merge-before-validate (single semantic pass on merged result) — the alternative would double-validate and complicate error attribution.
- **Init scaffold update** (#417) is a separate file (`Scaffold.kt` / `Init.kt`) and a separate PR, but lives in this spec because it shares the same release surface and the `.gitignore` policy comes from #320 documentation.

## Out of Boundary

- Auth credential field semantics (`token` vs `user`+`password` mutual exclusion) — #416 owns these. This spec only ensures the merge engine treats `Repository` as a record whose fields can be field-merged regardless of what fields exist; #416 adds the fields and their post-merge validation.
- `kolt run` / `kolt test` runtime behavior for sys_props — unchanged from `jvm-sys-props` spec; this spec only changes the source of values, not the consumer.
- Lockfile representation of repositories — `[repositories]` is a resolver input, not a locked artifact. Schema migration is a kolt.toml change; `kolt.lock` is unaffected.

## Upstream / Downstream

- **Upstream**: `jvm-sys-props` spec (`[test.sys_props]` / `[run.sys_props]` schema, `SysPropValue` model, ADR 0032 env-agnostic principle). `parseConfig` infrastructure (`KtomlMessageParse`, `RawKoltConfig`).
- **Downstream**: #416 Private Maven repository support — depends on `[repositories.<name>]` sub-table form and overlay engine landing first; will add `token` / `user` / `password` fields. CLI `-D<key>=<value>` flag work (#319 family) — independent thread, but the `--help` wording change here surfaces the three-layer merge order.

## Existing Spec Touchpoints

- **Extends**: `jvm-sys-props` (per-machine overlay was named in its Requirement 6 as designated follow-up).
- **Adjacent**: `412-new-preset-flow` touches `kolt init` / `kolt new` scaffolding; #417's `.gitignore` template edit must not collide with preset-specific .gitignore content. Coordinate via `Scaffold.kt`.

## Constraints

- **ktoml 0.7.1**: confirmed viable for partial decode via all-nullable `Raw*` class (precedent: `RawSysPropValue` in `SysPropValue.kt:26`). No new library introduction. Memory note `ktoml_decode_quirks` records that string-OR-table polymorphism is not viable — this spec stays within table-only shapes.
- **Pre-v1 breaking**: `[repositories]` migrates from `name = "url"` flat form to `[repositories.<name>] url = "..."` sub-table form. No migration shim per CLAUDE.md / memory `feedback_no_backcompat_pre_v1`. Release notes for v0.20.0 must document the upgrade step.
- **Kotlin/Native, no exceptions**: all new parse paths use `Result<V, ConfigError>`; new error variants extend existing `ConfigError` sealed hierarchy.
- **PR split (3 PRs in v0.20.0)**:
  - **PR-1 (#320)**: overlay engine base + sys_props merge + `[repositories]` schema migration (model only, no overlay) + docs + `--help`.
  - **PR-2 (#415)**: repositories field-merge in overlay + ADR 0034 skeleton.
  - **PR-3 (#417)**: `kolt init` `.gitignore` auto-append.
  - Schema migration lives in PR-1 (not PR-2) to keep PR-2's diff small and to concentrate the breaking change in one reviewable PR.
