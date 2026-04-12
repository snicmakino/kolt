---
name: kolt-usage
description: Guide to kolt, a lightweight Kotlin build tool. Use when building Kotlin projects with kolt, configuring kolt.toml, managing dependencies, testing, or using kolt commands.
argument-hint: "[command or topic]"
---

# Kolt Build Tool

Lightweight build tool for Kotlin. Compiles with `kotlinc` directly — no Gradle, no JVM startup tax.
Single binary (Kotlin/Native), instant startup, incremental builds via mtime-based caching.

## Quick Start

```sh
mkdir my-app && cd my-app
kolt init
kolt build
kolt run
```

## Commands

```
kolt init [name]            Create a new project
kolt build                  Compile the project
kolt run                    Build and run (kolt run -- args for app arguments)
kolt test                   Build and run tests (kolt test -- args for JUnit Platform)
kolt check                  Type-check without producing artifacts
kolt add <dep>              Add a dependency
kolt add --test <dep>       Add a test dependency
kolt install                Resolve dependencies and download JARs
kolt update                 Re-resolve dependencies and update lockfile
kolt deps tree              Show dependency tree
kolt fmt                    Format source files with ktfmt
kolt fmt --check            Check formatting (CI mode)
kolt clean                  Remove build artifacts
kolt toolchain install      Install kotlinc version defined in kolt.toml
kolt --version              Show version
```

## Configuration (kolt.toml)

```toml
name = "my-app"
version = "0.1.0"
kotlin = "2.1.0"
target = "jvm"
jvm_target = "17"
main = "MainKt"
sources = ["src"]
test_sources = ["test"]
resources = ["resources"]
test_resources = ["test-resources"]
fmt_style = "google"

[plugins]
serialization = true

[dependencies]
"org.jetbrains.kotlinx:kotlinx-coroutines-core" = "1.9.0"

[test-dependencies]
"io.kotest:kotest-runner-junit5" = "5.8.0"

[repositories]
central = "https://repo1.maven.org/maven2"
jitpack = "https://jitpack.io"
```

### Fields

| Field | Description | Default |
|-------|-------------|---------|
| `name` | Project name | (required) |
| `version` | Project version | (required) |
| `kotlin` | Kotlin compiler version | (required) |
| `target` | `"jvm"` | (required) |
| `jvm_target` | JVM bytecode target | `"17"` |
| `main` | Entry point class | (required) |
| `sources` | Source directories | (required) |
| `test_sources` | Test source directories | `["test"]` |
| `resources` | Resource directories included in JAR | `[]` |
| `test_resources` | Test resource directories added to test classpath | `[]` |
| `fmt_style` | ktfmt style: `"google"`, `"kotlinlang"`, `"meta"` | `"google"` |
| `[plugins]` | Compiler plugins (`serialization`, `allopen`, `noarg`) | `{}` |
| `[repositories]` | Maven repositories (name = URL, tried in order) | Maven Central only |

## Dependencies

```sh
kolt add org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0
kolt add org.jetbrains.kotlinx:kotlinx-coroutines-core   # latest stable
kolt add --test io.kotest:kotest-runner-junit5:5.8.0      # test dependency
```

- Transitive dependencies resolved automatically via POM metadata
- `kolt.lock` records versions and SHA-256 hashes for reproducible builds
- Global cache at `~/.kolt/cache/` (Maven-compatible layout, shared across projects)
- `kolt update` to re-resolve and refresh hashes

## Testing

Just use `kotlin.test`:

```kotlin
import kotlin.test.Test
import kotlin.test.assertEquals

class MyTest {
    @Test
    fun testAdd() {
        assertEquals(3, add(1, 2))
    }
}
```

```sh
kolt test
kolt test -- <extra runner args>
```

**JVM target (`target = "jvm"`):** kolt auto-injects `kotlin-test-junit5` matching the Kotlin version and runs via JUnit Platform Console Standalone. Supports kotlin.test, JUnit 5, and Kotest. Extra args are forwarded to the console launcher, e.g. `kolt test -- --include-classname ".*IntegrationTest"`.

**Native target (`target = "native"`):** kolt compiles main + test sources in a single `konanc -generate-test-runner` invocation and executes the resulting `build/<name>-test.kexe`. `kotlin.test` is provided by the bundled Kotlin/Native stdlib — no test dependency needs to be declared. Extra args are forwarded to the native test runner, e.g. `kolt test -- --ktest_filter=MyTest.*` or `--ktest_logger=SIMPLE`. The test executable exits non-zero on any failed test.

## Toolchain Management

```sh
kolt toolchain install   # Download kotlinc version from kolt.toml
```

Stored at `~/.kolt/toolchains/kotlinc/{version}/`. Used automatically when available, falls back to system `kotlinc`.

## Prerequisites

- `kotlinc` on PATH (or managed via `kolt toolchain install`)
- `java` on PATH (for running JVM targets and tests)

## Exit Codes

| Code | Meaning |
|------|---------|
| 0 | Success |
| 1 | Build error |
| 2 | Config error |
| 3 | Dependency error |
| 4 | Test error |
| 5 | Format error |
| 127 | Command not found |

Note: `kolt test` can exit with codes other than 0 or 4. Dependency resolution failures (e.g. a klib that cannot be fetched during native test) exit with `3`, test source compilation failures exit with `1`. Only actual test-runner failures (non-zero exit from the test binary) produce `4`. CI pipelines that branch on "tests failed vs other error" should check `== 4` rather than `!= 0`.
