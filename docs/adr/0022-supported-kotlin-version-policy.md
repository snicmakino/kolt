# ADR 0022: Minimum supported Kotlin version and re-evaluation policy

## Status

Accepted (2026-04-18). Follows the #138 spike (PR #140, `ce9c851`) and
formalises the direction the #136 stop-gap (PR #139, `d1f96d0`) took.
Per-version daemon spawn implementation is tracked in #138. Supersedes
ADR 0019 §1's single-version pin on `kotlin-build-tools-api` 2.3.20
(now version-scoped per §6 below); ADR 0019's header is updated in the
same PR that merges this ADR so the two documents move atomically.

## Summary

- **Soft floor: Kotlin 2.3.0.** 2.3+ is first-class (daemon path);
  `< 2.3` routes to the subprocess fallback with a single warning per
  cache-miss build. Not a hard reject. (§1, §2)
- **Forward policy: event-driven at each language release.** On each
  new `x.y.0` stable, re-run `spike/bta-compat-138/`. GREEN extends
  the supported range; RED holds and defers to the next release. No
  pre-commitment to future Kotlin versions. (§3)
- **Backward policy: event-driven at Kotlin 2.6.0 stable** (projected
  ~summer 2027). Apply a `(subprocess still functional × observed
  < 2.3 user base)` matrix to decide soft-floor continuation vs.
  migration to a hard floor. (§4, §5)
- **Forward-backward coupling.** If 2.4.0 and 2.5.0 forward spikes
  both go RED, the backward evaluation is deferred one language
  release — kolt should not narrow to a single `x.y` line. Every
  deferral names the next concrete trigger; never open-ended. (§6)
- **Implementation shape: per-version daemon spawn.** Socket path
  becomes `~/.kolt/daemon/<projectHash>/<kotlinVersion>/daemon.sock`;
  BTA-API/impl JARs are fetched per Kotlin version via
  `BtaImplFetcher`. IC state is already version-stamped (ADR 0019
  §5), so isolation comes free. Supersedes ADR 0019 §1's single-version
  pin; every other ADR 0019 decision stands. (§7, §9)
- **`KOLT_DAEMON_KOTLIN_VERSION` repositioned** from contract to
  "tested default baseline" — the CI-exercised primary path and the
  default when `config.kotlin` is omitted. The `verifyDaemonKotlinVersion`
  6-pin check stays. (§8)

## Context

### What the #138 spike established

`spike/bta-compat-138/` drove the same cold-then-incremental adapter
wiring that ADR 0019 ships against a matrix of
`kotlin-build-tools-api` / `kotlin-build-tools-impl` versions. The
verdict was sharp:

| Impl version | Verdict | Failure mode |
|---|---|---|
| 2.1.0 | RED | `KotlinToolchains.loadImplementation` → `NoImplementationFoundException` wrapping `ClassNotFoundException: org.jetbrains.kotlin.buildtools.internal.compat.KotlinToolchainsV1Adapter` |
| 2.2.20 | RED | same as above |
| 2.3.0  | GREEN | cold + incremental succeed |
| 2.3.10 | GREEN | cold + incremental succeed |
| 2.3.20 | GREEN | cold + incremental succeed (ADR 0019 baseline) |

The `KotlinToolchains` entry point is a 2.3.x addition. Pre-2.3
releases reach it through an internal V1 compatibility shim
(`internal.compat.KotlinToolchainsV1Adapter`) that is not visible to
the `SharedApiClassesClassLoader` topology ADR 0019 §3 relies on. BTA
is `@ExperimentalBuildToolsApi` precisely so JetBrains can make that
kind of cross-minor break; the spike confirmed they did. Full details
and the reproducible harness live in `spike/bta-compat-138/REPORT.md`.

### Kotlin's release model (as of 2026-04)

JetBrains does not publish an LTS. Releases follow:

- **Language release** (`x.y.0`), stabilising new features, roughly
  every 6 months — current schedule projects 2.4.0 around summer 2026,
  2.5.0 around winter 2026/27, 2.6.0 around summer 2027.
- **Tooling release** (`x.y.20`), bug and performance fixes within the
  same language-release line.

"Supporting older Kotlin" is therefore not a static target: each
language release produces a new surface, and BTA has demonstrated it
can break binary compatibility across those.

### kolt's value proposition

Phase A measurements (#96, merged as `a1b9a8f` on 2026-04-14) showed
daemon-warm compiles run ~7× faster than subprocess and 1.28–1.67× faster
than Gradle on the same fixtures. Phase B (ADR 0019) adds incremental
compile on top of that. Both wins are delivered by the compiler daemon;
the subprocess fallback exists as a safety net for when the daemon
cannot start (missing bootstrap JDK, socket collision, …), not as a
supported primary path.

The practical implication: a Kotlin version that cannot run the
daemon is a Kotlin version on which kolt delivers no value beyond
"wraps `kotlinc`". Declaring such a version supported would be
technically true (subprocess still compiles) but would mislead users
about what they are getting.

### The problem this ADR resolves

Before this ADR, kolt's position on Kotlin versions was implicit:

- The daemon was hard-pinned to a bundled 2.3.20 compiler; a
  `kolt.toml` `kotlin = "<other>"` silently used 2.3.20 for compile.
  This is Bug 1 of #135, fixed as a stop-gap by PR #139 (subprocess
  fallback when `config.kotlin != BUNDLED_DAEMON_KOTLIN_VERSION`).
- There was no written policy on which versions the daemon should
  support, nor on how that set evolves as Kotlin releases progress.

The stop-gap avoided silent wrong output, but left the broader policy
question open. This ADR closes it.

## Decision

### 1. Soft floor: Kotlin 2.3.0

kolt officially supports Kotlin 2.3.0 and above as a first-class
target. The daemon path — warm JVM plus incremental compile — is
available to every `kolt.toml` whose `[kotlin] version = "2.3.x"` resolves
within this range.

Below 2.3.0, kolt does not refuse to run. `config.kotlin.version < 2.3.0`
routes through the subprocess fallback with a single stderr warning
per affected build (see §2). This is a best-effort path: kolt's CI
does not exercise it, and compile-time or runtime failures on that
path are triaged at "known unsupported" priority. The subprocess
backend has no BTA coupling, so the observable behaviour is whatever
`kotlinc <version>` does with the sources kolt hands it.

This is the "soft floor" position — not a hard reject, not an
unannotated permissive — and it is deliberate. Hard-rejecting 2.2.x
today would cut off users trying kolt against existing projects
before they discover whether it works for them; permissive silence
would leave the user guessing why their build behaves differently
from the documented one. A single warning on the unsupported path
threads the needle.

### 2. Warning frequency and lifecycle: daemon-probe path, cache-miss only

The `< 2.3.0` warning is emitted exactly when:

- the native client would otherwise have dispatched to the daemon
  (i.e., not `--no-daemon`, not already subprocess), **and**
- `BuildCache` returned `Stale` — there is real compile work to do.

Concretely: `BuildCache` hits (the "nothing changed" path, ADR 0019
§8) produce no output regardless of Kotlin version. A user on
`config.kotlin.version = "2.2.20"` who runs `kolt build` twice in a row sees
the warning once, not twice; the second build is a 0.00–0.01 s cache
hit with silent exit.

