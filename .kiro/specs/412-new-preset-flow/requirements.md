# Requirements Document

## Introduction

`kolt new` currently asks `kind` (app / lib) and then `target` (jvm + five native targets in alphabetical order) as two independent axes. Following #411's tentative consensus on Route A (single target per project, no KMP-aware build model at v1), the natural primary axis is the (kind, target-class) pair — `jvm app`, `jvm lib`, `native app`, `native lib`. This feature restructures the interactive prompt around that primary axis and reorders the native target list by usage share with the deprecated entry surfaced explicitly.

CLI flag semantics (`--lib`, `--target=`, `--group=`) are preserved. Non-TTY defaults remain `jvm app`. The five-target native set (`linuxX64, linuxArm64, macosX64, macosArm64, mingwX64`) is unchanged in this feature; expanding it (iOS, Android Native, watchOS, tvOS) and adding a `multiplatform lib` preset are deferred to #411 Phase 2.

GitHub issue: #412. Related: #411 (Route A consensus), #407 (preceding `kolt new` UX polish, line-prompt + numeric-only input), `docs/dogfood.md` 2026-05-09 entry.

## Boundary Context

- **In scope**:
  - The TTY interactive prompt sequence emitted by `kolt new` (preset stage and native target sub-stage).
  - The CLI-flag interaction with that prompt sequence (`--lib`, `--target=`).
  - Native target list ordering and the `(deprecated)` marker on `macosX64`.
  - Non-TTY (non-interactive) default behavior.
- **Out of scope**:
  - Expanding the supported native target set (iOS / Android / watchOS / tvOS).
  - A `multiplatform lib` preset.
  - Host-aware default target selection (e.g. macOS host defaulting to `macosArm64`).
  - Changes to the scaffold templates (`Main.kt`, `Lib.kt`, generated `kolt.toml` body).
  - Changes to the post-scaffold next-step block (covered by #407).
  - Changes to the `kolt.toml` schema or to the `NATIVE_TARGETS` set.
- **Adjacent expectations**:
  - The group prompt (`Group (e.g. com.example, blank for none):`) and the post-scaffold next-step block continue to render unchanged.
  - The `--group=` flag continues to suppress the group prompt as today.
  - Visual polish from #407 (one option per line, dedicated `>` line for input, color coding for the default option, group separator line for native target groups) is inherited; this feature does not regress those styling commitments.
  - Color policy (stdout color enable/disable) for prompt lines remains governed by the existing color-policy mechanism; this feature inherits it without modification.

## Requirements

### Requirement 1: Preset prompt for the no-flag interactive path

**Objective:** As a user running `kolt new <name>` in a TTY without `--lib` or `--target=`, I want a single preset prompt that pairs kind with target-class, so I can reach the most common project shape in one selection.

#### Acceptance Criteria
1. When `kolt new <name>` is invoked interactively with neither `--lib` nor `--target=` provided, the kolt CLI shall emit a preset prompt listing exactly four numbered options in this order: `1) jvm app (default)`, `2) jvm lib`, `3) native app`, `4) native lib`.
2. When the user enters an empty line at the preset prompt, the kolt CLI shall select `jvm app`.
3. When the user enters a numeric value `N` in `1..4` at the preset prompt, the kolt CLI shall select the preset at position `N`.
4. If the user enters a value that is not an integer in `1..4` at the preset prompt, the kolt CLI shall emit an error message identifying the invalid input and the expected range, and shall exit with non-zero status.
5. When the user selects `jvm app` or `jvm lib` at the preset prompt, the kolt CLI shall pin the target to `jvm` and shall not emit a native target sub-prompt.

### Requirement 2: Native target sub-prompt for native presets

**Objective:** As a user who picked `native app` or `native lib`, I want a follow-up target prompt that orders concrete native targets by usage share, so the most common choice (linuxX64) is the default and rare or deprecated targets are visibly demoted.

#### Acceptance Criteria
1. When the user selects `native app` or `native lib` at the preset prompt, the kolt CLI shall emit a native target sub-prompt listing exactly five numbered options in this order: `1) linuxX64 (default)`, `2) macosArm64`, `3) mingwX64`, `4) linuxArm64`, `5) macosX64 (deprecated)`.
2. The kolt CLI shall render the literal suffix ` (deprecated)` after the `macosX64` option label in the native target sub-prompt.
3. When the user enters an empty line at the native target sub-prompt, the kolt CLI shall select `linuxX64`.
4. When the user enters a numeric value `N` in `1..5` at the native target sub-prompt, the kolt CLI shall select the native target at position `N`.
5. If the user enters a value that is not an integer in `1..5` at the native target sub-prompt, the kolt CLI shall emit an error message identifying the invalid input and the expected range, and shall exit with non-zero status.

### Requirement 3: Flag-driven prompt skipping

**Objective:** As a user who pre-specifies one axis via CLI flag, I want only the missing axis to be prompted, so flag input is honored without redundant preset selection.

#### Acceptance Criteria
1. When `kolt new <name> --lib` is invoked interactively and `--target=` is not provided, the kolt CLI shall skip the preset prompt and emit a target prompt listing six numbered options in this order: `1) jvm (default)`, `2) linuxX64`, `3) macosArm64`, `4) mingwX64`, `5) linuxArm64`, `6) macosX64 (deprecated)`.
2. When `kolt new <name> --target=<value>` is invoked interactively and `--lib` is not provided, the kolt CLI shall skip the preset prompt and the native target sub-prompt, and shall emit a kind prompt with options `1) app (default)` and `2) lib`.
3. When both `--lib` and `--target=<value>` are provided interactively, the kolt CLI shall not emit any preset, target, or kind prompt and shall use the flag values directly.
4. If `--target=<value>` is provided with a value that is not `jvm` and not one of the five supported native target identifiers, the kolt CLI shall emit an error identifying the invalid target and exit with non-zero status before emitting any prompt.

### Requirement 4: Non-interactive defaults

**Objective:** As a user invoking `kolt new <name>` in a non-TTY context (CI pipeline, redirected stdin), I want documented defaults that match the most common shape, so scripted invocations produce predictable output without prompts.

#### Acceptance Criteria
1. When `kolt new <name>` is invoked with stdin not a TTY and neither `--lib` nor `--target=` is provided, the kolt CLI shall produce a `jvm app` scaffold without emitting any prompt.
2. When `kolt new <name> --lib` is invoked with stdin not a TTY and `--target=` is not provided, the kolt CLI shall produce a `jvm lib` scaffold without emitting any prompt.
3. When `kolt new <name> --target=<value>` is invoked with stdin not a TTY and `--lib` is not provided, the kolt CLI shall produce an `app` scaffold targeting `<value>` without emitting any prompt, where `<value>` is `jvm` or one of the five supported native target identifiers.

### Requirement 5: Group prompt continuity

**Objective:** As a user, I want the group (Maven coordinates) prompt to behave exactly as before, so this restructure does not regress unrelated UX surfaced by #407.

#### Acceptance Criteria
1. While the kolt CLI runs `kolt new <name>` interactively, the kolt CLI shall emit the existing group prompt after preset, target, and kind resolution completes, irrespective of which flags were provided, except when suppressed by 5.2.
2. When `--group=<value>` is provided on the command line, the kolt CLI shall not emit the group prompt and shall use `<value>` directly.
