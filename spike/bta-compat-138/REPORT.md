# BTA-API binary-compat spike — verdict

**Issue:** #138
**Adapter compile-time API:** `kotlin-build-tools-api:2.3.20`
**Topology under test:** `URLClassLoader(impl jars, parent = SharedApiClassesClassLoader())` (matches `BtaIncrementalCompiler.create` in `kolt-compiler-daemon/ic`).
**Date:** 2026-04-17

## Verdict: RED across the 2.x line, GREEN within 2.3.x

| impl version | verdict | compiler version | cold | inc   | notes |
|--------------|---------|------------------|------|-------|-------|
| 2.1.0        | **RED** | —                | —    | —     | fails at `KotlinToolchains.loadImplementation` |
| 2.2.20       | **RED** | —                | —    | —     | same failure mode as 2.1.0 |
| 2.3.0        | GREEN   | 2.3.0            | 3730 | 508   | |
| 2.3.10       | GREEN   | 2.3.10           | 3106 | 499   | |
| 2.3.20       | GREEN   | 2.3.20           | 3275 | 423   | |

Wall times in ms. `linear-10` fixture, touch `F2.kt`.

## Failure mode (2.1.0 / 2.2.20)

```
org.jetbrains.kotlin.buildtools.api.NoImplementationFoundException:
    The classpath contains no implementation for org.jetbrains.kotlin.buildtools.api.KotlinToolchains
    at KotlinToolchains$Companion.loadImplementation(KotlinToolchains.kt:171)
Caused by: java.lang.ClassNotFoundException:
    org.jetbrains.kotlin.buildtools.internal.compat.KotlinToolchainsV1Adapter
    at URLClassLoader.findClass(URLClassLoader.java:445)
    ...
    at KotlinToolchains$Companion.loadImplementation(KotlinToolchains.kt:167)
```

Reading the 2.3.20 `KotlinToolchains.loadImplementation` path:

1. `KotlinToolchains` is the 2.3.x entry type. Pre-2.3 impls do not ship a
   `ServiceLoader` service descriptor for it — they ship the old
   `CompilationService` SPI instead.
2. 2.3.x API tries to bridge old impls via a shim class
   `org.jetbrains.kotlin.buildtools.internal.compat.KotlinToolchainsV1Adapter`.
   It `classLoader.loadClass(...)`es that shim on the classloader it was given.
3. The shim lives in the `...internal.compat.*` package. The daemon's
   `SharedApiClassesClassLoader` only exposes `org.jetbrains.kotlin.buildtools.api.*`
   and does *not* share `internal.compat.*`. The child `URLClassLoader` has
   the impl jars but not the shim, so the `loadClass` fails and the whole
   call turns into `NoImplementationFoundException`.

In short: `@ExperimentalBuildToolsApi` made no binary-compat promise, and the
concrete breaking change is a new entry type (`KotlinToolchains` in 2.3.0)
with a compat shim that our classloader topology does not expose.

## Patch-level stability within 2.3.x

2.3.0 / 2.3.10 / 2.3.20 all GREEN. The impl jar for 2.3.0 and 2.3.10 differ
by 1 byte; 2.3.20 is larger but API-compatible. Treat 2.3.x as one
binary-compat family for daemon-spawn purposes.

## Implications for #138

- **The "per-kotlinVersion daemon spawn" sketch as written does not work.**
  The sketch assumes the adapter stays compiled against 2.3.20 and we
  fetch an older `kotlin-build-tools-impl` to match `config.kotlin`. The
  spike shows that combination crashes at `loadImplementation` for any
  impl from before the 2.3.x line.
- **Proposed re-scope: floor = 2.3.0, forward on a per-release basis.**
  Kotlin has no LTS; 2.3.x is the current language release line, 2.4 is
  still EXPERIMENTAL at spike time (2026-04-17). Picking 2.3.0 as the
  floor means the adapter stays a single JAR — no per-API-major
  distribution, no V1 shim investigation — and every daemon user is on
  the tested topology. Below 2.3.0 is subprocess-only, same shape as the
  #136 stop-gap.
- **Forward policy is event-driven, not N-pinned.** Re-run this spike
  when the next language release (2.4.0) hits stable. If API+impl stays
  binary-compat with the 2.3.20-compiled adapter, extend the daemon
  support table and keep the existing JAR. If it drifts, choose then:
  ship a second adapter JAR or let 2.4 fall through to subprocess. We
  deliberately do **not** commit to `N=2` or "latest + previous" up
  front — Kotlin's own cadence (language release every ~6 months,
  tooling releases in between) makes a specific N brittle, and the
  spike harness here (`spike/bta-compat-138`) is the cheap reusable
  input to that judgement call.
- **BtaImplFetcher still lands.** Even with a single supported API line,
  the daemon needs to fetch `kotlin-build-tools-impl:<config.kotlin>`
  for each 2.3.x patch the user pins — the spike confirms 2.3.0 / 2.3.10
  / 2.3.20 all work against the 2.3.20-compiled adapter, so per-patch
  impl fetch is the load-bearing work item. Socket path keeps
  `<kotlinVersion>` as the issue sketches; IC state is already
  version-stamped (ADR 0019 §5).
- **Info-gate wording simplifies.** "Outside tested range" now means
  "not a 2.3.x release" rather than "a specific version kolt has not
  seen." The three-signal distinction (quiet / info / warning) still
  holds. `--no-daemon` remains the permanent escape hatch.

## Not in this spike (flagged for follow-up)

- Whether a custom parent classloader that also exposes `internal.compat.*`
  can resolve the V1 shim and rescue 2.2.x / 2.1.x impls with the current
  2.3.20 adapter. Plausible but untested. Not worth pursuing unless the
  floor-2.3 decision is reversed.
- Re-running this spike at Kotlin 2.4.0 stable — the harness here is
  the input to the forward-policy judgement call. Keep the spike
  checked in for that reason rather than deleting after #138 merges.

## Raw run log

`/tmp/bta-compat-work/run.log` after `./gradlew -p spike/bta-compat-138 run`.

## Reproducing

```
./gradlew -p spike/bta-compat-138 run --args="fixtures/linear-10 /tmp/bta-compat-work"
```
