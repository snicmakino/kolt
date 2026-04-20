# Architecture

kolt is a Kotlin build tool distributed as a single Kotlin/Native binary. It reads a declarative `kolt.toml`, resolves dependencies from Maven repositories, and compiles via warm JVM compiler daemons over Unix sockets — falling back to direct subprocess invocation when a daemon is unavailable. JVM and native targets have their own daemons; they share the wire protocol and spawn pattern but run as independent JVM processes (ADR 0024).

## Components

```
┌─────────────────────────────────────────────┐
│              kolt (Kotlin/Native)            │
│                                             │
│  cli/        Command dispatch, --watch      │
│  config/     TOML parsing, KoltPaths        │
│  build/      Compile pipeline, build cache  │
│  resolve/    Maven/POM/Gradle metadata      │
│  daemon/     JVM daemon client              │
│  nativedaemon/ Native daemon wire codec     │
│  tool/       Toolchain install (kotlinc/JDK)│
│  infra/      Process, filesystem, inotify   │
└───┬─────────────────────────────────────┬───┘
    │ Unix socket (length-prefixed JSON)  │
┌───▼───────────────────┐   ┌─────────────▼─────────────┐
│  kolt-compiler-daemon │   │   kolt-native-daemon       │
│        (JVM)          │   │        (JVM)               │
│                       │   │                            │
│  server/   lifecycle  │   │  server/    lifecycle      │
│  protocol/ wire       │   │  protocol/  wire           │
│  ic/       BTA IC     │   │  compiler/  K2Native refl. │
│  reaper/   IC cleanup │   │                            │
└───────────────────────┘   └────────────────────────────┘
```

### Native client (`src/nativeMain/kotlin/kolt/`)

| Package | Responsibility |
|---------|---------------|
| `cli` | Parse args, dispatch commands (`build`, `run`, `test`, `check`, `init`, `clean`, `fmt`, `deps`, `daemon`, `toolchain`). Watch mode loop. |
| `config` | Parse `kolt.toml` into `KoltConfig`. Manage `~/.kolt/` paths, including both daemon socket paths. |
| `build` | Construct compiler commands for JVM and Native targets. `CompilerBackend` / `NativeCompilerBackend` abstractions with daemon and subprocess implementations. Build cache (mtime). Test compilation and JUnit Platform execution. |
| `build/daemon` | `DaemonCompilerBackend` — connect-or-spawn with exponential backoff. Daemon JAR and BTA impl JAR resolution. Bootstrap JDK provisioning. |
| `build/nativedaemon` | `NativeDaemonBackend` — same connect-or-spawn pattern for the native daemon. Konanc JAR discovery and daemon preconditions. |
| `nativedaemon/wire` | Frame codec and message types for the native daemon (`NativeCompile` / `NativeCompileResult`). Mirrors the JVM daemon protocol but with flat konanc arg lists instead of structured `Compile` fields. |
| `resolve` | Transitive dependency resolution via POM/Gradle module metadata. Lockfile (v2, SHA-256 hashes). Plugin JAR fetching. |
| `daemon` | JVM daemon wire codec and client-side framing (4-byte length + JSON). |
| `tool` | Download and manage kotlinc, konanc, and JDK under `~/.kolt/toolchains/`. |
| `infra` | `fork`/`execvp` process execution, `spawnDetached` (double-fork for daemon), Unix socket client, inotify, SHA-256, HTTP downloads (libcurl cinterop). |

### Compiler daemon (`kolt-compiler-daemon/`)

A JVM process that stays warm across builds for JVM-target compilation. Launched by the native client via double-fork, communicates over a Unix domain socket.

| Package | Responsibility |
|---------|---------------|
| `server` | Accept socket connections, dispatch `Compile` → `CompileResult`, manage lifecycle (idle timeout, max compiles, heap watermark). |
| `protocol` | Wire types mirroring the native side. Frame codec for Java NIO `SocketChannel`. Diagnostic parser for kotlinc stderr. |
| `ic` | `BtaIncrementalCompiler` — adapter over kotlin-build-tools-api. Per-project file locks, classpath snapshot cache, plugin translation. `SelfHealingIncrementalCompiler` wraps it with cache-wipe-and-retry on internal errors. |
| `reaper` | Background thread that removes stale IC state for projects no longer active. |

ClassLoader hierarchy in the JVM daemon:

```
daemon classloader
  └─ SharedApiClassesClassLoader (org.jetbrains.kotlin.buildtools.api.*)
      └─ URLClassLoader (kotlin-build-tools-impl + plugin JARs)
```

### Native compiler daemon (`kolt-native-daemon/`)