The rule exists because the warning text is intentionally soft ("may
be removed in a future release") and the frequency cap keeps it from
becoming noise. It is the same observation that led #136's stop-gap
warning to ride the `reportFallback` rail rather than fire
unconditionally; this ADR formalises that design choice rather than
reinventing it.

Warning text, subject to wording polish in implementation:

> kolt officially supports Kotlin 2.3.0 and above. Using subprocess
> fallback for Kotlin `<version>`; this path is best-effort and may
> be removed in a future release. See ADR 0022 for policy.

### 3. Forward re-evaluation: event-driven at each language release

When a new Kotlin language release (`x.y.0`, e.g., 2.4.0, 2.5.0)
reaches stable, `spike/bta-compat-138/` is re-run against the new
BTA-API/impl pair. The spike harness is checked in for exactly this
purpose and is designed to be re-usable; see `spike/bta-compat-138/
README.md`.

Outcomes:

- **GREEN**: the daemon's BTA-impl bundle is extended to include the
  new version. The soft floor does not move. The supported range
  widens upward.
- **RED**: the daemon continues to support the prior range only. The
  spike report appends the failure mode. The next language release
  repeats the check.

The trigger is "new language release reaches stable", not a date.
Tooling releases (`x.y.20`) do not trigger re-evaluation unless a
user reports a regression — BTA's within-line compatibility has held
in the 2.3.x spike and is cheap to validate by bumping the daemon's
pin when convenient.

No forward pre-commitment is made here. Announcing "kolt will support
2.4" before 2.4 exists would either be broken by a RED spike or force
a V1-shim workaround (rejected in §Alternatives Considered). The
honest position is "we re-check at each language release, and widen
the range when the check passes".

### 4. Backward re-evaluation: event-driven at Kotlin 2.6.0 stable

The soft floor is not permanent. It is re-evaluated when Kotlin 2.6.0
stable ships (currently projected around summer 2027). At that point
kolt's maintainer(s) decide, using the §5 matrix, whether to:

- keep the soft floor and schedule the next evaluation at 2.7.0
  stable; or
- migrate to a hard floor (`kolt build` on `config.kotlin < 2.3.0`
  exits with an actionable error).

The 2.6.0 trigger is chosen because that release is two language
lines beyond the current floor — enough time for the ecosystem around
kolt to have moved on from 2.2.x, and enough data (bug reports,
actual `< 2.3` usage via the §2 warning path) to make the call
empirically.

Like §3, the trigger is a release event, not a date. If Kotlin's
cadence slips, the evaluation slips with it. The ADR deliberately
does not pre-commit to "hard floor by YYYY-MM"; there is no data
today that would make such a commitment honest.

### 5. Backward-evaluation decision matrix

At each backward evaluation point, the decision is made against this
matrix:

| Is `< 2.3` subprocess still functional? | Observed `< 2.3` user base | Decision |
|---|---|---|
| Yes | Significant | Keep soft floor; schedule next evaluation at the next language release |
| Yes | Negligible | Migrate to hard floor 2.3.0 |
| No (subprocess has degraded) | Any | Migrate to hard floor 2.3.0 (no point maintaining a soft contract on a path that no longer works) |

"Functional" means the subprocess path produces a successful compile
and runnable output on the standard kolt smoke test. "Significant"
and "negligible" are judgment calls made from the evidence available
at the evaluation point — issue tracker traffic, the warning's
`reportFallback` signal if we have telemetry by then, community
questions. The matrix does not try to quantify these; it documents
what factors the judgment weighs so a future evaluator does not have
to re-derive the framing.

### 6. Forward-backward coupling: deferral rule

If the forward spikes at 2.4.0 **and** 2.5.0 both resulted in RED
— daemon support is still 2.3.x only by the time 2.6.0 stable
arrives — the backward evaluation is deferred one language release
(to 2.7.0 stable) **regardless of §5's matrix outcome**. Narrowing
kolt's usable Kotlin range to a single `x.y` line (2.3.x only, no
2.4, no 2.5, and a hard floor cutting off 2.2 and below) would leave
users with an uncomfortably thin version target, which is not a
position kolt should take as a pre-1.0 tool.

The deferral itself is bounded: the next evaluation point is always
a concrete Kotlin language release, never "later" or "when we feel
comfortable". A nested deferral (if 2.6/2.7 forward spikes also stay
RED) applies the same rule again, each time naming the next trigger
explicitly.

