# Implementation Plan

Execute in TDD order inside each task: red test subtask first, implementation
subtask second. Commit granularity is left to the implementer (per project
convention, red/green/refactor do not need to be separate commits).

## 1. Config parser — accept `kind = "lib"` with conditional `[build] main`

- [x] 1.1 Replace the "not yet implemented" rejection test with the new kind × main matrix
  - Remove the existing `kindLibIsRejectedAsNotYetImplemented` case from the config test suite
  - Add unit coverage for five cases: lib without main parses; lib with main rejects with canonical text; app without main rejects with canonical text; app with main parses; missing kind defaults to app
  - Each rejecting assertion matches the canonical error string from design.md as a substring (`main has no meaning for a library; remove it` / `[build] main is required for kind = "app"`)
  - Observable: the new test file compiles and runs; every case fails against current production code before 1.2 lands.
  - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5_

- [x] 1.2 Update the parser to make `main` nullable, accept `kind = "lib"`, enforce the conditional rule, and propagate the type ripple to the native link helper
  - Make the `main` field nullable in both the raw deserialization shape and the parsed build section; neither ktoml nor downstream callers should require a value for libraries
  - Remove the `"lib"` rejection branch from kind validation; leave the unknown-kind rejection in place
  - Introduce the two canonical error strings as top-level `private const val` with one-line ADR 0023 §1 citation comments
  - Apply the 5-step validation order from design.md: kind → conditional main → FQN (only when main present) → target → config construction
  - Add a file-local `isLibrary()` extension on the parsed config so downstream code reads intent rather than string literals
  - Mechanical ripple (same change, kept atomic so the tree compiles): update the native link helper to take the entry-point FQN as an explicit `String` parameter instead of reading the now-nullable `build.main`, and update its existing call site and direct unit tests to match; no behavioral change, no kind gating yet
  - Observable: the 1.1 matrix passes; `./gradlew check` stays green; no `"lib"` reserved-but-unimplemented rejection remains in the source.
  - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5_
  - _Boundary: kolt.config, kolt.build (link helper signature ripple only)_

## 2. Native build — stop after stage 1 for libraries

- [ ] 2.1 Add failing tests for the native kind gate
  - Assert that the native build orchestration invokes only stage 1 (library compile) when the config is a library, and writes no `.kexe` artifact
  - Assert that an app native config still invokes both stages and produces the executable at the canonical output path
  - Use scoped fixture configs created inside the test; do not reuse or mutate the repository's own kolt.toml
  - Observable: the two new test cases exist, compile, and fail against current production code.
  - _Requirements: 3.1, 3.2, 3.3, 3.4_

- [ ] 2.2 Add the kind gate in native build orchestration
  - After stage 1 succeeds, return early when `isLibrary()` is true — do not invoke the link helper
  - On the app path, extract the entry-point FQN using a defensive `?: return Err(EXIT_BUILD_ERROR)` (ADR 0001 safe; unreachable by parser invariant) and pass it into the link helper whose signature was already adjusted in 1.2
  - Distinguish artifact kind ("library" / "executable") in the user-facing build-success stdout message
  - Cite ADR 0014 (two-stage flow) and ADR 0023 §1 (kind schema) in a one-line comment at the gate
  - Observable: the 2.1 cases pass; existing native-link unit tests still pass; app builds continue to produce `.kexe` and lib builds stop at `.klib`.
  - _Requirements: 3.1, 3.2, 3.3, 3.4_
  - _Depends: 1.2_

## 3. Run command — reject libraries before build work

- [ ] 3.1 Add failing tests for the library-run rejection
  - Assert that the run command against a library config exits with the config-error exit code and emits the canonical `library projects cannot be run` substring to stderr
  - Assert the rejection occurs before any artifact resolution or build invocation
  - Assert that run-watch against a library rejects once and exits cleanly (no per-source-change error spam)
  - Observable: three new failing tests in a dedicated file.
  - _Requirements: 4.1, 4.2, 4.3, 4.4_

- [ ] 3.2 Add the kind guard in the run command and the run-watch loop entry
  - Introduce the canonical run-rejection message as a top-level `private const val` near the run command with an ADR 0023 §1 citation comment
  - As the first statement after config parsing in both the single-shot run command and the run-watch loop entry, check `isLibrary()` and return `Err(EXIT_CONFIG_ERROR)` with the canonical stderr line
  - Observable: the 3.1 tests pass; existing run-command tests for apps stay green; no build work executes when a library is asked to run.
  - _Requirements: 4.1, 4.2, 4.3, 4.4_
  - _Depends: 1.2_

## 4. JVM library thin-jar invariants

- [ ] 4.1 (P) Assert JVM library jars are thin and declare no Main-Class
  - Using a library fixture config and the JVM target, drive the build end-to-end
  - Inspect `META-INF/MANIFEST.MF` in the produced jar and assert there is no `Main-Class` attribute
  - Assert the jar contains no Kotlin standard library entries and no resolved dependency classes
  - Inspect the kotlinc command line (as recorded via the daemon request or subprocess args) and assert there is no `-include-runtime` token
  - Observable: a new JVM-lib integration test passes without any production-code change beyond what task 1 already delivered.
  - _Requirements: 2.1, 2.2, 2.3, 2.4_
  - _Boundary: kolt.cli integration tests_
  - _Depends: 1.2_

## 5. Test command — non-regression for libraries

- [ ] 5.1 (P) Assert the test command works against a library configuration
  - Using a library fixture config with no `[build] main` declared, drive the test command end-to-end against both JVM and native targets
  - Assert exit code 0 and that fixture tests compile and execute
  - Assert via a captured path-read that the test flow does not read the `main` field when the field is null (confirms R5.2 structurally, not just behaviorally)
  - Observable: a new non-regression test passes without any production-code change beyond what task 1 already delivered.
  - _Requirements: 5.1, 5.2, 5.3_
  - _Boundary: kolt.cli integration tests_
  - _Depends: 1.2_

## 6. Regression verification and dogfood

- [ ] 6.1 Run full Gradle check and a throwaway library fixture build
  - Execute `./gradlew check` end-to-end (unit tests, daemon version verification, linuxX64 tests) and confirm no regressions in existing app paths
  - Build a throwaway `kind = "lib"` fixture against both the JVM and linuxX64 targets; confirm artifacts match the design invariants (`.klib` on native with no `.kexe`; thin `.jar` on JVM with no `Main-Class`)
  - Record the fixture contents and produced artifact listings in the PR description so reviewers can replay
  - Observable: `./gradlew check` exits zero; fixture artifacts at `build/<name>.klib` and `build/<name>.jar` match the documented invariants.
  - _Requirements: 2.4, 3.4, 4.4, 5.3_
  - _Depends: 2.2, 3.2, 4.1, 5.1_
