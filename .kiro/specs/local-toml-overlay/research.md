# Gap Analysis: local-toml-overlay

## 1. Current State Investigation

### Domain assets present today

| Asset | Location | Role |
| --- | --- | --- |
| `parseConfig(tomlString, path)` | `src/nativeMain/kotlin/kolt/config/Config.kt:435` | Single entry point. Returns `Result<KoltConfig, ConfigError>`. Strict-mode ktoml (`ignoreUnknownNames = false`). |
| `RawKoltConfig` / `KoltConfig` | `Config.kt:172` / `Config.kt:109` | Wire vs domain model split, already established pattern. |
| `Repository` analogue | none — `repositories: Map<String, String>` flat (Config.kt:118) | No record type for repositories; needs introduction for this spec. |
| `SysPropValue` + `RawSysPropValue` | `SysPropValue.kt:18` / `:26` | Reference template for a dedicated config-side module with sealed model + Raw type + custom KSerializer. |
| `KNOWN_TOP_LEVEL_SECTIONS` | `KtomlMessageParse.kt:29` | Allowlist for parse-error attribution (drift-guarded list of `build`, `cinterop`, `classpaths`, `dependencies`, `fmt`, `kotlin`, `repositories`, `run`, `test`, `test-dependencies`, `tools`). No overlay-eligibility companion. |
| `loadProjectConfig()` | `src/nativeMain/kotlin/kolt/cli/BuildCommands.kt:252` | Currently: `readFileAsString(KOLT_TOML) → parseConfig(...)`. Single TOML source today. |
| `Scaffold.kt` / `Init.kt` | `scaffoldProject()` orchestrator; `generateGitignore()` at `Init.kt:94` (hardcoded multi-line literal: `build/`, `workspace.json`, `.idea/`, `*.iml`, `.DS_Store`). | No preset-merge for gitignore — single string returned. |
| `printUsage()` | `Main.kt:234-265` (`-D` line at 264) | Static text emitter; mentions `-D<key>=<value> ... overlays [test|run.sys_props]` already. |
| `docs/architecture.md` | Top-level sections: `Components`, `Build flow`, `Daemon lifecycle`, `Dependency resolution`, `Error handling`, `Configuration change semantics`, `Out of scope`, `ADRs`. | No existing "config layering" section. |
| Test infra | `src/nativeTest/kotlin/kolt/config/` — 17 test files, ~3.7k LoC. `ParseUnknownKeyTest.kt` shows pattern for failure-message-asserting tests; `ConfigTest.kt:parseMinimalConfig` for passing-path. | Solid pattern to copy. |

### Validation pipeline (existing)

`parseConfig` runs ~13 steps after `Toml.decodeFromString`: kind validation → kind/main consistency → main FQN → target resolution → target validation → compiler-version cross-check → key dequoting → sys_props lift + validation → bundle cross-reference → sys_props project_dir containment → tool section parse → final `KoltConfig` assembly. A `mergeOverlay(base: RawKoltConfig, overlay: RawLocalOverlayConfig)` call **fits cleanly between step 1 (decode) and step 2 (kind validation)**, so the existing single semantic-validation pass runs on the merged result.

### Conventions observed

- Sealed `Raw*` data classes for ktoml wire shape; lifted via `lift*` helpers into domain types.
- Custom KSerializer only where exactly-one-of polymorphism is required (`SysPropValue.kt:33`); plain `@Serializable` data classes elsewhere.
- Errors are `ConfigError.ParseFailed(path, line, message)` — `KtomlMessageParse.kt` extracts ktoml's error string into structured `Pair<lineNo, key>`. Strict-mode rejection of unknown keys is the **established mechanism** we will reuse for allowlist enforcement.
- Tests live alongside the production package; one file per coherent area (e.g., `ConfigSysPropValidationTest.kt`).

## 2. Requirements Feasibility

