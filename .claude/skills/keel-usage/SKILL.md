---
name: keel-usage
description: Guide to keel, a lightweight Kotlin build tool. Use when building Kotlin projects with keel, configuring keel.toml, managing dependencies, testing, or using keel commands.
argument-hint: "[command or topic]"
---

# Keel Build Tool

Lightweight build tool for Kotlin. Compiles with `kotlinc` directly — no Gradle, no JVM startup tax.
Single binary (Kotlin/Native), instant startup, incremental builds via mtime-based caching.

## Quick Start

```sh
mkdir my-app && cd my-app
keel init
keel build
keel run
```

## Commands

```
keel init [name]            Create a new project
keel build                  Compile the project
keel run                    Build and run (keel run -- args for app arguments)
keel test                   Build and run tests (keel test -- args for JUnit Platform)
keel check                  Type-check without producing artifacts
keel add <dep>              Add a dependency
keel add --test <dep>       Add a test dependency
keel install                Resolve dependencies and download JARs
keel update                 Re-resolve dependencies and update lockfile
keel deps tree              Show dependency tree
keel fmt                    Format source files with ktfmt
keel fmt --check            Check formatting (CI mode)
keel clean                  Remove build artifacts
keel toolchain install      Install kotlinc version defined in keel.toml
keel --version              Show version
```

## Configuration (keel.toml)

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
keel add org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0
keel add org.jetbrains.kotlinx:kotlinx-coroutines-core   # latest stable
keel add --test io.kotest:kotest-runner-junit5:5.8.0      # test dependency
```

- Transitive dependencies resolved automatically via POM metadata
- `keel.lock` records versions and SHA-256 hashes for reproducible builds
- Global cache at `~/.keel/cache/` (Maven-compatible layout, shared across projects)
- `keel update` to re-resolve and refresh hashes

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
keel test
keel test -- <extra runner args>
```

**JVM target (`target = "jvm"`):** keel auto-injects `kotlin-test-junit5` matching the Kotlin version and runs via JUnit Platform Console Standalone. Supports kotlin.test, JUnit 5, and Kotest. Extra args are forwarded to the console launcher, e.g. `keel test -- --include-classname ".*IntegrationTest"`.

**Native target (`target = "native"`):** keel compiles main + test sources in a single `konanc -generate-test-runner` invocation and executes the resulting `build/<name>-test.kexe`. `kotlin.test` is provided by the bundled Kotlin/Native stdlib — no test dependency needs to be declared. Extra args are forwarded to the native test runner, e.g. `keel test -- --ktest_filter=MyTest.*` or `--ktest_logger=SIMPLE`. The test executable exits non-zero on any failed test.

## Toolchain Management

```sh
keel toolchain install   # Download kotlinc version from keel.toml
```

Stored at `~/.keel/toolchains/kotlinc/{version}/`. Used automatically when available, falls back to system `kotlinc`.

## Prerequisites

- `kotlinc` on PATH (or managed via `keel toolchain install`)
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

Note: `keel test` can exit with codes other than 0 or 4. Dependency resolution failures (e.g. a klib that cannot be fetched during native test) exit with `3`, test source compilation failures exit with `1`. Only actual test-runner failures (non-zero exit from the test binary) produce `4`. CI pipelines that branch on "tests failed vs other error" should check `== 4` rather than `!= 0`.
