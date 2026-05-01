---
status: implemented
date: 2026-04-14
---

# ADR 0016: Warm JVM compiler daemon with shared URLClassLoader

## Summary

- Ship `kolt-compiler-daemon`, a sidecar JVM that loads `kotlin-compiler-embeddable` via `URLClassLoader` and holds `K2JVMCompiler` for its lifetime. Compiler jars are passed on the CLI (`--compiler-jars`), not linked into the daemon's own classpath. (§1)
- Reuse the same `URLClassLoader` and `K2JVMCompiler` instance across every compile. Fresh-classloader-per-compile (scenario B) measured 2857 ms warm average — 2.6× over the 1000 ms budget — and is rejected. (§2)
- Transport is a Unix domain socket at `~/.kolt/daemon/<projectHash>/daemon.sock`. Frame format: big-endian u32 length prefix + UTF-8 JSON body; `classDiscriminator = "type"`; max body 64 MiB. One connection at a time (Phase A contract). (§3)
- Bound leaks with periodic restart: thresholds are 1000 compiles, 30 min idle, 1.5 GiB heap (live set via `MemoryMXBean`). `ExitReason` tags the cause on exit. (§4)
- The daemon is the default compile backend from day one (`FallbackCompilerBackend(DaemonCompilerBackend, SubprocessCompilerBackend)`). `--no-daemon` bypasses it per invocation. Only `BackendUnavailable` / `InternalMisuse` trigger fallback; `CompilationFailed` does not. (§5)
- Cold-spawn connect budget is 10s (was 3s through 2026-04-30). Budget covers cold spawn only — warm reconnects short-circuit before the retry loop and are unaffected. (§5)
- `kolt-compiler-daemon/` is an independent Gradle build included via `includeBuild`, not a subproject, to allow separate distribution per ADR 0018. (§6)

## Context and Problem Statement

A clean `kolt build` of a small project took ~8 s end-to-end before Phase A, with ~2.5 s attributable to JVM startup and `kotlin-compiler-embeddable` warmup on every invocation. The target for #14 Phase A was: clean build ~8 s → ~3 s, warm build under 1 s.

Four design questions had to be resolved: where the compiler runs (in-process, sidecar JVM, or JetBrains daemon), how to reuse it safely (shared state vs per-compile isolation), the IPC transport and wire format, and how to bound long-running memory leaks. None of these are derivable from the code; the benchmark numbers that justify the choices live in #86/#87, and this ADR is the written record.

The original §Context references an 8 s baseline and a sub-1 s warm target. The #96 scaling benchmark (below) revised both: subprocess baseline is 4.77 s at jvm-1 and 10.37 s at jvm-50; warm stays inside 1 s only below ~15–20 files. Both figures are retained as historical context; the scaling table below is the authoritative reference.

## Decision Drivers

