# Requirements Document

## Project Description (Input)
Add Cargo-style `--release` build profile to kolt. Default = debug, `--release` is opt-in. Resolves issue #261.

### Problem
`kolt build` currently compiles Native targets without a release/debug split, conflating "fast iteration" and "production-ready" workflows. Daily Native iteration pays optimization-class link time even when not needed, and distribution binaries are not explicitly marked as release-built. No profile concept also leaves kolt looking incomplete next to Cargo / Gradle / Bazel and blocks the Gradle-removal direction.

### Definition of Done (from issue #261)
- Adopt ADR 0030 `build-profiles.md` codifying:
  - default = debug, `--release` opt-in (Cargo style)
  - JVM: `--release` declared no-op. Reasons (kotlinc bytecode no significant diff, `-Xno-call-assertions` not default, line numbers always present, reproducibility is a separate concern) documented in §JVM treatment.
  - Native: `--release` adds `-opt`, debug profile omits `-opt` (and includes `-g`), output path split into `build/<profile>/`, daemon IC store split into `~/.kolt/daemon/ic/<version>/<projectId>/<profile>/`.
  - No `kolt.toml [profile]` section (fixed behavior). Custom compiler args / reproducibility / `extra_compiler_args` deferred.
- All entry commands (`kolt build`, `kolt test`, `kolt run`, `kolt check`) accept `--release`.
- Native: tests verify both `kolt build` and `kolt build --release` succeed with separated output paths and IC stores.
- JVM: tests assert `kolt build --release` produces the same artifact bytes / IC store as `kolt build` (no-op).
- Update README.md, CLAUDE.md, steering tech.md, `kolt-usage` skill build examples.
- Switch `assemble-dist.sh` to `kolt build --release`.

### Out of Scope (from issue #261)
- `kolt.toml [profile.dev]` / `[profile.release]` configuration.
- JVM reproducibility profile (epoch 0 timestamp, IC bypass) — separate issue.
- `extra_compiler_args` / pass-through compiler flags — separate issue.
- macOS / linuxArm64 profile-specific behavior — defer until #82 / #83.
- Behavior of `--release` on commands other than `kolt build`, `kolt test`, `kolt run`, `kolt check` (handling on `init`, `add`, `deps`, etc. is design-time TBD).

### Note on Current State
Issue #261's Problem section claims "always runs as release-equivalent (`konanc -opt`)". A scope survey suggests konanc is currently invoked **without** `-opt` (no optimization flags routed through `nativeLinkCommand`). The intent and direction of this feature are unchanged regardless: introduce an opt-in `--release` profile that adds optimization, and a debug default that explicitly omits it. Design phase will confirm the baseline before wiring `-opt` and `-g`.

### Related
- ADR 0019 (incremental compile), ADR 0027 (runtime classpath manifest) must remain consistent after profile path split.
- Prerequisite for #230 (curl | sh installer) and Gradle-removal plan.

## Introduction

