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
kolt new my-app          # creates ./my-app/ and scaffolds inside
cd my-app
kolt build
kolt run
```

Or scaffold into an existing directory:

```sh
mkdir my-app && cd my-app
kolt init                # scaffolds into the current directory
```

`kolt init` and `kolt new` accept the same flags; the difference is path handling. `init` requires that `kolt.toml` does not already exist; `new <name>` requires that `<name>/` does not already exist.

| Flag | Meaning |
|------|---------|
| `--app` (default) / `--lib` | Project kind. App scaffolds `src/Main.kt` with `fun main()`; lib scaffolds `src/Lib.kt` with `fun greet()` and omits `[build] main`. |
| `--target <jvm\|linuxX64\|linuxArm64\|macosX64\|macosArm64\|mingwX64>` | Build target (default `jvm`). Non-jvm targets omit `jvm_target`. |
| `--group <com.example>` | Nest sources under `<group>/<name>/` and add matching `package` declarations. App's `main` becomes the FQN (`com.example.myapp.main`). |

Both `--target VALUE` and `--target=VALUE` (and the same for `--group`) are accepted. Repeating a flag with the same value is fine; conflicting values error out.

Generated files for `kolt init`/`kolt new myapp` with no flags:

- `kolt.toml` — `target = "jvm"`, `jvm_target = "25"`, `main = "main"`, `sources = ["src"]`, `[kotlin] version = "2.3.20"`
- `src/Main.kt` — `fun main()` hello-world stub
- `test/MainTest.kt` — `kotlin.test` example
- `.gitignore` and a `git init` (skipped if already inside a worktree)

`kolt init` without `[name]` infers the project name from the current directory; `kolt new` requires `<name>` explicitly.

## Commands

```
kolt init [name]            Create a new project in the current directory
kolt new <name>             Create a new project in <name>/ (same flags as init)
kolt build                  Compile the project (debug profile by default)
kolt build --release        Compile under the release profile
kolt run                    Build and run (kolt run -- args for app arguments)
kolt test                   Build and run tests (kolt test -- args for JUnit Platform)
kolt check                  Type-check without producing artifacts
kolt add <dep>              Add a dependency (alias for deps add)
kolt add --test <dep>       Add a test dependency
kolt fmt                    Format source files with ktfmt
kolt fmt --check            Check formatting (CI mode)
kolt clean                  Remove build artifacts

kolt deps add <dep>         Add a dependency
kolt deps install           Resolve dependencies and download JARs
kolt deps update            Re-resolve dependencies and update lockfile
kolt deps tree              Show dependency tree

kolt toolchain install      Install kotlinc version defined in kolt.toml

kolt daemon stop            Stop the compiler daemon for this project
kolt daemon stop --all      Stop all compiler daemons
kolt daemon reap            Remove stale daemon directories and orphaned sockets

kolt --version              Show version
```

`install`, `update`, and `tree` are also available as top-level aliases.

### Flags

| Flag | Description |
|------|-------------|
| `--watch` | Watch source files and re-run on change (build/test/run). Not supported for `check` — use an LSP for editor type-check feedback. |
| `--no-daemon` | Skip the warm compiler daemon for this invocation. Always available, even on Kotlin versions outside the daemon's supported range (ADR 0022). |
| `--release` | Build under the release profile. Native: enables `-opt`, omits `-g`, writes artifacts to `build/release/`. JVM: declared no-op; the artifact still moves to `build/release/<name>.jar`. Debug remains the default. |
| `-D<key>=<value>` | JVM system property for `kolt test` / `kolt run` (JVM target only). Overlays declared `[test.sys_props]` / `[run.sys_props]`; same-key collisions drop the toml entry. CLI values are literal-only. |

### Build Profiles

kolt has a Cargo-style profile model: debug is the default, `--release`
is opt-in. Both profiles' artifacts coexist on disk so switching between
them does not invalidate the other's incremental cache.

```sh
kolt build                  # build/debug/<name>.kexe (or .jar)
kolt build --release        # build/release/<name>.kexe (or .jar)
kolt test --release         # release-profile test executable
kolt run  --release         # run the release-profile artifact
```

- **Native** (`target = "linuxX64"` etc.): debug omits `-opt` and adds
  `-g`; release adds `-opt` and omits `-g`. The project-local
  incremental-compile cache lives at `build/<profile>/.ic-cache`, so
  alternating profiles preserves both caches.
- **JVM** (`target = "jvm"`): `--release` is a declared no-op for
  compile arg shape and for the daemon IC store at
  `~/.kolt/daemon/ic/`. The jar still partitions under
  `build/<profile>/<name>.jar` so the two profiles' artifacts do not
  overwrite each other.

`kolt.toml` has no `[profile]` section; the active profile is
determined solely by the presence or absence of `--release` on the
command line. See ADR 0030 for the full policy.

### Watch Mode

`--watch` monitors source directories via inotify and re-executes the command when `.kt`/`.kts` files or `kolt.toml` change. Editor temp files (`.swp`, `~`, etc.) are filtered out. Multiple rapid saves are batched via a settle window.

```sh
kolt build --watch          # Rebuild on source change
kolt test --watch           # Retest on source/test change
kolt run --watch            # Rebuild and restart app on change
kolt run --watch -- --port 8080  # App args passed through
```

For `run --watch`, the running application is killed and restarted on each source change. Ctrl+C stops watch mode.

## Configuration (kolt.toml)

```toml
name = "my-app"
version = "0.1.0"

