# ADR 0024: Native compiler daemon via reflective K2Native in a sidecar JVM

## Status

Proposed (2026-04-19).

## Summary

- The existing JVM compiler daemon (ADR 0016) does not handle native compilation. Native builds shell out to `konanc` as a subprocess, paying ~3s JVM startup per invocation plus ~5–6s on the link stage (§1).
- A separate sidecar JVM daemon loads `kotlin-native-compiler-embeddable.jar` and calls `K2Native.exec()` reflectively — the same approach Gradle KGP uses, confirmed safe at 100 invocations with no state leakage (§2).
- The native daemon is a **separate process** from the JVM daemon, per ADR 0020 §2. It gets its own socket, lifecycle, and fallback path (§3).
- The wire protocol reuses ADR 0016's frame format (`u32 length + JSON`) with a new `NativeCompile` message that carries konanc args directly, not the structured `Compile` fields (§4).
- Both build stages (library + link) go through the daemon. The native client calls the daemon twice per build, same as it currently calls `konanc` twice (§5).
- IC flags (`-Xenable-incremental-compilation`, `-Xic-cache-dir`) are passed through as konanc args. The daemon does not manage IC state (§6).

## Context

kolt's native build invokes `konanc` as a subprocess twice per build: once for library compilation (source → klib) and once for linking (klib → kexe). Each invocation pays a ~3s fixed JVM startup cost. The link stage adds another ~5–6s of JVM startup on top of LLVM backend work.

Spike #166 confirmed that K2Native can run repeatedly in a persistent JVM with no state leakage across 100 invocations. Steady-state stage 1 wall time drops from ~4.5s (subprocess) to ~170–290ms (warm JVM). Stage 2 saves ~5–6s regardless of fixture size.

There is no Kotlin Build Tools API for native — `K2Native` must be invoked reflectively via `CLICompiler.exec(PrintStream, String[])`. Gradle KGP does the same, using `MainKt.daemonMain()` in an isolated classloader.

## Decision

### §1 Separate daemon process

Per ADR 0020 §2, the native daemon is a separate process from the JVM compiler daemon. Rationale:

- **`konan.home` is JVM-global.** `K2Native` reads `System.getProperty("konan.home")`. Sharing a JVM with the JVM daemon would require classloader-level property isolation — possible but fragile.
- **Different jar.** The JVM daemon loads `kotlin-compiler-embeddable.jar`; the native daemon loads `kotlin-native-compiler-embeddable.jar`. Mixing them in one classloader is untested and unnecessary.
- **Independent lifecycle.** Native builds are less frequent than JVM builds for most projects. The native daemon can have a shorter idle timeout without affecting JVM daemon availability.

### §2 Compiler entry point

The daemon loads `kotlin-native-compiler-embeddable.jar` via `URLClassLoader`, instantiates `org.jetbrains.kotlin.cli.bc.K2Native`, and calls `exec(PrintStream, String[])` reflectively for each request. The `K2Native` instance is held for the daemon's lifetime (shared-loader model, same as ADR 0016 §2).

Alternative: Gradle KGP uses `MainKt.daemonMain()` instead of `K2Native.exec()`. The spike tested `exec()` directly across 100 invocations and confirmed stability. `daemonMain` is a thin wrapper; switching to it later is a single-method change if needed.

### §3 Socket path and lifecycle

Socket path: `~/.kolt/daemon/<projectHash>/<kotlinVersion>/native-daemon.sock`

This mirrors the JVM daemon's path convention (`daemon.sock` → `native-daemon.sock`), keeping both daemons under the same project hash directory.

Lifecycle config (initial values, tunable later):

| Parameter | Value | Rationale |
|-----------|-------|-----------|
| Idle timeout | 10 min | Shorter than JVM daemon (30 min) — native builds are less frequent |
| Max compiles | 500 | Conservative; stress test showed no drift at 100 |
| Heap watermark | 2 GB | Stage 2 linking uses significant LLVM backend memory |
| `-Xmx` | 4 GB | Stress test ran cleanly at this limit |

The daemon is spawned on demand by the native client (same pattern as JVM daemon) and exits cleanly when any threshold is reached.

### §4 Wire protocol

Reuse ADR 0016's frame format: 4-byte big-endian length prefix + UTF-8 JSON body, `kotlinx.serialization` with `classDiscriminator = "type"`.

New message type for native compilation:

```
NativeCompile { args: List<String> }
NativeCompileResult { exitCode: Int, stderr: String }
```

Unlike `Message.Compile` (which carries structured fields: workingDir, classpath, sources, outputPath, moduleName), `NativeCompile` passes konanc args as a flat list. Rationale:

- K2Native's API is `exec(PrintStream, String[])` — it only accepts CLI args.
- No BTA exists for native, so there is no structured compilation API to model.
- The native client already constructs full konanc arg lists in `Builder.nativeLibraryCommand` and `nativeLinkCommand`. Forwarding them directly avoids a decompose/recompose round trip.

