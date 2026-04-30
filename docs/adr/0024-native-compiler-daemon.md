---
status: accepted
date: 2026-04-19
---

# ADR 0024: Native compiler daemon via reflective K2Native in a sidecar JVM

## Summary

- The existing JVM daemon (ADR 0016) does not handle native compilation. Native builds shell out to `konanc` as a subprocess, paying ~3 s JVM startup per invocation plus ~5–6 s on the link stage. (§1)
- A separate sidecar JVM daemon (`kolt-native-daemon/`) loads `kotlin-native-compiler-embeddable.jar` and calls `K2Native.exec()` reflectively — confirmed safe at 100 invocations with no state leakage. Warm stage 1 drops from ~4.5 s to ~170–290 ms. (§2)
- The native daemon is a separate process from the JVM daemon per ADR 0020 §2: `konan.home` is JVM-global, the embeddable jars differ, and native build frequency is lower. Socket: `~/.kolt/daemon/<projectHash>/<kotlinVersion>/native-daemon.sock`. (§3)
- Wire protocol reuses ADR 0016's frame format (u32 length + JSON) with a new `NativeCompile { args: List<String> }` / `NativeCompileResult { exitCode: Int, stderr: String }` message pair. Args are passed as a flat list; no structured compilation API exists for native. (§4)
- Both build stages (library and link) go through the daemon; the client calls it twice per build as it currently calls `konanc` twice. (§5)
- IC flags (`-Xenable-incremental-compilation`, `-Xic-cache-dir`) pass through as konanc args; the daemon does not manage IC state. (§6)

## Context and Problem Statement

kolt's native build invokes `konanc` as a subprocess twice per build — once for library compilation (source → klib) and once for linking (klib → kexe). Each invocation pays a ~3 s fixed JVM startup cost. The link stage adds another ~5–6 s of JVM startup on top of LLVM backend work.

There is no Kotlin Build Tools API for native; `K2Native` must be invoked reflectively via `CLICompiler.exec(PrintStream, String[])`. Gradle KGP uses the same approach via `MainKt.daemonMain()` in an isolated classloader.

Spike #166 confirmed that `K2Native.exec()` can run repeatedly in a persistent JVM with no state leakage across 100 invocations. Steady-state stage 1 wall time drops from ~4.5 s (subprocess) to ~170–290 ms (warm JVM). Stage 2 saves ~5–6 s regardless of fixture size. Full measurements are in `spike/konanc-daemon/REPORT.md`.

## Decision Drivers

- Stage 1 and stage 2 wall time must drop materially for native builds; the ~3 s per-subprocess JVM startup is the target.
- Daemon failure must never break a build; the existing `konanc` subprocess path must be an unconditional fallback.
- The native daemon must not share a JVM with the compiler daemon — `konan.home` is JVM-global and the embeddable jars differ.

## Decision Outcome

Chosen: **separate sidecar JVM (`kolt-native-daemon/`) with reflective `K2Native.exec()`**, because the spike validated stability at 100 invocations and the stage 1/2 savings are large enough to justify a second daemon process.

### §1 Separate daemon process

Per ADR 0020 §2, the native daemon is a separate process from the JVM compiler daemon, for three reasons:

- `konan.home` is JVM-global (`System.getProperty("konan.home")`). Sharing a JVM with the JVM daemon would require classloader-level property isolation — possible but fragile.
- The JVM daemon loads `kotlin-compiler-embeddable.jar`; the native daemon loads `kotlin-native-compiler-embeddable.jar`. Mixing them is untested and unnecessary.
- Native builds are less frequent than JVM builds; the native daemon can have a shorter idle timeout without affecting JVM daemon availability.

### §2 Compiler entry point: K2Native.exec() reflectively

The daemon loads `kotlin-native-compiler-embeddable.jar` via `URLClassLoader`, instantiates `org.jetbrains.kotlin.cli.bc.K2Native`, and calls `exec(PrintStream, String[])` reflectively for each request. The `K2Native` instance is held for the daemon's lifetime (shared-loader model, per ADR 0016 §2).

`-Dkonan.home` is set at daemon JVM startup. `kotlin-native-compiler-embeddable.jar` is passed on the CLI (`--konanc-jar <path>`) and loaded via `URLClassLoader`; it is not linked into the daemon's own classpath.

Gradle KGP uses `MainKt.daemonMain()` instead of `K2Native.exec()`. The spike tested `exec()` directly across 100 invocations and confirmed stability. `daemonMain` is a thin wrapper; switching is a single-method change if needed.

### §3 Socket path and lifecycle

Socket: `~/.kolt/daemon/<projectHash>/<kotlinVersion>/native-daemon.sock`

This mirrors the JVM daemon's path convention (`daemon.sock` → `native-daemon.sock`), keeping both daemons under the same project hash directory.

Lifecycle config (initial values, tunable later):

| Parameter | Value | Rationale |
|---|---|---|
| Idle timeout | 10 min | Shorter than JVM daemon (30 min) — native builds are less frequent |
| Max compiles | 500 | Conservative; stress test showed no drift at 100 |
| Heap watermark | 2 GB | Stage 2 linking uses significant LLVM backend memory |
| `-Xmx` | 4 GB | Stress test ran cleanly at this limit; pinned as `NativeDaemonBackend.HEAP_CEILING_XMX` |

