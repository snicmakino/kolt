# ADR 0003: TOML for `kolt.toml`, JSON for `kolt.lock`

## Status

Accepted (2026-04-09)

## Context

kolt has two persistent file formats:

- **`kolt.toml`** — the project configuration. Human-authored. Contains
  the project name, target (`jvm` / `native`), main class, source
  layout, dependency list, compiler plugins, custom Maven repositories,
  toolchain versions, and formatter options. Users edit this by hand,
  review it in diffs, and expect to be able to comment out a dependency
  while debugging.
- **`kolt.lock`** — the resolved dependency graph. Machine-written.
  Contains the full transitive closure with pinned versions, sha256
  hashes, source repository URLs, and (for native targets) the
  `available-at` redirect chain. Regenerated on every `kolt install` /
  `kolt update` / lockfile-invalidating change.

The Phase 1 prototype used JSON for both. `kolt.json` worked for the
handful of fields the prototype had, but as soon as dependencies,
plugins, repositories, and nested tables started showing up it became
painful:

- **No comments**: users could not annotate why a specific version was
  pinned, or temporarily disable a line with `//`.
- **Quote-heavy**: every key and every string value is quoted, which
  makes a dependency list visually noisy.
- **Trailing commas are a syntax error**: reordering or commenting
  lines requires fixing punctuation every time.
- **Nested tables are verbose**: `dependencies`, `test-dependencies`,
  `plugins`, `repositories` each become nested JSON objects with
  escalating brace depth.

These are exactly the problems TOML was designed to solve. Cargo, Poetry,
and other tools in the same niche have already converged on TOML for
the same reasons.

The lockfile is a different story. It is never edited by hand, so the
"human ergonomics" argument does not apply. What the lockfile needs is:

- A deterministic serialiser (so diffs are meaningful).
- A schema that can evolve (v1 → v2) without hand-written parsers.
- First-class support in Kotlin/Native without pulling in a second TOML
  implementation.

JSON fits all three. kotlinx-serialization-json already produces
deterministic output, handles schema evolution via `@SerialName` +
nullable / default fields, and is the de facto standard for Kotlin
data persistence.

## Decision

Split the two formats:

- **`kolt.toml`** is parsed with **ktoml 0.7.1**
  (`com.akuleshov7:ktoml-core` / `ktoml-file`). `parseConfig` reads the
  file and deserialises into the `KoltConfig` data class.
- **`kolt.lock`** stays on **kotlinx-serialization-json**. `Lockfile.kt`
  owns the v1 and v2 schemas; writes go through
  `Json { prettyPrint = true; prettyPrintIndent = "  " }` for
  reviewable diffs.

One ktoml quirk had to be worked around: when a TOML key is quoted
(e.g. `"org.jetbrains.kotlinx:kotlinx-coroutines-core" = "1.9.0"`,
required for Maven coordinates containing `:`), ktoml deserialises the
map key with the surrounding quotes *still attached*. `parseConfig`
strips a leading-and-trailing pair of `"` characters from every map
key before handing the config back. This is documented at the call site
and covered by a test.

ktoml is used only for `kolt.toml`. Tests for `kolt.toml` parsing live
in `ConfigTest.kt`; tests for the lockfile live in `LockfileTest.kt`.

## Consequences

### Positive

- **Comments in `kolt.toml`**: users can write
  `# pinned for netty compatibility, do not upgrade` next to a
  dependency, or comment a line out while debugging.
- **Clean human syntax**: dependency tables are one line per entry,
  quote-free keys where possible, no trailing-comma trap.
- **Familiar to users**: Cargo, Poetry, uv, and many others use TOML
  for the same role. The learning curve for `kolt.toml` is zero for
  anyone who has touched those tools.
- **Lockfile diffs remain reviewable**: two-space-indented JSON is
  stable across runs (kotlinx-serialization orders fields by the data
  class declaration, not by hash), so a lockfile change in git is a
  meaningful signal that dependencies moved.
- **Schema evolution stays cheap**: the v1 → v2 lockfile bump used
  `@SerialName` and a version-discriminated parser. No hand-written
  JSON walking was needed.

### Negative

- **Two parser dependencies**: ktoml for config, kotlinx-serialization
  for lockfile. Both are small Kotlin/Native-compatible libraries, but
  it is two instead of one.
- **ktoml quirks**: the quoted-map-key workaround is ugly, and
  `parseConfig` has to know about it. Any future ktoml upgrade has to
  re-verify the behaviour.
- **No single `kolt.*` serialiser helper**: config and lockfile go
  through different code paths, so there is no shared "write this data
  class to disk" primitive. Each side has its own read/write pair.
- **TOML is less expressive than JSON in some corners**: deeply nested
  heterogeneous arrays (e.g. `[[plugins.options]]` tables within tables)
  are awkward to express. kolt's schema is flat enough that this has
  not bitten us, but it constrains future extensions.

### Neutral

- **Neither format is binary**: both are diffable, grep-able, and
  editable if something goes wrong. This was a hard requirement.
- **ktoml supports a subset of TOML v1.0.0**: enough for `kolt.toml`'s
  schema. No feature we rely on sits in the unsupported set.

## Alternatives Considered

1. **TOML for everything (including the lockfile)** — rejected.
   Writing a lockfile means emitting a deterministic, machine-readable
   dump of a Kotlin data class. kotlinx-serialization-json does this
   out of the box; ktoml has no serialisation side at all
   (read-only). Adding a second dependency just to write TOML would
   buy us nothing, because nobody edits the lockfile by hand anyway.
2. **JSON for everything (keep Phase 1 as-is)** — rejected for the
   ergonomics reasons above. Users already disliked the prototype's
   `kolt.json` in informal feedback.
3. **YAML for `kolt.toml`** — rejected. YAML has comments, but it also
   has significant-whitespace pitfalls, the Norway problem (`no` →
   `false`), and no established Kotlin/Native parser. The combination
   of "requires a large parser port" and "known footguns" was a clear
   no.
4. **A custom mini-format parsed by kolt itself** — rejected. Every
   build tool that rolls its own config language eventually regrets it.
   TOML is standardised, documented, and has external tooling.

## Related

- `src/nativeMain/kotlin/kolt/config/Config.kt` — `parseConfig` and
  the quoted-key workaround
- `src/nativeMain/kotlin/kolt/resolve/Lockfile.kt` — v1/v2 schemas,
  JSON writer
- Commit `3325d37` (migration from `kolt.json` to `kolt.toml`)
