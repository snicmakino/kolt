---
status: accepted
date: 2026-04-30
---

# ADR 0032: kolt.toml is environment-agnostic

## Summary

- `kolt.toml` is a project-shared, commit-tracked file. Per-machine and per-environment values do not belong in it (§1).
- The parser does not perform `${env.X}` interpolation on any value. The decision is enforced at parse time, not by convention (§2).
- Environment-specific values arrive through two designated channels: a CLI `-D<key>=<value>` flag on `kolt test` / `kolt run` (#319, landed), and a per-user `kolt.local.toml` overlay (#320, deferred). The CLI flag overlays kolt.toml-declared sysprops literal-only; CLI value wins on key collision (§3).
- The `[test.sys_props]` and `[run.sys_props]` value shapes admitted today (literal / classpath / project_dir) are deliberately limited to project-derivable forms so the env-agnostic invariant is structural, not stylistic (§4).
- The decision is reversible only by superseding ADR if a future contributor wants to add interpolation. The follow-up issues are the path that does not require superseding (§5).

## Context and Problem Statement

The `jvm-sys-props` spec (#318) added `[test.sys_props]` and `[run.sys_props]` to `kolt.toml`. These tables let users declare `-D<key>=<value>` JVM system properties that `kolt test` and `kolt run` propagate to the spawned JVM.

System properties are the canonical place where environment-specific values appear — log levels that differ per developer, API endpoints that point at staging on one machine and production on another, fixture paths that resolve under a contributor's home directory. The first reaction is to mirror what Maven does and let `kolt.toml` values reference environment variables via `${env.X}` interpolation. That mixes two concerns the project has consistently kept apart: what the project IS (committed, shared, reproducible) versus how this particular machine builds it (per-developer, per-CI-environment).

This ADR commits to keeping those concerns separate. `kolt.toml` describes the project. Per-machine values arrive through other channels.

## Decision Drivers

- The same `kolt.toml` parsed on two machines must produce two structurally identical configurations. Anyone reading `kolt.toml` should be able to predict what will be passed to the JVM without knowing what environment variables happen to be set.
- A schema-level guarantee is preferred over a code-review convention. If a future contributor tries to author `${env.X}` style values, the parser should reject them, not the reviewer.
- The cost of not having env-specific values must stay bounded. Common cases (override one sysprop locally, ship a sandbox path) need a documented path even if it is not in `kolt.toml`.

## Considered Options

- **Maven-style** — admit `${env.X}` interpolation in `kolt.toml` values.
- **Cargo-style** — `kolt.toml` stays env-agnostic; per-user overrides go in a separate file (`.cargo/config.toml`-equivalent).
- **CLI-flag-only** — `kolt.toml` stays env-agnostic; per-user overrides come exclusively from CLI flags at invocation time.

## Decision Outcome

Chosen approach: **Cargo-style + CLI-flag escape hatch**. `kolt.toml` is env-agnostic. CLI flags (#319) and a per-user overlay file (#320) are the two designated channels for environment-specific values.

### §1 `kolt.toml` is committed and shared

`kolt.toml` is the kolt-project equivalent of `Cargo.toml`. It is checked into version control, copied across CI runners, and read by every contributor working on the project. Treating it as machine-shared means a contributor can `git pull` someone else's branch and trust the build to behave the same way for them. Embedding per-machine paths or per-developer endpoints in this file breaks that contract by making the build silently produce different artifacts depending on whose machine evaluated the TOML.

This is the same contract Cargo enforces by keeping `Cargo.toml` env-agnostic and pushing per-machine concerns into `.cargo/config.toml`. kolt adopts the same split.

### §2 The parser does not interpolate

The kolt config parser does not perform any environment-variable expansion on values inside `[test.sys_props]`, `[run.sys_props]`, `[classpaths.<name>]`, or anywhere else in `kolt.toml`. A literal value containing `$HOME` or `${FOO}` is passed through verbatim to the JVM as the string `$HOME` or `${FOO}`.

This is enforced in `SysPropResolver.resolveSysProps` (`src/nativeMain/kotlin/kolt/build/SysPropResolver.kt`): the literal branch returns `value.value` without any string transformation. Tests pin this behavior explicitly (`SysPropResolverTest.doesNotExpandEnvironmentVariablesInLiteral`).

The decision is structural rather than stylistic: a `$HOME`-shaped string in `kolt.toml` is not "almost interpolation that someone forgot to enable." It is a deliberate literal.

### §3 Designated channels for env-specific values

Two channels are committed for environment-specific override of declared sysprops:

- **CLI flag** (#319). `kolt test -D<key>=<value> [...]` and `kolt run -D<key>=<value> [...]` overlay literal values onto the kolt.toml-declared sysprops at invocation time. CLI values are literal-only — the structured `{ classpath = ... }` / `{ project_dir = ... }` forms remain `kolt.toml`-exclusive because they only make sense in the context of declared bundles and project paths. Use case: one-off debugging, ad-hoc fixture overrides.
- **Per-user override file** (#320). `kolt.local.toml` (project-local, recommended `.gitignore` entry) overlays kolt.toml's `[test.sys_props]` / `[run.sys_props]` for a single working tree. Use case: persistent per-developer settings that should not be committed.

The CLI flag landed (PR for #319 wires `-D<key>=<value>` through `parseKoltArgs` into `jvmTestArgv` / `jvmRunArgv` with overlay semantics: kolt.toml-declared sysprops first in declaration order, CLI flags appended in command-line order, same-key collisions drop the toml entry). The per-user overlay (#320) remains deferred. Neither was load-bearing for the v0.X release of `[test.sys_props]` / `[run.sys_props]` itself — the existing surface is useful as-is for project-derivable values (e.g., compiler-plugin classpaths, daemon source roots) which is the primary motivating use case (#315).

### §4 Admitted value shapes are project-derivable by design

`[test.sys_props]` and `[run.sys_props]` accept three value shapes:

- `{ literal = "..." }` — a verbatim constant the project author chooses.
- `{ classpath = "<bundle>" }` — a colon-joined path of jars that kolt resolves from a `[classpaths.<bundle>]` declaration in the same `kolt.toml`.
- `{ project_dir = "<rel>" }` — an absolute path of `<project root>/<rel>`, where the project root is the directory containing `kolt.toml`.

None of these shapes can encode an environment-specific value without going through the parser. There is no way to write `{ literal = ${env.HOME} }` because the parser does not interpolate; there is no way to write `{ classpath = "${MY_BUNDLE_NAME}" }` because that resolves to a literal string that won't match any declared bundle. The structural design and the env-agnostic invariant reinforce each other.

### §5 Reversibility and follow-up scope

This ADR forecloses on `${env.X}` interpolation in `kolt.toml`. A future contributor who wants interpolation must supersede this ADR and accept the consequences (every reader of every `kolt.toml` from then on has to know which variables are set on which machine).

The CLI flag (#319) and per-user overlay (#320) are the path that does not require superseding. They give projects a way to vary per-environment behavior without compromising the file-level contract that `kolt.toml` parses to the same configuration everywhere.

### Consequences

**Positive**
- Reading `kolt.toml` tells you what the build does, full stop. No need to chase down which env vars are set.
- Schema-level rejection of `${env.X}` means accidental authoring fails fast, before review.
- Two designated channels (CLI flag, override file) cover the common env-specific use cases without polluting `kolt.toml`.

**Negative**
- Common idioms like "log level controlled by `LOG_LEVEL`" require either a CLI flag at every invocation (`-Dlog.level=...`) or `kolt.local.toml` (until #320 lands).
- Until #320 lands, persistent per-developer sysprop overrides require an alias / wrapper script around `kolt test` / `kolt run`; the CLI flag covers one-off invocations only.

### Confirmation

`SysPropResolver` returns literal values verbatim with no string transformation; `SysPropResolverTest.doesNotExpandEnvironmentVariablesInLiteral` pins the behavior. PR review enforces "no interpolation logic added to value-decoding paths"; the ADR text serves as the durable record reviewers can point to.

## Alternatives considered

1. **Maven-style `${env.X}` interpolation in `kolt.toml`.** Rejected. Mixing committed configuration with environment lookups means the file no longer describes a single, predictable build. The "what does this `kolt.toml` evaluate to" question becomes "...on which machine, with which env vars, in which order." Maven adopted this in an era when CI was rare and per-machine pom.xml was the norm; current practice has moved away from it.

2. **CLI-flag-only escape hatch (no override file).** Rejected. CLI flags work for one-off invocations but are awkward for values that should stick across many invocations on a developer's machine. Forcing a contributor to wrap `kolt test` in a shell alias to set their local API endpoint is a UX regression that grows with feature use. The override file (#320) covers the persistent case at the cost of one extra file convention.

3. **Status quo (no env-specific channel at all).** Rejected. Some env-specific values are legitimate (debug log level, sandbox endpoints, per-developer fixture paths). Without a documented channel, contributors hardcode them into `kolt.toml` and the env-agnostic invariant erodes silently.

## Related

- #318 — jvm-sys-props spec (this ADR's parent)
- #319 — CLI `-D<key>=<value>` flag for `kolt test` / `kolt run` (landed)
- #320 — Per-user `kolt.local.toml` override file (deferred channel)
- ADR 0028 — v1 release policy (pre-v1 breaking changes are acceptable; this ADR commits a long-term contract)
- `.kiro/specs/jvm-sys-props/design.md` — design that this ADR codifies
- `src/nativeMain/kotlin/kolt/build/SysPropResolver.kt` — parser-level enforcement of §2
