# ADR 0018: Distribution layout and the path to a two-module self-host

## Status

Proposed (2026-04-14) — **in progress**. The direction has been agreed
but implementation lands incrementally. The first step (decoupling
`kolt-compiler-daemon/` into an independent Gradle build reached via
`includeBuild`) landed together with this ADR; everything else is
deferred until Phase B is underway or the first real release is on the
horizon. This ADR will move to Accepted once `scripts/assemble-dist.sh`
and the `bin/` + `libexec/` tarball layout are in place.

## Context

ADR 0016 landed the warm JVM compiler daemon as kolt's default compile
backend, and ADR 0017 decided that the bootstrap JDK is auto-provisioned
into `~/.kolt/toolchains/jdk/<bootstrap>/` rather than bundled. Those
two decisions together leave kolt's Phase A shippable from a behavior
standpoint but silent on three related questions that will decide how
users actually get kolt onto their machines:

1. **What does a kolt release tarball look like?** The native binary
   (`kolt.kexe`) and the daemon fat jar (`kolt-compiler-daemon-all.jar`)
   are separate build artifacts produced by two different toolchains
   (Kotlin/Native via konanc, JVM via Gradle + shadow). Nothing in the
   repo assembles them into a single distributable today. `DaemonJarResolver`
   already reserves a `<prefix>/libexec/kolt-compiler-daemon-all.jar`
   location relative to `bin/kolt`, but no tooling produces that layout.
2. **How does the daemon jar get onto a user's machine?** The two
   candidates are (a) ship it inside the release tarball alongside the
   native binary, or (b) publish it as an independent artifact and
   auto-download it on first use, the same way ADR 0017 handles the
   bootstrap JDK and ADR 0009 handles `kotlinc` / `konanc`.
3. **What is the long-term story for self-hosting?** kolt already
   self-hosts its native binary from `kolt.toml` (see the
   `self-host-smoke.yml` CI workflow). The daemon jar is the one piece
   that cannot be built by kolt itself yet, because kolt has no
   fat-jar output and because the daemon currently lives inside kolt's
   root Gradle multi-project build. Both of these are fixable, and the
   distribution structure we commit to now either helps or hurts that
   path.

The immediate trigger is that the current coupling (`include(":kolt-compiler-daemon")`
in the root `settings.gradle.kts`) makes it a Gradle subproject of the
native-client build. That is convenient for "one command rebuilds
everything" but it conflates two very different artifacts under one
build lifecycle and blocks any future attempt to replace
`kolt-compiler-daemon/build.gradle.kts` with a `kolt-compiler-daemon/kolt.toml`.

## Decision (direction)

The target state is **monorepo, independent builds per artifact,
assembled externally**:

1. **Two independent builds in one repo.** `kolt-compiler-daemon/` is
   a standalone Gradle project (its own `settings.gradle.kts`, its own
   plugin version pins) rather than a subproject of the root build.
   The root build reaches it via `includeBuild("kolt-compiler-daemon")`
   and depends on `:kolt-compiler-daemon:build` from its own `build`
   task, so `./gradlew build` still rebuilds both sides locally. This
   step is **done** as of 2026-04-14 and is what makes this ADR land as
   "in progress" rather than "proposed".