kolt currently compiles Native targets with a single, profile-less compiler invocation, so users cannot trade optimization quality for iteration speed at the command line. This feature introduces a Cargo-style opt-in `--release` profile across all build-driving commands, separates Native artifact output and daemon IC state per profile, and declares the flag a no-op on JVM targets where there is no comparable cost to optimize away. The change is the precondition for distributing release-built binaries via `scripts/assemble-dist.sh` and for the upcoming `curl | sh` installer (#230).

## Boundary Context

**In scope**:
- A `--release` opt-in flag accepted by `kolt build`, `kolt test`, `kolt run`, and `kolt check`.
- Native compile behavior split by profile (optimization and debug-info routing).
- Native output artifact path split by profile (`build/<profile>/`).
- Native daemon IC store isolation by profile.
- JVM target: `--release` is a declared no-op (no observable behavior change).
- `scripts/assemble-dist.sh` switched so distribution binaries are release-built.
- Documentation updates in `README.md`, `CLAUDE.md`, `.kiro/steering/tech.md`, and the `kolt-usage` skill.
- ADR 0030 (`docs/adr/0030-build-profiles.md`) documenting the policy.

**Out of scope**:
- `kolt.toml [profile.dev]` / `[profile.release]` configuration sections.
- JVM reproducibility (epoch-zero timestamps, IC bypass).
- Pass-through compiler flags (`extra_compiler_args` and similar).
- Profile-specific behavior on macOS or linuxArm64 (deferred to #82 / #83).
- Behavior of `--release` on non-build commands (`init`, `new`, `add`, `deps`, `fmt`, `toolchain`, `daemon`).
- Migration of pre-existing IC state at `~/.kolt/daemon/ic/<version>/<projectId>/`. Per project policy, breaking changes pre-v1.0 do not ship migration shims; users wipe the daemon directory if needed.

**Adjacent expectations**:
- ADR 0019 (incremental compile) and ADR 0027 (runtime classpath manifest) remain consistent after the artifact-path and IC-path split.
- The drift-guard test suite (`DriftGuardsTest`) continues to pass after profile-aware path computation lands.
- The `curl | sh` installer (#230) consumes release-built binaries assembled by `scripts/assemble-dist.sh`.

## Requirements

### Requirement 1: Profile flag acceptance on build-driving commands

**Objective:** As a kolt user, I want a `--release` opt-in flag on every build-driving command, so that I can choose the profile per invocation without changing project configuration.

#### Acceptance Criteria
1. When the user invokes `kolt build`, `kolt test`, `kolt run`, or `kolt check` without `--release`, the kolt CLI shall execute that command under the debug profile.
2. When the user invokes `kolt build`, `kolt test`, `kolt run`, or `kolt check` with `--release`, the kolt CLI shall execute that command under the release profile.
3. The kolt CLI shall accept `--release` in the same flag position as existing kolt-level flags such as `--no-daemon` and `--watch`.
4. While `kolt.toml` contains no profile-related configuration, the kolt CLI shall determine the active profile solely from the presence or absence of `--release` on the command line.

### Requirement 2: Native compile behavior per profile

**Objective:** As a kolt user building Native targets, I want the debug profile to skip optimization, so that daily iteration link time is not paid for unused optimization passes.

#### Acceptance Criteria
1. While operating under the debug profile on a Native target, the kolt CLI shall produce a binary without enabling compiler optimization passes that elongate link time.
2. While operating under the release profile on a Native target, the kolt CLI shall produce a binary with compiler optimization enabled.
3. While operating under the debug profile on a Native target, the kolt CLI shall include debug information in the produced binary.
4. While operating under the release profile on a Native target, the kolt CLI shall omit debug information from the produced binary.

### Requirement 3: JVM compile behavior is profile-independent

**Objective:** As a kolt user building JVM targets, I want `--release` to be a documented no-op, so that I can rely on a single observable behavior on JVM regardless of profile.

#### Acceptance Criteria
1. When the user invokes `kolt build`, `kolt test`, `kolt run`, or `kolt check` on a JVM target with `--release`, the kolt CLI shall produce the same compile output bytes as the same invocation without `--release`, modulo file-system timestamps.
2. When the user invokes a JVM target build with `--release`, the kolt CLI shall not surface a deprecation, warning, or error attributable to the flag.
3. While operating on a JVM target, the kolt CLI shall use the same daemon IC store path regardless of `--release` presence.

### Requirement 4: Output artifact path isolation per profile

**Objective:** As a kolt user, I want each profile to produce artifacts in a distinct directory, so that switching profiles does not silently overwrite the other profile's artifacts.

#### Acceptance Criteria
1. While operating under the debug profile, the kolt CLI shall write the primary build artifact under a directory whose path includes a `debug` profile segment.
2. While operating under the release profile, the kolt CLI shall write the primary build artifact under a directory whose path includes a `release` profile segment.
3. When the user runs `kolt build` followed by `kolt build --release` (or vice versa) without intervening cleanup, the kolt CLI shall preserve both profiles' artifacts on disk.
4. While the project has not been built under a given profile, the kolt CLI shall not require that profile's output directory to exist before invoking the build.

### Requirement 5: Native daemon IC store isolation per profile

**Objective:** As a kolt user iterating between profiles, I want the Native daemon's incremental-compile state partitioned per profile, so that profile switches do not invalidate each other's incremental state.

#### Acceptance Criteria
1. While operating under the debug profile on a Native target, the kolt CLI shall use a daemon IC store path containing a `debug` profile segment.
2. While operating under the release profile on a Native target, the kolt CLI shall use a daemon IC store path containing a `release` profile segment.
3. When the user alternates between debug and release builds of the same project, the kolt CLI shall preserve each profile's IC state across the alternation.
4. If the user removes a profile's IC store directory, the kolt CLI shall recreate it on the next build under that profile without affecting the other profile's IC state.

### Requirement 6: Distribution build is release by default

**Objective:** As a kolt maintainer cutting a release, I want `scripts/assemble-dist.sh` to produce optimized binaries, so that distributed kolt binaries are unambiguously release-built.

#### Acceptance Criteria
1. When `scripts/assemble-dist.sh` is invoked without overrides, the script shall build every kolt component under the release profile.
2. The kolt project shall keep `scripts/assemble-dist.sh` as the single source of truth for the profile selection used in the distribution tarball.
3. When `scripts/assemble-dist.sh` finishes successfully, the produced tarball shall contain artifacts that were built under the release profile.

### Requirement 7: Documentation reflects profile semantics

**Objective:** As a new kolt user, I want public docs to introduce the profile distinction up front, so that I do not infer release behavior is the default.

#### Acceptance Criteria
1. The kolt project shall update `README.md` so that build command examples reflect the debug-default and `--release` opt-in behavior.
2. The kolt project shall update `CLAUDE.md` and `.kiro/steering/tech.md` to mention the profile policy where build commands are documented.
3. The kolt project shall update the `kolt-usage` skill so its build examples and command reference mention `--release`.
4. The kolt project shall add ADR 0030 (`docs/adr/0030-build-profiles.md`) documenting:
   - Default = debug, `--release` opt-in (Cargo style) decision.
   - JVM `--release` no-op declaration with reasoning (kotlinc bytecode no significant diff, `-Xno-call-assertions` not default, line numbers always present, reproducibility is a separate concern).
   - Native compile flag routing rules (`-opt`, debug-info handling).
   - Output path and IC store partitioning shape (`build/<profile>/` and `~/.kolt/daemon/ic/<version>/<projectId>/<profile>/`).
   - Explicit rejection of `kolt.toml [profile]` configuration sections.
