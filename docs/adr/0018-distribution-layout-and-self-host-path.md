---
status: proposed
date: 2026-04-22
---

# ADR 0018: Distribution layout and the path to self-host

## Summary

- Ship kolt as a platform-specific tarball (`bin/` + `libexec/`) via
  GitHub Releases, installed by a `curl | sh` script, matching the
  UX of deno, bun, rye, uv (§1).
- Launch both daemons via `java @<argfile>` against a resolved
  classpath, not `java -jar <fat>.jar`. Removes fat-jar packaging
  from kolt's surface and sidesteps `META-INF/services` merge bugs
  (§2).
- Keep the two daemons as independent Gradle builds reached via
  `includeBuild`. This step shipped 2026-04-14 with the first cut of
  this ADR (§3).
- Assemble tarballs with `scripts/assemble-dist.sh`; install with a
  published `install.sh`. Both land with the first release (§4).
- Self-host of the daemons reduces to two kolt features: JVM
  `kind = "app"` emitting a runtime classpath manifest, and a
  multi-module `kolt.toml` schema. Fat-jar support is **not** on the
  path to self-host (§5).
- Bundle daemon jars inside the tarball rather than auto-provisioning
  them; separate provisioning would force a `protocolVersion`
  handshake into the wire protocol (§6).

## Context and Problem Statement

ADR 0016 landed the warm JVM compiler daemon as kolt's default compile
backend, and ADR 0017 decided that the bootstrap JDK is auto-provisioned
into `~/.kolt/toolchains/jdk/<bootstrap>/` rather than bundled. ADR 0024
added a second daemon (`kolt-native-daemon/`) with the same JVM
packaging shape. Together those decisions leave kolt's Phase A
shippable from a behavior standpoint but silent on four related
questions that decide how users actually get kolt onto their machines:

1. **What does a kolt release tarball look like?** The native binary
   (`kolt.kexe`) and the two daemon jars are separate build artifacts
   produced by different toolchains (Kotlin/Native via konanc, JVM via
   Gradle + shadow). Nothing in the repo assembles them into a single
   distributable today.
2. **How does the user install that tarball?** Manual `tar xzf` is
   acceptable but uncommon among comparable tools; the expected modern
   UX is `curl -fsSL <url> | sh`.
3. **How do the daemon jars get launched at runtime?** The first cut of
   this ADR assumed `java -jar <fat>.jar`, which forces kolt itself to
   grow fat-jar packaging support (or keep Gradle permanently in the
   loop). An alternative — `java -cp <deps>:<daemon>.jar <MainKt>` —
   was not considered in the first cut and is the trigger for this
   revision.
4. **What is the long-term story for self-host?** kolt self-hosts its
   native binary today from `kolt.toml` (see `self-host-smoke.yml`).
   The daemon jars are the one piece kolt cannot build by itself yet.
   Issue #97 lists the gap as JVM app target + multi-module +
   fat-jar. The distribution strategy chosen here decides which of
   those three kolt has to grow.

