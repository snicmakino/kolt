# Research Log: 412-new-preset-flow

## Discovery Scope

Light discovery (extension of existing interactive prompt). No external research required: the change is contained to `src/nativeMain/kotlin/kolt/cli/Prompt.kt` and its tests. All inputs, dependencies, and downstream consumers exist in-tree and are in steady state.

## Investigations

### Existing prompt architecture

- Entry: `resolveInteractive(parsed: ParsedInitArgs, io: ScaffoldIO, policy: ColorPolicy): Result<ResolvedScaffoldOptions, String>` in `Prompt.kt`.
- Calls three sub-prompts independently — `promptKind`, `promptTarget`, `promptGroup` — each returning `Result<T, String>`.
- Sub-prompts gated by what the user pinned via flags (`parsed.kind`, `parsed.target`, `parsed.groupSpecified`); axes pinned via flag are not re-prompted.
- Non-TTY (`!io.isStdinTty()`) collapses each unset axis to its hard-coded default (`ScaffoldKind.APP`, `DEFAULT_SCAFFOLD_TARGET`, `null` group).

### Current target list

- `NATIVE_TARGETS = {linuxX64, linuxArm64, macosX64, macosArm64, mingwX64}` in `Config.kt`.
- `PROMPT_TARGETS = listOf("jvm") + NATIVE_TARGETS.toList().sorted()` in `Prompt.kt:58`.
- Render adds a `-- native --` separator line before the first native option (#407).

### CLI flag interaction

- `parseInitArgs` (in `Scaffold.kt`) parses `--lib` / `--app` / `--target` / `--group`, returning `ParsedInitArgs` with nullable kind/target/group + `groupSpecified` flag.
- `--target=<value>` validates against `VALID_TARGETS = {jvm} + NATIVE_TARGETS` at parse time.

### Test surface

- `PromptTest.kt` covers 18 cases: TTY/no-flags, numeric input, default selection, invalid input rejection, mixed flag-and-prompt, non-TTY defaults, color rendering. Tests assert exact prompt header strings (`Kinds:`, `Targets:`, `Group (`) and option labels — feature change requires updating most expectations.

## Architecture Pattern Evaluation

The existing structure is essentially a `dispatcher → (axis-prompt | flag-pinned)` pipeline. The 4-preset flow extends the dispatcher: when neither kind nor target is pinned, the dispatcher first runs a combined-axis preset prompt, then optionally a native target sub-prompt. No new architecture pattern required.

## Design Decisions

### D1: Combined-axis preset prompt instead of dropping kind+target prompts

Picking `jvm app` in one stroke is the user-facing win of #411 Route A; keeping kind/target as fully separate prompts when neither is pinned would defeat the point. Decision: introduce a preset prompt that fires only when both axes are open; existing single-axis prompts stay for the partial-flag cases.

### D2: Reuse existing `promptTarget` for `--lib`-only path

Rather than introduce a separate prompt for the `--lib`-only fallback, modify `promptTarget` to render the 6 options (jvm + 5 native) in share order with `(deprecated)` suffix on `macosX64`. The native sub-prompt for the no-flag → native-preset path is a distinct prompt (5 native targets only).

### D3: Share order chosen by judgment, no benchmark

Order `linuxX64 > macosArm64 > mingwX64 > linuxArm64 > macosX64(deprecated)` was selected by user-discussion judgment based on kolt's expected user mix (server / CLI / lib developers). No telemetry exists; revisit when dogfood signals warrant it.

### D4: No new public types

The four presets do not need a public `Preset` enum — they are 4 (kind, isNative) tuples consumed only by the new internal preset prompt. Keep them as a private `listOf` of small data records inside `Prompt.kt` to avoid leaking transient UI shape into config or domain modules.

## Generalization

The dispatch can be modeled as "fill missing axes": kind axis ∈ {APP, LIB}, target axis ∈ {jvm, native(<concrete>)}. The preset prompt is a UX shortcut that lets the user pick both axes in one step when both are open. This shape extends naturally to a future `multiplatform lib` preset (= multi-target axis state) without restructuring.

## Build vs. Adopt

Pure UI logic, no candidate library. Reuse existing `ScaffoldIO`, `ColorPolicy`, `AnsiCodes`, and `kotlin-result` `Result`.

## Simplification

- No `Preset` enum exposed across modules.
- Preset prompt and native sub-prompt share the numeric-input parsing helper pattern of existing prompts; do not introduce a generic "list-prompt" abstraction in this PR (single-call sites, would be premature).

## Boundary Decisions

- **Owned**: dispatch logic in `resolveInteractive`, the new preset prompt, the new native sub-prompt, and the share-ordering / deprecated-suffix render in `promptTarget`.
- **Not owned**: `parseInitArgs` semantics, `ScaffoldKind` enum, `NATIVE_TARGETS` set, scaffold templates, post-scaffold next-step block. These are inputs/consumers stable across the change.

## Risks

- **Test churn**: 18 existing cases assert exact prompt headers; rewriting them is a big diff but mechanical.
- **Flag-only path coverage gaps**: existing tests cover the `--lib`-only mixed path but not `--target=`-only. New test must cover that branch.
- **Color rendering consistency**: `(deprecated)` suffix should follow the same color treatment as the option name itself; design specifies the suffix is part of the rendered label, color policy applies uniformly.

## Revalidation Triggers

- Adding new entries to `NATIVE_TARGETS` (e.g. iOS) — sub-prompt order and group structure must be revisited.
- Adding a `multiplatform lib` preset (#411 Phase 2) — the 4-preset list grows; dispatch must handle a new branch.
- Removing `macosX64` from `NATIVE_TARGETS` (when JetBrains drops support) — deprecated suffix logic becomes dead code.
