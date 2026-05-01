---
status: accepted
date: 2026-05-01
---

# ADR 0033: kolt.toml change-handling model

## Summary

- The change-handling model is expressed by a fixed three-value `SectionAction` taxonomy: `AutoReload(rebuild: Boolean)`, `NotifyOnly(recommendation: String)`, and `NoOp`. Every classified kolt.toml section maps to one of these (§1).
- Per-invocation entry points (`kolt build` / `kolt test` / `kolt run`, and `kolt deps {add, install, update}`) re-read kolt.toml fresh on every command. The matrix exists primarily to make this implicit guarantee explicit (§2).
- Watch mode (`kolt {build,run,test} --watch`) classifies each detected kolt.toml change into the same taxonomy and dispatches accordingly; auto-reload sections take effect silently on the next build, notify-only sections require explicit user action, no-op sections are ignored (§3).
- When a debounce window contains both auto-reload and notify-only changes, notify-only prevails: no auto-reload, no rebuild, only notifications are emitted. Auto-reload sections become effective when the user takes the recommended explicit action and the watch process restarts (§4).
- Config reload is held until the in-flight build completes; reload never cancels a running build. The kernel inotify buffer plus the existing synchronous event handler implement this without an additional state machine (§5).
- The kolt JVM and native compiler daemons remain stateless with respect to kolt.toml. The daemon protocol does not gain a config-changed message; daemon-side BTA invalidation for `[kotlin]` family changes is replaced by user-explicit watch restart via NotifyOnly (§6).
- `kolt deps remove` is the documented per-invocation symmetry follow-up (#297 Q6) — outside this ADR's scope (§7).

## Context and Problem Statement

`kolt.toml` is the single source of truth for build configuration, but the rules for what happens when it changes have grown implicit and inconsistent across entry points (#297). `kolt deps add` performs a full sync (rewrite + resolve + lockfile + JAR cache) on the spot. Hand-edits defer to whatever the next CLI invocation does. Watch mode reruns `doBuild` in process when `kolt.toml` changes but never reloads its own configuration, so `[build.sources]` edits leave the watch range stale and `[run.sys_props]` edits never reach the spawned JVM. The daemon has no observation channel for config changes and relies on BTA's internal invalidation for `[kotlin.plugins]` and `[kotlin] version`. Before adding more entry points (`kolt deps remove`, IDE/LSP integrations, future config sections) the rules need to be made explicit — otherwise each new feature invents its own change-handling semantics and the asymmetry compounds.

This ADR commits to a single uniform model: every section in `kolt.toml` maps to one of three actions, the watch loop classifies every detected change against this matrix, and per-invocation entry points are formalized as the eager-symmetric path that already exists in code today.

## Decision Drivers

- A contributor reading `kolt.toml` should be able to predict what happens next without consulting the source for each command. The current per-entry-point asymmetry violates this directly.
- Network I/O and daemon lifecycle changes must remain explicit user operations. Watch mode does not implicitly contact Maven Central or restart compiler daemons.
- BTA's internal cache invalidation for compiler-state-affecting fields is opaque from kolt's perspective; the model must not depend on guarantees the daemon does not give us.
- The matrix must cover every current kolt.toml section without gaps. Future schema additions must be classified before merging or the maintenance clause is broken.
- Build correctness over reload latency: the model never sacrifices a partial build for a fresh config.

## Considered Options

- **Eager auto-resync (β1)** — the watch loop detects every change, reloads config, runs dependency resolution if needed, and rebuilds. Modeled on IntelliJ + Gradle "Load Gradle Changes." Rejected; see Alternatives.
- **Daemon-side observation (α2)** — extend the daemon protocol with a config-changed message so the daemon can perform explicit IC invalidation in response to `[kotlin]` family changes. Rejected; see Alternatives.
- **Notify-with-prevail (chosen)** — watch loop is purely an informer for changes that require explicit operations (network I/O, daemon restart). Auto-reload is reserved for kolt-internal changes that do not cross those boundaries.

## Decision Outcome

Chosen approach: **Notify-with-prevail**. The full model is below.

### §1 SectionAction taxonomy

`SectionAction` has exactly three top-level values, defined in `src/nativeMain/kotlin/kolt/config/ChangeMatrix.kt`:

- **`AutoReload(rebuild: Boolean)`** — the watch loop reloads its in-memory `KoltConfig` from the new kolt.toml and either triggers a rebuild (`rebuild=true`) or refreshes config silently (`rebuild=false`, used for runtime-only sections like `[run.sys_props]`). The taxonomy keeps `rebuild` as a sub-attribute rather than splitting into separate cases so the dispatch logic stays single-branch.
- **`NotifyOnly(recommendation: String)`** — the watch loop emits a notification carrying the section name and the recommended user action (e.g. `Run kolt deps install`, `Run kolt daemon stop --all and restart watch`). No reload, no rebuild. The previous in-memory config is retained so subsequent source-driven rebuilds continue with consistent state.
- **`NoOp`** — the watch loop does nothing observable. Reserved for sections whose effect is fully external to watch's responsibilities (e.g. `[fmt]` is consumed only by `kolt fmt`).

Any section name absent from the matrix table receives a defensive `NotifyOnly("This section is not yet classified; please file an issue or update ChangeMatrix.kt")` so a forward-compatibility gap manifests as a visible notification rather than silent ignore.

### §2 Per-invocation matrix

Every kolt CLI command — `kolt build`, `kolt test`, `kolt run`, and `kolt deps {add, install, update}` — calls `loadProjectConfig()` (`src/nativeMain/kotlin/kolt/cli/BuildCommands.kt:242`) before executing, so any kolt.toml change is picked up on the next invocation without further action. The per-invocation matrix is therefore a description of observable effects rather than a dispatch table.

| Section | Effect on the next CLI invocation |
| --- | --- |
| `name` | New artifact name |
| `version` | New artifact version |
| `kind` | Build pipeline switch (app ↔ lib) |
| `[kotlin] compiler` / `[kotlin] version` / `[kotlin.plugins]` | New compiler binary or language/API version or plugin set |
| `[build] target` / `[build] jvm_target` / `[build] jdk` / `[build] main` | New build target or JDK pin or entry point |
| `[build] sources` / `[build] test_sources` / `[build] resources` / `[build] test_resources` | New compile inputs picked up on the next build |
| `[build.targets.<target>]` | Per-target override re-applied on the next build (resolution-time effect; sub-fields inherit from the parent classification) |
| `[fmt]` | New formatter style applied by `kolt fmt` |
| `[dependencies]` / `[test-dependencies]` / `[repositories]` | Resolution re-runs; lockfile updated if the resolution result changed |
| `[[cinterop]]` | cinterop bindings regenerated on next native build |
| `[classpaths]` | Bundle resolution re-runs (one resolution per declared `<name>`) |
| `[test.sys_props]` / `[run.sys_props]` | New sysprops applied to the spawned JVM |

`kolt deps add` additionally rewrites `kolt.toml` itself before invoking the same fresh-load path internally, so the post-state for `kolt deps add <gav>` matches what a hand-edit followed by `kolt deps install` would produce.

### §3 Watch matrix

In watch mode the kolt watch loop (`src/nativeMain/kotlin/kolt/cli/WatchLoop.kt`) classifies each detected kolt.toml change against the matrix below and dispatches per the SectionAction. Sub-sections of the same parent are listed together under a shared parent label.

Each row's "Section" cell uses the canonical key in `SECTION_ACTIONS` (`src/nativeMain/kotlin/kolt/config/ChangeMatrix.kt`) verbatim — the same string surfaced in user-facing notifications. Visual grouping by family is informational; the canonical key is what `SECTION_ACTIONS.keys` and the schema-coverage test compare against.

| Section | Watch action | Recommended user action (NotifyOnly only) |
| --- | --- | --- |
| `name` | `AutoReload(rebuild=true)` | — |
| `version` | `AutoReload(rebuild=true)` | — |
| `kind` | `NotifyOnly` | Restart watch — kind change alters build pipeline |
| `[kotlin]` family | | |
| &nbsp;&nbsp;`[kotlin] compiler` | `NotifyOnly` | Run kolt daemon stop --all and restart watch |
| &nbsp;&nbsp;`[kotlin] version` | `NotifyOnly` | Run kolt daemon stop --all and restart watch |
| &nbsp;&nbsp;`[kotlin.plugins]` | `NotifyOnly` | Run kolt daemon stop --all and restart watch |
| `[build]` family | | |
| &nbsp;&nbsp;`[build] target` | `NotifyOnly` | Restart watch — target change alters build pipeline |
| &nbsp;&nbsp;`[build] jvm_target` | `NotifyOnly` | Restart watch — jvm_target change alters compiler bytecode pin |
| &nbsp;&nbsp;`[build] jdk` | `NotifyOnly` | Restart watch — JDK pin change alters daemon process |
| &nbsp;&nbsp;`[build] main` | `AutoReload(rebuild=true)` | — |
| &nbsp;&nbsp;`[build] sources` | `AutoReload(rebuild=true)` | — |
| &nbsp;&nbsp;`[build] test_sources` | `AutoReload(rebuild=true)` | — |
| &nbsp;&nbsp;`[build] resources` | `AutoReload(rebuild=true)` | — |
| &nbsp;&nbsp;`[build] test_resources` | `AutoReload(rebuild=true)` | — |
| `[build.targets.<target>]` | `NotifyOnly` (defensive fallback at runtime; not classified individually in SECTION_ACTIONS today) | Restart watch — per-target override changes are not yet auto-reloaded |
| `[fmt]` | `NoOp` | — |
| `[dependencies]` | `NotifyOnly` | Run kolt deps install |
| `[test-dependencies]` | `NotifyOnly` | Run kolt deps install |
| `[repositories]` | `NotifyOnly` | Run kolt deps install |
| `[[cinterop]]` | `NotifyOnly` | Restart watch — cinterop binding regeneration required |
| `[classpaths]` | `NotifyOnly` | Run kolt deps install |
| `[test.sys_props]` | `AutoReload(rebuild=false)` | — |
| `[run.sys_props]` | `AutoReload(rebuild=false)` | — |

For the `AutoReload` rows that include `[build] sources` or `[build] resources`, the watch loop additionally reconstructs its watcher path set (full rebuild: unregister all existing inotify watches, re-run `collectWatchPaths`, re-register the new path set) before triggering the rebuild. Differential watcher updates are out of scope; a kolt project's watch path count is small enough that full rebuild costs nothing measurable.

For `[run.sys_props]` in `kolt run --watch` specifically, when the AutoReload path applies (i.e. `plan.reload && !plan.rebuild` and `[run.sys_props]` is in the changed sections), the running app is killed and respawned with the new sysprops via the existing run-loop spawn path. This is the only loop-specific dispatch — `watchCommandLoop` (build / test) does not respawn anything.

### §4 Notify-only-prevail in mixed windows

When a single debounce window contains changes to both auto-reload sections and notify-only sections, the watch loop treats the entire window as notify-only: it emits the notifications for the notify-only sections and skips reload, watcher reconstruction, and rebuild. The auto-reload sections become effective only when the user takes the recommended explicit action (typically `kolt deps install` or watch restart) and the next watch invocation begins from a fresh `loadProjectConfig`.

The reasoning is that auto-reload paths assume the resolved dependency set, daemon process state, and compiler args are still consistent with the previous config. If a notify-only section in the same window says otherwise, an auto-reload + rebuild would compile against stale dependencies or stale daemon IC, fail with a confusing error, and pollute the log alongside the more important notification. The user-explicit action is the only point where consistency is reliably re-established.

### §5 Build serialization without a state machine

Watch mode never cancels an in-flight build to apply a pending reload. The implementation does not introduce a new state machine for this; it relies on two structural properties of the existing watch loop:

- The inotify event handler is synchronous: when `commandRunner` is invoked, the calling event-loop frame blocks until the rebuild returns.
- The kernel inotify buffer holds events delivered while the handler is blocked, so subsequent kolt.toml modifications surface as the next event after the current build completes.

If a future change makes the handler asynchronous, the structural guarantee disappears and an explicit in-flight flag becomes necessary. That is recorded as a Revalidation Trigger in `.kiro/specs/toml-change-handling/design.md`.

### §6 Daemon stays stateless with respect to kolt.toml

The kolt JVM compiler daemon (`kolt-jvm-compiler-daemon/`) and the kolt native compiler daemon (`kolt-native-compiler-daemon/`) do not read `kolt.toml`. The daemon protocol does not gain a config-changed message. Daemon-side IC invalidation for `[kotlin]` family changes is not provided; instead, the watch loop classifies those sections as NotifyOnly so the user's explicit `kolt daemon stop --all` followed by watch restart performs the invalidation by completely retiring the old daemon process.

This decision (α1 in spec discovery) is preserved from prior practice but recorded explicitly here so a future ADR has to supersede this paragraph to add daemon-side observation. The trade-off is that we accept slower reaction time for `[kotlin]` family changes (one user action away rather than zero) in exchange for not having to trust BTA's IC invalidation behavior across compiler versions.

### §7 `kolt deps remove` is a documented follow-up

The current per-invocation entry points cover `kolt deps add`, `kolt deps install`, and `kolt deps update` but not `kolt deps remove` (#297 Q6). Adding it is symmetric — a hand-edit that removes a `[dependencies]` line followed by `kolt deps install` already produces the right post-state — but the explicit command is missing. This ADR names the gap; the implementation is tracked separately and is not within this spec's scope.

### Maintenance clause

Adding a new top-level section to the kolt.toml schema requires three changes in the same merge request:

1. Add the field to `KoltConfig` (`src/nativeMain/kotlin/kolt/config/Config.kt`).
2. Classify the section in `SECTION_ACTIONS` (`src/nativeMain/kotlin/kolt/config/ChangeMatrix.kt`) against the SectionAction taxonomy in §1.
3. Update both matrix tables in this ADR (§2 and §3) and the hardcoded section list in `ChangeMatrixTest.matrixCoverageMatchesKoltConfigSections` so the schema-coverage cross-validation continues to pass.

Skipping any of these is a reviewer-blocking issue. The schema-coverage test catches the most common drift mode (matrix updated but expected list not, or vice versa); the second defensive line is this clause and the CONTRIBUTING note near the schema definition.

### Consequences

**Positive**
- Every kolt.toml change is observable: silent failure (rebuild against stale config) is impossible.
- Per-invocation symmetry is documented rather than implicit. Hand-edit + next CLI invocation produces the same post-state as the equivalent `kolt deps` command.
- The watch loop's responsibility is precisely scoped: classify, dispatch, notify. No implicit network I/O, no implicit daemon lifecycle, no compile-against-stale-state.
- Future schema additions inherit explicit handling rather than implicit fallback.

**Negative**
- `[dependencies]` and `[kotlin]` family edits in watch mode interrupt the developer flow with a notification + manual action rather than reflecting automatically. This is the point — auto-resync would either fail with stale state or hide the network call inside watch — but it does mean editors expect more friction here than in IntelliJ + Gradle's "Load Gradle Changes" model.
- Mixed-window prevail means a developer who edits `[build.sources]` and `[dependencies]` in one save will see only the deps notification and have to re-trigger watch to pick up the source change. Acceptable trade-off for not running stale-deps builds.

### Confirmation

`SECTION_ACTIONS` in `ChangeMatrix.kt` is the canonical machine-readable matrix; the `matrixCoverageMatchesKoltConfigSections` test pins the section list against an independently hardcoded expected set so drift is detected at CI time. Per-section unit tests in `ChangeMatrixTest` assert each section's classification individually. Watch loop integration tests are out of scope for this ADR (deferred per `.kiro/specs/toml-change-handling/`); manual smoke against each matrix row is the substitute until the integration test infrastructure lands.

## Alternatives considered

1. **Eager auto-resync in watch mode (β1).** Rejected. Watch silently issuing dependency resolution against Maven Central when `[dependencies]` changes makes the network I/O surface implicit and surprising. It also creates a race between resolution and any in-flight build. The clearer model is the user explicitly running `kolt deps install` and then watch picks up the new lockfile state on its next iteration. Auto-resync is the responsibility of an IDE/LSP integration, not the build tool.

2. **Daemon-side config observation (α2).** Rejected. Adding a config-changed message to the daemon wire protocol so the daemon can perform explicit BTA IC invalidation looks like a clean fix for `[kotlin]` family changes, but it means kolt has to encode the invalidation policy of every BTA version it supports — a moving target. The chosen NotifyOnly + user-restart approach gives the same correctness guarantee (a fresh daemon process has fresh IC) without the policy coupling. If BTA gains a stable, document invalidation API in the future, this paragraph is the place to supersede.

3. **Status quo with no explicit matrix (a per-feature ad-hoc decision each time).** Rejected. The implicit rules are exactly what #297 was trying to fix; adding more entry points (`kolt deps remove`, IDE/LSP, future schema sections) without an explicit model just compounds the asymmetry that motivated this work in the first place.

## Related

- #297 — Discussion: define kolt.toml change handling semantics (this ADR's parent)
- #318 — jvm-sys-props spec (added `[run.sys_props]` / `[test.sys_props]` to kolt.toml — the immediate motivator for spec'ing watch behavior)
- #322 — Thread `[run.sys_props]` through `kolt run --watch` (partial implementation; this ADR generalizes the model)
- #297 Q6 — `kolt deps remove` per-invocation symmetry follow-up (deferred from this spec)
- ADR 0019 — Incremental JVM compilation via kotlin-build-tools-api (the BTA we choose not to depend on for invalidation policy in §6)
- ADR 0024 — Native compiler daemon (the daemon-stateless decision in §6 applies symmetrically)
- ADR 0028 — v1 release policy (this ADR commits a long-term contract before v1)
- `.kiro/specs/toml-change-handling/` — design and tasks that this ADR codifies
- `src/nativeMain/kotlin/kolt/config/ChangeMatrix.kt` — canonical SECTION_ACTIONS map
- `src/nativeMain/kotlin/kolt/cli/WatchLoop.kt` — classification dispatch