[kotlin]
version = "2.3.20"

[kotlin.plugins]
serialization = true

[build]
target = "jvm"
jvm_target = "25"
main = "main"
sources = ["src"]
test_sources = ["test"]
resources = ["resources"]
test_resources = ["test-resources"]

[fmt]
style = "google"

[dependencies]
"org.jetbrains.kotlinx:kotlinx-coroutines-core" = "1.9.0"

[test-dependencies]
"io.kotest:kotest-runner-junit5" = "5.8.0"

[repositories]
central = "https://repo1.maven.org/maven2"
jitpack = "https://jitpack.io"

[[cinterop]]
name = "libcurl"
def = "src/nativeInterop/cinterop/libcurl.def"
package = "libcurl"

# Named jar bundle, resolved independently of [dependencies].
[classpaths.serialization-tools]
"org.jetbrains.kotlinx:kotlinx-serialization-core" = "1.6.0"

[test.sys_props]
"my.app.fixture" = { project_dir = "test/fixtures" }
"my.app.tools"   = { classpath = "serialization-tools" }
"my.app.mode"    = { literal = "test" }

[run.sys_props]
"my.app.config" = { project_dir = "config" }
```

### Fields

| Key | Description | Default |
|-----|-------------|---------|
| `name` | Project name | (required) |
| `version` | Project version | (required) |
| `[kotlin] version` | Kotlin language/API version (and the default compiler version) | (required) |
| `[kotlin] compiler` | Override kotlinc/daemon version independently of `version`. Must be `>= version`. | `version` |
| `[kotlin.plugins]` | Compiler plugins (`serialization`, `allopen`, `noarg`) | `{}` |
| `[build] target` | `"jvm"` or a KonanTarget (`"linuxX64"`, `"linuxArm64"`, `"macosX64"`, `"macosArm64"`, `"mingwX64"`) | (required) |
| `[build] jvm_target` | JVM bytecode target | `"25"` |
| `[build] jdk` | JDK version pin for daemon/runtime | (host JDK) |
| `[build] main` | Entry point function FQN (e.g. `"main"` or `"com.example.main"`) | (required) |
| `[build] sources` | Source directories | (required) |
| `[build] test_sources` | Test source directories | `["test"]` |
| `[build] resources` | Resource directories included in JAR | `[]` |
| `[build] test_resources` | Test resource directories added to test classpath | `[]` |
| `[fmt] style` | ktfmt style: `"google"`, `"kotlinlang"`, `"meta"` | `"google"` |
| `[[cinterop]]` | C interop bindings for native targets (array of `.def` entries) | `[]` |
| `[repositories]` | Maven repositories (name = URL, tried in order) | Maven Central only |
| `[classpaths.<name>]` | Named jar bundle resolved independently of `[dependencies]`. Same TOML shape (`"group:artifact" = "version"`). Referenced from `[test\|run.sys_props]` via `{ classpath = "<name>" }`. JVM target only. | `{}` |
| `[test.sys_props]` | Map of `-D<key>=<value>` for the test JVM. Each value is `{ literal = "..." }` / `{ classpath = "<bundle>" }` / `{ project_dir = "<rel>" }`. JVM target only. | `{}` |
| `[run.sys_props]` | Same shape as `[test.sys_props]` for `kolt run`. JVM target + `kind = "app"` only. | `{}` |

### C Interop

For native target projects, declare one `[[cinterop]]` entry per `.def` file. kolt invokes the konan `cinterop` tool, caches `build/<name>.klib`, and links it via `konanc -l`. Fields: `name` (klib base), `def` (.def path), `package` (optional Kotlin package). Compiler and linker flags belong inside the `.def` file itself via the Kotlin/Native standard `compilerOpts.<platform>` / `linkerOpts.<platform>` keys — kolt does not duplicate them in `kolt.toml`. Klibs are regenerated when the `.def` file's mtime changes.

### JVM System Properties (`[test.sys_props]` / `[run.sys_props]`)

JVM target projects can declare `-D<key>=<value>` system properties for `kolt test` (`[test.sys_props]`) and `kolt run` (`[run.sys_props]`). Each value is one inline-table shape:

| Shape | Resolution |
|-------|------------|
| `{ literal = "..." }` | Verbatim string. |
| `{ classpath = "<bundle>" }` | Colon-joined absolute paths from `[classpaths.<bundle>]`. |
| `{ project_dir = "<rel>" }` | `<project root>/<rel>` as absolute path; `"."` resolves to the project root. |

```toml
[classpaths.compiler-plugins]
"org.example:my-plugin" = "1.2.0"

