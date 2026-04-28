# Implementation Plan

## Phase 1: Foundation — Profile type

- [x] 1. Foundation: Profile enum
- [x] 1.1 Introduce the Profile enum as the single source of truth for profile literals
  - Define a closed enum with exactly two values, each carrying a stable `dirName` string ("debug" / "release")
  - Add a unit test asserting both values exist, are exhaustive, and produce the literal directory names that the rest of the feature depends on
  - The new file compiles under `./gradlew linuxX64Test` and the new test runs green
  - _Requirements: 1.1, 1.2, 4.1, 4.2, 5.1, 5.2_

## Phase 2: Core — Native compile pipeline becomes profile-aware

- [x] 2. Native artifact path partition
- [x] 2.1 Thread Profile through every kolt-managed artifact path function
  - Update output path producers (kexe, jar, runtime classpath manifest, native test kexe) so the resulting path includes the profile directory segment
  - Update the existing unit tests that assert literal `build/<name>.kexe` / `build/<name>.jar` to expect `build/<profile>/...` and add release-variant assertions
  - Running `kolt build` against a fixture writes to `build/debug/<name>.kexe`; `kolt build --release` writes to `build/release/<name>.kexe` (test verifies the path string returned by the path function for both profiles)
  - _Requirements: 4.1, 4.2_

- [x] 3. Native IC cache path partition
- [x] 3.1 Replace the hardcoded native IC cache constant with a profile-aware function
  - Remove the `NATIVE_IC_CACHE_DIR` const in favor of a function that takes a Profile and returns `build/<profile>/.ic-cache`
  - Update the konanc link command builder so `-Xic-cache-dir=` reflects the active profile, and update existing tests asserting that arg literal (in the builder unit tests, the native daemon backend test, and the wire frame codec fixture)
  - The function returns `build/debug/.ic-cache` for Debug and `build/release/.ic-cache` for Release; the konanc link command for each profile contains the corresponding `-Xic-cache-dir=` arg
  - _Requirements: 5.1, 5.2_

- [x] 4. Native compile arg routing per profile
- [x] 4.1 Route `-opt` and `-g` based on Profile in the native link stage
  - In the link-stage command builder, add `-opt` if and only if the profile is Release, and add `-g` if and only if the profile is Debug
  - Leave the library/klib stage profile-agnostic (no `-opt`, no `-g`)
  - Add unit tests asserting both branches: Debug's args contain `-g` and exclude `-opt`; Release's args contain `-opt` and exclude `-g`; library-stage args contain neither under either profile
  - _Requirements: 2.1, 2.2, 2.3, 2.4_