The daemon is spawned on demand by the native client and exits cleanly when any threshold is reached.

### §4 Wire protocol: new NativeCompile message, same frame format

Reuse ADR 0016's frame format: 4-byte big-endian length prefix + UTF-8 JSON body, `kotlinx.serialization`, `classDiscriminator = "type"`.

New message pair:

```
NativeCompile { args: List<String> }
NativeCompileResult { exitCode: Int, stderr: String }
```

Unlike `Message.Compile` (structured fields: workingDir, classpath, sources, outputPath, moduleName), `NativeCompile` passes konanc args as a flat list because `K2Native`'s API is `exec(PrintStream, String[])` — it only accepts CLI args. No BTA exists for native, so there is no structured compilation API to model. The native client already constructs full konanc arg lists in `Builder.nativeLibraryCommand` and `nativeLinkCommand`; forwarding them directly avoids a decompose/recompose round trip.

Diagnostics: stderr returned as a single string, same as the current subprocess path.

### §5 Two-stage build

Both stages go through the daemon:

1. **Stage 1 (library)**: `NativeCompile { args: ["-target", "linux_x64", <sources>, "-p", "library", "-nopack", "-Xenable-incremental-compilation", "-Xic-cache-dir=build/.ic-cache", "-o", "build/<name>-klib"] }`
2. **Stage 2 (link)**: `NativeCompile { args: ["-target", "linux_x64", "-p", "program", "-e", "<main>", "-Xinclude=build/<name>-klib", "-o", "build/<name>"] }`

The daemon treats both identically — it calls `K2Native.exec()` with whatever args it receives. Stage discrimination is the client's responsibility.

### §6 IC passthrough

IC flags (`-Xenable-incremental-compilation`, `-Xic-cache-dir`) are part of the konanc args the client sends. The daemon does not interpret, manage, or cache IC state — it is a transparent execution vehicle. IC cache lives on disk at `build/.ic-cache/` (managed by konanc itself). This differs from the JVM daemon (ADR 0019), where BTA manages IC state server-side; the difference is unavoidable as no BTA exists for native.

### §7 Fallback

`FallbackCompilerBackend(NativeDaemonBackend, NativeSubprocessBackend)` — same pattern as ADR 0016 §5. On any daemon failure (connect refused, spawn failure, unexpected disconnect), the client falls back to the existing `konanc` subprocess path. `CompileError.CompilationFailed` does not trigger fallback.

### §8 Daemon jar

`kolt-native-daemon/` is a new module: an independent Gradle build included via `includeBuild`, same pattern as `kolt-compiler-daemon/`.

## Consequences

**Positive**
- Stage 1 drops from ~4.5 s to ~200 ms (warm); ~3 s JVM startup tax eliminated.
- Stage 2 drops by ~5–6 s per invocation.
- Total native build saves ~8–10 s per warm invocation.
- Frame codec, socket lifecycle, and `FallbackCompilerBackend` pattern are battle-tested from ADR 0016.
- IC and daemon are orthogonal; IC flags pass through transparently.

**Negative**
- Two daemon processes per project; RSS is additive (~200 MB JVM daemon + ~300 MB native daemon). Most projects target one platform, so only one daemon runs in practice.
- Reflection on `K2Native` and `CLICompiler.exec()` — may break between Kotlin versions. Mitigated by version pinning (ADR 0022).
- No structured diagnostics; stderr is a blob, same as subprocess.

### Confirmation

Spike #166 validates `K2Native.exec()` stability at 100 invocations. Integration tests on every build must exercise at least one real native compile through the daemon. `kolt daemon stop` inherits support for the native daemon by walking `~/.kolt/daemon/<projectHash>/` without a hard-coded list.

## Alternatives considered

1. **Run K2Native inside the existing JVM daemon.** `konan.home` is JVM-global, the embeddable jar differs, and ADR 0020 §1 prohibits non-compile responsibilities for a different compiler entirely. Rejected.
2. **Use `MainKt.daemonMain()` instead of `K2Native.exec()`.** Deferred: the spike validated `exec()` at 100 invocations; `daemonMain` adds Xcode renderer support. Switching is a single-method change if needed.
3. **Structured `NativeCompile` message (sources, classpath, outputPath fields).** K2Native only accepts CLI args; decomposing into structured fields and recomposing back to args adds complexity with no benefit. Rejected.
4. **Share IC management with the daemon (like BTA in ADR 0019).** Not possible: no BTA for native; IC state is managed by konanc via CLI flags.

## Related

- ADR 0016 — JVM compiler daemon (wire protocol, lifecycle, fallback pattern)
- ADR 0019 — IC via BTA (JVM only; no native equivalent)
- ADR 0020 — compiler daemon scope (§2: second daemon for new use case)
- ADR 0022 — supported Kotlin version policy
- ADR 0026 — current daemon naming authority (see for new module names; this ADR's identifiers are preserved as historical record)
- ADR 0018 — distribution layout (native daemon jar bundled in tarball alongside JVM daemon)
- Spike #166 — `konanc` daemon feasibility (`spike/konanc-daemon/REPORT.md`)
- Spike #160 — native IC feasibility
