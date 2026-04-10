# keel

> v0.8.0 — Early-stage project. Expect breaking changes.

A lightweight build tool for Kotlin. Compiles with `kotlinc` directly — no Gradle, no JVM startup tax.

Written in Kotlin/Native. Single binary, instant startup. Incremental builds via mtime-based caching — unchanged sources skip compilation entirely.

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
mkdir my-app && cd my-app
keel init
keel build
keel run
```

This creates the following structure in the current directory:

```
keel.toml
src/Main.kt
test/MainTest.kt
```

The project name is inferred from the directory name. To use a different name:

```sh
keel init custom-name
```

## Commands

```
keel init [name]   Create a new project
keel build         Compile the project
keel run           Build and run (keel run -- args for app arguments)
keel test          Build and run tests (keel test -- args for JUnit Platform arguments)
keel check         Type-check without producing artifacts
keel add           Add a dependency (see below)
keel install       Resolve dependencies and download JARs
keel fmt           Format source files with ktfmt
keel fmt --check   Check formatting (CI mode)
keel clean         Remove build artifacts
keel deps tree     Show dependency tree
keel update        Re-resolve dependencies and update lockfile
keel toolchain install  Install kotlinc version defined in keel.toml
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
resources = ["resources"]
test_resources = ["test-resources"]

[plugins]
serialization = true

[dependencies]
"org.jetbrains.kotlinx:kotlinx-coroutines-core" = "1.9.0"

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
| `resources` | Resource directories to include in build output | `[]` |
| `test_resources` | Test resource directories added to test classpath | `[]` |
| `fmt_style` | ktfmt style: `"google"`, `"kotlinlang"`, `"meta"` | `"google"` |
| `[plugins]` | Compiler plugins (`serialization`, `allopen`, `noarg`) | `{}` |
| `[repositories]` | Maven repositories (name = URL) | Maven Central only |

### Dependencies

Add dependencies with `keel add`:

```sh
keel add org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0
keel add org.jetbrains.kotlinx:kotlinx-coroutines-core   # latest stable version
keel add --test io.kotest:kotest-runner-junit5:5.8.0      # test dependency
```

Or declare Maven coordinates directly in `[dependencies]`:

```toml
[dependencies]
"org.jetbrains.kotlinx:kotlinx-coroutines-core" = "1.9.0"
"com.squareup.okhttp3:okhttp" = "4.12.0"
```

Run `keel install` to resolve and download all dependencies without building.

Transitive dependencies are resolved automatically via POM metadata. A `keel.lock` file records versions and SHA-256 hashes for reproducible builds.

### Custom Repositories

By default, keel resolves dependencies from Maven Central. To add custom repositories (e.g., JitPack), declare them in `[repositories]`:

```toml
[repositories]
central = "https://repo1.maven.org/maven2"
jitpack = "https://jitpack.io"
```

Repositories are tried in declaration order. When omitted, Maven Central is used.

### Compiler Plugins

Enable Kotlin compiler plugins in `[plugins]`:

```toml
[plugins]
serialization = true
```

Supported plugins: `serialization`, `allopen`, `noarg`. Plugin JARs are resolved from the Kotlin compiler distribution.

### Resource Files

Include resource files (config files, templates, static assets) in the build output:

```toml
resources = ["resources"]
test_resources = ["test-resources"]
```

Files in `resources` directories are copied into the build output and included in the JAR. Files in `test_resources` directories are added to the classpath during test execution. Non-existent directories are silently skipped.

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

## Toolchain Management

keel can manage its own kotlinc installation, so you don't need to install it system-wide.

```sh
keel toolchain install   # Download kotlinc version specified in keel.toml
```

Toolchains are stored under `~/.keel/toolchains/kotlinc/{version}/`. When a managed toolchain is available, keel uses it automatically; otherwise it falls back to system `kotlinc` on PATH.

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
| 5 | Format error |
| 127 | Command not found |

## Claude Code Integration

This project includes [Claude Code](https://claude.ai/code) skills. If you use Claude Code, the `/keel-usage` skill provides interactive help with keel commands, configuration, and dependency management. You can also [read it directly](.claude/skills/keel-usage/SKILL.md) as a reference.

## License

MIT
