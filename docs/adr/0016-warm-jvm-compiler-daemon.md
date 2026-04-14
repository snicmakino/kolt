# ADR 0016: Warm JVM compiler daemon with shared URLClassLoader

## Status

Accepted (2026-04-13). Updated 2026-04-14 for #14 PR3: the native-side
client landed on branch `14/daemon-native-client`, and §5 has been
rewritten to reflect the revised Phase A rollout — the daemon is the
**default** compile backend, with the subprocess compile path retained
only as a fallback. The JVM-side daemon subproject (`kolt-compiler-daemon/`)
and wire protocol landed in PR2 (#86, merged); the native client,
`FallbackCompilerBackend`, and `--no-daemon` escape hatch land in PR3
(#14). Also updated 2026-04-14: `kolt-compiler-daemon/` is now an
**independent Gradle build** included from the root via `includeBuild`
rather than a subproject via `include`. The wire-protocol and runtime
decisions below are unchanged; the split is a build-system boundary
that sets up the distribution plan in ADR 0018 and keeps a future
self-host path (native built by kolt, daemon jar built by Gradle →
eventually by `kolt build --fat-jar`) open. `./gradlew build` still
rebuilds both sides via an explicit `dependsOn` from the root build
to the included build's `:build` task, so the dev-fallback path in
`DaemonJarResolver.kt` never sees a stale jar.

## Context

A clean `kolt build` of a small project currently takes about 8 seconds
end-to-end. Profiling shows roughly 2.5 of those seconds are spent
**starting a fresh JVM, loading `kotlin-compiler-embeddable`, and
warming `K2JVMCompiler` to the point where it can emit a single class
file**. Every invocation of `kolt build` pays that cost from scratch,
because kolt today shells out to `java -jar kotlin-compiler.jar` via a
short-lived subprocess. For a tool whose pitch is "lightweight Kotlin
builds", a 2.5 s fixed tax on the happy path is unacceptable.

The target for #14 Phase A is: **clean build ~8 s → ~3 s, with any
subsequent `kolt build` completing in under a second once the daemon is
warm**. Hitting that target required resolving four design questions:

1. **Where does the compiler run?** In the kolt native process (via
   JNI), in a sidecar JVM we own, or in the JetBrains-provided Kotlin
   compiler daemon?
2. **Within the sidecar, how do we reuse the compiler safely?** Recreate
   it per compile (isolation), or hold one compiler instance across
   every compile (reuse)?
3. **How does the native client talk to the sidecar?** What transport,
   what framing, what message shape?
4. **How do we protect against long-running leaks?** Kotlin compiler
   instances are famous for retaining PSI caches, CoreEnvironment
   state, and ClassLoader roots. A daemon that runs for days must not
   grow unbounded.

The answers shape every piece of `kolt-compiler-daemon/` and the native
`CompilerBackend` interface that will sit in front of it. They are not
derivable from the code: the benchmark numbers that justify them live
only in #86, and without a written record the next person (or the next
Claude session) will relitigate them.

## Decision

### 1. Helper JVM + `kotlin-compiler-embeddable`, loaded directly

kolt ships a small sidecar JVM (`kolt-compiler-daemon`) that the native
process spawns on demand. The sidecar `implementation` classpath does
**not** include `kotlin-compiler-embeddable`; instead, the compiler jars
are passed on the CLI (`--compiler-jars <path>`) and loaded into a
dedicated `URLClassLoader` at daemon startup. `K2JVMCompiler` is
instantiated reflectively and held for the daemon's lifetime. The
daemon process never links the compiler against its own classloader.

### 2. One shared `URLClassLoader`, reused across every compile

A single `URLClassLoader` is created once at daemon startup and reused
for every `Compile` frame. `K2JVMCompiler` is instantiated once, held
in `SharedCompilerHost`, and its `exec(PrintStream, String...)` method
is invoked reflectively for each request. There is no per-compile
classloader churn and no per-compile `K2JVMCompiler` construction.

### 3. Length-prefix + JSON body over a Unix domain socket

The wire protocol is a stream of frames, each a big-endian u32 length
followed by a UTF-8 JSON body serialised by `kotlinx.serialization`.
The message types are `Compile`, `CompileResult`, `Ping`, `Pong`, and
`Shutdown`. Transport is a Unix domain socket
(`java.net.UnixDomainSocketAddress`, JDK 16+), one socket per project
under `~/.kolt/daemon/<projectHash>/daemon.sock`. Diagnostics are
serialised as `{severity, file, line, column, message}` inside
`CompileResult`.

#### Wire format (reproducible by a stranger)

A native client that wants to talk to the daemon without reading
`FrameCodec.kt` needs exactly these four facts:

- **Length prefix**: 4 bytes, **big-endian unsigned int32**. The
  prefix counts body bytes only.
- **Body encoding**: UTF-8 JSON, `kotlinx.serialization` output.
- **Sealed discriminator**: the JSON object carries a `"type"` field
  whose value is the serial name of the variant — `"Compile"`,
  `"CompileResult"`, `"Ping"`, `"Pong"`, `"Shutdown"`. The codec is
  configured with `Json { classDiscriminator = "type" }`.
- **Maximum body size**: `FrameCodec.MAX_BODY_BYTES = 64 MiB`. Frames
  larger than this are rejected as `FrameError.Malformed` on both the
  write and read paths. A client that receives a frame longer than
  its own limit should treat it symmetrically (reject and close the
  connection), not truncate.
- **Concurrency**: the daemon serialises connections — `serve()`
  accepts one client at a time and runs `handleConnection` to
  completion before the next `accept()`. A client must not assume
  multiple in-flight compiles on a single daemon will overlap. This
  is a **Phase A contract, not a forever contract**; the Phase B plan
  to revisit it for `kolt watch` (#15) lives in Consequences /
  Negative.

#### Socket path lifecycle

- The native client is responsible for picking the socket path and
  passing it to the daemon via `--socket <path>`.
- The daemon itself creates any missing parent directories before
  binding (`Files.createDirectories(socketPath.parent)`), removes any
  stale socket file from a previous run, and deletes the socket on
  clean exit.
- If the parent path cannot be created (e.g. permission denied, a
  component exists and is not a directory), `DaemonServer.serve()`
  returns `Err(DaemonError.BindFailed)` before any watchdog or bind
  work starts. No partial state is left behind.

#### Exit codes (for the native client)

The CLI `Main` translates the daemon's internal result into one of a
small fixed set of POSIX exit codes so the native client can tell a
clean shutdown apart from an init failure:

| Code | Meaning                                                     |
|------|-------------------------------------------------------------|
| 0    | Clean exit: `Shutdown`, `IdleTimeout`, `MaxCompilesReached`, `HeapWatermarkReached` |
| 64   | Usage error: unknown flag, missing `--socket`, missing or empty `--compiler-jars` |
| 70   | Init failure: `SharedCompilerHost.create` returned `LoaderInitFailed` (usually a wrong compiler-jars path) |
| 71   | Serve failure: `DaemonServer.serve()` returned `DaemonError` (bind or socket cleanup) |

Client action per exit code:

- **0** — daemon finished cleanly. The next `kolt build --daemon`
  should spawn a fresh daemon (same `--compiler-jars`, same socket
  path); no user-visible error.
- **64** — the native client mis-wrote the CLI. This is a kolt bug,
  not a user bug; report it and fall back to the subprocess compile
  path for this build.
- **70** — the compiler jars path is wrong or the jars are corrupt.
  The native client must not retry the daemon with the same
  `--compiler-jars` argument; it should fall back to the subprocess
  path for this build and surface a warning so the user can rerun
  `kolt install` or update the toolchain.
- **71** — bind or socket cleanup failed (stale socket, permission
  denied, unwritable parent). The native client should fall back to
  subprocess for this build; a fresh daemon will be attempted next
  build after the native client has had a chance to scrub
  `~/.kolt/daemon/<projectHash>/`.

### 4. Periodic restart as a leak safety net, not classloader isolation

The daemon tracks `compilesServed`, idle time since the last activity,
and heap-used bytes. When any of the thresholds in `DaemonConfig`
(default: 1000 compiles, 30 minutes idle, 1.5 GiB heap) are exceeded,
the daemon exits cleanly with an `ExitReason` indicating the cause. The
native client is expected to notice the closed socket and spawn a fresh
daemon on the next build. This bounds any leak without paying the
per-compile cost of discarding the compiler instance.

### 5. Default on from day one, with subprocess as fallback only

The daemon is the **default** compile backend starting with #14 PR3.
`kolt build` wires `FallbackCompilerBackend(DaemonCompilerBackend,
SubprocessCompilerBackend)` so that every build first attempts the
warm daemon and silently falls back to the existing subprocess compile
path on any `CompileError.BackendUnavailable` or
`CompileError.InternalMisuse`. A user who wants to bypass the daemon
for a single invocation passes `kolt build --no-daemon`; there is no
`kolt.toml` knob and no global opt-out. `CompileError.CompilationFailed`
(real user code errors) does **not** trigger fallback — the daemon's
verdict is the build's verdict in that case.

This decision supersedes an earlier plan to ship Phase A as opt-in
(`kolt build --daemon`) and flip the default only after the follow-up
spikes #88–#91 landed. The opt-in plan was abandoned for #14 PR3
because every transient artifact it would produce — an opt-in flag,
"experimental" caveats in user-facing docs, an interim ADR status —
would have to be reversed a release later once the default flipped.
Going straight to default-on removes that churn at the cost of
making the fallback contract (ADR 0016 §3 exit-code table,
`FallbackCompilerBackend`'s eligibility classifier) load-bearing from
day one instead of after the spikes. The daemon is still never
load-bearing for **correctness**: any failure drops to the subprocess
path, and the subprocess path is exactly the pre-daemon `kolt build`
behaviour, so the worst case for a user on a broken daemon is the
old ~8 s clean build.

The `reportFallback` helper in `doBuild` emits one stderr line per
fallback: a `warning` for `BackendUnavailable.*` (expected transient
conditions — missing bootstrap JDK, unwritable daemon dir, stale
socket, connect refused) and an `error` log for `InternalMisuse`
(kolt bug, exit code 64). `InternalMisuse` is deliberately loud so
dogfooding surfaces bugs rather than silently masking them.

## Consequences

### Positive

- **Warm compiles drop to ~85–250 ms.** The spike measured 85 ms late
  warm average and 123 ms total warm average across 50 iterations on
  the shared-loader configuration (benchmark table below). That is
  firmly inside the 1 s budget stated in the Phase A target.
- **Cold-start cost is paid once.** The first compile after a daemon
  spawn still pays roughly 2.6 s to initialise the compiler, but every
  subsequent compile amortises that cost to zero. The second and later
  `kolt build` invocations are the ones users notice.
- **Honest error boundaries.** Every fallible daemon entry point
  returns a `Result<V, E>`: `SharedCompilerHost.create` ->
  `Result<_, CompileHostError.LoaderInitFailed>`,
  `SharedCompilerHost.compile` -> `Result<CompileOutcome, CompileHostError>`,
  `FrameCodec.readFrame` / `FrameCodec.writeFrame` ->
  `Result<_, FrameError>`, `DaemonServer.serve` ->
  `Result<ExitReason, DaemonError>`. Reflective, I/O, and bind
  failures are caught at the boundary and converted to error variants;
  no exception escapes into the accept loop or the CLI, and the native
  client's eventual `DaemonCompilerBackend` will return
  `Result<Unit, CompileError>` in the same style. This is a direct
  application of ADR 0001.
- **Leak bounds are explicit.** `DaemonConfig` is the one place that
  describes when a daemon must die, and `ExitReason` tags the cause on
  the way out. Tuning the thresholds in a later phase is a single-file
  change, not an architecture change. The heap sample is taken via
  `ManagementFactory.getMemoryMXBean().heapMemoryUsage.used`, which
  reflects the live set after the most recent GC rather than the
  "committed minus free" transient, so the watermark fires on actual
  retention growth rather than on allocation spikes.
- **The daemon is disposable.** Because the native client always has a
  subprocess fallback and any daemon error forces a clean restart, a
  broken daemon is never a broken build. The worst case is that a user
  sees the old 8-second build time.

### Negative

- **Reflection everywhere in the hot path.** `SharedCompilerHost` finds
  `K2JVMCompiler` and `CLICompiler.exec` by string name and invokes
  them through `java.lang.reflect.Method`. When Kotlin 2.4 or 3.0
  renames, reorders, or changes the signature of those APIs, the
  daemon will break at runtime, not at compile time. We mitigate by
  pinning the compiler jar version and by the integration test in
  `SharedCompilerHostTest` that actually compiles Hello.kt end-to-end
  on every build.
- **Structured diagnostics are not captured yet.** The Phase A host
  pipes compiler output through a captured `PrintStream` and returns
  `diagnostics = emptyList()` with the stderr text as a single blob.
  Hooking into `MessageCollector` to produce real `{severity, file,
  line, col}` records is deferred. Until that lands, users see the
  compiler's human-readable error lines, not structured diagnostics.
- **One connection at a time.** `DaemonServer.serve()` handles one
  connection sequentially. `kolt watch` and concurrent `kolt build`
  invocations on the same project will serialise. That is acceptable
  for Phase A (single CLI user, no watch mode yet) but must be
  revisited before #15 lands.
- **Socket path is global-ish.** `~/.kolt/daemon/<projectHash>/` uses a
  hash of the absolute project path, so moving or renaming a project
  directory invalidates the daemon. That is fine — the next build just
  spawns a new one — but it means daemons accumulate under `~/.kolt/`
  and the native client will need a reaper eventually.

### Neutral

- **No JetBrains Kotlin compiler daemon.** We do not link
  `kotlin-daemon-client`, and we do not ship the JetBrains daemon
  launcher. That reduces surface area and keeps the wire protocol
  under our control, at the cost of giving up whatever future
  optimisations JetBrains ships into that daemon.
- **JSON, not a binary protocol.** At the message sizes involved
  (source paths and classpath entries, not source file contents), the
  encode/decode cost is in the microseconds. A binary protocol would
  save bytes on the wire but would not measurably move the warm-build
  number and would cost us debuggability (`od -c` on a frame shows
  exactly what was sent).

## Benchmark results (from the spike)

Measured on OpenJDK 21.0.7 Corretto, Kotlin 2.3.20, compiling the
same single-file fixture repeatedly. `n` is the number of iterations
in the run; the cold column is the first compile's wall time.

| Scenario | n | cold | warm median | warm avg | heap Δ |
|---|---|---|---|---|---|
| A — shared `URLClassLoader` | 10 | 3147 ms | 250 ms | 233 ms | — |
| B — fresh `URLClassLoader` per compile | 10 | 2620 ms | 2645 ms | 2857 ms | — |
| **C — shared loader, long run** | 50 | 2634 ms | 108 ms | **123 ms** | **+9 MB** |

Late-warm average on scenario C is **85 ms** (JIT warmup improves
times over the run; no upward drift). Scenario B fails the Phase A
kill criterion by a factor of 2.6×. Scenario C passes cleanly and is
the configuration adopted.

## Alternatives Considered

1. **JNI in-process compiler.** Link `kotlin-compiler-embeddable` into
   the kolt Kotlin/Native binary via JNI or a GraalVM host. Rejected:
   dragging a JVM and the full compiler into every native process
   eliminates the "lightweight build tool" pitch, and the CoreEnvironment
   / PSI cache leaks that the daemon addresses become unavoidable
   because the kolt process itself must stay alive to keep the
   compiler warm.
2. **JetBrains `kotlin-daemon-client`.** Talk to the compiler daemon
   that ships with the Kotlin distribution. Rejected: its wire protocol
   is an internal-use Java RMI service, historically unstable across
   Kotlin versions, and depends on a launcher process whose lifecycle
   we do not control. We would trade our own bespoke protocol for a
   larger upstream API surface with worse debuggability.
3. **Fresh `URLClassLoader` per compile (classloader isolation).** The
   original Phase A plan — discard and recreate the classloader between
   compiles to guarantee no state leaks. Rejected on measurements:
   2857 ms warm average vs a 1000 ms budget, as shown in scenario B
   above. The cost of loading the compiler jars dominated every compile
   and made the daemon strictly worse than the existing subprocess
   path.
4. **TCP socket instead of a Unix domain socket.** Rejected on two
   grounds: TCP requires picking a port (racy against anything else on
   the system) and leaks the daemon to anyone on the local network by
   default, whereas UDS uses filesystem permissions for access control
   and naturally scopes to the host. JDK 16+ makes UDS available in
   `java.nio`, so the portability argument for TCP no longer applies
   on the platforms we target.
5. **Protobuf or a hand-rolled binary frame format.** Rejected. The
   debugging value of plain-text JSON frames outweighs the handful of
   bytes saved on each compile, and `kotlinx.serialization` already
   ships with the daemon's other dependencies.
6. **No daemon, just a warmer subprocess path.** Rejected. The cost we
   are removing is entirely in JVM + compiler startup; there is no
   further optimisation available to a short-lived subprocess that
   does not amount to caching the JVM itself, which is what the daemon
   does explicitly.

## Related

- #14 — parent issue (Kotlin Compiler Daemon integration)
- #86 — Phase A scope and spike results
- #87 (merged) — the compile-bench spike that produced the numbers
  above
- #88–#91 — follow-up spikes (rotating fixtures, multi-module,
  long-run leak check, concurrent-compile thread safety). Originally
  gating the Phase B default flip; after #14 PR3 landed default-on,
  these remain valuable as regression-monitoring follow-ups but no
  longer gate the rollout
- #3 — incremental compilation (Phase B, depends on this)
- #15 — `kolt watch` (Phase C, depends on this)
- ADR 0001 — `Result<V, E>` error handling discipline (applies to the
  daemon protocol: all fallible paths return `Result`, never throw)
