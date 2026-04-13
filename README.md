# kolt

> v0.9.0 — Early-stage project. Expect breaking changes.

A lightweight build tool for Kotlin. Compiles with `kotlinc` directly — no Gradle, no JVM startup tax.

Written in Kotlin/Native. Single binary, instant startup. Incremental builds via mtime-based caching — unchanged sources skip compilation entirely.

## Installation

Build from source:

```sh
git clone https://github.com/snicmakino/kolt.git
cd kolt
./gradlew build
```

The binary is produced at `build/bin/linuxX64/debugExecutable/kolt.kexe`. Copy it to a directory on your PATH:

```sh
cp build/bin/linuxX64/debugExecutable/kolt.kexe ~/.local/bin/kolt
```

## Quick Start

```sh
mkdir my-app && cd my-app
kolt init
kolt build
kolt run
```

This creates the following structure in the current directory:

```
kolt.toml
src/Main.kt
test/MainTest.kt
```

The project name is inferred from the directory name. To use a different name:

```sh
kolt init custom-name
```

## Commands

```
kolt init [name]   Create a new project
kolt build         Compile the project
kolt run           Build and run (kolt run -- args for app arguments)
kolt test          Build and run tests (kolt test -- args for JUnit Platform arguments)
kolt check         Type-check without producing artifacts
kolt add           Add a dependency (see below)
kolt install       Resolve dependencies and download JARs
kolt fmt           Format source files with ktfmt
kolt fmt --check   Check formatting (CI mode)
kolt clean         Remove build artifacts
kolt deps tree     Show dependency tree
kolt update        Re-resolve dependencies and update lockfile
kolt toolchain install  Install kotlinc version defined in kolt.toml
kolt --version     Show version
```

## Configuration

`kolt.toml` — declarative, TOML-based:

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
| `[[cinterop]]` | C interop bindings for `target = "native"` (one array entry per `.def`) | `[]` |
| `[repositories]` | Maven repositories (name = URL) | Maven Central only |

### Dependencies

Add dependencies with `kolt add`:

```sh
kolt add org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0
kolt add org.jetbrains.kotlinx:kotlinx-coroutines-core   # latest stable version
kolt add --test io.kotest:kotest-runner-junit5:5.8.0      # test dependency
```

Or declare Maven coordinates directly in `[dependencies]`:

```toml
[dependencies]
"org.jetbrains.kotlinx:kotlinx-coroutines-core" = "1.9.0"
"com.squareup.okhttp3:okhttp" = "4.12.0"
```

Run `kolt install` to resolve and download all dependencies without building.

Transitive dependencies are resolved automatically via POM metadata. A `kolt.lock` file records versions and SHA-256 hashes for reproducible builds.

### Custom Repositories

By default, kolt resolves dependencies from Maven Central. To add custom repositories (e.g., JitPack), declare them in `[repositories]`:

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

Plugins work for both `target = "jvm"` and `target = "native"`. On native, kolt compiles the project in two konanc stages (`-p library` then `-p program -Xinclude=...`) so the plugin registrars run on the library stage; this is a workaround for a konanc quirk where single-step `-p program` invocations silently skip compiler plugins. See ADR 0014 for details. Enabling a plugin on a native project currently provisions the kotlinc distribution as a sidecar purely to borrow plugin jars from `<kotlincHome>/lib/`; a follow-up will switch to resolving them from Maven Central directly.

### C Interop (native target)

For `target = "native"` projects that need to call C libraries, declare one `[[cinterop]]` entry per `.def` file. kolt invokes the konan `cinterop` tool for each entry, caches the generated `.klib` under `build/`, and passes it to `konanc` via `-l` on both the library and link stages.

```toml
[[cinterop]]
name = "libcurl"
def = "src/nativeInterop/cinterop/libcurl.def"
package = "libcurl"
```

| Field | Description | Default |
|-------|-------------|---------|
| `name` | Output klib base name (`build/<name>.klib`) | (required) |
| `def` | Path to the `.def` file describing the binding | (required) |
| `package` | Kotlin package for generated bindings | (derived from `.def`) |

Compiler and linker options belong inside the `.def` file itself, using the Kotlin/Native standard `compilerOpts.<platform>` / `linkerOpts.<platform>` keys. For example:

```ini
headers = curl/curl.h
compilerOpts.linux = -I/usr/include -I/usr/include/x86_64-linux-gnu
linkerOpts.linux = -L/usr/lib/x86_64-linux-gnu -lcurl
```

The `cinterop` klib is regenerated when the `.def` file's mtime changes; source-only edits reuse the cached klib. Multiple `[[cinterop]]` entries are allowed and are linked in declaration order.

### Resource Files

Include resource files (config files, templates, static assets) in the build output:

```toml
resources = ["resources"]
test_resources = ["test-resources"]
```

Files in `resources` directories are copied into the build output and included in the JAR. Files in `test_resources` directories are added to the classpath during test execution. Non-existent directories are silently skipped.

### Test Dependencies

For JVM targets, kolt automatically injects `kotlin-test-junit5` matching your Kotlin version. Just write tests using `kotlin.test`:

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

kolt runs tests via JUnit Platform Console Standalone, supporting:

- **kotlin.test** (via kotlin-test-junit5, auto-injected)
- **JUnit 5** (direct)
- **Kotest** (via kotest-runner-junit5)

```sh
kolt test
kolt test -- --include-classname ".*IntegrationTest"
```

## Dependency Management

- **Global cache**: `~/.kolt/cache/` — shared across projects (Maven-compatible layout)
- **Lockfile**: `kolt.lock` records versions and SHA-256 hashes
- **SHA-256 verification**: Mismatches cause errors (no silent re-download)
- **Explicit update**: `kolt update` to re-resolve and refresh hashes

## Toolchain Management

kolt can manage its own kotlinc installation, so you don't need to install it system-wide.

```sh
kolt toolchain install   # Download kotlinc version specified in kolt.toml
```

Toolchains are stored under `~/.kolt/toolchains/kotlinc/{version}/`. When a managed toolchain is available, kolt uses it automatically; otherwise it falls back to system `kotlinc` on PATH.

## Why kolt?

Gradle is powerful but heavy for simple Kotlin projects. It imposes:

- JVM startup cost
- Build script compilation
- Task graph construction

kolt aims to be what `go build` is to Go or `cargo build` is to Rust — a fast, focused tool for building Kotlin projects with a declarative config file and zero ceremony.

## Prerequisites

- `kotlinc` on PATH (matching the version in `kolt.toml`)
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

This project includes [Claude Code](https://claude.ai/code) skills. If you use Claude Code, the `/kolt-usage` skill provides interactive help with kolt commands, configuration, and dependency management. You can also [read it directly](.claude/skills/kolt-usage/SKILL.md) as a reference.

## Name

**kolt** = **Kot**lin + bo**lt** — fast, lightweight Kotlin tooling.

> Previously named `keel`. Renamed in v0.9.0 to avoid collision with
> [keel.sh](https://keel.sh) and [Spinnaker keel](https://github.com/spinnaker/keel).

## License

MIT