### 7. Per-version daemon spawn (implementation commitment, supersedes ADR 0019 §1)

The single-version pin ADR 0019 §1 committed to
(`kotlin-build-tools-api:2.3.20`) is replaced by a per-version scheme,
as the concrete vehicle for §1's soft floor on the supported side.
Implementation details are tracked in #138; the load-bearing
commitments made by this ADR are:

- **Socket path segmentation**: daemon sockets live at
  `~/.kolt/daemon/<projectHash>/<kotlinVersion>/daemon.sock`, one
  daemon process per `(project, kotlin version)` pair. This is the
  minimum structural change that lets a user running two projects on
  different 2.3.x versions get correct daemon behaviour for both.
- **BTA-impl JAR fetching**: a `BtaImplFetcher` mirrors
  `PluginJarFetcher` (#65) and follows ADR 0009's `ensureKotlincBin`
  template. Per-version BTA-API/impl artifacts are downloaded on
  first use and cached under `~/.kolt/`.
- **Daemon CLI `--bta-impl-jars <path>`**: the native client passes
  the resolved per-version JAR set at daemon spawn time. The daemon
  process's classloader hierarchy (ADR 0019 §3) is unchanged in
  shape; only the URLs loaded into the impl loader vary.
- **IC state isolation**: free. ADR 0019 §5 already version-stamps
  `~/.kolt/daemon/ic/<kotlinVersion>/<projectHash>/`, so per-version
  IC state is the existing design.
- **First-build cost disclosure**: the first `kolt build` on a new
  Kotlin version pays a one-time BTA-impl fetch latency on the order
  of seconds, not tens of seconds. This cost is documented in the
  first-run output; subsequent builds hit the cached JARs.

The per-version scheme covers the supported range (2.3.0 and above,
extended forward per §3). The `< 2.3` soft-floor path does not need
BTA at all — it goes through subprocess — so `BtaImplFetcher` is not
invoked in that case.

### 8. `KOLT_DAEMON_KOTLIN_VERSION` is a baseline, not a contract

Before this ADR, `KOLT_DAEMON_KOTLIN_VERSION` (and its paired
`BUNDLED_DAEMON_KOTLIN_VERSION` on the native client) named "the
Kotlin version the daemon bundle was built against, and the only
version `config.kotlin` can equal without a fallback". That framing
is dropped.

After this ADR, the constant names "the tested default baseline" —
the version kolt's CI exercises as the primary path and the default
that kolt ships with if a user omits `config.kotlin`. Other 2.3.x
versions run via `BtaImplFetcher` (§7); they are supported, not
exceptional. The constant's sole remaining purpose is CI baseline +
default, not a contract with the user's `kolt.toml`.

The `verifyDaemonKotlinVersion` 6-pin consistency check established
in the #136 stop-gap (covering `BundledKotlinVersion.kt`, daemon
`Main.kt`, and the two Gradle pair artifacts on both the daemon and
IC subprojects) remains in place. It enforces "the baseline is
coherent across the build", which is still true — the baseline just
no longer implies a hard floor ceiling.

### 9. Interaction with the #136 stop-gap

PR #139's subprocess fallback is **preserved**, not replaced. Its
observable behaviour already matches this ADR's soft-floor path:
daemon attempted, fallback condition triggers, warning printed. The
only change this ADR drives to the stop-gap's routing is the fallback
condition itself: from `!= BUNDLED_DAEMON_KOTLIN_VERSION` (a single
equality check) to "is this daemon-capable?" — answered positively
for all 2.3.x under §7's per-version scheme, negatively for `< 2.3`.

The warning text on the subprocess fallback rail is specified in §2.

No ADR 0019 §5 / §7 behaviour changes for the supported range.
Incremental compile, self-heal, classpath snapshot caching all work
the same on 2.3.10 as on 2.3.20 as on (hypothetically GREEN-at-spike)
2.4.0. The per-version change is a classloader URL set, not a
compile-pipeline rewrite.

