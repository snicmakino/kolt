# keel

> v0.4.0 — Early-stage project. Expect breaking changes.

A lightweight build tool for Kotlin. Compiles with `kotlinc` directly — no Gradle, no JVM startup tax.

Written in Kotlin/Native. Single binary, instant startup.

## Installation

Build from source:

```sh
git clone https://github.com/snicmakino/keel.git
cd keel
./gradlew build
```

The binary is produced at `build/bin/linuxX64/debugExecutable/keel.kexe`. Copy it to a directory on your PATH:

```sh
cp build/bin/linuxX64/debugExecutable/keel.kexe ~/.local/bin/keel
```

## Quick Start

```sh
keel init my-app
cd my-app
keel build
keel run
```

This creates a project with the following structure:

```
my-app/
  keel.toml
  src/Main.kt
  test/MainTest.kt
```

## Commands

```
keel init [name]   Create a new project
keel build         Compile the project
keel run           Build and run (keel run -- args for app arguments)
keel test          Build and run tests (keel test -- args for JUnit Platform arguments)
keel check         Type-check without producing artifacts
keel clean         Remove build artifacts
keel deps tree     Show dependency tree
keel update        Re-resolve dependencies and update lockfile
keel --version     Show version
```

## Configuration

`keel.toml` — declarative, TOML-based:

```toml
name = "my-app"
version = "0.1.0"
kotlin = "2.1.0"
target = "jvm"
jvm_target = "17"
main = "MainKt"
sources = ["src"]

[dependencies]
"org.jetbrains.kotlinx:kotlinx-coroutines-core" = "1.9.0"
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

### Dependencies

Declare Maven coordinates in `[dependencies]`:

```toml
[dependencies]
"org.jetbrains.kotlinx:kotlinx-coroutines-core" = "1.9.0"
"com.squareup.okhttp3:okhttp" = "4.12.0"
```

Transitive dependencies are resolved automatically via POM metadata from Maven Central. A `keel.lock` file records versions and SHA-256 hashes for reproducible builds.

### Test Dependencies

For JVM targets, keel automatically injects `kotlin-test-junit5` matching your Kotlin version. Just write tests using `kotlin.test`:

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

For additional test frameworks (e.g., Kotest), declare them in `[test-dependencies]`:

```toml
[test-dependencies]
"io.kotest:kotest-runner-junit5" = "5.8.0"
```

## Testing

keel runs tests via JUnit Platform Console Standalone, supporting:

- **kotlin.test** (via kotlin-test-junit5, auto-injected)
- **JUnit 5** (direct)
- **Kotest** (via kotest-runner-junit5)

```sh
keel test
keel test -- --include-classname ".*IntegrationTest"
```

## Dependency Management

- **Global cache**: `~/.keel/cache/` — shared across projects (Maven-compatible layout)
- **Lockfile**: `keel.lock` records versions and SHA-256 hashes
- **SHA-256 verification**: Mismatches cause errors (no silent re-download)
- **Explicit update**: `keel update` to re-resolve and refresh hashes

## Why keel?

Gradle is powerful but heavy for simple Kotlin projects. It imposes:

- JVM startup cost
- Build script compilation
- Task graph construction

keel aims to be what `go build` is to Go or `cargo build` is to Rust — a fast, focused tool for building Kotlin projects with a declarative config file and zero ceremony.

## Prerequisites

- `kotlinc` on PATH (matching the version in `keel.toml`)
- `java` on PATH (for running JVM targets and tests)

## Exit Codes

| Code | Meaning |
|------|---------|
| 0 | Success |
| 1 | Build error |
| 2 | Config error |
| 3 | Dependency error |
| 4 | Test error |
| 127 | Command not found |

## License

MIT
