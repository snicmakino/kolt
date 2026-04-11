---
name: keel-dev
description: Development guide for contributing to keel. Use when working on keel's source code, architecture, error handling, testing patterns, or dependency resolution internals.
argument-hint: "[topic]"
---

# Keel Development Guide

## Build & Test

```bash
./gradlew build              # Build + test + binary
./gradlew linuxX64Test       # Tests only
./gradlew compileKotlinLinuxX64  # Compile only
```

Binary: `build/bin/linuxX64/debugExecutable/keel.kexe`

## Tech Stack

- Kotlin 2.3.20 / Kotlin/Native (linuxX64)
- ktoml 0.7.1: keel.toml parsing
- kotlinx-serialization-json: keel.lock parsing
- kotlin-result (michael-bull) 2.3.1: error handling
- libcurl (cinterop): HTTP downloads
- kotlincrypto sha2-256 0.2.7: SHA256 hashing

## Architecture

| Package | File | Role |
|---|---|---|
| cli | Main.kt | CLI entrypoint, command dispatch |
| cli | BuildCommands.kt | Build pipeline orchestration (check, build, run, test, clean) |
| cli | DependencyCommands.kt | Dependency commands (init, add, install, update, tree) |
| cli | ToolchainCommands.kt | Toolchain management commands (install) |
| cli | FormatCommands.kt | Format command (keel fmt) |
| cli | PluginSupport.kt | Compiler plugin argument resolution |
| cli | ExitCode.kt | Standardized exit code constants |
| config | Config.kt | Parse keel.toml, KeelConfig data class |
| config | KeelPaths.kt | ~/.keel/ path resolution (cache, tools, toolchains) |
| config | Init.kt | Project template generation (keel.toml, Main.kt) |
| config | Version.kt | Keel version string |
| build | Builder.kt | Build kotlinc command args (pure function) |
| build | BuildCache.kt | Build state tracking via mtime comparison |
| build | Runner.kt | Build java -jar command args (pure function) |
| build | TestBuilder.kt | Build kotlinc command for test compilation (pure function) |
| build | TestRunner.kt | Build java command for test execution via JUnit Platform (pure function) |
| build | TestDeps.kt | Auto-injected test dependencies based on target platform (pure function) |
| build | Formatter.kt | Build ktfmt command args for code formatting (pure function) |
| build | Workspace.kt | Generate workspace.json / kls-classpath for IDE support |
| resolve | Dependency.kt | Maven coordinate parsing, JAR/POM URL/cache path construction (pure function) |
| resolve | Resolution.kt | Pure BFS dependency graph resolution (pure function, no I/O) |
| resolve | Resolver.kt | Dependency resolution entry point, delegates to TransitiveResolver |
| resolve | TransitiveResolver.kt | I/O orchestration: POM/JAR fetching, hashing, lockfile change detection |
| resolve | PomParser.kt | POM XML parsing, property interpolation (pure function) |
| resolve | GradleMetadata.kt | Gradle .module file parsing, KMP redirect detection |
| resolve | VersionCompare.kt | Maven version comparison (pure function) |
| resolve | Lockfile.kt | Parse/serialize keel.lock v1/v2 (JSON) |
| resolve | Metadata.kt | Maven metadata.xml parsing, latest version extraction |
| resolve | AddDependency.kt | TOML string manipulation to add dependencies |
| resolve | DepsTree.kt | Dependency tree ASCII rendering |
| infra | FileSystem.kt | File I/O, directory creation, stderr output |
| infra | Process.kt | Command execution via fork/execvp, output capture via popen |
| infra | Downloader.kt | HTTP file download via libcurl cinterop |
| infra | Sha256.kt | SHA256 hash computation |
| infra | Format.kt | Duration formatting utility |
| tool | ToolManager.kt | External tool download and caching (ktfmt, JUnit Console Launcher) |
| tool | ToolchainManager.kt | Kotlinc/JDK toolchain download, verification, auto-install, and path resolution |

## Error Handling Policy

**Exception throwing is prohibited.** Use kotlin-result `Result<V, E>` for error representation.

- Specify only the error types a function can return as type parameters
- Use sealed class only when a common parent error is meaningful; use individual data class for independent errors
- Consumers use `getOrElse` + `when` to exhaustively match all variants
- Pure functions (Builder, Runner) don't need Result — apply Result to side-effectful functions

```
parseConfig()       -> Result<KeelConfig, ConfigError>
readFileAsString()  -> Result<String, OpenFailed>
ensureDirectory()   -> Result<Unit, MkdirFailed>
executeCommand()    -> Result<Int, ProcessError>
executeAndCapture() -> Result<String, ProcessError>
```

## Testing Conventions

- Follow TDD (Red -> Green -> Refactor)
- Place test files in `src/nativeTest/kotlin/keel/<package>/` mirroring main source structure
- Naming: `XxxTest.kt` for the test file corresponding to `Xxx.kt`
- Use `kotlin.test` assertions
- Pure functions are straightforward to test (most of build/ and resolve/ packages)
- I/O-dependent tests may use `/tmp` directories with `try/finally` cleanup

## Coding Conventions

- Write all code, comments, documentation, and commit messages in English
- Annotate POSIX API usage with `@OptIn(ExperimentalForeignApi::class)` at function level
- Keep build/resolve functions pure where possible — push I/O to the edges (infra package)

## Design References

- [coursier](https://github.com/coursier/coursier) — Primary reference for transitive resolution: state-machine resolution (Done/Missing/Continue), exclusions (MinimizedExclusions), version intervals/constraints, immutable resolution state.