## Consequences

### Positive

- **User contract is explicit.** 2.3.0+ is first-class, `< 2.3` is
  best-effort with a single warning per build. No more implicit
  policy derived from reading CI matrices and Gradle pins.
- **Forward and backward policies are symmetric.** Both are
  event-driven at Kotlin language releases, both use the checked-in
  spike harness as the empirical input, both name a concrete next
  trigger rather than an open-ended deferral.
- **Silent wrong output from Bug 1 cannot recur.** Per-version daemon
  spawn (§7) means `config.kotlin.version = "2.3.10"` cannot transparently
  use a 2.3.20 compiler. The IC state path is already version-stamped
  (ADR 0019 §5), so the existing design extends cleanly.
- **Warning is informative, not punitive.** The cache-miss frequency
  cap (§2) means users on the best-effort path see the message when
  they have compile work to do, not on every invocation.
- **`spike/bta-compat-138/` becomes a durable tool.** Treating it as
  the canonical forward-re-evaluation harness, rather than a
  throwaway, pays back the spike cost on every Kotlin release.

### Negative

- **Multiple daemons per developer machine.** A user with projects
  on 2.3.10, 2.3.20, and (eventually) 2.4.0 will run three daemon
  processes. RSS cost is real but bounded; no single daemon is
  duplicated within a project.
- **First-build cost per new Kotlin version.** The BTA-impl JAR
  fetch on the first build of a new version adds seconds to that
  build. Subsequent builds are cached. Documented, but a user
  changing `kolt.toml` `[kotlin] version` and immediately running `kolt build`
  sees it.
- **Test matrix grows with the supported range.** Each additional
  GREEN Kotlin version is another CI configuration to keep passing.
  Bounded in practice (GREEN versions so far are same-line 2.3.x
  patches and match each other closely), but real.
- **Backward deferral can chain across multiple language releases.**
  If forward spikes at 2.4 and 2.5 stay RED, §6 defers the backward
  evaluation; the same rule applies again at 2.7, and so on. Each
  individual deferral is bounded (§6 names the next trigger
  explicitly), but the chain as a whole has no pre-committed terminus.
  This is accepted: it is strictly better than shipping a hard floor
  with no forward expansion, which would strand kolt on a single
  minor line.
- **`kolt.toml` `[kotlin] version = "<old>"` users get weaker guarantees.**
  This is the point of the policy, not an accident. Users on those
  versions can read the warning and decide; kolt does not block
  them.

### Neutral

- **`KOLT_DAEMON_KOTLIN_VERSION` survives, with a changed meaning.**
  The constant and its 6-pin verify stay. Only the framing
  ("contract" → "baseline") changes.
- **`--no-daemon` contract is unchanged.** Opting out of the daemon
  opts out of BTA, which opts out of this ADR's policy surface.
  That exit remains available at any Kotlin version.
- **ADR 0019 substance is unchanged for 2.3.x.** Only §1's pin
  wording is generalised. Adapter, wire protocol, IC state layout,
  failure classification all stand.

## Alternatives Considered

1. **Hard floor 2.3.0 from day one.** Reject `kolt build` with an
   actionable error for `config.kotlin < 2.3.0`. Rejected because it
   cuts off users trying kolt against existing 2.2.x projects before
   they learn whether their build would otherwise work. The
   subprocess fallback already does the right thing; blocking it
   buys cleaner CI at the cost of user discoverability. Reconsidered
   at each backward evaluation point per §4.

2. **Pure soft floor with no sunset path.** Keep the warning forever,
   never move to a hard floor. Rejected because it turns "best-effort"
   into "permanent second class", with no triage priority ever
   improving. The §4 periodic re-evaluation with the §5 matrix gives
   the soft floor a concrete way to graduate.