[test.sys_props]
"my.app.fixture" = { project_dir = "test/fixtures" }
"my.app.plugins" = { classpath = "compiler-plugins" }
"my.app.mode"    = { literal = "test" }

[run.sys_props]
"my.app.config" = { project_dir = "config" }
```

`-D<key>=<value>` flags on the command line overlay declared sysprops at invocation time. Toml entries are emitted first in declaration order; CLI entries are appended in command-line order; same-key collisions drop the toml entry (CLI wins). CLI values are literal-only.

```sh
kolt test -Dlog.level=DEBUG
kolt run -Dapi.endpoint=http://localhost:8080
kolt test -Dmy.app.mode=integration   # overlays [test.sys_props].my.app.mode
```

`kolt.toml` does not interpolate `${env.X}` — values are literal at parse time (ADR 0032). Per-machine values arrive through the CLI flag (now) or `kolt.local.toml` (future, #320). The schema rejects `[test.sys_props]` / `[run.sys_props]` / `[classpaths.*]` on native targets at parse time, and `[run.sys_props]` on `kind = "lib"`.

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

**Native target (e.g. `target = "linuxX64"`):** kolt compiles main + test sources into an intermediate klib (`build/<name>-test-klib`) with any enabled compiler plugins applied, then links that klib into `build/<name>-test.kexe` via `konanc -p program -generate-test-runner -Xinclude=...`. `kolt build` uses the same two-stage shape (`build/<name>-klib` → `build/<name>.kexe`). `kotlin.test` is provided by the bundled Kotlin/Native stdlib — no test dependency needs to be declared. Extra args are forwarded to the native test runner, e.g. `kolt test -- --ktest_filter=MyTest.*` or `--ktest_logger=SIMPLE`. The test executable exits non-zero on any failed test.

## Toolchain Management

```sh
kolt toolchain install   # Download kotlinc version from kolt.toml
```

Stored at `~/.kolt/toolchains/kotlinc/{version}/`. Used automatically when available, falls back to system `kotlinc`.

### CI cache

`~/.kolt/toolchains/` is a stable cache target across the 1.x line (per ADR 0028 §3): the `jdk/<version>/`, `kotlinc/<version>/`, and `konanc/<version>/` subpaths will not be reorganised without a major release. Cache the directory on a key derived from `kolt.toml` (so a Kotlin/JDK pin bump invalidates) and you'll keep hits across patch and minor upgrades. The bootstrap JDK that powers the warm compiler daemon (ADR 0017) lives in the same directory, so a single cache step also avoids re-downloading the daemon JDK on every run.

GitHub Actions:

```yaml
- name: Cache kolt toolchains
  uses: actions/cache@v4
  with:
    path: ~/.kolt/toolchains
    key: ${{ runner.os }}-kolt-toolchains-${{ hashFiles('kolt.toml') }}
    restore-keys: |
      ${{ runner.os }}-kolt-toolchains-
```

The `restore-keys` fallback lets a `kolt.toml` edit reuse the previous cache as a base and only re-download the changed tool. Cache `~/.kolt/daemon/` and the build output directory separately if at all — they have different invalidation lifetimes.

## Kotlin Version Support

kolt supports **Kotlin 2.3.0 and above** on the daemon path, including `[kotlin.plugins]` projects. Kotlin 2.3.20 is the bundled default (no fetch on first build); other 2.3.x patches get `kotlin-build-tools-impl` fetched from Maven Central on first use. Below 2.3.0 is a soft floor — `kolt build` falls back to subprocess with a one-line warning, or silence it with `--no-daemon`. Forward support (2.4.x+) is re-evaluated at each Kotlin language release. Policy: ADR 0022.

### Pinning `[kotlin] compiler` independently of `version`

To target an older language surface (e.g. 2.1) while still running on the
daemon, split compiler and language: `version` stays the language/API version
(also what the lockfile records), and `compiler` pins the kotlinc/daemon
version — `compiler` defaults to `version` and must be `>= version`.

```toml
[kotlin]
version = "2.1.0"
compiler = "2.3.20"
```

When the two differ, kolt drives the `compiler` kotlinc and passes
`-language-version <major.minor>` / `-api-version <major.minor>` (derived
from `version` — kotlinc only accepts `major.minor`) to every compile so the
language surface stays pinned. Equal (or unset) `compiler` keeps the
flag-free original behavior.

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
