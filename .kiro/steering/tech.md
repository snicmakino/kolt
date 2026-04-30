# Technology Stack

## Architecture

kolt is a **native-first CLI with JVM sidecar daemons**. The user-facing binary is
Kotlin/Native (`kolt.kexe`, linuxX64); compilation work is delegated to JVM daemon
processes over Unix sockets to amortize kotlinc startup cost.

Self-host closed as of v0.14.0 (#97 / #228): both the native binary and the daemon
JARs build from their own `kolt.toml`s. The `self-host-post` CI job exercises this
path end-to-end (3× `kolt build` + `scripts/assemble-dist.sh` stitch + tarball
install + fixture smoke). Gradle is kept in parallel as the bootstrap path and as
the cross-check until v1.0.

Top-level build layout uses Gradle `includeBuild()` to decouple three pieces: the
root native project, `kolt-jvm-compiler-daemon/` (JVM daemon), and
`kolt-native-compiler-daemon/` (native-compiler sidecar).

## Core Technologies

- **Language**: Kotlin 2.3.x (pinned in both `build.gradle.kts` and `kolt.toml`). The
  Kotlin compiler version used for user builds is independently overridable via
  `[kotlin] compiler = "..."` so users can pin an older API.
- **JVM target**: JDK 25 for daemon JARs and Gradle builds.
- **Native target**: linuxX64 via Kotlin/Native (konanc). Two-stage library+link
  compile flow (ADR 0014) to keep plugin registrars working on native.

## Key Libraries

Only the libraries that shape development patterns — not the full dependency set.

- **kotlin-result 2.3.x** — `Result<V, E>` for all fallible paths. Exceptions are not
  the project convention (ADR 0001). Note: kotlin-result 2.x is a value class, so
  `is Ok` / `is Err` do not work — use `getOrElse`, `getError`, `isErr`.
- **libcurl cinterop** — HTTP in native contexts (ADR 0006, chosen over ktor-client).
- **libarchive cinterop** — toolchain archive extraction in native contexts (ADR 0031, replaces `unzip` / `tar` shell-outs).
- **kotlincrypto.hash sha2-256** — lockfile SHA-256 verification.
- **kotlinx.serialization** — JSON lockfile format, daemon wire protocol
  (`@Serializable` sealed Message types).
- **ktoml-core** — `kolt.toml` parsing.
- **kotlin.test** — test framework (`Test`, `assertEquals`, `assertIs`, etc.).

## Development Standards

### Error Handling
`Result<V, E>` is mandatory across layers. Do not throw. Domain errors are modelled
as sealed ADTs (e.g., `DaemonJarResolution`). The CLI's `main` returns an `ExitCode`
enum rather than raising.

### Formatting
**ktfmt** (user-selectable style) via `kolt fmt` / `kolt fmt --check`. No static
linter (no detekt) — formatter + compiler warnings are the quality gate.

### Testing
Tests live in `nativeTest/kotlin/` mirroring production package structure. `*Test.kt`
for unit, `*IntegrationTest.kt` for integration. Shared utilities go in
`testfixture` subpackages.

### Code Style
No wildcard imports — every symbol is imported explicitly. ADR numbers are cited in
comments when the code encodes a design decision that would otherwise read as
arbitrary (retry budgets, fallback policy, native link stages).

### Language Policy
Code, comments, commit messages, ADRs, and specification documents are written in
English. Conversation with contributors may be bilingual, but anything checked into
the repo is English-first.

## Development Environment

### Required Tools
- **JDK** (host or `[build] jdk`-managed). Daemon pins JDK 25.
- **kotlinc** — auto-downloaded to `~/.kolt/toolchains/kotlinc/{version}/` on first
  run; falls back to system kotlinc if present.
- **konanc** — auto-installed on first native build; also provisioned by Gradle
  during development.
- **libcurl** (Linux native build): `libcurl4-openssl-dev` headers.
- **libarchive** (Linux native build): `libarchive-dev` headers; `libarchive13` runtime.
- **Gradle 8.12** — only for building the daemon JARs / doing dev work; end users of
  kolt never see Gradle.

### Common Commands

User-facing CLI:
```
kolt init [name]                  # create project
kolt build    [--watch] [--release]   # compile (debug default; --release writes build/release/)
kolt run      [--watch] [--release]   # build + run
kolt test     [--watch] [--release]   # build + run tests
kolt check    [--watch] [--release]   # type-check only
kolt fmt      [--check]           # ktfmt format (or verify in CI)
kolt add <dep>                    # add dependency to kolt.toml
kolt deps [install|update|tree]   # resolve / inspect deps
kolt toolchain install            # provision kotlinc for pinned version
kolt daemon stop [--all]          # stop warm daemon
kolt daemon reap                  # clean stale sockets
```

`--release` is a Cargo-style opt-in profile: Native adds `-opt` and omits
`-g`; JVM is a declared no-op (same kotlinc args, same daemon IC path)
with the artifact moved to `build/release/<name>.jar`. Both profiles'
artifacts coexist on disk. See ADR 0030.

Development (for working on kolt itself):
```
kolt build                                                  # native binary
kolt test                                                   # unit tests
cd kolt-jvm-compiler-daemon && kolt build                   # JVM daemon thin jar
cd kolt-native-compiler-daemon && kolt build                # Native daemon thin jar

# Special-purpose (kept until kolt-native equivalents land):
./gradlew check                                             # tests + daemon version verification
./gradlew linkDebugExecutableLinuxX64 \
  && ./build/bin/linuxX64/debugExecutable/kolt.kexe build   # CI bootstrap self-host smoke
```

## Key Technical Decisions

Authoritative ADRs live in `docs/adr/`. The load-bearing ones:

- **ADR 0001** — `Result<V, E>` for all error paths; no exceptions.
- **ADR 0016** — Warm JVM compiler daemon with shared URLClassLoader.
- **ADR 0019** — Incremental JVM compilation via kotlin-build-tools-api (BTA).
- **ADR 0018** — Distribution layout and self-host path.
- **ADR 0024** — Native compiler daemon (proposed) for konanc warm path.
- **ADR 0030** — Build profiles: Cargo-style `--release` opt-in; debug default; Native routes `-opt` / `-g`; JVM no-op; `build/<profile>/` partition.

When changing code that encodes one of these decisions, update the ADR in the same
commit.
