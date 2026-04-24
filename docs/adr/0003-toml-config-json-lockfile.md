---
status: accepted
date: 2026-04-09
---

# ADR 0003: Use TOML for `kolt.toml` and JSON for `kolt.lock`

## Summary

- `kolt.toml` is parsed with ktoml 0.7.1; human-authored config needs comments, quote-free keys, and no trailing-comma traps. (§1)
- `kolt.lock` stays on kotlinx-serialization-json; machine-written output needs deterministic serialisation and schema evolution, not human ergonomics. (§2)
- ktoml deserialises quoted map keys with the surrounding quotes still attached; `parseConfig` strips one leading-and-trailing `"` pair from every map key. (§3)
- Neither format is binary; both are diffable, grep-able, and editable — a hard requirement. (§4)

## Context and Problem Statement

kolt has two persistent file formats: `kolt.toml` (human-authored project config containing project name, target, main class, source layout, dependencies, compiler plugins, repositories, toolchain versions, and formatter options) and `kolt.lock` (machine-written resolved dependency graph with pinned versions, sha256 hashes, source URLs, and native `available-at` redirect chains).

The Phase 1 prototype used JSON for both. As soon as dependencies, plugins, repositories, and nested tables appeared, JSON became painful: no comments, quote-heavy keys, trailing-comma errors on reordering, and escalating brace depth for nested tables. Cargo, Poetry, and uv have already converged on TOML for the same role and the same reasons.

The lockfile is different — never edited by hand, so human ergonomics are irrelevant. It needs a deterministic serialiser (stable diffs), schema evolution without hand-written parsers, and first-class Kotlin/Native support. kotlinx-serialization-json satisfies all three and is already in the dependency graph.

## Decision Drivers

- Users must be able to comment out a dependency or annotate a pinned version in `kolt.toml`.
- Lockfile diffs must be stable across runs so a changed line signals a real dependency move.
- Schema evolution (`v1 → v2 → v3`) must require no hand-written JSON walking.
- No new Kotlin/Native-incompatible dependency.

## Decision Outcome

Chosen option: **TOML for `kolt.toml`, JSON for `kolt.lock`**, because each format matches the actual usage of its file.

### §1 `kolt.toml` — ktoml 0.7.1

`parseConfig` reads `kolt.toml` using `com.akuleshov7:ktoml-core` / `ktoml-file` and deserialises into the `KoltConfig` data class. ktoml is used only for `kolt.toml`. Tests live in `ConfigTest.kt`.

### §2 `kolt.lock` — kotlinx-serialization-json

`Lockfile.kt` owns the schema. Writes go through `Json { prettyPrint = true; prettyPrintIndent = "  " }` for reviewable diffs. kotlinx-serialization orders fields by data class declaration (not by hash), so output is stable across runs. Schema evolution uses `@SerialName` plus nullable/default fields.

Schema history:

- **v1 → v2**: added `LockEntry.transitive: Boolean = false` so direct vs transitive origin is recoverable from the lockfile alone.
- **v2 → v3**: added `LockEntry.test: Boolean = false` so main vs test closure is recoverable offline (spec `main-test-closure-separation`). Per the pre-v1 clean-break policy (CLAUDE.md), `parseLockfile` rejects v1 and v2 with `LockfileError.UnsupportedVersion`; users regenerate via `kolt deps install`. ADR 0027 §1 consumes this flag to keep test-origin deps out of the runtime classpath manifest.

Tests live in `LockfileTest.kt`.

### §3 ktoml quoted-key workaround

When a TOML key is quoted (e.g. `"org.jetbrains.kotlinx:kotlinx-coroutines-core" = "1.9.0"`, required for Maven coordinates containing `:`), ktoml returns the map key with the surrounding quotes still attached. `parseConfig` strips one leading-and-trailing `"` pair from every map key. This is documented at the call site and covered by a test. Any ktoml upgrade must re-verify this behaviour.

### §4 Both formats are diffable

Neither `kolt.toml` nor `kolt.lock` is binary. Both are diffable, grep-able, and editable when debugging. ktoml supports the subset of TOML v1.0.0 needed by `kolt.toml`'s schema; no relied-upon feature sits in the unsupported set.

### Consequences

**Positive**
- Users can comment out a dependency or annotate a pinned version in `kolt.toml`.
- Lockfile diffs are stable and reviewable; a changed line is a meaningful signal.
- Schema evolution (v1 → v2 → v3 lockfile bumps) required only `@SerialName` and a version-discriminated parser — no hand-written JSON walking.
- TOML is familiar to anyone who has used Cargo, Poetry, or uv.

**Negative**
- Two parser dependencies: ktoml for config, kotlinx-serialization for lockfile.
- ktoml's quoted-map-key behaviour is a quirk that must be re-verified on every ktoml upgrade.
- No shared serialiser helper: config and lockfile go through separate read/write pairs.
- Deeply nested heterogeneous arrays are awkward in TOML. kolt's flat schema is unaffected, but it constrains future extensions.

### Confirmation

`parseConfig` and `Lockfile.kt` enforce the format split. The quoted-key workaround is covered by a dedicated test in `ConfigTest.kt`. Parser tests fail if the workaround is removed or if ktoml's behaviour changes.

## Alternatives considered

1. **TOML for everything (including the lockfile).** Rejected. ktoml is read-only (no serialisation side). Adding a dependency just to write TOML buys nothing for a machine-written file nobody edits.
2. **JSON for everything (keep Phase 1 as-is).** Rejected. Users disliked `kolt.json` in informal feedback, and the ergonomic problems are real.
3. **YAML for `kolt.toml`.** Rejected. YAML has significant-whitespace pitfalls, the Norway problem (`no` → `false`), and no established Kotlin/Native parser.
4. **A custom mini-format.** Rejected. Every build tool that rolls its own config language eventually regrets it. TOML is standardised, documented, and has external tooling.

## Related

- `src/nativeMain/kotlin/kolt/config/Config.kt` — `parseConfig` and the quoted-key workaround
- `src/nativeMain/kotlin/kolt/resolve/Lockfile.kt` — v3 schema, JSON writer (v1/v2 rejected per pre-v1 clean-break)
- Commit `3325d37` — migration from `kolt.json` to `kolt.toml`