The immediate trigger for this revision is that ADR 0025 shipped on
2026-04-22 (alongside #30/#222), pinning the `kind = "lib"` artifact
shape for both native and JVM. Once that landed, the daemon self-host
question became concrete: does `kind = "app"` on JVM follow the same
pipeline, and what is it required to produce? The fat-jar assumption
in the first cut of this ADR was blocking a clean answer.

## Decision Drivers

- **One-command install.** Users must be able to install kolt with a
  single shell command, without a system package manager and without
  Gradle on their host.
- **No `META-INF/services` merge.** Collapsing many jars into one
  requires correct handling of `META-INF/services` aggregation,
  duplicate resources, and manifest merging — the most common
  fat-jar failure mode.
- **Daemon start latency unchanged.** Any new launch scheme must not
  regress the warm-daemon targets from ADR 0016 nor the subprocess
  fallback latency already measured in Phase A.
- **Self-host features must be independently justifiable.** Any new
  `kolt.toml` axis must have a user-visible reason beyond "the daemon
  needs it".
- **No new wire-protocol version discipline pre-emptively.** Splitting
  release engineering across two pipelines (tarball + daemon) would
  demand a `protocolVersion` handshake; defer that cost.

## Considered Options

- **Option A** — Tarball + `curl | sh` install + classpath-launched
  daemons. Fat-jar packaging deliberately avoided.
- **Option B** — Tarball + `curl | sh` install + fat-jar daemons (the
  direction in this ADR's first cut).
- **Option C** — Self-extracting single binary: `kolt.kexe` with the
  runtime archive appended to the executable, extracted to a
  per-version cache on first use.
- **Option D** — Auto-provisioned daemon jars downloaded on first use
  into `~/.kolt/toolchains/daemon/<version>/`, analogous to ADR 0017's
  JDK provisioning.
- **Option E** — Split the daemon into its own repository. Kept from
  the first cut of this ADR as an alternative to revisit.

## Decision Outcome

Chosen option: **Option A**, because it meets every driver while
adding the least new machinery on both sides. Classpath launch
removes the fat-jar requirement from kolt's build-tool surface, and
the tarball + `install.sh` pattern matches the install UX that
comparable tools (deno, bun, rye, uv) have already normalized.

### §1 Tarball as the release artifact

Platform-specific tarballs published on GitHub Releases. The tarball
extracts to a versioned top-level directory under
`~/.local/share/kolt/<version>/`; `install.sh` then links the active
version into `~/.local/bin/kolt` (§4).

```
kolt-<version>-linux-x64.tar.gz

kolt-<version>-linux-x64/
├── bin/
│   └── kolt                              (= kolt.kexe)
├── libexec/
│   ├── kolt-compiler-daemon/
│   │   ├── kolt-compiler-daemon.jar      (thin class jar; see §2)
│   │   └── deps/*.jar                    (resolved runtime jars)
│   ├── kolt-native-daemon/
│   │   ├── kolt-native-daemon.jar
│   │   └── deps/*.jar
│   └── classpath/
│       ├── compiler-daemon.argfile
│       └── native-daemon.argfile
└── VERSION                               (bare semver, matches git tag)
```

`bin/kolt` locates `libexec/` by resolving its own executable path
via a platform resolver (`/proc/self/exe` on Linux,
`_NSGetExecutablePath` on macOS, `GetModuleFileNameW` on Windows) and
taking the `../libexec/` peer. Symlinks are followed, so
`~/.local/bin/kolt` → `<prefix>/bin/kolt` works. The tarball is the
**unit of relocation**; `cp bin/kolt` elsewhere breaks the anchor and
is unsupported. Per-daemon `deps/` are not deduplicated across
daemons — the isolation simplifies classpath resolution and keeps
each daemon's thin jar + deps co-located.

The layout of kolt's runtime tree on disk (under `~/.kolt/`) is
unchanged by this ADR; `libexec/` is part of the **release**, not
the runtime cache.

### §2 Classpath launch for daemons

Both daemons launch via:

```
java @<libexec>/classpath/<name>.argfile
```

with main-class FQNs `kolt.daemon.MainKt` (compiler daemon) and
`kolt.nativedaemon.MainKt` (native daemon).

**Argfile format** (one switch per line, absolute paths, no quoting
required):

```
-cp
<libexec>/kolt-compiler-daemon/kolt-compiler-daemon.jar<SEP><libexec>/kolt-compiler-daemon/deps/<a>.jar<SEP>...
-Xmx<tune>
kolt.daemon.MainKt
```

`<SEP>` is `:` on POSIX and `;` on Windows. The argfile is generated
per-platform at `assemble-dist.sh` time; `@argfile` is a plain text
substitution and does not normalize separators. JVM tuning flags
(`-Xmx`, `-XX:...`) live in the argfile, not in the `bin/kolt`
command line, so daemon tuning is a text edit on the installed
tarball rather than a kolt recompile.

Consequences:

- No fat-jar packaging step anywhere in kolt's pipeline.
- `shadowJar` stays in the daemon Gradle builds through the first
  release that ships `assemble-dist.sh`, then comes out once
  repo-wide callers (§Confirmation) are migrated.
- `DaemonJarResolver` (and its native-daemon peer) becomes a
  classpath resolver: given `libexec/<daemon>/`, return the argfile
  path. No `-all.jar` slot exists under `libexec/`.
- `META-INF/services` files stay in their own jars and the JVM's
  `ServiceLoader` aggregates them normally.
- Java `@argfile` exists to bypass OS `ARG_MAX`, so classpath length
  is not a concern even as transitive deps grow.
- kolt's existing Maven resolver + lockfile already produces the
  runtime jar list; argfile content is derivable at assemble time
  without new resolution logic.

### §3 Monorepo with independent Gradle builds

The two daemon subprojects are reached from the root build via
`includeBuild`, not `include` (shipped 2026-04-14). Plugin pins,
Kotlin versions, and release cadences are independent per subproject,
while `./gradlew build` at the root still rebuilds both through an
explicit `dependsOn`. Moving to two repositories remains deferred
(Option E).

### §4 `assemble-dist.sh` and `install.sh`

Two scripts, both outside kolt's build graph. They are small in
spirit but not trivial in scope:

- `scripts/assemble-dist.sh` **consumes** pre-built Gradle outputs;
  it does not invoke Gradle itself (CI runs `./gradlew build` first).
  Inputs: native kexe path, each daemon's thin class jar, and each
  daemon's resolved `runtimeClasspath` as a text manifest emitted by
  a small Gradle task. Outputs: the tarball in §1. Post-self-host
  the input manifests come from kolt's lockfile rather than Gradle,
  with no change to the tarball shape.
- `install.sh` detects platform (`uname -s`/`uname -m` whitelist;
  unsupported host fails loudly), downloads the matching tarball
  from the latest GitHub Release, extracts to
  `~/.local/share/kolt/<version>/`, and symlinks
  `~/.local/bin/kolt`. First release covers fresh install only.
  Upgrade (replace the symlink, keep old versions for rollback),
  uninstall, and shell-rc `PATH` bootstrap (bash/zsh/fish) are
  follow-ups tracked separately; deno/bun's install scripts run to
  200+ lines because of those, so a mature kolt `install.sh` will
  too. Hosted initially at the GitHub Releases raw URL; a stable
  `https://kolt.dev/install.sh` redirect is a later concern.

Neither script exists today. Both land with the first real release.

### §5 Daemon self-host requirements

Under classpath launch, self-hosting the daemons requires two kolt
features, both with independent justifications outside the daemon:

1. **JVM `kind = "app"` build path with a runtime classpath manifest.**
   ADR 0025 pinned `kind = "lib"` on JVM as a thin class jar; the app
   form is the same jar plus a declared runtime classpath. kolt
   already resolves the classpath during dependency resolution
   (`DependencyResolution.kt` builds the list fed into
   `--compiler-jars` today), so the new work is emitting it into a
   consumable manifest. Schema — lockfile key vs separate file,
   exact shape — is deferred to a follow-up ADR; §4's
   `assemble-dist.sh` reads whatever that ADR pins.
2. **Multi-module `kolt.toml`.** The repo layout is a top-level
   native project plus two JVM app subprojects. kolt.toml today is
   strictly single-project. The needed schema covers workspace root
   discovery, per-module configs, cross-module dependency declaration,
   and a shared lockfile — **comparable in scope to #30 / ADR 0025**,
   not a small extension. Any monorepo user-facing project needs the
   same feature; the daemon just pulls its timeline forward.

Fat-jar packaging is **not** on this list. Issue #97 will be
rewritten to remove it.

### §6 Bundle vs auto-provision

Daemon jars ship inside the tarball rather than being downloaded on
first use. A single git SHA produces both the native binary and the
daemon jars, so users get a matched set and no `protocolVersion`
handshake is needed. Auto-provisioning as a separate toolchain
(Option D) remains open for later; trigger conditions are in its
Pros and Cons entry.

If Option D is ever triggered, the **provisioned form remains
thin-jar + `deps/`**, not a fat jar. The no-fat-jar commitment is
on the packaging format (§2), independent of the delivery channel.
Option D therefore adds a wire-protocol handshake and a provisioning
pipeline but does **not** re-introduce fat-jar packaging.

### Consequences

**Positive**

- Path from an unconfigured machine to a working `kolt build` is
  one shell command.
- No new packaging feature is required inside kolt to reach
  self-host; remaining work extends existing pipelines.
- Fat-jar merge bugs are out of scope for kolt's packaging format
  permanently (§6 anchors this even under Option D).
- Daemon runtime classpath is described by kolt's own lockfile once
  self-host lands. Protocol-version coupling stays at the native-binary
  level; the tarball remains the only release-engineering pipeline.

**Negative**

- Distribution grows a new script surface (`assemble-dist.sh`,
  `install.sh`). Both are small and platform-neutral in content but
  they cross CI and release tooling.
- A kolt install is multi-file on disk (`bin/kolt` plus a
  `libexec/` tree). Users expecting a Go-style single-file binary
  will be mildly surprised; the tarball README covers this.
- Multi-module `kolt.toml` becomes the critical path for daemon
  self-host. It was on the roadmap anyway for monorepo projects,
  but the daemon pulls its timeline forward.
- `./gradlew build` still relies on an explicit `dependsOn` wiring
  to rebuild the `includeBuild`-included daemons; `unit-tests.yml`'s
  jar-existence assertion catches regressions (from #99 review).

### Confirmation

- `scripts/assemble-dist.sh` exists and CI runs it on tag push,
  uploading the tarball to the matching GitHub Release.
- A smoke test extracts the produced tarball into a clean directory
  and runs `./bin/kolt --version` with no `~/.kolt/` state; must
  succeed.
- `install.sh` is exercised by a `curl | sh` CI job at least once
  per release.
- `shadowJar` removal is gated on a repo-wide grep for `-all.jar`
  and `shadowJar` references; each caller (CI workflow, dev
  fallback resolver, docs) is updated before the Gradle task is
  deleted. `unit-tests.yml`'s current assertion on
  `kolt-compiler-daemon-all.jar` is the most visible caller.
- Self-host follow-up issues (including the rewritten #97) cite
  this ADR as the authority for "no fat-jar".

## Pros and Cons of the Options

### Option A — Tarball + classpath launch

- Good, because classpath launch reuses kolt's existing resolver
  and lockfile; no new kolt feature is required to produce the
  argfile content.
- Good, because it avoids `META-INF/services` merge complexity,
  the single most common fat-jar failure mode.
- Good, because the install UX matches deno / bun / rye / uv and
  does not require Gradle on the user's machine.
- Bad, because the install is multi-file on disk; users who expect
  a Go-style single binary are mildly surprised.
- Neutral, because distribution requires two new scripts
  (`assemble-dist.sh`, `install.sh`) maintained outside kolt's
  build graph.

### Option B — Tarball + fat-jar daemons

- Good, because `java -jar <daemon>.jar` is the most widely
  understood JVM launch form.
- Good, because the on-disk layout is simpler (one jar per daemon
  under `libexec/`).
- Bad, because kolt must grow fat-jar packaging support, including
  `META-INF/services` merge rules, duplicate-resource handling,
  and manifest merging. This is the complexity Gradle Shadow owns
  today; reproducing it is a non-trivial feature.
- Bad, because this full feature then sits on the critical path to
  self-host (#97's "option 2" estimate is dominated by it).
- Bad, because inlined `kotlin-compiler-embeddable` inside the fat
  jar couples daemon hotfixes to a full daemon-tarball respin.

### Option C — Self-extracting single binary

- Good, because the user sees exactly one file; matches the Go
  binary UX precisely.
- Good, because it does not preclude Option A's on-disk layout —
  the extracted tree **is** the Option A layout, just produced at
  first run instead of at install time.
- Bad, because it adds a self-extract subsystem: trailer format
  spec, content-addressed cache layout, concurrent-invocation
  locking, integrity verification, and `assemble-dist.sh` changes
  to produce the appended archive. Not one code path — a small
  feature.
- Bad, because first-run pays extraction latency; subsequent runs
  pay nothing.
- Neutral, because the resulting binary is ~50 MB, comparable to
  the Gradle wrapper distribution — not prohibitive.
- Deferred, not rejected. Option A is a precondition for Option C,
  and the A→C jump is additive — Option A callers keep working.

### Option D — Auto-provisioned daemon jars

- Good, because daemon hotfixes can ship without re-downloading
  kolt itself; multiple native-client versions can share a daemon
  cache.
- Good, because it reuses the ADR 0017 / ADR 0009 provisioning
  machinery directly.
- Bad, because it demands a `protocolVersion` field in
  `Ping`/`Pong` to reject mismatched pairs. The wire protocol has
  no such field today, and adding one pre-emptively is speculative.
- Bad, because release engineering splits across two pipelines
  (tarball + daemon jar publish), doubling the chance a release
  ships half-broken.
- Deferred, not rejected. Revisit when either (a) the bundled jar
  size becomes a real download problem, or (b) a daemon-only
  hotfix scenario actually arises.

### Option E — Two repositories

The daemon is not a reusable library and has no external consumers,
so cross-repo versioning overhead is pure cost. Retreat is cheap:
`git subtree split` carves the daemon out later with history
preserved. Trigger conditions that would force this:

- An external consumer (IntelliJ plugin, `kotlin-lsp`, another
  build tool) depends on the daemon jar directly.
- Multiple native-client versions need to coexist against a single
  daemon deployment.
- Protocol semver becomes a real operational concern — a protocol
  change that must roll out without breaking in-flight users.

## Related

- #14 — parent issue for compiler-daemon work.
- #97 — self-host gap; §5 above redefines its scope (fat-jar
  removed, multi-module + JVM app target remain).
- #3 — incremental compilation (Phase B); its cross-cutting
  protocol changes benefit from the monorepo layout (§3).
- #15 — `kolt watch` (Phase C); same rationale as #3.
- ADR 0009 — auto-install managed toolchains (the pattern Option
  D would reuse).
- ADR 0016 — warm JVM compiler daemon (defines the artifact being
  distributed).
- ADR 0017 — bootstrap JDK provisioning (the adjacent toolchain
  slot under `~/.kolt/`).
- ADR 0023 §1 — target/kind schema (the `kind = "app"` JVM slot
  §5 extends).
- ADR 0024 — native compiler daemon (the second daemon in §1's
  layout).
- ADR 0025 — library artifact layout (the JVM `kind = "lib"` this
  ADR's §2 consumes directly).
- `self-host-smoke.yml` — existing CI workflow for native-side
  self-host; a companion will be needed once daemon self-host
  lands.
- `scripts/assemble-dist.sh` — to be written (§4).
- `install.sh` — to be written (§4).
