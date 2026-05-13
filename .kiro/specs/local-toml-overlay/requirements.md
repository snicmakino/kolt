# Requirements Document

## Introduction

This feature introduces `kolt.local.toml`, a per-project overlay file that lives alongside `kolt.toml` and supplies environment-specific values without polluting the team-shared `kolt.toml` (which must remain env-agnostic per ADR 0032 from the `jvm-sys-props` spec). The overlay surface is intentionally narrow: only `[test.sys_props]`, `[run.sys_props]`, and `[repositories.<name>]` may appear in `kolt.local.toml`. Sections outside this allowlist are rejected at parse time.

To support field-level overlay on repositories, the `[repositories]` schema in `kolt.toml` migrates from the legacy flat form (`name = "url"` string entries) to a sub-table form (`[repositories.<name>] url = "..."`). This is a pre-v1 breaking change documented in the v0.20.0 release notes.

The feature also wires `kolt init` / `kolt new` to append `kolt.local.toml` to the generated `.gitignore` automatically, surfaces the overlay in `kolt --help` text and `docs/architecture.md`, and lands ADR 0034 as a skeleton shared with the downstream private-Maven-repo work (#416).

## Boundary Context

- **In scope**:
  - Discovery and partial parse of `kolt.local.toml` in the project root, with section allowlist enforcement.
  - Key-replace merge for `[test.sys_props]` / `[run.sys_props]`; field-level merge for `[repositories.<name>]`.
  - `[repositories]` schema migration to sub-table form with `url` as the only declared field.
  - `kolt init` / `kolt new` `.gitignore` auto-append for `kolt.local.toml`.
  - `kolt --help` text update to show the three-layer merge order; `docs/architecture.md` overlay chapter.
  - Validation on the merged config: cross-file references resolve against the merged result, env-agnostic literal rule applies to both files.
- **Out of scope**:
  - Repository auth fields (`token` / `user` / `password`) and their mutual-exclusion semantics — owned by #416.
  - `${env.X}` interpolation in either `kolt.toml` or `kolt.local.toml` — deferred to v1.1+ per ADR 0032.
  - Overlay support for any other top-level section (`[build]`, `[dependencies]`, `[classpaths]`, `[kotlin]`, ...).
  - Per-user-home overlay locations (`$XDG_CONFIG_HOME/kolt/local.toml`, etc.) — permanently out.
  - Multi-file overlay chains (`kolt.local.toml` + `kolt.user.toml`, etc.).
  - Encryption or secret-store integration; overlay values are plaintext.
- **Adjacent expectations**:
  - The `jvm-sys-props` spec owns the `[test.sys_props]` / `[run.sys_props]` schema, the `SysPropValue` value shapes, and runtime delivery. This spec reuses those rules unchanged; per-criterion duplication of decode coverage is intentionally omitted.
  - The downstream `#416` work depends on the `[repositories.<name>]` sub-table form landing here, and will extend the `Repository` record with auth fields.
  - The `412-new-preset-flow` spec owns scaffolding orchestration; the `.gitignore` template edit here coexists with preset-specific gitignore content.

## Requirements

### Requirement 1: Overlay file discovery and partial parse

**Objective:** As a kolt user, I want kolt to read a `kolt.local.toml` overlay next to my `kolt.toml` so that environment-specific values stay out of the shared, committed config.

#### Acceptance Criteria

1. When `kolt.local.toml` exists in the same directory as `kolt.toml`, the kolt config parser shall parse it as a partial overlay and merge it into the `kolt.toml` config.
2. When `kolt.local.toml` does not exist in the same directory as `kolt.toml`, the kolt config parser shall produce the same merged config as if only `kolt.toml` were parsed, without reading or referencing any other location.
3. If `kolt.local.toml` contains TOML syntax errors, the kolt config parser shall reject the configuration with a message identifying `kolt.local.toml` as the source file and reporting the offending line.
4. The kolt config parser shall apply the same env-agnostic literal-value rule to `kolt.local.toml` as it applies to `kolt.toml`; no `${env.X}` or other environment-variable interpolation shall be performed on overlay values.

### Requirement 2: Allowlist enforcement on the overlay file

**Objective:** As a kolt user, I want the overlay file to reject sections it does not officially support so that I am not silently misled into thinking an unsupported overlay is taking effect.

#### Acceptance Criteria

1. The kolt config parser shall accept `kolt.local.toml` files whose top-level sections are limited to any subset of `[test.sys_props]`, `[run.sys_props]`, and `[repositories.<name>]`.
2. If `kolt.local.toml` contains any top-level construct outside the allowlist — either a section such as `[build]`, `[kotlin]`, `[dependencies]`, `[test-dependencies]`, `[classpaths]`, or a top-level key-value assignment such as `name = "..."` — the kolt config parser shall reject the configuration with a message naming the offending construct and identifying `kolt.local.toml` as the source file.
3. If `kolt.local.toml` contains an unknown sub-key inside an allowlisted section (e.g., `[run.foo]`, `[test.unknown_sub]`), the kolt config parser shall reject the configuration with a message naming the offending sub-key and identifying `kolt.local.toml` as the source file.

### Requirement 3: Overlay merge for `[test.sys_props]` and `[run.sys_props]`

**Objective:** As a kolt user, I want `kolt.local.toml` to override or add individual sys_prop entries without forcing me to redeclare entries I am not changing, so that I can keep overlays minimal and reviewable.

#### Acceptance Criteria

1. When `kolt.local.toml` declares an entry under `[test.sys_props]` or `[run.sys_props]` whose key matches an entry under the same section in `kolt.toml`, the kolt config parser shall replace the `kolt.toml` value with the `kolt.local.toml` value in the merged config.
2. When `kolt.local.toml` declares an entry under `[test.sys_props]` or `[run.sys_props]` whose key does not match any entry under the same section in `kolt.toml`, the kolt config parser shall add the entry to the merged config.
3. If a `{ classpath = "<bundle>" }` value declared in `kolt.local.toml` names a bundle not declared as `[classpaths.<bundle>]` in `kolt.toml`, the kolt config parser shall reject the configuration with a message naming the missing bundle and identifying `kolt.local.toml` as the source file.

### Requirement 4: `[repositories]` schema migration

**Objective:** As a kolt user, I want repositories to be declared as named sub-tables so that future per-field overlays and credential fields have a place to live.

#### Acceptance Criteria

1. The kolt config parser shall accept `[repositories.<name>]` sub-table declarations in `kolt.toml` whose body contains a `url` string field.
2. The kolt config parser shall accept multiple distinct `[repositories.<name>]` declarations in the same `kolt.toml`.
3. If `kolt.toml` contains a `[repositories]` table whose entries assign string URLs directly to repository names (the legacy flat form), the kolt config parser shall reject the configuration with a message stating that the schema has migrated to `[repositories.<name>] url = "..."` and identifying `kolt.toml` as the source file.
4. If `kolt.toml` declares a `[repositories.<name>]` sub-table without a `url` field, the kolt config parser shall reject the configuration with a message naming the offending repository.

### Requirement 5: Overlay merge for `[repositories.<name>]`

**Objective:** As a kolt user, I want `kolt.local.toml` to override fields of an existing repository declaration so that I can redirect to a local mirror or supply a future credential without redeclaring the repository.

#### Acceptance Criteria

1. When `kolt.local.toml` declares `[repositories.<name>]` whose `<name>` matches a `[repositories.<name>]` declared in `kolt.toml`, the kolt config parser shall merge the fields declared in `kolt.local.toml` into the corresponding `kolt.toml` declaration on a per-field basis (replacing only the declared fields), and the merged repository shall keep the `kolt.toml` declaration's position in the ordered repository list.
2. If `kolt.local.toml` declares `[repositories.<name>]` whose `<name>` does not match any `[repositories.<name>]` declared in `kolt.toml`, the kolt config parser shall reject the configuration with a message naming the offending repository and identifying `kolt.local.toml` as the source file.
3. If, after merging `kolt.local.toml` into `kolt.toml`, any repository's `url` field is absent or empty, the kolt config parser shall reject the configuration with a message naming the offending repository.

### Requirement 6: `kolt init` / `kolt new` `.gitignore` auto-append

**Objective:** As a kolt user creating a new project, I want kolt to add `kolt.local.toml` to my `.gitignore` automatically so that I do not accidentally commit per-machine values to the team-shared repository.

#### Acceptance Criteria

1. When `kolt init` or `kolt new` generates a new project's `.gitignore`, the kolt init command shall include an entry that excludes `kolt.local.toml` from version control.
2. The kolt init command shall include the `kolt.local.toml` entry in the `.gitignore` regardless of the project's `kind` (`bin` / `lib`) and `target`.

### Requirement 7: Discoverability via `kolt --help` and architecture documentation

**Objective:** As a kolt user discovering features through `kolt --help` or `docs/architecture.md`, I want the overlay file to be visible there so that I can find it without reading source code or release notes.

#### Acceptance Criteria

1. The kolt CLI `--help` output shall describe `kolt.local.toml` as a per-project overlay file and shall present the three-layer override order (`kolt.toml` ← `kolt.local.toml` ← `-D<key>=<value>`) wherever it currently describes the `-D<key>=<value>` overlay.
2. The kolt project documentation shall include a section in `docs/architecture.md` that describes `kolt.local.toml`, the section allowlist (`[test.sys_props]`, `[run.sys_props]`, `[repositories.<name>]`), and the merge semantics for each allowlisted section.

### Requirement 8: Resolver output stability across schema migration

**Objective:** As an existing kolt user whose project does not need the overlay, I want the `[repositories]` schema migration to be silent at the dependency-resolution layer so that my `kolt.lock` does not churn on the upgrade.

#### Acceptance Criteria

1. When a project's `kolt.toml` declares its repositories using the new `[repositories.<name>] url = "..."` sub-table form and no `kolt.local.toml` is present, the kolt dependency resolver shall produce the same resolution and the same `kolt.lock` content as it produced for the equivalent legacy-flat-form `[repositories]` declaration before this feature was introduced.