| Req | Capability needed | Current state | Gap |
| --- | --- | --- | --- |
| R1 (discovery) | Conditional read of `kolt.local.toml`; share parser pipeline | Single-source today (KOLT_TOML only) | **Missing**: add path resolution + optional read in `loadProjectConfig` |
| R1.5 (env-agnostic on overlay) | Same literal-only rule | Validation runs in `parseConfig`; runs once on merged input → free | **Constraint**: merge must happen pre-validation |
| R2 (allowlist) | Reject `[build]`, `[kotlin]`, …, top-level scalars in `kolt.local.toml` | Strict-mode ktoml + nullable-only `RawLocalOverlayConfig` does it automatically | **Missing**: `RawLocalOverlayConfig` (~10-line class); also extend `KNOWN_TOP_LEVEL_SECTIONS` accounting or add parallel const |
| R3 (sys_props merge) | Key-replace within `[test.sys_props]` / `[run.sys_props]`; cross-file `{ classpath = X }` resolves against merged config | `liftSysPropsMap` + bundle cross-ref check exist in `parseConfig` | **Missing**: `mergeSysProps` helper; existing validators already cover the cross-file invariant once merge runs pre-validation |
| R4 (repo schema migration) | Accept `[repositories.<name>] url = "..."`; reject legacy `name = "url"` flat form with named message | `Map<String, String>` today | **Missing**: `Repository` record type + `RawRepository` + migration error path; **schema breaking — fan-out audit needed (Research)** |
| R5 (repo overlay merge) | Field-level merge; local-only name rejected; silent url override | None | **Missing**: `mergeRepositories` helper; depends on R4 |
| R6 (gitignore append) | Add `kolt.local.toml` line to generated `.gitignore` | Hardcoded literal | **Missing**: 1-line addition in `generateGitignore`; trivial |
| R7 (discoverability) | `kolt --help` shows 3-layer order; `docs/architecture.md` overlay chapter | Existing `-D` line mentions overlays; no overlay chapter | **Missing**: text edits |
| R8 (back-compat for non-adopters) | Projects without `kolt.local.toml` behave identically (same JVM args, same `kolt.lock`) | Free if overlay read is conditional and `Repository(url)` lifts identically to today's `Map<String, String>` downstream | **Constraint**: `Repository` lift must produce a value that downstream resolver consumes identically |

### Complexity signals

- **Algorithmic logic**: merge functions are small, ~20-30 LOC each.
- **Schema migration ripple**: `[repositories]` consumers — resolver, lockfile read, snapshots, `kolt deps` printing — all currently iterate `Map<String, String>`. Each call site needs `entry.value` → `entry.value.url`. Mechanical but wide.
- **Wire format**: `kolt.local.toml` is a new on-disk artifact; CLAUDE.md flags wire-format additions as SDD-worthy. Compatible with current spec choice.

## 3. Implementation Approach Options

### Option A: Inline-extend `Config.kt`

Add `RawLocalOverlayConfig`, `mergeOverlay()`, `mergeSysProps()`, `mergeRepositories()` directly inside `Config.kt`. Extend `parseConfig` to take an optional second TOML string. `loadProjectConfig` reads both files and passes both strings to `parseConfig`.

**Trade-offs**:
- ✅ Minimum new files, easiest to follow flow.
- ❌ `Config.kt` already at ~600 LoC and central; bloating risk.
- ❌ Mixes the wire concern (RawLocalOverlay) with orchestration (parseConfig).

### Option B: Dedicated `LocalOverlay.kt` module (recommended)

Create `src/nativeMain/kotlin/kolt/config/LocalOverlay.kt` housing:
- `RawLocalOverlayConfig` (all-nullable Raw type)
- `mergeSysProps`, `mergeRepositories` (pure functions on Raw types)
- `mergeOverlay(base: RawKoltConfig, overlay: RawLocalOverlayConfig): RawKoltConfig`
- `parseLocalOverlay(tomlString, path): Result<RawLocalOverlayConfig, ConfigError>`

`Config.kt:parseConfig` accepts an optional `RawLocalOverlayConfig`, runs `mergeOverlay` between decode and kind validation, and is otherwise unchanged. `BuildCommands.kt:loadProjectConfig` reads both files and orchestrates.

The `[repositories]` schema migration (introduce `Repository` record, update `RawKoltConfig.repositories`, ripple consumers) lands in `Config.kt` + downstream call sites and is independent of `LocalOverlay.kt`.

`generateGitignore()` in `Init.kt` gets a 1-line addition. `printUsage()` in `Main.kt` gets a `-help` text edit. `docs/architecture.md` gets a new `## Configuration layers` subsection.

**Trade-offs**:
- ✅ Mirrors the `SysPropValue.kt` precedent — dedicated file per cohesive config concern.
- ✅ Keeps `Config.kt` focused on the canonical parse + validate pipeline.
- ✅ Tests follow naturally as `LocalTomlOverlayMergeTest.kt` + `LocalTomlOverlayDecodeTest.kt` + repo-migration coverage in existing `ConfigTest.kt`.
- ❌ One more file to navigate; minor.

### Option C: TOML-string pre-merge (rejected)

Read both files, concatenate or string-merge before ktoml decode. Already evaluated and rejected during discovery (`brief.md`): ktoml has no writer, error attribution breaks, complexity exceeds the merge it would replace.