2. **A single repository, not a split.** The daemon is kolt's private
   implementation detail, not a library with third-party consumers.
   Splitting into two repositories would impose semver / protocol-
   version discipline as pure tax without a consumer that benefits, and
   would make the cross-cutting protocol changes that Phase B (#3
   incremental) and Phase C (#15 `kolt watch`) will require into
   two-PR dances. Monorepo keeps those changes atomic.
3. **Assemble the release tarball outside kolt.** Packaging is not a
   build-tool concern. A thin `scripts/assemble-dist.sh` (or a CI
   workflow step, equivalently) takes the two build outputs and places
   them into the layout that `DaemonJarResolver` already expects:
   ```
   kolt-<version>-linux-x64/
   ├── bin/
   │   └── kolt
   └── libexec/
       └── kolt-compiler-daemon-all.jar
   ```
   This script does not exist yet. It will be written when the first
   real release is on the horizon; writing it before then means
   iterating on a tarball that nobody is downloading.
4. **Bundle the jar in the tarball, at least initially.** The daemon
   jar is small (~2.6 MB) and changes in lockstep with the native
   client when the wire protocol moves. Shipping it inside the tarball
   keeps version-compat trivial: a single `git` SHA produces both
   artifacts, and users who get kolt via the tarball get a matched
   set. Auto-provisioning the jar as a separate toolchain (the
   alternative discussed during the design) remains open for later
   but is not required for a first release. See "Alternatives".
5. **The long-term endgame is two `kolt.toml` files.** Once kolt
   supports fat-jar output (`kolt build --fat-jar` or equivalent),
   `kolt-compiler-daemon/build.gradle.kts` can be replaced by
   `kolt-compiler-daemon/kolt.toml`, and kolt builds both halves of
   itself. The `includeBuild` boundary landed in step 1 is the seam
   that makes this migration local: the native side already has its
   own `kolt.toml`, and the daemon side just needs to grow one. The
   fat-jar feature is out of scope for this ADR and for Phase B; this
   ADR records only that we are not doing anything that blocks it.

## Alternatives considered

1. **Two repositories (split `kolt-compiler-daemon` into its own
   repo).** Rejected for now. The daemon is not a reusable library and
   has no consumers beyond kolt itself, so the versioning overhead is
   pure cost. The retreat path is cheap: because the daemon is already
   an independent Gradle build as of step 1, `git subtree split` can
   carve it out later with history preserved. We will revisit this if
   any of the following trigger:
   - An external consumer (IntelliJ plugin, `kotlin-lsp`, another
     build tool) wants to depend on `kolt-compiler-daemon-all.jar`.
   - Multiple native-client versions need to coexist against a single
     daemon (e.g. Linux-only users stuck on an old kolt while Mac/
     Windows users are on a newer one).
   - Protocol semver becomes a real operational concern (i.e. we ship
     a protocol change that has to be rolled out without breaking
     in-flight users).
2. **Auto-provision the daemon jar as a toolchain artifact** (publish
   separately, download on first use into `~/.kolt/toolchains/daemon/<version>/`).
   Deferred, not rejected. This is the more flexible distribution
   model and reuses the provisioning machinery from ADR 0009 and
   ADR 0017, but it demands a `protocolVersion` handshake in
   `Ping`/`Pong` (because the downloaded jar may not match the native
   client's commit) and splits release engineering across two
   pipelines. Neither is worth paying for before we even have
   assemble-dist.sh. Revisit when either (a) the bundled-jar size
   becomes a problem, or (b) users need to pick up a daemon hotfix
   without re-downloading kolt itself.
3. **Bundle a JDK into the release tarball.** Rejected by ADR 0017
   for the same reasons that apply here: ~100 MB tarball blow-up,
   release-engineering coupling to JDK CVEs. The auto-provisioned
   bootstrap JDK remains the right answer.
4. **Keep `include(":kolt-compiler-daemon")`.** Rejected. The two
   artifacts have different toolchains (Kotlin/Native vs JVM),
   different release cadences (once fat-jar support lands), and
   different future build tools (kolt vs Gradle, then kolt vs kolt).
   Keeping them as subprojects of one Gradle build conflates all of
   that under a single lifecycle and actively blocks the self-host
   migration in step 5. The move to `includeBuild` is reversible but
   pays off immediately as a clean boundary.

## Consequences

### Positive

- **The self-host seam is now explicit.** Replacing one Gradle build
  with a `kolt.toml` is a local refactor, not a repo-wide one. The
  native side is already self-hosting via `self-host-smoke.yml`; the
  daemon side is blocked only on the fat-jar feature.
- **Protocol changes remain one-PR atomic.** Because both halves live
  in the same repo and the `includeBuild` wiring ensures `./gradlew build`
  rebuilds both, a PR that edits `src/nativeMain/.../wire/Message.kt`
  and `kolt-compiler-daemon/src/main/.../Message.kt` together stays a
  single reviewable unit. Phase B (#3) and Phase C (#15) both need this.
- **Distribution concerns stop leaking into build files.** Tarball
  layout lives in `scripts/assemble-dist.sh` or CI, not in
  `build.gradle.kts`. If we later change how releases are packaged
  (e.g. adding macOS / Windows native binaries) the build files do
  not need to know.

### Negative

- **Two plugin-version declarations for Kotlin.** The root build and
  the included build both pin Kotlin `2.3.20` in their `plugins`
  blocks. Upgrading Kotlin is now a two-file edit. An obvious
  mitigation (a Gradle version catalog) is available if this grows
  painful, but for two numbers it is not worth the indirection yet.
- **`./gradlew build` at the root relies on an explicit `dependsOn`.**
  With `include` the aggregation was automatic; with `includeBuild`
  we wire it by hand in the root `build.gradle.kts`. A future
  contributor who removes that wiring silently re-introduces the
  stale-jar footgun on the dev-fallback path. The comment on the
  `dependsOn` block flags this, and the `self-host-smoke.yml` CI job
  would notice within one PR because it rebuilds from `linkDebugExecutableLinuxX64`
  and then runs the resulting kolt, which would flip to the subprocess
  fallback if the jar were missing. Not a great safety net — the
  fallback is silent by design — so a regression test here is a
  candidate follow-up.
- **Assemble-dist.sh does not exist yet.** Until it does, there is no
  way to produce a downloadable kolt release; users have to build
  from source. This is acceptable pre-1.0 but will need to land
  before the first tagged release.

### Neutral

- **No change to wire format, concurrency contract, or fallback
  behavior.** All of those are ADR 0016's domain and are unaffected.
- **No change to toolchain provisioning.** ADR 0009 and ADR 0017
  remain authoritative; this ADR does not introduce a new provisioner.

## Open questions

- **When does `scripts/assemble-dist.sh` land?** Proposal: gate it
  on "we want to cut 0.4.0". Not before, because a packaging script
  without a release target is dead weight that will rot against
  Phase B protocol changes.
- ~~**Do we need a regression test for the `./gradlew build → daemon
  jar` wiring?**~~ Resolved in #99 review: `unit-tests.yml` now runs
  `./gradlew build` after `linuxX64Test` and asserts
  `kolt-compiler-daemon/build/libs/kolt-compiler-daemon-all.jar`
  exists. The added CI cost is just the daemon's `shadowJar` (a few
  seconds) because the linuxX64 tasks above already warmed the
  Kotlin Native and Gradle caches.
- **When (if ever) do we switch to auto-provisioned daemon jars?**
  See Alternative 2. The trigger conditions are listed there; none
  are present today.

## Related

- ADR 0016 — warm JVM compiler daemon (defines the artifact that is
  being distributed)
- ADR 0017 — bootstrap JDK provisioning (defines the adjacent
  toolchain slot under `~/.kolt/`)
- ADR 0009 — auto-install managed toolchains (the pattern that would
  be reused if Alternative 2 is adopted)
- `self-host-smoke.yml` — the CI workflow that exercises the existing
  native-side self-host and will need a companion once the daemon
  side migrates to `kolt-compiler-daemon/kolt.toml`
- #14 — parent issue for the compiler-daemon work
- #3 — incremental compilation (Phase B, will drive the first
  cross-cutting protocol change after this ADR lands)
- #15 — `kolt watch` (Phase C)
