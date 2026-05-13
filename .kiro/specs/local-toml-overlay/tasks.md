# Implementation Plan

Tasks below map to three PRs documented in `design.md`:

- **PR-1** (#320): Phases 1-5 — overlay engine base + sys_props merge + `[repositories]` schema migration + discoverability + validation.
- **PR-2** (#415): Phase 6 — repositories field-merge in overlay + ADR 0034 skeleton.
- **PR-3** (#417): Phase 7 — `kolt init` `.gitignore` auto-append.

Each implementation sub-task pairs the failing test (RED) and its passing implementation (GREEN) in a single commit per the project's TDD policy (memory: `feedback_tdd_red_green_atomic`).

---

## Phase 1: Foundation (PR-1)

- [ ] 1. Foundation: path constant, repository record, ktoml error-shape investigation

- [x] 1.1 Add `KOLT_LOCAL_TOML` filename constant
  - Introduce `KOLT_LOCAL_TOML = "kolt.local.toml"` alongside the existing `KOLT_TOML` in the config-paths module.
  - **Observable done**: callers can refer to the overlay filename through a single typed constant without string duplication.
  - _Requirements: 1.1_
  - _Boundary: KoltPaths_

- [x] 1.2 Introduce `Repository` and `RawRepository` data classes
  - Add `data class Repository(val url: String)` to the config domain model.
  - Add the matching `@Serializable internal data class RawRepository(val url: String? = null)` wire type.
  - Validation of the `url` field is NOT introduced here; the empty/missing-url rejection happens in 2.1 (decode-lift step) and in 3.4 (post-merge), as required by design.md's two-phase validation rule.
  - **Observable done**: the two data classes compile and are reachable from `Config.kt` without altering any consumer behavior yet.
  - _Requirements: 4.1, 4.2_
  - _Boundary: Config_

- [x] 1.3 Pin ktoml 0.7.1 decode-error shape for `Map<String, RawRepository>` flat-form input
  - Add a probe test in `ConfigParseMessageFormatTest` that decodes a TOML containing `[repositories]` with `central = "https://…"` into the production `Map<String, RawRepository>` shape and asserts the actual ktoml exception class and message string.
  - The pinned message is the source of truth for whether the migration error can be substituted in-place or must be surfaced via an appended hint.
  - **Observable done**: a new pinned test in `ConfigParseMessageFormatTest` records the exact exception type and message; `research.md` Topic 6 is updated with the verdict (substitution vs. hint-append) and the chosen branch.
  - _Requirements: 4.3_
  - _Depends: 1.2_
  - _Boundary: ConfigParseMessageFormatTest, research.md_

---

## Phase 2: Schema Migration (PR-1)

- [ ] 2. Schema migration: flip `[repositories]` to sub-table form and propagate

- [x] 2.1 Flip `RawKoltConfig.repositories` to `Map<String, RawRepository>`, lift into `Repository`, and wire the migration error
  - Change `RawKoltConfig.repositories` type to `Map<String, RawRepository>`; update its default `central` entry.
  - Add the decode-lift step that maps `RawRepository` → `Repository` and rejects any entry whose `url` is null or empty with a message naming the offending repository (R4.4).
  - Catch the ktoml decode error pinned in 1.3 and translate it into the user-facing migration message (substitution or hint-append, per 1.3's verdict, R4.3).
  - Add `RepositorySchemaMigrationTest.kt` covering: the sub-table form decodes to `Repository("…")` (R4.1); multiple sub-tables are preserved in declaration order; the legacy flat form is rejected with the migration message identifying `kolt.toml`; an empty sub-table (`[repositories.x]` with no `url`) is rejected.
  - **Observable done**: the four assertions above pass; `kolt.toml` parses now require the sub-table form.
  - _Requirements: 4.1, 4.2, 4.3, 4.4_
  - _Depends: 1.2, 1.3_
  - _Boundary: Config, RepositorySchemaMigrationTest_

- [x] 2.2 Update 9 resolver and CLI consumers
  - Mechanically replace `config.repositories.values.toList()` with `config.repositories.values.map { it.url }.toList()` at the call sites enumerated in `design.md` File Structure Plan: `BundleResolver.kt:180`, `NativeResolver.kt:67`, `TransitiveResolver.kt:20`, `OutdatedCommand.kt:42`, `ToolCommands.kt:163`, `DependencyCommands.kt:54`/`:296`/`:388`/`:407`.
  - **Observable done**: `grep -n "config\.repositories\.values\.toList" src/nativeMain/` returns zero hits, and `kolt build` of the root project still compiles.
  - _Requirements: 4.1_
  - _Depends: 2.1_
  - _Boundary: Resolver consumers, CLI consumers_

- [x] 2.3 (P) Update 4 Kotlin Map test fixtures
  - In `ChangeMatrixTest.kt:215`, `BundleResolutionIntegrationTest.kt:399`, `BundleResolverProgressTest.kt:98`, and `NativeResolverJvmOnlyFallbackTest.kt:39`, convert `mapOf("central" to "https://…")` to `mapOf("central" to Repository("https://…"))`.
  - **Observable done**: each affected test compiles and passes against the new schema.
  - _Requirements: 4.1_
  - _Depends: 2.1_
  - _Boundary: ChangeMatrixTest, BundleResolutionIntegrationTest, BundleResolverProgressTest, NativeResolverJvmOnlyFallbackTest_

- [x] 2.4 (P) Update TOML heredoc test fixtures
  - In `ConfigTest.kt` lines 812, 837, 870, 896 and in `DoAddAtomicWriteTest.kt:82`, flip each `[repositories]` heredoc body from the flat form to `[repositories.<name>] url = "…"` sub-table form.
  - **Observable done**: all five heredoc-bearing tests run green against the new schema.
  - _Requirements: 4.1_
  - _Depends: 2.1_
  - _Boundary: ConfigTest, DoAddAtomicWriteTest_

- [x] 2.5 (P) Update README and skill documentation code blocks
  - In `README.md` lines 127 and 185, `README.ja.md` lines 126 and 184, and `.claude/skills/kolt-usage/SKILL.md` line 157, replace the flat `[repositories]` code blocks with the sub-table form.
  - **Observable done**: `grep -n "^\\[repositories\\]" README.md README.ja.md .claude/skills/kolt-usage/SKILL.md` returns zero hits for the legacy flat shape.
  - _Requirements: 4.1_
  - _Depends: 2.1_
  - _Boundary: README, kolt-usage SKILL_

- [x] 2.6 (P) Update `generateKoltToml` scaffolding template
  - In the kolt-init scaffolding template, change the emitted `[repositories]` block from `central = "https://repo1.maven.org/maven2"` to `[repositories.central]\nurl = "https://repo1.maven.org/maven2"`.
  - **Observable done**: `kolt new sandbox` (or equivalent test fixture) produces a `kolt.toml` whose `[repositories]` block matches the sub-table form, and the generated project parses cleanly.
  - _Requirements: 4.1_
  - _Depends: 2.1_
  - _Boundary: Init scaffolding_

---

## Phase 3: Overlay decode and merge (PR-1)

- [ ] 3. Overlay decode, error path, and merge for sys_props

- [x] 3.1 Thread source-file path through `KtomlMessageParse` for both root-scope and nested-scope unknown keys
  - Update the existing ktoml-error wrapper so it accepts a source-file path parameter, used to identify whether the offending file is `kolt.toml` or `kolt.local.toml`.
  - Cover both the `Unknown key received: <k> in scope <rootNode>` branch (already pinned in `ConfigParseMessageFormatTest`) and the nested-scope branch (`scope <run>`, `scope <test>`, etc.) — the latter uses the scope info already returned by `parseUnknownKey` and was previously unattributed to a source file.
  - Extend `ConfigParseMessageFormatTest` with new pinned cases for the nested-scope path so the error format is recorded before 3.2 consumes it.
  - **Observable done**: the new pinned `ConfigParseMessageFormatTest` cases pass; the wrapper signature change compiles against existing `parseConfig` callers.
  - _Requirements: 2.2, 2.3_
  - _Boundary: KtomlMessageParse, ConfigParseMessageFormatTest_

- [x] 3.2 Implement `LocalOverlay` decoder and decode tests
  - Add `LocalOverlay.kt` with `RawLocalOverlayConfig` (all nullable fields: `test`, `run`, `repositories`) and `parseLocalOverlay(tomlString, path)` returning `Result<RawLocalOverlayConfig, ConfigError>`.
  - `parseLocalOverlay` consumes the path-threaded wrapper from 3.1 so overlay errors are attributed to `kolt.local.toml`.
  - Add `LocalTomlOverlayDecodeTest.kt` covering: TOML syntax error attribution (R1.3), root-scope unknown section `[build]` and bare `name = "…"` (R2.2), nested-scope unknown sub-key `[run.foo]` (R2.3).
  - **Observable done**: the new decode tests pass; each rejection message names both the offending construct and `kolt.local.toml` as the source file.
  - _Requirements: 1.3, 2.1, 2.2, 2.3_
  - _Depends: 3.1_
  - _Boundary: LocalOverlay, LocalTomlOverlayDecodeTest_

- [x] 3.3 Implement `mergeSysProps` and `mergeOverlay` (sys_props path only)
  - Implement `mergeSysProps(base, overlay)` with key-replace plus union semantics, applied to both `[test.sys_props]` and `[run.sys_props]` from inside `mergeOverlay`.
  - `mergeOverlay` returns the merged `RawKoltConfig` and threads the overlay path through any future structural errors (Phase 6 uses this for repositories).
  - Add `LocalTomlOverlayMergeTest.kt` with unit tests for: same-key replace across both sections (R3.1), new-key union across both sections (R3.2).
  - **Observable done**: the new merge unit tests pass; an overlay sys_prop replaces same-key base entries and adds new keys in both sections.
  - _Requirements: 3.1, 3.2_
  - _Depends: 3.2_
  - _Boundary: LocalOverlay, LocalTomlOverlayMergeTest_

- [x] 3.4 Extend `parseConfig` to accept the overlay and run validation on the merged result
  - Add the `overlayString: String? = null` and `overlayPath: String? = null` parameters to `parseConfig`; default behavior is preserved for callers that omit them.
  - Order: decode base → optionally decode overlay → call `mergeOverlay` → run existing validators on the merged `RawKoltConfig` → lift to `KoltConfig`.
  - Add the post-merge empty-`url` repository validator inside the validation pass: after merge, any `Repository.url` that is absent or empty is rejected with a message naming the offending repository (R5.3).
  - Cover both the overlay-present and overlay-null branches with `parseConfig`-level integration tests in `LocalTomlOverlayMergeTest.kt`; assert that the overlay-null branch produces the same `KoltConfig` as a base-only parse.
  - **Observable done**: `parseConfig(base, …, overlayString = null)` returns a `KoltConfig` equal (via `equals`) to `parseConfig(base, …)`, the overlay-present branch surfaces merged sys_props correctly, and a synthetic test producing an empty `url` post-merge is rejected.
  - _Requirements: 1.1, 1.2, 5.3_
  - _Depends: 3.3_
  - _Boundary: Config, LocalTomlOverlayMergeTest_

- [x] 3.5 Extend `loadProjectConfig` to read the overlay file when present
  - Add a conditional `readFileAsString(KOLT_LOCAL_TOML)` after the base read; on file-not-found, pass `null` as the overlay; on any other IO error, surface it as a config error.
  - **Observable done**: a temp-directory test demonstrates that creating `kolt.local.toml` next to `kolt.toml` causes a subsequent `loadProjectConfig` call to reflect the overlay in the returned `KoltConfig`, while deleting it returns the project to base-only behavior with no error.
  - _Requirements: 1.1, 1.2_
  - _Depends: 3.4, 1.1_
  - _Boundary: BuildCommands_

---

## Phase 4: Discoverability (PR-1)

- [ ] 4. Surface overlay in CLI help and architecture documentation

- [x] 4.1 (P) Update `printUsage` `-D` line and add `kolt.local.toml` description
  - Update the existing `-D<key>=<value>` description so it reads as the third layer of a three-layer override (`kolt.toml` ← `kolt.local.toml` ← `-D`).
  - Add a short note in the flags section that describes `kolt.local.toml` as a per-project optional overlay file restricted to `[test.sys_props]`, `[run.sys_props]`, and `[repositories.<name>]`.
  - **Observable done**: a snapshot test (or direct invocation in CI) of `kolt --help` includes both the three-layer order and the `kolt.local.toml` mention.
  - _Requirements: 7.1_
  - _Boundary: Main printUsage_

- [x] 4.2 (P) Add `## Configuration layers` chapter to `docs/architecture.md`
  - Insert the new chapter between `## Error handling` and `## Configuration change semantics`.
  - Cover: the two on-disk files (shared vs. gitignored), the section allowlist, merge semantics (key-replace for sys_props, field-merge for repositories), the three-layer override order, and a forward reference noting that ADR 0034 (private Maven repos, shared with #416) is authored in PR-2.
  - **Observable done**: `docs/architecture.md` contains the new section in the specified position and forward-references ADR 0034 by number; the in-tree ADR link itself does not need to resolve until PR-2 (6.2) lands.
  - _Requirements: 7.2_
  - _Boundary: docs/architecture.md_

---

## Phase 5: Validation (PR-1)

- [ ] 5. End-to-end integration and self-host smoke

- [x] 5.1 Add end-to-end integration tests for the merged config
  - In `LocalTomlOverlayMergeTest.kt`, add: (a) realistic mixed overlay end-to-end (sys_props with a `{ classpath = X }` reference to a bundle declared in base, resolves correctly post-merge — R3.3), (b) overlay `${env.X}` literal rejection via the existing env-agnostic validator (R1.4).
  - **Observable done**: the two new integration tests pass and exercise the post-merge validation pipeline against overlay-sourced values.
  - _Requirements: 1.4, 3.3_
  - _Depends: 3.4_
  - _Boundary: LocalTomlOverlayMergeTest_

- [x] 5.2 (P) Upgrade self-host `kolt.toml` files and confirm dogfood path
  - Flip the `[repositories]` declarations in the three top-level `kolt.toml` files (root, `kolt-jvm-compiler-daemon`, `kolt-native-compiler-daemon`) to the sub-table form.
  - Run `kolt build` against each sub-project locally and confirm the existing self-host CI job stays green.
  - **Observable done**: each of the three `kolt.toml` files declares `[repositories.central] url = "…"` and the self-host smoke CI step passes on the PR.
  - _Requirements: 4.1, 8.1_
  - _Depends: 2.6, 3.4_
  - _Boundary: Repo root, kolt-jvm-compiler-daemon, kolt-native-compiler-daemon_

---

## Phase 6: Repositories overlay (PR-2)

- [ ] 6. Field-level overlay for `[repositories.<name>]`

- [x] 6.1 Implement `mergeRepositories` and extend `mergeOverlay`
  - Add `mergeRepositories(base, overlay, overlayPath)` returning `Result<Map<String, RawRepository>, ConfigError>` with field-level merge (data-class copy semantics) for matching names and rejection (file-attributed) for overlay-only names.
  - Wire the new merge into `mergeOverlay` so repositories merge happens alongside sys_props.
  - Extend `LocalTomlOverlayMergeTest.kt` with unit tests for: matching-name field-merge with url replaced (R5.1), overlay-only name rejection naming `kolt.local.toml` (R5.2), ordered list position preservation, post-merge empty-`url` rejection on an overlay that clears a base url (R5.3, exercises the 3.4 validator on an overlay-introduced empty value).
  - **Observable done**: the new repository overlay tests pass; merged `KoltConfig.repositories` reflects per-field overlay while preserving declaration order from `kolt.toml`.
  - _Requirements: 5.1, 5.2, 5.3_
  - _Depends: 3.4_
  - _Boundary: LocalOverlay, LocalTomlOverlayMergeTest_

- [x] 6.2 (P) Land ADR 0034 skeleton (private Maven repos)
  - Create `docs/adr/0034-private-maven-repos.md` with Status (Proposed), Summary (5-7 bullets per memory `adr_summary_section`), Context (env-agnostic vs. credentials), Decision (overlay-file as credential home, env-var resolution deferred to v1.1+), and Consequences sections.
  - Auth-field specifics (`token` / `user` / `password` mutual exclusion, etc.) are stubbed with explicit "to be filled by #416" notes so the document is shape-complete but content-pending.
  - **Observable done**: `docs/adr/0034-private-maven-repos.md` exists with the skeleton sections; the forward reference added in 4.2 now resolves to this file.
  - _Requirements: 7.2_
  - _Boundary: docs/adr/0034-private-maven-repos.md_

---

## Phase 7: Gitignore auto-append (PR-3)

- [ ] 7. `kolt init` writes `kolt.local.toml` to `.gitignore`

- [x] 7.1 Append `kolt.local.toml` to the gitignore template
  - Update the kolt-init gitignore template to include `kolt.local.toml` in a deterministic position (alphabetical or alongside `workspace.json`); the template remains a hardcoded literal — no preset-merge step is introduced.
  - Add or extend `InitGitignoreTest.kt` to assert that `kolt init` (and `kolt new`) produces a `.gitignore` containing `kolt.local.toml` exactly once, regardless of project `kind` (`bin` / `lib`) and `target`.
  - **Observable done**: a fresh `kolt init` produces a `.gitignore` whose contents include `kolt.local.toml` on its own line.
  - _Requirements: 6.1, 6.2_
  - _Boundary: Init scaffolding, InitGitignoreTest_

---

## Implementation Notes

- **Task 1.3 verdict (consumed by 2.1)**: ktoml 0.7.1 surfaces a legacy flat-form `[repositories]\ncentral = "..."` decode against `Map<String, RawRepository>` as `UnknownNameException` with `Unknown key received: <central> in scope <rootNode>` — byte-identical to a real root-scope typo. Migration-message wiring in 2.1 must use **hint-append** (raw ktoml error preserved + hint paragraph appended) based on an input-content heuristic (`[repositories]` table present plus key in offending position), NOT substitution keyed on the exception. Pinned by `ConfigParseMessageFormatTest.legacyFlatRepositoriesShapeSurfacesAsUnknownNameAtRootScope`.
- **Task 2.1 follow-up (deferred, non-blocking)**: `src/nativeMain/kotlin/kolt/resolve/Resolver.kt:154` still emits the user-facing string `"no repositories configured (add a `[repositories]` entry to kolt.toml)"`. After the schema flip this hint points at the rejected legacy form. Out of scope for tasks 2.1-2.6 (Resolver.kt was not in design.md's enumerated file list). Recommend folding into the v0.20.0 release-note pass alongside other user-facing hint updates.
- **Task 5.1 follow-ups (deferred, non-blocking)**:
  - R1.4 interpretation: `${env.X}` in overlay literals is passed through verbatim (no interpolation, no rejection) per ADR 0032 §2. design.md §Integration Tests line 575 frames it as a rejection, which doesn't match the actual contract. Tests in `LocalTomlOverlayMergeTest.parseConfigOverlayEnvLiteralIsPreservedVerbatim` pin the empirical contract. Reconcile design.md wording in a follow-up; OR build a `${env.X}`-parse-time rejector in a separate spec.
  - R3.3 negative branch: `validateBundleReferences` (Config.kt:357) does not attribute errors to a source file, so an overlay-only `{ classpath = X }` referencing a missing bundle cannot today be tested to name `kolt.local.toml` in the error. Tighten the validator to surface the originating section's source file as a follow-up.