- [x] 5. JVM no-op signature propagation
- [x] 5.1 Accept Profile on JVM-targeted command builders and runners without changing behavior
  - Update the JVM check command builder, the JVM test build command builder, and the JVM run command so they accept a Profile argument they ignore for arg construction
  - Add a unit test asserting that the same JVM compile command (same args, same module name, same classpath, same `outputPath`'s file name modulo profile dir) is produced whether Profile.Debug or Profile.Release is passed
  - The JVM no-op test runs green with both profiles producing identical compile command args
  - _Requirements: 3.1, 3.2_

- [x] 6. Native IC cleanup follows profile
- [x] 6.1 (P) Make the native IC wipe operate per profile, leave JVM IC reaper unchanged
  - Update `wipeNativeIcCache` to take a Profile and remove only `build/<profile>/.ic-cache`
  - Confirm the JVM `~/.kolt/daemon/ic/` traversal logic in the same module remains untouched and its existing tests still pass
  - Add a test that creates IC content under both profiles, wipes one, and asserts only the wiped profile's directory is gone
  - _Requirements: 5.4_
  - _Boundary: IcStateCleanup_
  - _Depends: 1.1, 3.1_

## Phase 3: Integration — CLI threads profile through entry commands

- [x] 7. CLI integration: parse `--release` and dispatch Profile
- [x] 7.1 Extract `--release` in the kolt-level argument parser and construct a Profile token once
  - Add the `--release` flag constant alongside existing kolt-level flag constants and follow the same `koltLevel.contains(...) + filter` style
  - Strip the flag from `filteredArgs` so it does not leak into subcommand parsing
  - Add a unit test asserting that, given a sample kolt-level arg list, the parser yields `Profile.Release` when `--release` is present and `Profile.Debug` when absent, and that the filtered subcommand args no longer contain `--release`
  - _Requirements: 1.1, 1.2, 1.3_

- [x] 7.2 Thread Profile into the four build-driving entry functions
  - Update `doBuild`, `doCheck`, `doTest`, `doRun` to take Profile and pass it to every downstream call site (path producers, IC cache function, command builders, runners, IC wipe)
  - Update existing unit tests that exercise these entry functions to pass an explicit Profile and assert the right downstream behavior
  - The CLI dispatcher passes the parsed Profile into each entry function and existing test suites for these entry functions stay green
  - _Requirements: 1.1, 1.2, 1.4_
  - _Depends: 6.1, 7.1_

- [x] 7.3 Carry Profile through the watch loop so rebuilds inherit the initial profile
  - Update the watch loop to capture the Profile of the initial invocation and pass it into every rebuild it triggers
  - Confirm via test that, given an initial `--release` invocation, every rebuild call site sees `Profile.Release` (and vice versa for Debug)
  - _Requirements: 1.1, 1.2_
  - _Depends: 7.2_

## Phase 4: Quality — Drift guard and distribution script

- [x] 8. Drift guard for profile literals
- [x] 8.1 (P) Add an assertion that profile dirNames stay aligned with the distribution script
  - Extend the existing drift-guards test with a fourth assertion that reads `Profile.Debug.dirName` / `Profile.Release.dirName` and asserts the distribution script contains the matching `kolt build --release` invocation
  - The new assertion fails with a clear message if the literals or the script invocation drift apart
  - _Requirements: 7.4_
  - _Boundary: DriftGuardsTest_
  - _Depends: 1.1, 9.1_

- [x] 9. Distribution script switches to release
- [x] 9.1 (P) Update assemble-dist.sh to build release artifacts and read from `build/release/`
  - Pass `--release` to every `kolt build` invocation in the distribution script (root native build and both daemon sub-builds)
  - Update the post-build artifact path for the root native binary to `build/release/kolt.kexe`
  - The self-host distribution path successfully assembles a tarball whose root binary lives under `build/release/`; the existing `self-host-post` CI job exercises this end-to-end
  - _Requirements: 6.1, 6.2, 6.3_
  - _Boundary: assemble-dist.sh_
  - _Depends: 2.1_

## Phase 5: Validation — End-to-end behavior

- [x] 10. Integration validation
- [x] 10.1 Profile alternation integration test
  - Run, in order, `kolt build` then `kolt build --release` then `kolt build` again against a fixture project
  - Assert that after the sequence both `build/debug/<name>.kexe` and `build/release/<name>.kexe` exist on disk simultaneously, and that neither IC cache directory was wiped by the alternating builds
  - _Requirements: 4.3, 5.3, 5.4_
  - _Depends: 7.2, 6.1_

- [x] 10.2 (P) JVM no-op contract integration test
  - Build a JVM fixture project with `kolt build` and `kolt build --release`
  - Assert: identical compile output bytes (modulo file-system timestamps), identical `~/.kolt/daemon/ic/` working directory used, and no warning or error attributable to the flag on stderr
  - _Requirements: 3.1, 3.2, 3.3_
  - _Boundary: JVM end-to-end_
  - _Depends: 7.2_

## Phase 6: Documentation

- [x] 11. Documentation
- [x] 11.1 (P) Author ADR 0030
  - Write `docs/adr/0030-build-profiles.md` codifying: default = debug, `--release` opt-in, JVM no-op declaration with reasons (kotlinc bytecode no significant diff, `-Xno-call-assertions` not default, line numbers always present, reproducibility separate concern), Native flag routing rules (`-opt`, `-g`), output and IC path partitioning shape, explicit rejection of `kolt.toml [profile]` sections, and the resolution of issue #261's misattribution about `~/.kolt/daemon/ic/<v>/<id>/<profile>/`
  - The ADR file exists with Status / Summary / Context / Decision / Consequences sections matching the project's existing ADR style
  - _Requirements: 7.4_
  - _Boundary: docs/adr/_

- [x] 11.2 (P) Update user-facing README content
  - Update `README.md` and `README.ja.md` so build command examples mention `--release` and reflect the `build/<profile>/` artifact layout where relevant
  - The user-facing examples in both READMEs read coherently for a new user opening the project for the first time post-feature
  - _Requirements: 7.1_
  - _Boundary: README files_

- [x] 11.3 (P) Update agent-facing documentation
  - Add a one-line "Profiles" entry to CLAUDE.md Key Rules pointing at ADR 0030
  - Update `.kiro/steering/tech.md` so the User-facing CLI snippet shows `--release` on relevant commands
  - Update `.claude/skills/kolt-usage/SKILL.md` build/test/run/check examples and add a brief profile section
  - All three files mention the profile concept consistently and point to ADR 0030 for the policy
  - _Requirements: 7.2, 7.3_
  - _Boundary: agent docs_
