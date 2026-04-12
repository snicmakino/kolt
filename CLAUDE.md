# kolt

Lightweight build tool written in Kotlin/Native (linuxX64).
Reads `kolt.toml`, compiles with `kotlinc`, and runs with `java -jar`.

## Build & Test

```bash
./gradlew build              # Build + test + binary
./gradlew linuxX64Test       # Tests only
./gradlew compileKotlinLinuxX64  # Compile only
```

## Key Rules

- **Exception throwing is prohibited** — use kotlin-result `Result<V, E>` for all error handling
- **Follow TDD** (Red -> Green -> Refactor)
- **Write all code, comments, documentation, and commit messages in English**
- Place test files in `src/nativeTest/kotlin/kolt/<package>/XxxTest.kt` mirroring main source structure
- Annotate POSIX API usage with `@OptIn(ExperimentalForeignApi::class)` at function level

## Skills

- `/kolt-usage` — How to use kolt (commands, kolt.toml configuration, dependencies)
- `/kolt-dev` — Development guide (architecture, error handling policy, testing patterns)
