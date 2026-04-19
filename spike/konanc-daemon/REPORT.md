# Spike #166: konanc daemon feasibility

## Summary

- K2Native can run as a persistent JVM process. `K2Native.exec()` is safe to call repeatedly in the same JVM — no state leakage observed across 4 consecutive invocations per fixture (§1).
- Stage 1 (source → klib): warm JVM eliminates ~2.4–4.0s of fixed JVM startup cost, consistent across fixture sizes (§2).
- Stage 2 (klib → kexe, linking): 6.4s saved on native-10. Only one fixture size measured — stage 2 size scaling is unknown (§2).
- Both stages produce correct output: the linked binary runs and prints expected results (§2).
- No BTA (kotlin-build-tools-api) equivalent exists for native — the daemon must invoke K2Native reflectively via `CLICompiler.exec(PrintStream, String[])` (§3).
- 100-invocation stress test passed: no state leakage, heap bounded, wall time stable at 166–550ms after warmup (§6).
- Stage 2 savings are size-independent: ~5–6s fixed JVM startup cost across native-{10,25,50} (§6).
- Gradle KGP uses `MainKt.daemonMain()` via isolated classloader, not `K2Native.exec()` directly (§3b).
- Recommendation: **all prerequisites cleared** (§5). Proceed to daemon ADR and implementation.

## §1 Feasibility

K2Native (`org.jetbrains.kotlin.cli.bc.K2Native`) extends `CLICompiler`, which exposes `exec(PrintStream, String[]): ExitCode`. This is the same class hierarchy as `K2JVMCompiler`.

The prototype loads `kotlin-native-compiler-embeddable.jar` (the single jar shipped with Kotlin/Native at `$KONAN_HOME/konan/lib/`) and calls `exec()` repeatedly with the same arguments. Required JVM properties: `-Dkonan.home=$KONAN_HOME`.

All invocations returned `ExitCode.OK`. The output klib and kexe are identical to subprocess-produced artifacts.

## §2 Measurements

### Stage 1: source → klib

| size | cold subprocess median (ms) | warm-hot best (ms) | saved (ms) | speedup |
|------|----------------------------:|--------------------:|-----------:|--------:|
| 1    | 3,379                       | 432                 | 2,947      | 7.8x    |
| 10   | 4,584                       | 620                 | 3,964      | 7.4x    |
| 25   | 5,137                       | 2,757               | 2,380      | 1.9x    |
| 50   | 6,220                       | 3,796               | 2,424      | 1.6x    |

Absolute savings are 2.4–4.0s regardless of fixture size. The warm JVM eliminates a ~3s fixed JVM startup cost; the speedup ratio varies because the compilation work itself grows with file count.

JIT warmup is visible: run 2 is slower than runs 3–4 (e.g. native-1: 673 → 432 → 458ms).

### Stage 2: klib → kexe (native-10)

| mode | wall (ms) |
|------|----------:|
| cold subprocess | 17,987 |
| warm-cold (1st in JVM) | 16,243 |
| warm-hot (2nd in JVM) | 11,629 |

Stage 2 is link-dominated (LLVM backend), so the JVM warmup benefit is smaller but still meaningful: **6.4s saved** per link on native-10.

Only native-10 was measured for stage 2. Size 25/50 data would clarify whether the 6.4s saving is size-independent (fixed JVM cost) or scales with link work. This is a gap worth filling before implementation.

### Correctness

Binary output from reflective build: `bench -764012367 229 577029334 23` — matches subprocess-built binary.

## §3 Architecture implications

### What works (reuse from ADR 0016)

- **Wire protocol**: `Message.Compile` / `CompileResult` over Unix domain socket — no change needed. The daemon dispatches to K2Native instead of BTA based on a target discriminator.
- **Socket lifecycle**: spawn-on-demand, idle timeout, max-compiles/heap watermark — all applicable.
- **Fallback**: `FallbackCompilerBackend` pattern (daemon → subprocess) works unchanged.

### What differs from JVM daemon

| Concern | JVM daemon (current) | Native daemon (proposed) |
|---------|---------------------|-------------------------|
| Compiler entry | BTA `CompilationService` (structured API) | `K2Native.exec(PrintStream, String[])` (CLI API) |
| Incremental compilation | BTA-managed, daemon-internal | Not available via BTA; would need `-Xenable-incremental-compilation` flag |
| Diagnostics | BTA provides structured errors | Must parse stderr (same as subprocess path) |
| Classpath | `kotlin-compiler-embeddable.jar` | `kotlin-native-compiler-embeddable.jar` |
| System property | none | `-Dkonan.home` required |

