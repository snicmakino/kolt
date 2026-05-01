# Project Structure

## Organization Philosophy

**Native-first multiplatform with JVM sidecars as separate builds.** The root project
only has `nativeMain` / `nativeTest` source sets; JVM daemon code lives in two
independent kolt subprojects (`kolt-jvm-compiler-daemon/`, `kolt-native-compiler-daemon/`) with their
own `kolt.toml`s. Packages inside the native source are split **by responsibility**,
not by layer.

## Top-Level Layout

- `src/nativeMain/kotlin/` — production code (native linuxX64).
- `src/nativeTest/kotlin/` — tests, mirroring the production package tree.
- `src/nativeInterop/cinterop/` — C FFI (e.g. `libcurl.def`).
- `kolt-jvm-compiler-daemon/` — JVM daemon, independent kolt subproject.
- `kolt-native-compiler-daemon/` — native-compiler daemon sidecar, independent kolt subproject.
- `docs/` — prose docs and ADRs (`docs/adr/`).
- `spike/` — experimental probes and benchmark harnesses. May be deleted freely;
  should never be imported from production code.
- `scripts/` — dev and release scripts.
- `kolt.toml` — build configuration (kolt self-host config).

`.kiro/` and `.claude/` hold spec-driven-development metadata and agent tooling. They
are load-bearing for the workflow but out of scope for production code patterns.

## Package Organization

Root package: `kolt`. Subpackages split by responsibility, with a strict downward
dependency direction: **cli → build → resolve / infra**.

- `kolt.cli` — command entry points (`Main.kt`, `BuildCommands.kt`,
  `DependencyCommands.kt`, etc.). Thin — parses args, dispatches to `kolt.build` or
  `kolt.resolve`.
- `kolt.build` — compilation orchestration, workspace generation, formatter
  integration. The `CompilerBackend` sealed interface unifies daemon vs. subprocess
  execution.
- `kolt.build.daemon` — JVM daemon preconditions, JAR resolution, bootstrap JDK
  handling, IC state lifecycle.
- `kolt.build.nativedaemon` — native-compiler daemon backend (mirrors
  `kolt.build.daemon`).
- `kolt.resolve` — dependency resolution kernel. Pure domain logic: `Coordinate`,
  `Dependency`, `Resolution` (immutable fixpoint state), POM and Gradle-metadata
  parsers, transitive resolver. This package does not depend on `kolt.build` or
  `kolt.cli`.
- `kolt.config` — `kolt.toml` deserialization and validation.
- `kolt.daemon.wire` — daemon IPC codec (sealed `Message` / `Compile` / `CompileResult`
  / `Ping` / `Pong`), serialized via kotlinx.serialization.
- `kolt.infra` — OS primitives: file I/O, process spawning, Unix sockets, sha256.
- `kolt.tool` — toolchain management (kotlinc / JDK install and version resolution).

## Naming Conventions

- **Files**: one public type per file, filename matches the type (`Dependency.kt`,
  `DaemonCompilerBackend.kt`).
- **Classes**: CamelCase, no Hungarian-style prefix. Domain errors are sealed ADTs
  (`DaemonJarResolution.Found` / `.Missing` / ...).
- **Functions**: camelCase. Pure helpers sometimes suffixed `Pure`
  (`resolveDaemonJarPure`) to distinguish from their side-effecting callers.
- **Constants**: `UPPER_SNAKE_CASE` (`BOOTSTRAP_JDK_VERSION`, `SUN_PATH_CAPACITY`).
- **Tests**: `FooTest.kt` for unit, `FooIntegrationTest.kt` for integration.
- **Test fixtures**: `testfixture` subpackage
  (`kolt.infra.net.testfixture.UnixEchoServer`). Reusable across tests.

## Import Organization

- **No wildcard imports.** Each symbol imported explicitly.
- **Result chain style**: `kotlin-result`'s `getOrElse` / `getError` / `isErr` —
  **not** `is Ok` / `is Err` (kotlin-result 2.x `Result` is a value class).
- **Serialization**: `@Serializable` on sealed hierarchies for wire protocol and
  `kolt.toml` model.
- Intra-project imports follow the cli → build → resolve direction; reverse
  dependencies are a structural smell.

## Code Organization Principles

- **Sealed interfaces + ADTs** for domain polymorphism (`CompilerBackend`,
  `DaemonJarResolution`, `Message`). Preferred over enums when variants carry data,
  preferred over open inheritance always.
- **Pure resolution kernel**: `kolt.resolve` has no I/O; callers inject network and
  cache adapters. The kernel is the reliability floor — treat it with Gradle/Maven-
  spec parity in mind.
- **Explicit fallback policy**: all daemon failures except `CompilationFailed` fall
  back to subprocess. Retry budgets (e.g. 10..200ms within 10s) are inlined with ADR
  references rather than hidden in framework code.
- **Daemon version sync**: Kotlin version pins, daemon main-class FQNs, and the
  bootstrap JDK pin each fan out across multiple files. `DriftGuardsTest`
  asserts all three triangles under `kolt test`.
- **ADR citations in code**: when a block encodes a non-obvious decision (link
  stages, fallback routing, retry budgets), reference the ADR number in a comment so
  a reader can pull the rationale without git archaeology.
