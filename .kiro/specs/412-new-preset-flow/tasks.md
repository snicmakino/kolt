# Implementation Plan

## Phase 1: Foundation — share-ordered native target render with deprecated suffix

- [x] 1. Foundation

- [x] 1.1 Reorder the existing target prompt by share and surface the deprecated marker on macosX64
  - Replace the alphabetical `NATIVE_TARGETS.sorted()` list inside the existing target prompt with a hand-curated share order: `linuxX64, macosArm64, mingwX64, linuxArm64, macosX64`. The jvm option keeps position 1 with its `(default)` marker; the `-- native --` separator from #407 stays.
  - Render the literal suffix ` (deprecated)` after the `macosX64` label. The suffix is rendered outside the color-wrapped portion so the existing `ColorPolicy` treatment of the target identifier is unchanged.
  - TDD: update the three existing target-list tests (`ttyTargetPromptListsOptionsOnePerLineWithNumbers`, `ttyTargetPromptAcceptsNumericInput`, `ttyTargetPromptSeparatesJvmFromNativeWithMarker`) so they exercise the new order — including that `2) linuxX64` follows `-- native --` and that numeric `2` selects `linuxX64`. Add one new test asserting that `macosX64` appears with the literal ` (deprecated)` suffix.
  - Observable completion: the four target-prompt unit tests pass against the new order; running `kolt new myapp --lib` interactively (or via the `--lib`-driven test pipeline) shows `1) jvm (default)`, `2) linuxX64`, `3) macosArm64`, `4) mingwX64`, `5) linuxArm64`, `6) macosX64 (deprecated)`.
  - _Requirements: 2.2, 3.1_
  - _Boundary: Prompt.kt — promptTarget render only_

## Phase 2: Core — preset prompt and native sub-prompt for the no-flag path

- [x] 2. Core

- [x] 2.1 Replace the no-flag dispatch with a 4-preset prompt followed by an optional native sub-prompt
  - Introduce two private prompt functions inside the prompt module:
    - A preset prompt that emits header `Presets:`, four numbered lines (`1) jvm app (default)`, `2) jvm lib`, `3) native app`, `4) native lib`), the `>` input line, and applies the same numeric-only input policy as the existing kind prompt — empty / EOF → option 1, `1..4` → that option, anything else → `Result.Err` carrying the invalid input and the expected range.
    - A native sub-prompt that emits header `Native target:` and five numbered lines in share order (`1) linuxX64 (default)`, `2) macosArm64`, `3) mingwX64`, `4) linuxArm64`, `5) macosX64 (deprecated)`) with the same numeric-only policy — empty / EOF → option 1, `1..5` → that option, anything else → `Result.Err`.
  - Rewrite the no-flag branch of the interactive dispatcher: when neither kind nor target is pinned and stdin is a TTY, call the new preset prompt; for `jvm` presets pin `target = "jvm"` and skip any further target prompt, for `native` presets call the new sub-prompt and use its result. The flag-pinned and non-TTY branches stay unchanged in this task.
  - Apply the existing color treatment (cyan default + yellow non-default) to the preset prompt option labels and the yellow-on-non-default treatment to the native sub-prompt option labels, so `ColorPolicy.Always` produces ANSI-wrapped labels and `ColorPolicy.Never` produces plain text — matching the pattern of the existing kind/target prompts.
  - TDD: rewrite the four existing no-flag-path tests so each asserts the new preset path end-to-end — `ttyNoFlagsPromptsKindThenTargetThenGroup` becomes `ttyNoFlagsPromptsPresetThenGroupForJvm` (asserts `Presets:` header appears, the `Native target:` header does NOT appear when option 1 is selected, and the group prompt fires after the preset prompt); `ttyKindPromptListsOptionsOnePerLineWithNumbers` becomes `ttyPresetPromptListsFourOptionsOnePerLineWithNumbers`; `ttyKindPromptDefaultIsApp` becomes `ttyPresetPromptEmptyInputProducesJvmApp`; `ttyKindPromptExplicitLibViaNumber` becomes `ttyPresetPromptNumericTwoProducesJvmLib`. Update `ttyKindInvalidInputExitsNonZero` to drive the preset prompt with non-numeric input. Update `ttyKindPromptColorsAppCyanAndLibYellowWhenPolicyAllows` and `ttyKindPromptHasNoAnsiWhenPolicyDisables` so they exercise the preset prompt instead of the old kind prompt. Update `ttyEofOnFirstPromptCollapsesToDefaults` so it drives the preset prompt with closed stdin and asserts `jvm app` defaults still apply.
  - Add seven new tests for the native sub-prompt path: `ttyPresetThreeShowsNativeSubPromptWithFiveOptions` (numeric `3` triggers `Native target:` header and exactly five numbered lines), `ttyPresetFourAlsoShowsNativeSubPrompt`, `ttyPresetOneSkipsNativeSubPrompt` (numeric `1` does not emit `Native target:`), `ttyNativeSubPromptDefaultIsLinuxX64` (preset `3` then empty produces `target = "linuxX64"`), `ttyNativeSubPromptNumericTwoSelectsMacosArm64` (preset `3` then numeric `2` produces `target = "macosArm64"`), `ttyNativeSubPromptShowsDeprecatedSuffixOnMacosX64`, and `ttyNativeSubPromptInvalidInputExitsNonZero`.
  - Observable completion: every PromptTest case in the file passes; an interactive `doInit(["myapp"])` run with inputs `["3", "2", ""]` produces a `kolt.toml` containing `target = "macosArm64"`; with inputs `["1", ""]` produces `target = "jvm"` and a `Main.kt` source; the `Native target:` header never appears for `jvm` presets; the existing `nonTtyNoPromptsAndDefaultsApply` test continues to pass without modification, confirming the dispatcher rewrite preserves the non-TTY no-flag default.
  - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 2.1, 2.2, 2.3, 2.4, 2.5, 4.1, 5.1_
  - _Boundary: Prompt.kt — promptPreset, promptNativeTarget, and resolveInteractive no-flag branch_