## 4. Effort & Risk

| Component | Effort | Risk | Notes |
| --- | --- | --- | --- |
| `LocalOverlay.kt` (decode + merge) | S (1-2 days) | Low | Tight scope, ktoml viability already confirmed |
| `[repositories]` schema migration | M (2-3 days) | Medium | Fan-out across resolver / lockfile / snapshots; mechanical but wide; **Research Needed: enumerate consumers** |
| `parseConfig` wiring + validation re-run on merged result | S (1 day) | Low | Validation pipeline already discovered as merge-before-validate friendly |
| `loadProjectConfig` file I/O extension | S (½ day) | Low | One conditional read |
| `kolt init` `.gitignore` (PR-3) | S (½ day) | Low | One-line template edit + 1 test |
| `--help` text + `docs/architecture.md` chapter | S (½ day) | Low | Text-only |
| ADR 0034 skeleton | S (½ day) | Low | Skeleton only; #416 fills in |
| **Total (3 PRs)** | **M (≈ 4-6 days)** | **Medium** | Schema migration is the only medium-risk piece |

Risk drivers:
- Schema migration consumer fan-out is the only place a subtle regression can hide; mitigated by mechanical replacement + existing resolver test coverage.
- PR-1 + PR-2 + PR-3 split confines breaking change to PR-1 (one bisect target).

## 5. Research Needed (carry into design / implementation)