A peer JVM process for native-target compilation (ADR 0024). Same spawn / wire pattern as the JVM daemon, but loads `kotlin-native-compiler-embeddable.jar` and invokes `K2Native.exec(PrintStream, String[])` reflectively — there is no Build Tools API for native.

| Package | Responsibility |
|---------|---------------|
| `server` | Accept socket connections, dispatch `NativeCompile` → `NativeCompileResult`, manage lifecycle (idle timeout, max compiles, heap watermark). Single-threaded accept loop — K2Native is reused per daemon and is not safe under concurrent calls. |
| `protocol` | Wire types and frame codec (same 4-byte length + JSON framing as the JVM daemon). |
| `compiler` | `ReflectiveK2NativeCompiler` — loads konanc via a dedicated classloader and invokes `exec` reflectively. The instance is held for the daemon's lifetime. |

ClassLoader topology for the native daemon:

```
bootstrap classloader (JDK only)
  └─ URLClassLoader(arrayOf(konancUrl), null)   // konanc + its bundled stdlib
daemon classloader (separate, holds kolt-native-daemon code + kotlin-result)
```

The konanc classloader has a **null parent** — the daemon's own classpath (kotlin-result, kotlinx-serialization-json, daemon-core) is not visible to konanc, and konanc's bundled Kotlin stdlib / kotlinx-serialization don't leak back. This differs from the JVM daemon's shared-API hierarchy, which deliberately shares a stable API surface (`org.jetbrains.kotlin.buildtools.api.*`) with impl JARs. There is no equivalent API for native, so isolation is strict.

## Build flow

`kolt build` on a JVM target:

1. Parse `kolt.toml` → `KoltConfig`
2. Check build cache (source mtimes vs last build)
3. Resolve dependencies — read lockfile or run transitive resolution, download JARs to `~/.kolt/cache/`
4. Select compiler backend — try the JVM daemon (Unix socket), fall back to subprocess
5. Compile sources → `build/classes/`
6. Package → `build/{name}.jar` (fat JAR with `-include-runtime`)

For native targets, steps 4–6 follow the same shape but go through `NativeCompilerBackend`: resolve via `resolveNativeCompilerBackend` (daemon primary, `NativeSubprocessBackend` fallback), then run konanc twice — library stage (`-p library -nopack`) and link stage — both dispatched to the backend. The two-stage split stays to work around a konanc plugin quirk (ADR 0014); with the daemon rail, both stages hit the warm JVM instead of paying ~3s startup per invocation (ADR 0024 §5).

## Daemon lifecycle

Both daemons share the spawn and connect pattern; they differ in tuning.

- **Sockets**: `~/.kolt/daemon/<projectHash>/<kotlinVersion>/daemon.sock` (JVM) and `.../native-daemon.sock` (native). Co-locating them under the same version dir keeps `kolt daemon stop` to a single enumeration pass (ADR 0020 §2, ADR 0024).
- **Spawn**: Native client calls `spawnDetached()` (double-fork + setsid) from the matching backend.
- **Connect**: Client retries with exponential backoff (10–200 ms, 3 s budget).
- **Shutdown**: Idle timeout, max compile count reached, heap watermark exceeded, or explicit `kolt daemon stop`. Native daemon defaults are tighter (10 min idle vs 30 min; 2 GB heap watermark) because native builds are less frequent.
- **Stale cleanup**: `DaemonReaper` removes orphaned sockets and version directories for both daemons. `IcReaper` prunes unused BTA IC state for the JVM daemon only — the native daemon does not manage IC state (konanc handles its own `-Xic-cache-dir`).

## Dependency resolution

- Resolves transitive dependencies via BFS over POM metadata
- Supports Maven repositories, Gradle module metadata, version intervals, exclusions
- Global cache at `~/.kolt/cache/` (Maven-compatible layout)
- `kolt.lock` is the single source of truth for versions and SHA-256 hashes
- Hash mismatch → hard error (no silent re-download)

## Error handling

All functions return `Result<V, E>` (kotlin-result). Exceptions are prohibited.

Fallback chain for JVM compilation: daemon backend → subprocess backend, via `FallbackCompilerBackend`. The daemon's `SelfHealingIncrementalCompiler` wipes IC state and retries once on internal errors.

Fallback chain for native compilation: `FallbackNativeCompilerBackend` wraps `NativeDaemonBackend` over `NativeSubprocessBackend`. `isNativeFallbackEligible` decides per error whether to retry on the subprocess — infrastructure failures (connect refused, spawn failure, unexpected disconnect) fall back; genuine compilation errors do not, so the user sees konanc diagnostics without a duplicate subprocess run.

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
- [0024](adr/0024-native-compiler-daemon.md) — Native compiler daemon via reflective K2Native