- Warm `kolt build` wall time materially below 1 s for small projects; Phase B (#3) will address the per-file slope.
- Daemon failure must never break a build — the subprocess path must be an unconditional fallback.
- Wire format must be self-describing enough to debug with `od -c`; no binary protocol required.
- Leak bounds must be explicit and tunable without touching the architecture.

## Decision Outcome

Chosen: **sidecar JVM (`kolt-compiler-daemon`) with a shared `URLClassLoader`**, because the shared-loader scenario C (85 ms late-warm average, +9 MB heap over 50 iterations) is the only configuration that passes the warm-build target with bounded leak growth.

### §1 Sidecar JVM, compiler jars loaded via URLClassLoader

The daemon is a separate JVM process (`kolt-compiler-daemon`). Its Gradle `implementation` classpath does not include `kotlin-compiler-embeddable`; compiler jars are passed via `--compiler-jars <path>` and loaded into a `URLClassLoader` at startup. `K2JVMCompiler` is instantiated reflectively and held in `SharedCompilerHost`. The daemon process never links the compiler into its own classloader.

### §2 Shared URLClassLoader, reused across compiles

One `URLClassLoader` is created at daemon startup and never replaced. `K2JVMCompiler` is instantiated once; `CLICompiler.exec(PrintStream, String...)` is invoked reflectively for each `Compile` frame. No per-compile classloader churn, no per-compile `K2JVMCompiler` construction.

Benchmarks (OpenJDK 21.0.7 Corretto, Kotlin 2.3.20, single-file fixture):

| Scenario | n | cold | warm median | warm avg | heap Δ |
|---|---|---|---|---|---|
| A — shared URLClassLoader | 10 | 3147 ms | 250 ms | 233 ms | — |
| B — fresh URLClassLoader per compile | 10 | 2620 ms | 2645 ms | 2857 ms | — |
| **C — shared loader, long run** | 50 | 2634 ms | 108 ms | **123 ms** | **+9 MB** |

Late-warm average on C is **85 ms**. Scenario B fails the kill criterion at 2.86× budget; scenario C passes cleanly and is the configuration adopted.

### §3 Wire protocol: u32 length + JSON over Unix domain socket

Socket path: `~/.kolt/daemon/<projectHash>/daemon.sock`. One socket per project; path hash is derived from the absolute project path.

Wire format spec (sufficient to write a client without reading `FrameCodec.kt`):

- **Length prefix**: 4 bytes, big-endian unsigned int32, counts body bytes only.
- **Body encoding**: UTF-8 JSON, `kotlinx.serialization`, `Json { classDiscriminator = "type" }`.
- **Discriminator values**: `"Compile"`, `"CompileResult"`, `"Ping"`, `"Pong"`, `"Shutdown"`.
- **Max body**: `FrameCodec.MAX_BODY_BYTES = 64 MiB`. Both read and write paths reject frames exceeding this as `FrameError.Malformed`.
- **Concurrency**: `serve()` accepts one client at a time; multiple in-flight compiles on a single daemon never overlap. Phase A contract only — must be revisited before `kolt watch` (#15) lands.

Socket path lifecycle:

- The native client picks the socket path and passes it to the daemon via `--socket <path>`.
- The daemon creates missing parent directories (`Files.createDirectories`), removes any stale socket, and deletes the socket on clean exit.
- If the parent cannot be created, `DaemonServer.serve()` returns `Err(DaemonError.BindFailed)` before bind; no partial state is left.

Exit codes for the native client:

| Code | Meaning |
|------|---------|
| 0 | Clean exit: `Shutdown`, `IdleTimeout`, `MaxCompilesReached`, `HeapWatermarkReached` |
| 64 | Usage error: unknown flag, missing `--socket`, missing or empty `--compiler-jars` |
| 70 | Init failure: `SharedCompilerHost.create` returned `LoaderInitFailed` |
| 71 | Serve failure: `DaemonServer.serve()` returned `DaemonError` |

Client action: exit 0 → spawn fresh daemon next build; exit 64 → kolt bug, fall back; exit 70 → wrong compiler-jars path, fall back and warn; exit 71 → bind/cleanup failure, fall back, daemon will retry next build.

### §4 Periodic restart as leak safety net

`DaemonConfig` thresholds (defaults): 1000 compiles, 30 min idle, 1.5 GiB heap. When any threshold is exceeded the daemon exits cleanly with an `ExitReason`. The native client spawns a fresh daemon on the next build. Heap is sampled via `ManagementFactory.getMemoryMXBean().heapMemoryUsage.used` (live set after last GC, not committed-minus-free), so the watermark fires on actual retention, not allocation spikes.

### §5 Default-on with subprocess fallback

The daemon is the default backend from PR3 of #14. `kolt build` wires `FallbackCompilerBackend(DaemonCompilerBackend, SubprocessCompilerBackend)`. `BackendUnavailable` (missing JDK, unwritable daemon dir, stale socket, connect refused) and `InternalMisuse` (kolt bug) trigger fallback. `CompilationFailed` (user code errors) does not — the daemon's verdict is the build's verdict.

`reportFallback` emits one stderr line per fallback: `warning` for `BackendUnavailable.*`, `error` for `InternalMisuse`. `InternalMisuse` is deliberately loud so dogfooding surfaces bugs. `kolt build --no-daemon` bypasses the daemon for a single invocation; no `kolt.toml` knob exists.

The opt-in plan (ship as `kolt build --daemon`, flip default after spikes #88–#91) was abandoned because all its transient artifacts — flag, doc caveat, interim ADR status — would need reverting a release later.

Cold-spawn connect budget: `DaemonCompilerBackend.CONNECT_TOTAL_BUDGET_MS = 10_000`. The 3s default shipped with #14 false-positively fell back on cold-cache CI (#310): the §2 cold-start figure (~2.6s) plus the WSL2/CI 9p stat-storm outlier (~3.3s, see Benchmark notes) routinely cleared 3s before the daemon listened. The retry loop runs only after a spawn — warm reconnects succeed on the first `connector()` call and never enter `retryConnect()` — so the headroom is invisible to dev-loop builds. Cost: when the daemon is genuinely broken, fallback now takes up to ~10s per build; this is paid once because subsequent builds still re-spawn rather than wait, but the failure mode is louder than before.

### §6 Independent Gradle build via includeBuild

`kolt-compiler-daemon/` is an independent Gradle build included from the root via `includeBuild`, not `include`. `./gradlew build` rebuilds both via an explicit `dependsOn`; `DaemonJarResolver.kt` never sees a stale jar. This boundary enables the distribution plan in ADR 0018 and keeps the native/JVM build split clean.

## Benchmark results — Scaling (#96, 2026-04-14)

End-to-end `kolt build` wall time on `spike/bench-scaling/fixtures/jvm-{1,10,25,50}` (deterministic generator; `kotlin-stdlib`-only classpath; per file: a data class, a generic + reified inline function, a sealed `Op` hierarchy, lambdas with capture, cross-package import). Harness: `spike/bench-scaling/run-bench.sh`, N=10 per cell, warm modes discard first 5 runs. Wall time: `/usr/bin/time -f '%e'`. Numbers below are run 3; runs 1 and 2 are preserved in `results-2026-04-14-run1.md` / `run2.md`.

Medians, seconds (OpenJDK 21 Corretto, WSL2):

| Fixture | nodaemon | daemon-cold | daemon-warm | gradle-warm | nodaemon/warm | gradle/warm |
|---|---|---|---|---|---|---|
| jvm-1  | 4.77 | 4.86 | 0.70 | 1.17 | 6.8× | 1.67× |
| jvm-10 | 6.42 | 6.64 | 0.83 | 1.30 | 7.7× | 1.57× |
| jvm-25 | 8.30 | 8.23 | 1.16 | 1.49 | 7.2× | 1.28× |
| jvm-50 | 10.37 | 10.18 | 1.32 | 1.82 | 7.9× | 1.38× |

Key readings:

- **~7× stable across sizes.** Nodaemon/warm ratio is 6.8–7.9× with no monotone trend. Quote "~7×" outside the hello-world regime; the earlier 8.5× was inside it.
- **Daemon cold regression is zero.** Cold medians track nodaemon within ±0.25 s.
- **`warm < 1 s` holds below ~15–20 files.** Floor is ~0.70 s; per-file slope is ~12 ms. Phase B (#3) targets the slope.
- **Gradle does not cross daemon-warm.** Gradle-warm stays 1.28–1.67× slower; its per-file slope ((1.82 − 1.17) / 49 ≈ 13 ms/file) matches kolt's ~12 ms/file, so the gap is entirely in the fixed-cost floor (~1.17 s vs ~0.70 s). The claim "kolt amortises fixed cost faster on small projects" is supported; "kolt scales better than Gradle" on clean builds is not extrapolatable from this fixture family.
- **~3.3 s size-independent outlier.** 2–3 of 10 nodaemon and daemon-cold samples cluster ~3.3 s above median across all fixture sizes, with a constant delta (+3.40/+3.38/+3.26/+3.41 s), ruling out compile work. Leading hypothesis: WSL2 9p stat-storm on `kotlinc` startup (classpath scan, JMOD indexing). The `kill_daemon` poll hypothesis from run 2 is retracted (poll ceiling is ~2.3 s; warm-cell outliers occur where the poll never ran). Disambiguating requires phase-level self-timing plus `strace -c`; flagged for #88/#90.

Methodology limitations (not fixed): fixed block-per-cell mode ordering, no page-cache drop for daemon-cold, N=10 underpowered for bootstrapping, uncontrolled CPU governor on WSL2. Full raw tables in `spike/bench-scaling/results-2026-04-14.md`.

## Consequences

**Positive**
- Warm compiles drop to 85–250 ms (shared-loader scenario C), within the Phase A target for small projects.
- Cold-start cost (~2.6 s) is paid once per daemon lifetime.
- Every fallible daemon entry point returns `Result<V, E>` (ADR 0001): `SharedCompilerHost.create` → `Result<_, CompileHostError.LoaderInitFailed>`, `SharedCompilerHost.compile` → `Result<CompileOutcome, CompileHostError>`, `FrameCodec.readFrame`/`writeFrame` → `Result<_, FrameError>`, `DaemonServer.serve` → `Result<ExitReason, DaemonError>`. No exception escapes into the accept loop or CLI.
- Leak bounds are explicit and tunable in `DaemonConfig` without architecture changes.
- A broken daemon is never a broken build; worst case is the pre-daemon subprocess time.

**Negative**
- Reflection on `K2JVMCompiler` and `CLICompiler.exec` by string name. A compiler rename in Kotlin 2.4+ breaks at runtime, not compile time. Mitigated by version pinning and `SharedCompilerHostTest` (compiles Hello.kt on every build).
- Structured diagnostics deferred: Phase A returns `diagnostics = emptyList()` with stderr as a blob.
- One connection at a time: concurrent `kolt build` or future `kolt watch` on the same project serialises. Acceptable for Phase A; must be revisited before #15.
- Daemons accumulate under `~/.kolt/daemon/` and need a reaper eventually.
- Own protocol means no benefit from JetBrains daemon improvements. JSON is human-debuggable but adds microseconds per frame vs a binary protocol — acceptable at these message sizes.

### Confirmation

`SharedCompilerHostTest` compiles a real Hello.kt fixture on every build. The exit-code table in §3 is the contract `DaemonCompilerBackend` in the native client must implement; deviations are caught by integration tests and code review.

## Alternatives considered

1. **JNI in-process compiler.** Rejected: drags a JVM and full compiler into every native process; PSI/CoreEnvironment cache leaks become permanent because the kolt process itself must stay alive.
2. **JetBrains `kotlin-daemon-client`.** Rejected: internal-use Java RMI, historically unstable across Kotlin versions, launcher lifecycle not under kolt's control.
3. **Fresh `URLClassLoader` per compile.** Rejected on measurements: 2857 ms warm average (scenario B) is 2.86× over budget. Loading compiler jars dominates every compile and makes the daemon strictly worse than subprocess.
4. **TCP socket.** Rejected: port selection is racy, exposes the daemon to the local network by default. UDS uses filesystem permissions for access control; JDK 16+ makes it available without portability trade-off.
5. **Protobuf or binary framing.** Rejected: debugging value of JSON (`od -c` readable) outweighs bytes saved; `kotlinx.serialization` is already a transitive dependency.
6. **Warmer subprocess path.** Rejected: the cost is JVM + compiler startup — a subprocess-level optimisation cannot remove it.

## Related

- #14 — parent issue (Kotlin Compiler Daemon integration)
- #86 — Phase A scope and spike results; source of `SharedLoaderCompileDriver`
- #87 — compile-bench spike that produced the §2 numbers
- #96 — Phase A scaling benchmark (§Benchmark results — Scaling)
- #88–#91 — follow-up spikes (rotating fixtures, multi-module, long-run leak, concurrency); no longer gate rollout after default-on landed
- #3 — incremental compilation (Phase B); ADR 0019
- #15 — `kolt watch` (Phase C); concurrency constraint in §3
- ADR 0001 — `Result<V, E>` discipline applied throughout daemon
- ADR 0018 — distribution layout (§6 independent Gradle build enables this)
- ADR 0019 — incremental compilation (extends this ADR's compile backend)
- ADR 0020 — compiler daemon scope (caps what this daemon may absorb)
- ADR 0026 — current daemon naming authority (see for new module names; this ADR's identifiers are preserved as historical record)
- `spike/bench-scaling/results-2026-04-14.md` — full raw tables for §Benchmark results