Diagnostics: stderr is returned as a single string (same as the current subprocess path). Structured diagnostic parsing is deferred — the native compiler's stderr format is the same as subprocess, so no user-visible regression.

### §5 Two-stage build

Both stages go through the daemon:

1. **Stage 1 (library)**: `NativeCompile { args: ["-target", "linux_x64", <sources>, "-p", "library", "-nopack", "-Xenable-incremental-compilation", "-Xic-cache-dir=build/.ic-cache", "-o", "build/<name>-klib"] }`
2. **Stage 2 (link)**: `NativeCompile { args: ["-target", "linux_x64", "-p", "program", "-e", "<main>", "-Xinclude=build/<name>-klib", "-o", "build/<name>"] }`

The daemon treats both identically — it just calls `K2Native.exec()` with whatever args it receives. Stage discrimination is the client's responsibility.

### §6 IC passthrough

IC flags (`-Xenable-incremental-compilation`, `-Xic-cache-dir`) are part of the konanc args the client sends. The daemon does not interpret, manage, or cache IC state — it is a transparent execution vehicle. IC cache lives on disk at `build/.ic-cache/` (managed by the konanc compiler itself).

This differs from the JVM daemon (ADR 0019), where BTA manages IC state server-side. The difference is unavoidable: BTA does not exist for native.

### §7 Fallback

Same pattern as ADR 0016 §5: `FallbackCompilerBackend(NativeDaemonBackend, NativeSubprocessBackend)`. On any daemon failure (connect refused, spawn failure, unexpected disconnect), the client falls back to the existing `konanc` subprocess path. `CompileError.CompilationFailed` (real compilation errors) does not trigger fallback.

### §8 Daemon jar

The native daemon is a new module: `kolt-native-daemon/`, an independent Gradle build included via `includeBuild` (same pattern as `kolt-compiler-daemon/`). Its `implementation` classpath is minimal; `kotlin-native-compiler-embeddable.jar` is passed on the CLI (`--konanc-jar <path>`) and loaded via `URLClassLoader`.

System property `-Dkonan.home` is set at daemon JVM startup.

## Consequences

### Positive

- **Stage 1 drops from ~4.5s to ~200ms.** The ~3s JVM startup tax is eliminated.
- **Stage 2 drops by ~5–6s.** Link time goes from ~18s to ~12s on native-10 (warm).
- **Total native build saves ~8–10s per invocation.** For a touch-build (IC handles stage 1 skip, only link runs), the saving is ~5–6s.
- **Wire protocol and fallback reuse.** Frame codec, socket lifecycle, and `FallbackCompilerBackend` pattern are battle-tested from ADR 0016.
- **IC and daemon are orthogonal.** IC flags pass through transparently. Neither depends on the other.

### Negative

- **Two daemon processes per project.** RSS is additive (~200MB JVM daemon + ~300MB native daemon). Most projects target one platform, so only one daemon runs in practice.
- **Reflection on internal APIs.** `K2Native` and `CLICompiler.exec()` may change between Kotlin versions. Mitigated by Kotlin version pinning (ADR 0022).
- **No structured diagnostics.** stderr is a blob, same as subprocess. Parsing konanc's error format is deferred.

### Neutral

- **`kolt daemon stop` must enumerate both daemons.** ADR 0020 §2 already anticipated this. Both live under `~/.kolt/daemon/<projectHash>/` — stop iterates the directory.

## Alternatives Considered

1. **Run K2Native inside the existing JVM daemon.** Rejected: `konan.home` is JVM-global, different embeddable jar, ADR 0020 §1 prohibits non-compile responsibilities and this is a different compiler entirely.

2. **Use `MainKt.daemonMain()` instead of `K2Native.exec()`.** Deferred: the spike validated `exec()` at 100 invocations. `daemonMain` is a thin wrapper that adds Xcode renderer support. Switching is a single-method change if needed.

3. **Structured `NativeCompile` message (sources, classpath, outputPath fields).** Rejected: K2Native only accepts CLI args. Decomposing into structured fields and recomposing back to args adds complexity with no benefit.

4. **Share IC management with the daemon (like BTA in ADR 0019).** Not possible: no BTA for native. IC state is managed by konanc itself via CLI flags.

## Related

- ADR 0016 — JVM compiler daemon (wire protocol, lifecycle, fallback pattern)
- ADR 0019 — incremental compilation via BTA (JVM only)
- ADR 0020 — compiler daemon scope (§2: second daemon for new use case)
- ADR 0022 — supported Kotlin version policy
- Spike #166 — konanc daemon feasibility (REPORT.md in `spike/konanc-daemon/`)
- Spike #160 — native IC feasibility
