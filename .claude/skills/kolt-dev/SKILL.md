---
name: kolt-dev
description: Development guide for contributing to kolt. Use when working on kolt's source code, architecture, error handling, testing patterns, or dependency resolution internals.
argument-hint: "[topic]"
---

# Kolt Development Guide

## Build & Test

```bash
./gradlew build              # Build + test + binary
./gradlew linuxX64Test       # Tests only
./gradlew compileKotlinLinuxX64  # Compile only
```

Binary: `build/bin/linuxX64/debugExecutable/kolt.kexe`

## Tech Stack

- Kotlin 2.3.20 / Kotlin/Native (linuxX64)
- ktoml 0.7.1: kolt.toml parsing
- kotlinx-serialization-json: kolt.lock parsing
- kotlin-result (michael-bull) 2.3.1: error handling
- libcurl (cinterop): HTTP downloads
- kotlincrypto sha2-256 0.2.7: SHA256 hashing

## Architecture

Rows are per-package. Files are only called out when the nuance is load-bearing; leaf additions within a package should not require touching this table.

### Native client — `src/nativeMain/kotlin/kolt/`

| Package | Role |
|---|---|
| `cli` | Command dispatch (`Main.kt`), per-command handlers (`BuildCommands`, `DependencyCommands`, `DaemonCommands`, `ToolchainCommands`, `FormatCommands`), watch-mode loop (`WatchLoop`), orphan-socket reaper (`DaemonReaper`), exit-code constants. `BuildCommands` is the pipeline spine — holds `resolveCompilerBackend` / `resolveNativeCompilerBackend` and the `applyPluginsFingerprintToFile` JVM-socket naming (#138). |
| `config` | `kolt.toml` parsing (`Config`), `~/.kolt/` path resolution including both daemon sockets (`KoltPaths`), project init templates (`Init`), `main` FQN validation + JVM facade derivation (`Main`, ADR 0015). |
| `build` | Pure command-arg builders (`Builder` for kotlinc / konanc — konanc is a library → link two-stage split, ADR 0014/0015; `Runner`, `TestBuilder`, `TestRunner`, `TestDeps`, `Formatter`), build cache via mtime (`BuildCache`), workspace / classpath emission (`Workspace`), and the two compiler-backend seams: `CompilerBackend` + `SubprocessCompilerBackend` + `FallbackCompilerBackend` + `FallbackReporter` for JVM (ADR 0016), and the native-side siblings `NativeCompilerBackend` + `NativeSubprocessBackend` + `FallbackNativeCompilerBackend` + `NativeFallbackReporter` (ADR 0024 §7). |
| `build.daemon` | JVM daemon client: `DaemonCompilerBackend` (spawn-or-connect + exponential backoff), `DaemonJarResolver`, `DaemonPreconditions`, `BtaImplFetcher`, `BtaImplJarResolver`, bootstrap JDK provisioning (`BootstrapJdk`), IC state cleanup (`IcStateCleanup`), `ProjectHash`. ADR 0016 / 0019. |
| `build.nativedaemon` | Native daemon client: `NativeDaemonBackend` (peer of `DaemonCompilerBackend`; asymmetric constructor — `konancJar` + `konanHome`, no BTA / plugin jars), `NativeDaemonJarResolver`, `NativeDaemonPreconditions`. ADR 0024. |
| `daemon.wire` | JVM daemon wire codec and `Message` types — 4-byte length prefix + JSON frames. |
| `nativedaemon.wire` | Native daemon wire codec and `Message` types. Structurally mirrors `daemon.wire` but distinct `Message` sealed interface; `NativeCompile` carries flat konanc args, not structured fields (ADR 0024 §4). |
| `resolve` | Maven coordinate parsing + path construction (`Dependency`), pure BFS resolution (`Resolution`, ADR 0004), transitive I/O orchestration (`Resolver` → `TransitiveResolver`), POM parsing (`PomParser`), Gradle module metadata (`GradleMetadata` — KMP redirect detection), Maven version comparison (`VersionCompare`), `kolt.lock` v1/v2 (`Lockfile`), `maven-metadata.xml` (`Metadata`), TOML mutation for `deps add` (`AddDependency`), dep-tree rendering (`DepsTree`). |
| `infra` | POSIX-facing I/O: filesystem (`FileSystem` — includes `listFiles` / `listSubdirectories`), process exec via fork/execvp + double-fork `spawnDetached` for daemons (`Process`), HTTP via libcurl cinterop (`Downloader`), SHA-256 (`Sha256`), inotify recursive watch (`Inotify`), duration formatting (`Format`), self-exe path lookup (`SelfExe`). Raw cinterop types stay confined to this package; all entry points return `Result<_, …>`. |
| `infra.net` | AF_UNIX stream socket client (`UnixSocket` — `connect` / `sendAll` / `recvExact` / `shutdownWrite` / `close`; all entry points return `Result<_, UnixSocketError>`). Shared by both daemon clients. |
| `tool` | External tool download + caching (`ToolManager` — ktfmt, JUnit Console Launcher), kotlinc / konanc / JDK toolchain management under `~/.kolt/toolchains/` (`ToolchainManager`). |

### JVM compiler daemon — `kolt-jvm-compiler-daemon/src/main/kotlin/kolt/`

| Package | Role |
|---|---|
| `daemon` | Daemon entry point (`Main.kt`) — argv parsing, classloader construction, `DaemonServer` wire-up. |
| `daemon.server` | Accept loop + lifecycle (`DaemonServer` — idle timeout, max compiles, heap watermark), config (`DaemonConfig`). |
| `daemon.protocol` | Wire types mirroring the native side (`Message`, `FrameCodec`), kotlinc stderr diagnostic parser (`DiagnosticParser`). |
| `daemon.reaper` | Background thread pruning stale BTA IC state for inactive projects (`IcReaper`). |

BTA integration lives outside the daemon's own package tree (it's loaded via a separate classloader under `SharedApiClassesClassLoader`, ADR 0019). See `docs/architecture.md` for the classloader hierarchy.

