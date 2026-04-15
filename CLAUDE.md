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
- **Comments default to deletion** — keep only design invariants, "why-not" decisions, and anchored external-tool gotchas. See `/kolt-dev`.
- **Write all code, comments, documentation, and commit messages in English**
- Place test files in `src/nativeTest/kotlin/kolt/<package>/XxxTest.kt` mirroring main source structure
- Annotate POSIX API usage with `@OptIn(ExperimentalForeignApi::class)` at function level

## Issue & PR writing

- Issues: problem, definition of done, scope. Skip project background and tech prerequisites — the reader already knows what kolt is.
- PRs: what changed and where the reviewer should focus (judgment calls, places to double-check). Don't restate what the diff shows. Don't repeat the issue's background.
- Write prose, not checklists. Don't mechanically fill a `## Summary` / `## Test plan` template — default `gh pr create` scaffolding is fine to discard.

## Skills

- `/kolt-usage` — How to use kolt (commands, kolt.toml configuration, dependencies)
- `/kolt-dev` — Development guide (architecture, error handling policy, testing patterns)
