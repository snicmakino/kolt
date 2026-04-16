# Architecture

kolt is a Kotlin build tool distributed as a single Kotlin/Native binary. It reads a declarative `kolt.toml`, resolves dependencies from Maven repositories, and compiles via a warm JVM compiler daemon over Unix sockets — falling back to direct subprocess invocation when the daemon is unavailable.

## Components

```
┌─────────────────────────────────────────────┐
│              kolt (Kotlin/Native)            │
│                                             │
│  cli/        Command dispatch, --watch      │
│  config/     TOML parsing, KoltPaths        │
│  build/      Compile pipeline, build cache  │
│  resolve/    Maven/POM/Gradle metadata      │
│  daemon/     Unix socket client, wire codec │
│  tool/       Toolchain install (kotlinc/JDK)│
│  infra/      Process, filesystem, inotify   │
└────────────────┬────────────────────────────┘
                 │ Unix socket (length-prefixed JSON)
┌────────────────▼────────────────────────────┐
│       kolt-compiler-daemon (JVM)            │
│                                             │
│  server/     Socket listener, lifecycle     │
│  protocol/   Wire types, frame codec        │
│  ic/         BTA incremental compiler       │
│  reaper/     Stale IC state cleanup         │
└─────────────────────────────────────────────┘
```

### Native client (`src/nativeMain/kotlin/kolt/`)

| Package | Responsibility |
|---------|---------------|
| `cli` | Parse args, dispatch commands (`build`, `run`, `test`, `check`, `init`, `clean`, `fmt`, `deps`, `daemon`, `toolchain`). Watch mode loop. |
| `config` | Parse `kolt.toml` into `KoltConfig`. Manage `~/.kolt/` paths. |
| `build` | Construct compiler commands for JVM and Native targets. `CompilerBackend` abstraction with daemon and subprocess implementations. Build cache (mtime). Test compilation and JUnit Platform execution. |
| `resolve` | Transitive dependency resolution via POM/Gradle module metadata. Lockfile (v2, SHA-256 hashes). Plugin JAR fetching. |
| `daemon` | `DaemonCompilerBackend` — connect-or-spawn with exponential backoff. Frame codec (4-byte length + JSON). Daemon JAR and BTA impl JAR resolution. Bootstrap JDK provisioning. |
| `tool` | Download and manage kotlinc, konanc, and JDK under `~/.kolt/toolchains/`. |
| `infra` | `fork`/`execvp` process execution, `spawnDetached` (double-fork for daemon), Unix socket client, inotify, SHA-256, HTTP downloads (libcurl cinterop). |

### Compiler daemon (`kolt-compiler-daemon/`)

A JVM process that stays warm across builds. Launched by the native client via double-fork, communicates over a Unix domain socket.

| Package | Responsibility |
|---------|---------------|
| `server` | Accept socket connections, dispatch `Compile` → `CompileResult`, manage lifecycle (idle timeout, max compiles, heap watermark). |
| `protocol` | Wire types mirroring the native side. Frame codec for Java NIO `SocketChannel`. Diagnostic parser for kotlinc stderr. |
| `ic` | `BtaIncrementalCompiler` — adapter over kotlin-build-tools-api. Per-project file locks, classpath snapshot cache, plugin translation. `SelfHealingIncrementalCompiler` wraps it with cache-wipe-and-retry on internal errors. |
| `reaper` | Background thread that removes stale IC state for projects no longer active. |

ClassLoader hierarchy in the daemon:

```
daemon classloader
  └─ SharedApiClassesClassLoader (org.jetbrains.kotlin.buildtools.api.*)
      └─ URLClassLoader (kotlin-build-tools-impl + plugin JARs)
```

## Build flow

`kolt build` on a JVM target:

1. Parse `kolt.toml` → `KoltConfig`
2. Check build cache (source mtimes vs last build)
3. Resolve dependencies — read lockfile or run transitive resolution, download JARs to `~/.kolt/cache/`
4. Select compiler backend — try daemon (Unix socket), fall back to subprocess
5. Compile sources → `build/classes/`
6. Package → `build/{name}.jar` (fat JAR with `-include-runtime`)

For Native targets, step 5–6 use konanc in two stages (library then link) to work around a konanc plugin quirk (ADR 0014).

## Daemon lifecycle

- **Spawn**: Native client calls `spawnDetached()` (double-fork + setsid). Daemon binds to `~/.kolt/daemon/{projectHash}/daemon.sock`.
- **Connect**: Client retries with exponential backoff (10–200 ms, 3 s budget).
- **Shutdown**: Idle timeout, max compile count reached, heap watermark exceeded, or explicit `kolt daemon stop`.
- **Stale cleanup**: `DaemonReaper` removes orphaned sockets and directories. `IcReaper` prunes unused IC state.

## Dependency resolution

- Resolves transitive dependencies via BFS over POM metadata
- Supports Maven repositories, Gradle module metadata, version intervals, exclusions
- Global cache at `~/.kolt/cache/` (Maven-compatible layout)
- `kolt.lock` is the single source of truth for versions and SHA-256 hashes
- Hash mismatch → hard error (no silent re-download)

## Error handling

All functions return `Result<V, E>` (kotlin-result). Exceptions are prohibited.

Fallback chain for compilation: daemon backend → subprocess backend. The daemon's `SelfHealingIncrementalCompiler` wipes IC state and retries once on internal errors.

## Out of scope

- **Task runner** — No custom command definitions (npm scripts style). Build lifecycle hooks are a separate discussion ([#119](https://github.com/snicmakino/kolt/issues/119)).
- **Plugin system** — No in-process extension API. kolt is a Kotlin/Native binary and cannot load JVM plugins. See [ADR 0021](adr/0021-no-plugin-system.md).
- **Kotlin Multiplatform (KMP)** — Managing multiple targets in a single project. High complexity, not a differentiator from Gradle.
- **Android** — Reimplementing AGP is not realistic.
- **IDE integration** — IntelliJ/VSCode plugins. The tool itself comes first.

## ADRs

Architectural decisions are recorded in `docs/adr/`. Key ones:

- [0001](adr/0001-result-type-error-handling.md) — Result type error handling
- [0004](adr/0004-pure-io-separation.md) — Pure/IO separation in resolver
- [0016](adr/0016-warm-jvm-compiler-daemon.md) — Warm JVM compiler daemon
- [0019](adr/0019-incremental-build-kotlin-build-tools-api.md) — Incremental build via BTA
- [0021](adr/0021-no-plugin-system.md) — No plugin system
