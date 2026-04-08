# keel

Lightweight build tool written in Kotlin/Native (linuxX64). A Kotlin port of keel (Zig).
Reads `keel.toml`, compiles with `kotlinc`, and runs with `java -jar`.

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
- ktor-client-curl 3.4.2: HTTP downloads
- kotlincrypto sha2-256 0.2.7: SHA256 hashing

## Architecture

| File | Role |
|---|---|
| Config.kt | Parse keel.toml, KeelConfig data class |
| FileSystem.kt | File I/O, directory creation, stderr output |
| Process.kt | Command execution via fork/execvp, output capture via popen |
| Builder.kt | Build kotlinc command args (pure function) |
| Runner.kt | Build java -jar command args (pure function) |
| VersionCheck.kt | Parse kotlinc version string (pure function) |
| Dependency.kt | Maven coordinate parsing, JAR/POM URL/cache path construction (pure function) |
| Lockfile.kt | Parse/serialize keel.lock v1/v2 (JSON) |
| Downloader.kt | HTTP file download via Ktor Client (Curl engine) |
| Sha256.kt | SHA256 hash computation |
| PomParser.kt | POM XML parsing, property interpolation (pure function) |
| VersionCompare.kt | Maven version comparison (pure function) |
| Resolution.kt | Pure BFS dependency graph resolution (pure function, no I/O) |
| TransitiveResolver.kt | I/O orchestration: POM/JAR fetching, hashing, lockfile change detection |
| Resolver.kt | Dependency resolution entry point, delegates to TransitiveResolver |
| Main.kt | CLI entrypoint, module integration |

## Error Handling Policy

**Exception throwing is prohibited.** Use kotlin-result `Result<V, E>` for error representation.

- Specify only the error types a function can return as type parameters
- Use sealed class only when a common parent error is meaningful; use individual data class for independent errors
- Consumers use `getOrElse` + `when` to exhaustively match all variants

```
parseConfig()     → Result<KeelConfig, ConfigError>
readFileAsString() → Result<String, OpenFailed>
ensureDirectory()  → Result<Unit, MkdirFailed>
executeCommand()   → Result<Int, ProcessError>
executeAndCapture() → Result<String, ProcessError>
```

## Design References

- [coursier](https://github.com/coursier/coursier) — Scala-based Maven/Ivy dependency resolver. Primary reference for transitive resolution design: state-machine resolution (Done/Missing/Continue), exclusions (MinimizedExclusions), version intervals/constraints, immutable resolution state.

## Coding Conventions

- Follow TDD (Red → Green → Refactor)
- Place test files in `src/nativeTest/kotlin/keel/` with `XxxTest.kt` naming
- Pure functions (Builder, Runner, VersionCheck) don't need Result. Apply Result to side-effectful functions
- Annotate POSIX API usage with `@OptIn(ExperimentalForeignApi::class)` at function level
- Write all code, comments, documentation, and commit messages in English