1. ~~**`[repositories]` consumer fan-out**~~ — **resolved**: 9 call sites total, all of the form `config.repositories.values.toList()` (`BundleResolver.kt:180`, `NativeResolver.kt:67`, `TransitiveResolver.kt:20`, `OutdatedCommand.kt:42`, `ToolCommands.kt:163`, `DependencyCommands.kt:54`/`:296`/`:388`/`:407`). Mechanical replacement to `config.repositories.values.map { it.url }`; no helper method needed.
2. ~~**Lockfile interaction**~~ — **resolved**: `grep -n "repositor\|repoUrl\|repo_url\|baseUrl" src/nativeMain/kotlin/kolt/resolve/Lockfile.kt` → no matches. Lockfile is unaffected by the schema migration.
3. ~~**`ChangeMatrix.kt`**~~ — **resolved**: `ChangeMatrix.kt:106` registers `[repositories]` as `SectionDescriptor("[repositories]", NotifyOnly(RECOMMEND_FETCH)) { it.repositories }`. Map equality on `Repository` data class works automatically; no descriptor update needed for the shape flip.
4. **ADR 0034 skeleton content**: the PR-2 (#415) skeleton needs explicit "env-agnostic ↔ overlay" framing so #416 has a place to insert auth-field sections. Outline structure in design phase (below).
5. **v0.20.0 release-note upgrade snippet**: exact upgrade text for the `[repositories]` shape flip + daemon-restart-before-edit recommendation. Coordinate with release-PR file checklist (memory: `feedback_release_pr_file_checklist`) — surfaced in tasks.md, not design.md.
6. **[INVESTIGATION GATE before R4.3 implementation] ktoml error shape for `Map<String, RawRepository>` flat-form decode**: design.md `parseConfig` Implementation Notes calls for a pinned message-shape probe before the migration-message substitution path is committed. Adversarial review flagged that ktoml's exact exception type and message for "expected table, got string at map entry" is unverified — `KtomlMessageParse.kt` today only handles `"Unknown key received: <k> in scope <s>"` shape. Required artifact: a ~20-LoC probe in `RepositorySchemaMigrationTest.kt` that pins the actual exception class and message string from ktoml 0.7.1 when decoding `central = "url"` into `Map<String, RawRepository>`. If the exception is deterministically inspectable (matchable regex / prefix), substitute the message; if not, append a `KtomlMessageParse` hint paragraph without substitution. Must complete before tasks for R4.3 are claimed.

   **Verdict** (pinned by task 1.3 in `ConfigParseMessageFormatTest.kt:legacyFlatRepositoriesShapeSurfacesAsUnknownNameAtRootScope`):

   - **Exception class**: `com.akuleshov7.ktoml.exceptions.UnknownNameException` (a sealed `TomlDecodingException` subclass; existing `catch (e: TomlDecodingException)` traps it).
   - **Message shape** (verbatim): `Unknown key received: <central> in scope <rootNode>. Switch the configuration option: 'TomlConfig.ignoreUnknownNames' to true if you would like to skip unknown keys` — matches the existing `parseUnknownKey` regex `^Unknown key received: <([^>]+)> in scope <([^>]*)>` with key=`central`, scope=`rootNode`.
   - **Notable absence**: no `Line N:` prefix (verified against ktoml v0.7.1 source — `UnknownNameException(key, parent)` has no `lineNo` parameter), so `extractKtomlLineNo` returns `null` for this case.
   - **Decision**: **hint-append**, not in-place substitution.
   - **Justification**: ktoml decodes `[repositories]` with scalar children by walking down into the `Map<String, RawRepository>` decoder, which treats `central = "..."` as if `central` were an unknown root-scope key inside the synthetic `rootNode` table — the resulting exception is byte-identical in shape to a real top-level typo (`koltn = "stray"`, already pinned). The exception alone is therefore NOT deterministic enough to distinguish "legacy flat-form repositories" from "stray scalar at root scope". Substituting the message based on key match would corrupt legitimate root-scope typo errors (e.g. if a user happens to typo `central` at root scope). Task 2.1 will surface ktoml's raw error and append a separate hint paragraph keyed on the presence of `[repositories]` in the input plus the key looking like a repository name — the hint is the user-actionable part and the original ktoml error remains intact.
7. **Watcher behavior across schema migration edit** (adversarial review Topic 3): no code action — captured in design.md Migration Strategy as `kolt daemon stop --all` recommendation in v0.20.0 release notes. Implementation surface is documentation only.

## 6. Synthesis Outcomes

### Generalization
Approach B's `RawLocalOverlayConfig` field set IS the overlay allowlist — the type encodes the rule, no separate `KNOWN_OVERLAY_SECTIONS` constant needed. Future overlay-eligible sections add one nullable field + one merge function; the interface generalizes without an abstraction layer.

### Build vs Adopt
- TOML parse: adopt `ktoml-core 0.7.1` (already in stack).
- ktoml strict-mode unknown-section rejection: adopt as the runtime allowlist enforcement mechanism — no separate guard code.
- Merge functions: build (~50 LoC total across `mergeSysProps` + `mergeRepositories`); no library is a fit.

### Simplification
- Single `LocalOverlay.kt` file, not multiple. Decode + merge are tightly coupled and small (~150 LoC total).
- `Repository(val url: String)` ships with one field. No optional `token` / `user` / `password` placeholders — #416 will extend cleanly via data-class copy semantics.
- No `KNOWN_OVERLAY_SECTIONS` const — runtime enforcement is via ktoml strict mode + the `RawLocalOverlayConfig` field set; no need to maintain two drift-prone lists.
- No `loadOverlayConfig()` separate function — extend `loadProjectConfig()` directly with optional file read; one function, one place.

## 7. Recommendations for Design Phase

- **Preferred approach**: Option B (dedicated `LocalOverlay.kt`).
- **Key design decisions to lock in design.md**:
  - Merge point in pipeline: between `Toml.decodeFromString` and kind validation, on `RawKoltConfig`.
  - `Repository` exposed as `data class Repository(val url: String)` with no auth fields yet (#416 will extend).
  - Allowlist enforcement: rely on ktoml `ignoreUnknownNames = false` + nullable-only `RawLocalOverlayConfig`; no separate guard code.
  - Error attribution: ktoml exceptions caught at `parseLocalOverlay` boundary, wrapped as `ConfigError.ParseFailed(path = "kolt.local.toml", ...)`.
  - 9 mechanical replacements `config.repositories.values.toList()` → `config.repositories.values.map { it.url }` at the call sites enumerated above.
- **PR split is design input, not a design decision** — already locked in brief.md; design.md should describe Option B's components and let the tasks.md follow PR-1/2/3 partition.

## 6. Recommendations for Design Phase

- **Preferred approach**: Option B (dedicated `LocalOverlay.kt`).
- **Key design decisions to lock in design.md**:
  - Merge point in pipeline: between `Toml.decodeFromString` and kind validation, on `RawKoltConfig`.
  - `Repository` exposed as `data class Repository(val url: String)` with no auth fields yet (#416 will extend).
  - Allowlist enforcement: rely on ktoml `ignoreUnknownNames = false` + nullable-only `RawLocalOverlayConfig`; no separate guard code.
  - `KNOWN_OVERLAY_SECTIONS` const added to `KtomlMessageParse.kt` for documentation/test coupling, not for runtime enforcement (ktoml does the runtime enforcement).
  - Error attribution: ktoml exceptions caught at `parseLocalOverlay` boundary, wrapped as `ConfigError.ParseFailed(path = "kolt.local.toml", ...)`.
- **PR split is design input, not a design decision** — already locked in brief.md; design.md should describe Option B's components and let the tasks.md follow PR-1/2/3 partition.