## Phase 3: Coverage — explicit assertions for the remaining dispatch branches

- [x] 3. Coverage

- [x] 3.1 Add explicit test coverage for the partial-flag and non-TTY dispatch branches
  - Add `ttyTargetFlagJvmOnlyPromptsKindButNotPreset`: `doInit(["myapp", "--target=jvm"], io = TTY, inputs = ["", ""])` emits `Kinds:` and never emits `Presets:` or `Native target:`; the resulting `kolt.toml` has `target = "jvm"` and no `kind = "lib"`.
  - Add `ttyTargetFlagNativeOnlyPromptsKindButNotPreset`: `doInit(["myapp", "--target=linuxX64"], io = TTY, inputs = ["", ""])` emits `Kinds:`, never emits `Presets:` or `Native target:`, and produces `target = "linuxX64"` in the resulting toml.
  - Add `ttyBothFlagsPinnedSkipsPresetTargetAndKindPrompts`: `doNew(["mylib", "--lib", "--target=linuxX64"], io = TTY, inputs = [""])` (group blank) emits no `Presets:`, no `Kinds:`, no `Targets:`, and no `Native target:` headers; the resulting `mylib/kolt.toml` has `kind = "lib"` and `target = "linuxX64"`.
  - Add `nonTtyLibFlagProducesJvmLibScaffold`: `doInit(["mylib", "--lib"], io = non-TTY, inputs = [])` emits no prompts; the resulting `kolt.toml` contains `kind = "lib"` and `target = "jvm"`.
  - Add `nonTtyTargetFlagNativeProducesAppScaffoldForThatTarget`: `doInit(["myapp", "--target=linuxX64"], io = non-TTY, inputs = [])` emits no prompts; the resulting `kolt.toml` contains `target = "linuxX64"` and no `kind = "lib"`.
  - Add `nonInteractiveInvalidTargetFlagExitsBeforePrompt` if no equivalent exists: `doInit(["myapp", "--target=wasm"], io = TTY, inputs = [])` returns `EXIT_CONFIG_ERROR` and produces no scaffold output (verifies that `parseInitArgs` rejects the value before the dispatcher runs).
  - Add `ttyGroupFlagSuppressesGroupPrompt` if no equivalent exists in the broader test suite: `doInit(["myapp", "--group=com.example"], io = TTY, inputs = [""])` (preset blank → `jvm app`) emits no `Group (` header; the resulting source tree nests under `src/com/example/myapp/`.
  - Observable completion: six (or seven, including the group-flag test) new test cases pass and the full `PromptTest` suite is green; every branch of the dispatch flow diagram in `design.md` has direct test coverage.
  - _Requirements: 3.2, 3.3, 3.4, 4.2, 4.3, 5.2_
  - _Boundary: Prompt.kt — resolveInteractive dispatch coverage_
