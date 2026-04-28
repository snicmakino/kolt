---
status: implemented
date: 2026-04-18
---

# ADR 0022: Minimum supported Kotlin version and re-evaluation policy

## Summary

- **Soft floor: Kotlin 2.3.0.** 2.3+ is first-class (daemon path); `< 2.3` routes to the subprocess fallback with a single warning per cache-miss build. Not a hard reject. (§1, §2)
- **Forward policy: event-driven at each language release.** On each new `x.y.0` stable, re-run `spike/bta-compat-138/`. GREEN extends the supported range; RED holds and defers to the next release. No pre-commitment to future Kotlin versions. (§3)
- **Backward policy: event-driven at Kotlin 2.6.0 stable** (projected ~summer 2027). Apply a `(subprocess still functional × observed < 2.3 user base)` matrix to decide soft-floor continuation vs. migration to a hard floor. (§4, §5)
- **Forward-backward coupling.** If 2.4.0 and 2.5.0 forward spikes both go RED, defer the backward evaluation one language release. Every deferral names the next concrete trigger; never open-ended. (§6)
- **Per-version daemon spawn** supersedes ADR 0019 §1's single-version pin. Socket path becomes `~/.kolt/daemon/<projectHash>/<kotlinVersion>/daemon.sock`; BTA-API/impl JARs are fetched per Kotlin version via `BtaImplFetcher`. IC state isolation is free (ADR 0019 §5 already version-stamps the path). (§7, §9)
- **`KOLT_DAEMON_KOTLIN_VERSION` repositioned** from contract to "tested default baseline" — the CI-exercised primary path and the default when `config.kotlin` is omitted. The 6-pin consistency check stays (migrated to `DriftGuardsTest` in #268). (§8)

## Context and Problem Statement

### What the #138 spike established

`spike/bta-compat-138/` drove the same cold-then-incremental adapter wiring that ADR 0019 ships against a matrix of `kotlin-build-tools-api` / `kotlin-build-tools-impl` versions:

| Impl version | Verdict | Failure mode |
|---|---|---|
| 2.1.0 | RED | `KotlinToolchains.loadImplementation` → `NoImplementationFoundException` wrapping `ClassNotFoundException: org.jetbrains.kotlin.buildtools.internal.compat.KotlinToolchainsV1Adapter` |
| 2.2.20 | RED | same as above |
| 2.3.0  | GREEN | cold + incremental succeed |
| 2.3.10 | GREEN | cold + incremental succeed |
| 2.3.20 | GREEN | cold + incremental succeed (ADR 0019 baseline) |

The `KotlinToolchains` entry point is a 2.3.x addition. Pre-2.3 releases reach it through an internal V1 compatibility shim (`internal.compat.KotlinToolchainsV1Adapter`) that is not visible to the `SharedApiClassesClassLoader` topology ADR 0019 §3 relies on. BTA is `@ExperimentalBuildToolsApi` precisely so JetBrains can make that kind of cross-minor break. Full details and the reproducible harness live in `spike/bta-compat-138/REPORT.md`.

### Kotlin's release model (as of 2026-04)

JetBrains does not publish an LTS. Language releases (`x.y.0`) stabilise new features roughly every 6 months — current schedule projects 2.4.0 around summer 2026, 2.5.0 around winter 2026/27, 2.6.0 around summer 2027. Tooling releases (`x.y.20`) fix bugs within the same line. Each language release produces a new BTA surface, and the spike confirmed BTA can break binary compatibility across those.

### kolt's value proposition

Phase A measurements (#96, `a1b9a8f`, 2026-04-14) showed daemon-warm compiles run ~7× faster than subprocess and 1.28–1.67× faster than Gradle on the same fixtures. Phase B (ADR 0019) adds incremental compile on top. Both wins require the compiler daemon; subprocess exists as a safety net, not a supported primary path. A Kotlin version that cannot run the daemon is a version on which kolt delivers no value beyond "wraps `kotlinc`".

### The problem this ADR resolves

Before this ADR, kolt's position on Kotlin versions was implicit: the daemon was hard-pinned to a bundled 2.3.20 compiler, and a `kolt.toml` `kotlin = "<other>"` silently used 2.3.20 for compile (Bug 1 of #135). PR #139 added a subprocess fallback when `config.kotlin != BUNDLED_DAEMON_KOTLIN_VERSION`, but left the broader policy question open. This ADR closes it.

## Decision Drivers

- User contract for which Kotlin versions get daemon vs. subprocess must be explicit and written down.
- Forward and backward policies must be event-driven, not date-driven, so they move with Kotlin's cadence.
- Silent wrong output (Bug 1) must be structurally prevented, not patched per-release.
- The `< 2.3` path must remain usable (subprocess) while clearly marked as best-effort.

## Decision Outcome

Chosen option: **soft floor at 2.3.0 with event-driven re-evaluation**, because it makes the contract explicit, preserves usability for `< 2.3` users, and ties policy decisions to empirical evidence at each Kotlin language release.

### §1 Soft floor: Kotlin 2.3.0

kolt officially supports Kotlin 2.3.0 and above as a first-class target. The daemon path — warm JVM plus incremental compile — is available to every `kolt.toml` whose `[kotlin] version = "2.3.x"` resolves within this range.

Below 2.3.0, kolt does not refuse to run. `config.kotlin.version < 2.3.0` routes through the subprocess fallback with a single stderr warning per affected build (§2). This is a best-effort path: kolt's CI does not exercise it, and failures are triaged at "known unsupported" priority. The subprocess backend has no BTA coupling; observable behaviour is whatever `kotlinc <version>` does with the sources kolt hands it.

Hard-rejecting 2.2.x would cut off users trying kolt against existing projects before they discover whether it works; permissive silence would leave users guessing why their build behaves differently from the documented one. A single warning on the unsupported path threads the needle.

### §2 Warning frequency: cache-miss only

The `< 2.3.0` warning is emitted exactly when the native client would otherwise have dispatched to the daemon (not `--no-daemon`, not already subprocess) **and** `BuildCache` returned `Stale`. A user on `config.kotlin.version = "2.2.20"` who runs `kolt build` twice sees the warning once; the second build is a 0.00–0.01 s cache hit with silent exit.

Warning text (subject to wording polish in implementation):

> kolt officially supports Kotlin 2.3.0 and above. Using subprocess fallback for Kotlin `<version>`; this path is best-effort and may be removed in a future release. See ADR 0022 for policy.

### §3 Forward re-evaluation: event-driven at each language release

When a new Kotlin language release (`x.y.0`) reaches stable, re-run `spike/bta-compat-138/` against the new BTA-API/impl pair. The harness is checked in for exactly this purpose; see `spike/bta-compat-138/README.md`.

- **GREEN**: extend the daemon's BTA-impl bundle to include the new version. The soft floor does not move. The supported range widens upward.
- **RED**: daemon continues to support the prior range only. Append the failure mode to the spike report. Repeat at the next language release.

Tooling releases (`x.y.20`) do not trigger re-evaluation unless a user reports a regression — BTA's within-line compatibility has held in the 2.3.x spike.

### §4 Backward re-evaluation: event-driven at Kotlin 2.6.0 stable

Re-evaluate the soft floor when Kotlin 2.6.0 stable ships (projected summer 2027). Using the §5 matrix, decide whether to keep the soft floor and schedule the next evaluation at 2.7.0 stable, or migrate to a hard floor (`kolt build` on `config.kotlin < 2.3.0` exits with an actionable error). The 2.6.0 trigger is two language lines beyond the current floor — enough time for the ecosystem to move on from 2.2.x and for the §2 warning path to accumulate signal.

The trigger is a release event, not a date. If Kotlin's cadence slips, the evaluation slips with it.

### §5 Backward-evaluation decision matrix

| Is `< 2.3` subprocess still functional? | Observed `< 2.3` user base | Decision |
|---|---|---|
| Yes | Significant | Keep soft floor; schedule next evaluation at the next language release |
| Yes | Negligible | Migrate to hard floor 2.3.0 |
| No (subprocess has degraded) | Any | Migrate to hard floor 2.3.0 |

"Functional" means the subprocess path produces a successful compile and runnable output on the standard kolt smoke test. "Significant" and "negligible" are judgment calls from the evidence at the evaluation point — issue tracker traffic, `reportFallback` signal, community questions.

### §6 Forward-backward coupling: deferral rule

If forward spikes at 2.4.0 **and** 2.5.0 both result in RED — daemon support is still 2.3.x only by the time 2.6.0 stable arrives — defer the backward evaluation one language release (to 2.7.0 stable) **regardless of §5's matrix outcome**. Narrowing kolt's usable Kotlin range to a single `x.y` line is not a position kolt should take as a pre-1.0 tool.

Each deferral names the next concrete trigger. A nested deferral applies the same rule again, always naming the next language release explicitly.

### §7 Per-version daemon spawn (supersedes ADR 0019 §1)

The single-version pin from ADR 0019 §1 (`kotlin-build-tools-api:2.3.20`) is replaced by a per-version scheme. Implementation is tracked in #138; the load-bearing commitments are:

- **Socket path segmentation**: `~/.kolt/daemon/<projectHash>/<kotlinVersion>/daemon.sock`, one daemon per `(project, kotlin version)` pair.
- **BTA-impl JAR fetching**: `BtaImplFetcher` mirrors `PluginJarFetcher` (#65) and follows the ADR 0009 `ensureKotlincBin` template. Per-version BTA-API/impl artifacts are downloaded on first use and cached under `~/.kolt/`.
- **Daemon CLI `--bta-impl-jars <path>`**: the native client passes the resolved per-version JAR set at daemon spawn time. The daemon's classloader hierarchy (ADR 0019 §3) is unchanged in shape; only the URLs loaded into the impl loader vary.
- **IC state isolation**: free. ADR 0019 §5 already version-stamps `~/.kolt/daemon/ic/<kotlinVersion>/<projectHash>/`.
- **First-build cost**: a one-time BTA-impl fetch on the order of seconds, not tens of seconds. Subsequent builds hit the cached JARs.

`BtaImplFetcher` is not invoked on the `< 2.3` path — that path goes through subprocess and has no BTA coupling.

### §8 `KOLT_DAEMON_KOTLIN_VERSION` is a baseline, not a contract

The constant now names "the tested default baseline" — the version kolt's CI exercises as the primary path and the default when a user omits `config.kotlin`. Other 2.3.x versions run via `BtaImplFetcher` (§7); they are supported, not exceptional. The constant's sole remaining purpose is CI baseline + default, not a hard floor on `kolt.toml`.

The 6-pin consistency check (covering `BundledKotlinVersion.kt`, both daemon `Main.kt` files, and the kotlinc/BTA artifact pins on both the daemon and IC subprojects) remains in place — migrated from the Gradle `verifyDaemonKotlinVersion` task to `DriftGuardsTest` in #268.

### §9 Interaction with the #136 stop-gap

PR #139's subprocess fallback is preserved, not replaced. The only change is the fallback condition: from `!= BUNDLED_DAEMON_KOTLIN_VERSION` (single equality check) to "is this daemon-capable?" — positive for all 2.3.x under §7's per-version scheme, negative for `< 2.3`. No ADR 0019 §5 / §7 behaviour changes for the supported range; incremental compile, self-heal, and classpath snapshot caching work the same on 2.3.10 as on 2.3.20.

### Consequences

**Positive**
- User contract is explicit: 2.3.0+ is first-class, `< 2.3` is best-effort with a single warning per build.
- Forward and backward policies are symmetric — both event-driven at language releases, both using the checked-in spike harness.
- Bug 1 (silent wrong compiler) cannot recur: per-version daemon spawn means `config.kotlin.version = "2.3.10"` cannot transparently use a 2.3.20 compiler.
- `spike/bta-compat-138/` becomes a durable forward-re-evaluation tool.

**Negative**
- Multiple daemons per developer machine: a user with projects on 2.3.10, 2.3.20, and (eventually) 2.4.0 will run three daemon processes. RSS cost is real but bounded.
- First-build cost per new Kotlin version: BTA-impl JAR fetch adds seconds on the first build of a new version.
- Test matrix grows with the supported range; each additional GREEN version is another CI configuration.
- Backward deferral can chain across multiple language releases if forward spikes stay RED. Each deferral is individually bounded (§6), but the chain has no pre-committed terminus.
- `--no-daemon` exits this ADR's policy surface entirely; the `< 2.3` best-effort guarantee does not apply to `--no-daemon` invocations either.

### Confirmation

Per-version daemon spawn verified in #138 implementation PR. The native `DriftGuardsTest` (#268) enforces baseline coherence in CI. Forward re-evaluation trigger: file an issue when each Kotlin `x.y.0` stable ships.

## Alternatives considered

1. **Hard floor 2.3.0 from day one.** Rejected — cuts off users with existing 2.2.x projects before they can evaluate kolt. Reconsidered at each backward evaluation point per §4.
2. **Pure soft floor with no sunset path.** Rejected — "best-effort" becomes "permanent second class" with no triage priority ever improving. The §4/§5 evaluation gives the soft floor a concrete graduation path.
3. **Load 2.2.x via a V1 compatibility shim + custom parent loader.** Expose `internal.compat.KotlinToolchainsV1Adapter` by widening the parent loader beyond `api.*`. Rejected: the shim is `internal.*` and can be renamed or removed by JetBrains in any tooling patch with no binary-compat obligation; 2.2.x daemon support is not load-bearing for kolt's target audience. Documented here for the future maintainer who will consider it again.
4. **Per-minor-line adapter JARs (2.2.x + 2.3.x adapters).** 2.2.x's entry point is `CompilationService`, a different surface from 2.3.x's `KotlinToolchains`. Rejected: estimated 1 week up-front plus continuous maintenance, against a user base kolt is not targeting at floor 2.3.0.
5. **Declare `< 2.3` subprocess a first-class path.** Rejected: the subprocess path is not a kolt-value-delivering path and CI does not exercise it. A first-class contract kolt cannot keep.
6. **Time-based sunset ("hard floor at 2027-07-01").** Rejected: a date-driven decision cannot account for Kotlin cadence changes, multiple RED forward spikes, or significant `< 2.3` usage at evaluation time. §4 and §6 keep the decision attached to the evidence.

## Related

- #135 — unified bug tracker (Bug 1: daemon silently uses bundled Kotlin; Bug 2: daemon IC state survives `kolt clean`, resolved in PR #137)
- #136 — Bug 1 stop-gap, merged as PR #139 (`d1f96d0`)
- #138 — per-version daemon spawn; implementation vehicle for §7
- #140 — BTA binary-compat spike, merged as `ce9c851`
- ADR 0009 — `ensureKotlincBin` provisioning (template for `BtaImplFetcher`)
- ADR 0016 — warm JVM compiler daemon (the process this ADR version-segments)
- ADR 0019 — incremental compile via BTA (§1's single-version pin superseded here; every other section stands)
- ADR 0020 — daemon scope is compile-only (unaffected)
- `spike/bta-compat-138/REPORT.md` — full matrix results, failure stack traces, and the reproducible harness
