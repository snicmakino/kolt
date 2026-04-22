---
status: implemented
date: 2026-04-22
---

# ADR 0026: Daemon module naming convention

## Summary

- Adopt `kolt-<target>-<role>-daemon` for every daemon subproject. `<target>` comes from the ADR 0023 target schema (`jvm`, `native`, future `js`/`wasm`); `<role>` is the daemon's job (`compiler`, future `runner`, etc.). (§1)
- Rename the two existing daemons: `kolt-compiler-daemon/` → `kolt-jvm-compiler-daemon/`, `kolt-native-daemon/` → `kolt-native-compiler-daemon/`. (§2)
- Socket filenames, process titles, and log prefixes derive from the module name (`kolt-<target>-<role>-daemon/` → `<target>-<role>-daemon.sock`); the shared `~/.kolt/daemon/<hash>/<kotlinVersion>/` directory stays, with one socket per daemon. (§3)
- No abbreviations (no `d`/`n`/`j`/`comp`). These identifiers appear in Gradle files, `ps`, and logs — discoverability and grep hits beat terminal width. (§4)
- Breaking rename; no compat shim per the pre-v1 policy in CLAUDE.md. Release note instructs `rm -rf ~/.kolt/daemon/`. (§5)

## Context and Problem Statement

ADR 0016 introduced `kolt-compiler-daemon` when kolt had one daemon; ADR 0024 added `kolt-native-daemon` for `konanc`. The asymmetry — one name drops target, the other drops role — means a new contributor cannot predict where the axis sits.

A warm JVM runner daemon for `kolt run` / scripts is the most likely next daemon, and at that point the rename is forced. Fixing the convention now, while only two daemons exist, is cheap.

## Decision Drivers

- A new contributor adding a third daemon should be able to pick the right name without reading this ADR.
- The scheme must survive the next realistic daemon (JVM runner, test runner, LSP sidecar) without collapsing an axis again.
- Module/process/socket identifiers are not CLI surface — optimize for grep and code review, not typing.
- Pre-v1 breaking renames are acceptable (CLAUDE.md), so the cost is mechanical, not political.

## Decision Outcome

Chosen scheme: **`kolt-<target>-<role>-daemon`**, because it keeps both axes explicit and composes with ADR 0023's target vocabulary.

### §1 Naming scheme

```
kolt-<target>-<role>-daemon
```

- `<target>` — from the ADR 0023 target schema: currently `jvm`, `native`; reserved `js`, `wasm`.
- `<role>` — lowercase-ASCII word naming the daemon's job: currently `compiler`; anticipated `runner`, `test`, `lsp`.
- Both components are always present, even when one side has only a single occupant today (`kolt-native-compiler-daemon`, not `kolt-native-daemon`).

A new `<role>` is introduced by the ADR that adds the daemon claiming it — the pattern established by ADR 0016 (`compiler` for JVM) and ADR 0024 (`compiler` for native). No separate role-registry ADR is required; the daemon's own ADR fixes the role. A daemon serving multiple jobs (e.g. `kolt run` + `kolt test` in one process) picks one `<role>` covering them; roles are not composed in the module name.

This ADR governs only modules whose name ends in `-daemon`. Non-daemon sidecars (e.g. a one-shot `kolt lsp` process, codegen worker jars invoked per build) are out of scope; if one later grows a daemon variant it joins the scheme.

### §2 Renames

| Old | New |
|---|---|
| `kolt-compiler-daemon/` | `kolt-jvm-compiler-daemon/` |
| `kolt-native-daemon/` | `kolt-native-compiler-daemon/` |

Both are independent Gradle builds included via `includeBuild` from the root (ADR 0016, ADR 0024 §8). The `includeBuild` paths, `:build` task wiring in the root, and any `DaemonJarResolver` lookups update together.

### §3 Downstream identifiers

Every user- or log-visible daemon identifier tracks the module name:

| Surface | Old | New |
|---|---|---|
| Socket file | `daemon.sock` | `jvm-compiler-daemon.sock` |
| Socket file | `native-daemon.sock` | `native-compiler-daemon.sock` |
| Process title / log prefix | `kolt-compiler-daemon` | `kolt-jvm-compiler-daemon` |
| Process title / log prefix | `kolt-native-daemon` | `kolt-native-compiler-daemon` |

**Socket filename derivation.** Strip `kolt-` from the module name, append `.sock`: `kolt-<target>-<role>-daemon/` → `<target>-<role>-daemon.sock`.

**Cache directory.** `~/.kolt/daemon/<projectHash>/<kotlinVersion>/` stays as the shared parent for every daemon in a project. `<kotlinVersion>` is the shared cache key — exact for the compiler daemons and conservative for others (a runner daemon's warm state technically invalidates on user classpath / JRE changes, not on Kotlin bumps; keying it under `<kotlinVersion>` accepts one extra cold start per Kotlin bump to keep the directory layout uniform). Only the leaf socket name differs per daemon.

**Stop behaviour.** `kolt daemon stop` discovers running daemons by walking this directory, so a new daemon inherits stop for free — no hard-coded list to update.

### §4 No abbreviations

Write `jvm`, `native`, `compiler`, `runner` in full. Abbreviations (`j`, `n`, `comp`, `-d`) save typing but cost grep and onboarding, and these identifiers are read far more often than typed.

CLI-facing names may short-form later if ergonomics demand it (e.g. `kolt daemon stop jvm-compiler`).

### §5 Breaking change handling

The rename lands in one PR with no shim (pre-v1 policy, CLAUDE.md). Release note text:

> Daemon module names were renamed. Run `rm -rf ~/.kolt/daemon/` to clear stale sockets from the previous layout.

Prior ADRs (0016, 0019, 0020, 0024) keep their historical text and are not edited by this ADR. This ADR is the source of truth for current daemon names; the mapping tables in §2–§3 bridge readers arriving from the older ADRs.

### Consequences

**Positive**
- Future daemons (JVM runner, test daemon, LSP sidecar) have an unambiguous slot in the naming space before anyone has to argue about it.
- `grep -r kolt-jvm-` and `grep -r kolt-native-` become reliable ways to find target-specific code.
- The scheme is self-documenting enough that a follow-up `CONTRIBUTING` note is optional.

**Negative**
- One breaking rename users have to accept, with a cache wipe.
- Several files move at once (Gradle includes, socket constants, log format strings, ADR cross-refs). Mechanical but wide.

### Confirmation

Enforced by review, not by lint. A PR that introduces `kolt-foo-daemon/` (no target) or `kolt-jvm-daemon/` (no role) is rejected pointing at this ADR.

## Alternatives considered

1. **Keep the asymmetric pair (`kolt-compiler-daemon` + `kolt-native-daemon`).** Rejected; see §Context.
2. **Target-only scheme (`kolt-jvm-daemon`, `kolt-native-daemon`).** Rejected. Collapses the role axis and re-creates the same ambiguity the moment a second JVM daemon lands.
3. **Role-only scheme (`kolt-compiler-daemon`, `kolt-native-compiler-daemon`).** This is roughly the status quo. Rejected for symmetry: mixing "role implicit by target" and "target implicit by role" is the current problem.
4. **Abbreviated identifiers (`kolt-j-compd`, `kolt-n-compd`).** Rejected; see §4.

## Related

- ADR 0016 — JVM compiler daemon (original `kolt-compiler-daemon` module)
- ADR 0020 — compiler daemon scope (caps what `kolt-jvm-compiler-daemon` may absorb)
- ADR 0023 — target and kind schema (source of `<target>` vocabulary)
- ADR 0024 — native compiler daemon (original `kolt-native-daemon` module; §3 socket path renamed here)
- CLAUDE.md — pre-v1 no-backcompat policy justifying a clean rename
