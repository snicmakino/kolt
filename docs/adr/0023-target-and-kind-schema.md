# ADR 0023: `target` and `kind` schema for the build axis

## Status

Accepted (2026-04-19).

## Context

`kolt.toml`'s `target` field accepts `"jvm"` or `"native"`. The
`"native"` value collapses every Kotlin/Native triple into one
identifier, which works today only because the build pipeline
hardcodes `linuxX64` (output paths, libcurl `.def` linker opts, CI).
Adding a second native triple — `macosArm64` or `linuxArm64`, both
on the README's v1.0 list — forces every additional target into
host-detection branches and pushes platform identity out of the
config and into runtime inference.

Independently, `kolt.toml` requires `main`, which implicitly commits
every project to being an application. There is no way to declare a
library, which blocks `kolt publish` and the ADR 0018 self-host
endgame (where `kolt-compiler-daemon/` would be a kolt-built library
rather than a Gradle subproject).

These two gaps share a schema design surface: a `kind = "lib"`
project still needs a `target`, and a `target = "linuxX64"` project
still needs a `kind`. Designing them together also means the
schema can accommodate a future "KMP non-goal retracted" ADR
without a second migration. Whether to retract is out of scope
here; whether the schema can absorb the retraction is not.

## Decision

Three changes to the build-axis schema, taken together.

### Schema placement

| Field | Location | Why |
| :--- | :--- | :--- |
| `kind` | top-level | Project identity (next to `name`, `version`), invariant across targets. |
| `target` (scalar) | inside `[build]` | Unchanged location from the current schema — only the accepted values change. |
| `[build.targets.X]` tables | nested under `[build]` | Multi-target is a refinement of the build axis; the nesting makes the containment explicit and keeps shared fields in `[build]` directly. |

### 1. Introduce `kind`

```toml
# top-level, next to `name` / `version`
kind = "app"   # or "lib"
```

Project-level, immutable across targets. Default `"app"` so every
existing `kolt.toml` parses unchanged. `kind = "lib"` is reserved
by this ADR but rejected at build time with a "not yet implemented"
error; the slot exists so library support is a localized follow-up,
not a schema revision.

When `kind = "lib"` is eventually implemented, `[build] main` will
be rejected for libraries ("`main` has no meaning for a library;
remove it") and `kolt run` against a library will be a usage error.
Until then, `kind = "lib"` itself is rejected at parse time, so the
`main` rule is reserved schema semantics rather than active
validation.

### 2. `target` uses `KonanTarget` identifiers

```toml
[build]
target = "jvm" | "linuxX64" | "linuxArm64"
       | "macosX64" | "macosArm64" | "mingwX64"
```

The native vocabulary matches `KonanTarget.<n>.name`, which is
also what Gradle Module Metadata uses (modulo the well-known
`linuxX64` ↔ `linux_x64` casing — one mapping table inside
`NativeResolver`).

`target = "native"` is removed with no auto-migration. The parser
returns:

> `target = "native"` is no longer accepted. Use a specific Konan
> target, e.g. `target = "linuxX64"`.

This follows ADR 0015's precedent: silent two-mode interpretation
is more confusing than a parse-time error, and kolt is pre-1.0.

### 3. `[build] target = "X"` and `[build.targets.X]` are mutually exclusive

```toml
# Single-target form
[build]
target = "linuxX64"

# Multi-target form (reserved)
[build]
# shared fields only — no `target` scalar

[build.targets.jvm]
[build.targets.linuxX64]
```

Specifying both a scalar `target` in `[build]` and one or more
`[build.targets.X]` tables is a parse error. There is no
`target = "kmp"` sentinel — the existence of two or more
`[build.targets.X]` tables is itself the multi-target signal.

Multi-target form is reserved by this ADR: parsing accepts exactly
one `[build.targets.X]` table (de-sugared to `[build] target = "X"`
internally); two or more produce a "multi-target builds are not yet
implemented" error. This is the surface a future KMP ADR would build
on.

Empty `[build.targets.X]` tables are legal — their presence is the
declaration. The per-target field vocabulary (which existing `[build]`
fields may be overridden inside a `[build.targets.X]` table) is
deferred to the KMP follow-on ADR; multi-target is reserved, so the
question does not need to be answered here.

## Consequences

### Positive

- **`kolt.toml` becomes self-describing.** What artifact a build
  produces is determined by the config alone, not by host detection.
  ADR 0018's tarball naming becomes a function of `target`, not of
  CI runner identity.
- **Library packaging is unblocked at the schema level.** The slot
  to declare a library exists; the implementation follow-up
  changes the build pipeline without revisiting the parser.
- **The KMP question gets a clean landing surface.** A future ADR
  retracting the KMP non-goal needs only to lift the
  "one `[build.targets.X]` only" restriction and define per-source-set
  semantics; no schema redesign.
- **No sentinel values.** Each legal `target` value names a real,
  distinct artifact shape. The parser's `when` over targets stays
  meaningful.

### Negative

- **Two breaking changes in one release.** `target = "native"`
  rejection has no compatibility shim; the `kind` default mitigates
  the second change. Acceptable pre-1.0; called out in the release
  notes.
- **"Reserved but unimplemented" errors.** Both `kind = "lib"` and
  multi-target form land as explicit rejection messages. The text
  has to make it unambiguous that this is intentional reservation,
  not a bug.
- **Cross-compilation expectations rise.** Once `target =
  "macosArm64"` is a writable value, users will expect to invoke it
  from any host. konanc supports this, but cinterop `.def` files
  will need per-target keys (`compilerOpts.osx`, `compilerOpts.mingw`)
  the format already supports. Documentation work, not code work.

## Alternatives considered

1. **Keep `target = "native"` and auto-detect the host.** Rejected.
   Same `kolt.toml` would produce different artifacts depending on
   who runs `kolt build`. ADR 0015 rejected the same shape for the
   same reason.
2. **Introduce `target = "kmp"` as a sentinel.** Rejected. The
   presence of two or more `[targets.X]` tables is itself the
   signal; a sentinel would be redundant and would create a
   "what wins when both are declared" ambiguity. Cargo and Amper
   both omit the equivalent sentinel.
3. **Fold `kind` into `target` (`target = "jvm-lib"` etc.).**
   Rejected. Conflates orthogonal axes and explodes the vocabulary
   multiplicatively (six targets × two kinds = twelve values
   instead of six plus two).
4. **Place `kind` inside `[build]`.** Rejected. `kind` is project
   identity, invariant across targets — it belongs next to `name`
   and `version`, not with build-axis knobs. A future multi-target
   `kolt.toml` will carry multiple `[build.targets.X]` tables sharing
   one `kind`; nesting it inside `[build]` would invite the same
   "which target's kind wins" ambiguity that disqualifies
   `target = "kmp"` above.

## Related

- #167 — tracking issue (parser, validation, migration, `kolt init`,
  resolver dispatch update)
- ADR 0010 — Gradle Module Metadata for native resolution
  (consumes the Konan target identifier)
- ADR 0015 — `main` is a Kotlin function FQN (precedent for
  hard-error migration with a hint)
- ADR 0018 — Distribution layout (its tarball naming becomes a
  function of the `target` value chosen here)
- README — `Not yet supported` list (`macOS and linuxArm64
  targets` becomes a target-vocabulary question after this ADR)
- `architecture.md` — `Out of scope` section's KMP entry (the
  follow-on ADR will revisit; this ADR does not)
