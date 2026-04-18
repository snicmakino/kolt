# kolt

[![Unit tests](https://github.com/snicmakino/kolt/actions/workflows/unit-tests.yml/badge.svg)](https://github.com/snicmakino/kolt/actions/workflows/unit-tests.yml)
[![Self-host smoke test](https://github.com/snicmakino/kolt/actions/workflows/self-host-smoke.yml/badge.svg)](https://github.com/snicmakino/kolt/actions/workflows/self-host-smoke.yml)

English | [日本語](README.ja.md)

> v0.10.1 — Early-stage project. Expect breaking changes.

A lightweight build tool for Kotlin. TOML config — no Kotlin DSL build scripts to evaluate. Distributed as a single Kotlin/Native binary — no Java install required to use it.

The tool itself starts instantly. Actual compilation delegates to `kotlinc` / `konanc`, so build times track the Kotlin compiler directly. Incremental builds via mtime-based caching skip unchanged sources entirely. A warm JVM compiler daemon amortizes JVM startup across successive builds — typical warm-build latency is ~0.3 s.

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

kolt is built with Gradle. Partial self-hosting works (kolt can compile the native binary from `kolt.toml`), but the compiler daemon JAR still requires Gradle to build ([#97](https://github.com/snicmakino/kolt/issues/97)).

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
kolt init [name]       Create a new project
kolt build             Compile the project
kolt run               Build and run (kolt run -- args for app arguments)
kolt test              Build and run tests (kolt test -- args for JUnit Platform arguments)
kolt check             Type-check without producing artifacts
kolt add <dep>         Add a dependency (alias for deps add)

# --watch flag: monitor sources, rebuild on change
kolt build --watch     Watch and rebuild
kolt test --watch      Watch and retest
kolt run --watch       Watch, rebuild, and restart app
kolt check --watch     Watch and type-check
kolt fmt               Format source files with ktfmt
kolt fmt --check       Check formatting (CI mode)
kolt clean             Remove build artifacts

kolt deps add <dep>    Add a dependency
kolt deps install      Resolve dependencies and download JARs
kolt deps update       Re-resolve dependencies and update lockfile
kolt deps tree         Show dependency tree

kolt toolchain install Install kotlinc version defined in kolt.toml

kolt daemon stop       Stop the compiler daemon for this project
kolt daemon stop --all Stop all compiler daemons
kolt daemon reap       Remove stale daemon directories and orphaned sockets

kolt --version         Show version
```

`install`, `update`, and `tree` are also available as top-level aliases (e.g. `kolt install`).

### Flags

| Flag | Description |
|------|-------------|
| `--watch` | Watch source files and re-run the command on change (build/check/test/run) |
| `--no-daemon` | Skip the warm compiler daemon for this invocation. Always available, including on Kotlin versions outside the daemon's supported range (ADR 0022). |

## Configuration

`kolt.toml` — declarative, TOML-based:

```toml
name = "my-app"
version = "0.1.0"
kotlin = "2.3.20"
target = "jvm"
jvm_target = "17"
main = "main"
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
| `main` | Entry point function FQN (e.g. `"main"` or `"com.example.main"`) | (required) |
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

### Not yet supported

Planned for v1.0:

- Library packaging (currently `app` only)
- `kolt publish` / `kolt new`
- macOS and linuxArm64 targets
- Private Maven repository authentication

Planned post-1.0:

- Multi-module projects
- Mixed Kotlin/Java source compilation

## Why kolt?

Gradle is powerful but heavy for simple Kotlin projects. It imposes:

- JVM startup cost
- Build script compilation
- Task graph construction

kolt aims to be what `go build` is to Go or `cargo build` is to Rust — a fast, focused tool for building Kotlin projects with a declarative config file and zero ceremony.

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

## Architecture

See [docs/architecture.md](docs/architecture.md) ([日本語](docs/architecture.ja.md)) for the internal design — component overview, build flow, daemon lifecycle, and architectural decisions.

## Claude Code Integration

This project includes [Claude Code](https://claude.ai/code) skills. If you use Claude Code, the `/kolt-usage` skill provides interactive help with kolt commands, configuration, and dependency management. You can also [read it directly](.claude/skills/kolt-usage/SKILL.md) as a reference.

## Name

**kolt** = **Kot**lin + bo**lt** — fast, lightweight Kotlin tooling.

## License

MIT