3. **Load 2.2.x via a V1 compatibility shim + custom parent loader.**
   Expose `internal.compat.KotlinToolchainsV1Adapter` by widening
   the parent loader beyond `api.*` so the 2.2 BTA impl can find it.
   Spike estimated 1–2 days and a relaxation of ADR 0019 §3's
   classloader contract. Rejected: the shim is explicitly `internal.*`,
   can be renamed or removed by JetBrains in any tooling patch with
   no binary-compat obligation, and the reward (2.2.x daemon support)
   is not load-bearing for kolt's target audience. Documented here
   for the future maintainer who will, inevitably, consider it again.

4. **Per-minor-line adapter JARs (2.2.x adapter + 2.3.x adapter).**
   2.2.x's entry point is `CompilationService`, a different surface
   from 2.3.x's `KotlinToolchains`. Supporting both means two adapter
   code paths, both shipped, both maintained, both bumped each time
   BTA moves. Rejected: estimated 1 week up-front plus continuous
   maintenance, against a user base kolt is not targeting at floor
   2.3.0.

5. **Declare "support kotlinc, not daemon" for `< 2.3`.** Announce
   that `< 2.3` is supported via subprocess as a first-class path.
   Rejected because the subprocess path is not a kolt-value-delivering
   path (see Context) and CI does not exercise it. Calling it
   first-class would be a contract we cannot keep.

6. **Time-based sunset ("hard floor at 2027-07-01").** Pick a date
   and hard-floor regardless of state. Rejected: this ADR cannot see
   2027 from 2026. If Kotlin's cadence changes, if 2.4 / 2.5 forward
   spikes all go RED, or if `< 2.3` usage remains significant, a
   date-driven sunset makes a decision in the wrong direction. §4
   and §6 keep the decision attached to the evidence at the
   evaluation point.

## Follow-ups (not decisions of this ADR)

- **#138 per-version daemon spawn** — the concrete implementation of
  §7, including `BtaImplFetcher`, `--bta-impl-jars` CLI wiring, and
  the transition from the #136 stop-gap subprocess route to the
  per-version daemon route for `2.3.x != BUNDLED`.
- **2.4.0 forward re-evaluation** — file an issue when Kotlin 2.4.0
  stable ships. Action: re-run `spike/bta-compat-138/` with the new
  version appended to the matrix; update `REPORT.md`; on GREEN, bump
  the daemon's supported set per §3.
- **2.6.0 backward re-evaluation** — file an issue when Kotlin 2.6.0
  stable ships. Action: apply §5's matrix, factor in §6's coupling
  rule, decide soft/hard.
- **Telemetry for `reportFallback` hit rate** — not a blocker for
  this ADR, but the §5 matrix's "user base" axis is judgment today
  and could be evidence later. If kolt grows telemetry, the §2
  warning's emission count is the load-bearing signal.
- **Warning wording polish** — the §2 text is a sketch. Finalised
  during the #138 implementation PR, when the `reportFallback` rail
  is edited.

## Related

- #135 — unified bug tracker (Bug 1: daemon silently uses bundled
  Kotlin; Bug 2: daemon IC state survives `kolt clean`, resolved in
  PR #137)
- #136 — Bug 1 stop-gap, merged as PR #139 (`d1f96d0`)
- #138 — per-version daemon spawn; this ADR's implementation vehicle
- #140 — BTA binary-compat spike, merged as `ce9c851`
- ADR 0009 — `ensureKotlincBin` provisioning (template for
  `BtaImplFetcher`)
- ADR 0016 — warm JVM compiler daemon (the process this ADR
  version-segments)
- ADR 0019 — incremental compile via BTA (§1's single-version pin
  is superseded here; every other section stands)
- ADR 0020 — daemon scope is compile-only (unaffected; this ADR
  operates within the scope 0020 defined)
- `spike/bta-compat-138/REPORT.md` — full matrix results, failure
  stack traces, and the reproducible harness