### Key risk: `-Dkonan.home` is JVM-global

`konan.home` is read via `System.getProperty()`. If the JVM hosts both JVM and native daemon, the property must either be set once at startup (requiring the native toolchain path at daemon launch time) or use classloader isolation. The simplest path: **separate daemon processes** for JVM and native, each with its own jar and system properties.

### Two-stage build

kolt's native build is two stages (library + link). Both stages call `konanc` with different `-p` flags. The daemon can handle both — the prototype confirmed this. The wire protocol's `Message.Compile` already carries args generically.

## §3b Gradle KGP reference

Gradle's Kotlin/Native compilation ("konanc daemon") is not a standalone daemon process. KGP loads `kotlin-native-compiler-embeddable.jar` in an isolated, cached `URLClassLoader` inside the Gradle daemon JVM and calls `MainKt.daemonMain()` via reflection. Key points:

- **Entry point**: `org.jetbrains.kotlin.cli.utilities.MainKt.daemonMain`, not `K2Native.exec()` directly.
- **`konan.home` resolution**: not set as a JVM-global system property — the compiler detects it from the classloader's resource location.
- **Classloader isolation**: `ClassLoadersCachingBuildService` caches the `URLClassLoader` across builds, avoiding repeated class loading.
- **Out-of-process fallback**: `kotlin.native.disableCompilerDaemon=true` spawns a separate JVM via `javaexec()`.
- **Two tasks**: `KotlinNativeCompile` (klib) and `KotlinNativeLink` (kexe), both using the same `KotlinNativeToolRunner`.

Source: `libraries/tools/kotlin-gradle-plugin/src/common/kotlin/org/jetbrains/kotlin/internal/compilerRunner/native/`.

## §4 Blockers and risks

1. **No BTA for native**: the daemon must use the CLI reflection path. This means no structured incremental compilation API — IC would need `-Xenable-incremental-compilation` as a CLI flag, with cache management in the daemon or client.
2. **API stability**: `K2Native` and `CLICompiler.exec()` are internal APIs. JetBrains may change them between Kotlin versions. Mitigation: pin to specific Kotlin versions (already done per ADR 0022).
3. ~~**State leakage at scale**~~: **resolved** — 100-invocation stress test (§6) showed no leakage. All 100 returned OK, wall time stable at 170–290ms after warmup, heap bounded (58–290MB, no monotonic growth).
4. **Memory**: `kotlin-native-compiler-embeddable.jar` is 60MB. Combined with LLVM backend memory usage, the daemon may need `-Xmx4G` or higher. Stress test ran at `-Xmx4G` without issue.

## §5 Recommendation

**Feasibility confirmed.** K2Native runs repeatedly in a persistent JVM with correct output and meaningful speedup (~3s fixed cost eliminated).

Prerequisites (all cleared):

1. ~~**Native IC ships first.**~~ Done — IC shipped and dogfooded.
2. ~~**Stress test (≥100 invocations)**~~ Done — §6 confirms no state leakage at 100 invocations.
3. ~~**Stage 2 size scaling**~~ Done — §6 confirms savings are size-independent (~5–6s fixed cost).
4. **Architectural questions** around the second daemon process to be addressed in a daemon ADR: socket paths, daemon reaper, fallback backend, observability, `kolt daemon stop --all` enumeration.

## §6 Stress test results (2026-04-19)

### 100-invocation stage 1 (source → klib, native-10)

100/100 OK. No exceptions. `-Xmx4G`, no `-XX:TieredStopAtLevel=1` (C2 JIT allowed to warm up).

| phase | runs | wall range (ms) |
|-------|------|-----------------|
| cold (run 1) | 1 | 5,448 |
| JIT warmup (2–10) | 9 | 466–1,459 |
| steady state (11–100) | 90 | 166–550 |

Heap oscillated 58–290MB with no monotonic growth — GC reclaimed normally across all 100 invocations.

### Stage 2 size scaling (klib → kexe)

| size | cold subprocess (ms) | warm-hot best (ms) | saved (ms) | speedup |
|------|---------------------:|--------------------:|-----------:|--------:|
| 10   | 16,082               | 10,953              | 5,129      | 1.5x    |
| 25   | 18,411               | 11,932              | 6,479      | 1.5x    |
| 50   | 19,869               | 14,545              | 5,324      | 1.4x    |

Stage 2 savings are **size-independent** (~5–6s fixed JVM startup cost), confirming the §2 hypothesis. Link work itself scales with size (10,953 → 14,545ms).

## Raw data

- `results-2026-04-19.md` — original 4-invocation spike
- `results-stress-2026-04-19.md` — 100-invocation stress test + stage 2 scaling