### Native compiler daemon — `kolt-native-compiler-daemon/src/main/kotlin/kolt/`

| Package | Role |
|---|---|
| `nativedaemon` | Daemon entry point (`Main.kt`). |
| `nativedaemon.server` | Accept loop + lifecycle (`DaemonServer` — idle 10 min / max 500 / 2 GB heap watermark per ADR 0024 §3), config (`DaemonConfig`). Single-threaded — `K2Native.exec` is not safe under concurrent calls. |
| `nativedaemon.protocol` | Wire types and frame codec. Same 4-byte length + JSON framing as the JVM daemon; distinct `Message` sealed interface. |
| `nativedaemon.compiler` | `NativeCompiler` interface + `ReflectiveK2NativeCompiler` — loads `kotlin-native-compiler-embeddable.jar` via `URLClassLoader(arrayOf(url), null)` (bootstrap parent, ADR 0024 §2) and invokes `K2Native.exec(PrintStream, String[])` reflectively. The instance is held for the daemon's lifetime. |

## Error Handling Policy

**Exception throwing is prohibited.** Use kotlin-result `Result<V, E>` for error representation.

- Specify only the error types a function can return as type parameters
- Use sealed class only when a common parent error is meaningful; use individual data class for independent errors
- Consumers use `getOrElse` + `when` to exhaustively match all variants
- Pure functions (Builder, Runner) don't need Result — apply Result to side-effectful functions

```
parseConfig()       -> Result<KoltConfig, ConfigError>
readFileAsString()  -> Result<String, OpenFailed>
ensureDirectory()   -> Result<Unit, MkdirFailed>
executeCommand()    -> Result<Int, ProcessError>
executeAndCapture() -> Result<String, ProcessError>
```

## Testing Conventions

- Follow TDD (Red -> Green -> Refactor)
- Place test files in `src/nativeTest/kotlin/kolt/<package>/` mirroring main source structure
- Naming: `XxxTest.kt` for the test file corresponding to `Xxx.kt`
- Use `kotlin.test` assertions
- Pure functions are straightforward to test (most of build/ and resolve/ packages)
- I/O-dependent tests may use `/tmp` directories with `try/finally` cleanup

## Coding Conventions

- Write all code, comments, documentation, and commit messages in English
- Annotate POSIX API usage with `@OptIn(ExperimentalForeignApi::class)` at function level
- Keep build/resolve functions pure where possible — push I/O to the edges (infra package)

## Comment Discipline

Default to no comments. If a comment doesn't fit one of the three categories below, delete it.

### 1. Design invariants

Constraints the code upholds; breaking them breaks the program.

```kotlin
// Invariant: up-to-date path must not hit the network.
// Invariant: BFS prefers the newest version within the same artifact.
// sha256 mismatch never triggers auto-redownload (supply-chain).
```

ADR pointers are the degenerate form. One-line pointer is correct; paraphrasing ADR prose is not.

```kotlin
// Invariant: daemon never loads plugin jars — see ADR 0021 §2.
```

### 2. Non-obvious "why not"

Why a plausible approach was rejected. Not reconstructable from the code.

```kotlin
// Not traversing POM parent chain: unnecessary for Maven Central
// and would blow up request count.
```

### 3. External-tool gotchas (with anchors)

Undocumented behaviour of kotlinc / Maven Central / JUnit Platform / POSIX that the code depends on. **Must carry an anchor** (issue number, verified version, or bug URL). No anchor → delete.

```kotlin
// kotlinc -Xplugin breaks on paths with spaces (verified 2.1.0, KT-XXXXX).
```

### Always delete

- Code narration ("call foo, then bar, then return")
- Restating type / parameter / default from the signature
- ADR paragraph summaries (collapse to a one-line pointer)
- Repeated ADR citations on adjacent declarations (one at the top)
- Section-heading KDoc (`### Seams`) over <10-line code blocks
- Explanations of standard Kotlin / POSIX / JVM semantics

## Design References

- [coursier](https://github.com/coursier/coursier) — Primary reference for transitive resolution: state-machine resolution (Done/Missing/Continue), exclusions (MinimizedExclusions), version intervals/constraints, immutable resolution state.
