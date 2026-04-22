---
status: accepted
date: 2026-04-22
---

# ADR 0027: Runtime classpath manifest for JVM `kind = "app"` builds

## Summary

- Emit `build/<name>-runtime.classpath` alongside `build/<name>.jar` for
  JVM `kind = "app"` builds. Plain text, one absolute jar path per line,
  dependencies only (self jar excluded), sorted alphabetically by file
  name (§1).
- Keep `.kolt.lock` as pure resolution state. Runtime jar paths depend
  on the local `~/.kolt/cache` layout and do not belong in a
  checked-in lockfile (§2).
- `scripts/assemble-dist.sh` is the sole in-tree consumer. It reads
  the self jar plus the manifest, copies both into
  `libexec/<daemon>/{<name>.jar,deps/*.jar}`, and generates the
  platform-specific `libexec/classpath/<daemon>.argfile`. kolt does
  not know the tarball layout (§3).
- JVM `kind = "app"` is the only emitter. `kind = "lib"` omits the
  manifest; downstream consumers resolve their own runtime. Native
  targets have no JVM classpath to describe (§4).
- `kolt run` continues to assemble classpath in-process from the
  resolver's return value. The manifest is for **external** JVM
  launchers only; no second resolver pass, no round-trip through
  the file (§5).

## Context and Problem Statement

ADR 0018 §5 reduced the daemon self-host gap (#97) to one kolt
feature: "JVM `kind = "app"` build path with a runtime classpath
manifest". A dogfood spike on 2026-04-22 confirmed that the `kind =
"app"` compile and thin-jar steps already work — both
`kolt-jvm-compiler-daemon` (with `:ic` sources merged in) and
`kolt-native-compiler-daemon` build end-to-end on the existing
`kind = "lib"` JVM pipeline (#222). The only observable gap is that
nothing emits the list of resolved runtime jars that
`scripts/assemble-dist.sh` needs to stitch the release tarball.

The question this ADR answers: **what file does kolt write so that
`assemble-dist.sh` can drop jars into `libexec/<daemon>/deps/` and
emit the `java @argfile` without re-running dependency resolution?**

ADR 0018 §5 deferred the schema choice explicitly — lockfile key vs
separate file, exact shape. This ADR pins it.

The spike above was recorded as a comment on #97 (2026-04-22); no
separate spike report file is carried alongside this ADR.

## Decision Drivers

- `assemble-dist.sh` is a thin stitcher (ADR 0018 §4). It must work
  from kolt's build outputs without knowing the `~/.kolt/cache`
  layout or re-running Maven resolution.
- `.kolt.lock` is the resolution contract (ADR 0003). Artifact paths
  are host-local and portable-lockfile hostile; keeping them separate
  preserves the lockfile's single purpose.
- mtime-based build cache (ADR 0007) reuses artifacts under `build/`;
  a build artifact sitting next to `<name>.jar` lands naturally
  inside that cache domain.
- `java @argfile` format is platform-specific (`:` vs `;` separator).
  kolt emits a platform-neutral input; the final argfile is produced
  at tarball-assembly time by the consumer that knows the target
  platform.
- `kind = "lib"` artifacts are consumed by downstream builds that do
  their own resolution. A baked-in host path list would be the wrong
  contract and would encode the publisher's cache location into the
  jar's neighborhood.

## Decision Outcome

Chosen option: **emit `build/<name>-runtime.classpath` as a separate
plain-text file**, because it keeps `.kolt.lock` focused on
resolution state, stays cache-aware via ADR 0007, and leaves the
platform-dependent argfile concerns with the stitcher that actually
knows the target platform. Alternatives (lockfile key, pre-assembled
argfile) are listed at the end.

### §1 Emit format

For every JVM `kind = "app"` build, kolt writes
`build/<name>-runtime.classpath` with the following shape:

```
/home/alice/.kolt/cache/com.akuleshov7/ktoml-core-jvm/0.7.1/…/ktoml-core-jvm-0.7.1.jar
/home/alice/.kolt/cache/com.michael-bull.kotlin-result/kotlin-result-jvm/2.3.1/…/kotlin-result-jvm-2.3.1.jar
/home/alice/.kolt/cache/org.jetbrains.kotlin/kotlin-build-tools-api/2.3.20/…/kotlin-build-tools-api-2.3.20.jar
/home/alice/.kolt/cache/org.jetbrains.kotlinx/kotlinx-serialization-json-jvm/1.7.3/…/kotlinx-serialization-json-jvm-1.7.3.jar
/home/alice/.kolt/cache/org.jetbrains/kotlin-stdlib/2.3.20/…/kotlin-stdlib-2.3.20.jar
```

Rules:

- **Plain text, UTF-8, LF line endings, no trailing blank line.** No
  header, no comment syntax. One jar per line.
- **Absolute paths.** The jars live under `~/.kolt/cache` after
  resolution; the consumer does not need to walk the cache.
- **Deps only.** `build/<name>.jar` is NOT listed; its path is a
  function of `KoltConfig.name` and the consumer computes it
  directly. Listing self would invite a duplicate when the consumer
  composes `<self>:<deps>`.
- **Alphabetical by last path component (file name).** Stable order
  across rebuilds and across machines; diffs in the manifest
  correspond to real dependency changes, not resolver internals.
  On a file-name collision (rare: rename/shading across GAVs),
  tiebreak by the full `group:artifact:version` string of the
  resolver entry.
- **Kotlin stdlib is included.** The JVM resolver treats stdlib as
  a normal transitive dependency (ADR 0011's skip is native-only,
  since konanc bundles stdlib in its distribution). The daemon JVM
  has no ambient stdlib, so listing it is required for launch.
- **Transitive closure, post-exclusion.** The manifest is the
  effective runtime classpath: exactly what the resolver selected
  after BFS, version intervals, and `exclusions` (the Phase 3
  algorithm shipped in v0.3.0).

Helper `outputRuntimeClasspathPath(config): String` in
`kolt/config/Config.kt` parallels `outputJarPath(config)` /
`outputNativeKlibPath(config)`. Callers do not hand-build the path.

### §2 Why not in `.kolt.lock`

`.kolt.lock` records the resolution decision: GAV coordinates,
resolved version, and integrity metadata that lets another machine
re-derive the same graph. An absolute path like
`/home/alice/.kolt/cache/…` is neither portable across machines nor
stable across a `kolt cache clean`. Storing it in the lockfile would
muddle two contracts:

- the portable part (GAVs + integrity) belongs in version control;
- the host-local part (where the jar sits on this machine) is a
  derived artifact and belongs under `build/`.

Consequence: `.kolt.lock` stays single-purpose. Future lockfile
schema changes (ADR 0003 successors) do not have to carve out a
"host-local" subsection.

### §3 `assemble-dist.sh` contract

The stitcher reads two inputs per daemon:

- `<daemon>/build/<daemon>.jar` — the self jar (ADR 0025 §3 shape).
- `<daemon>/build/<daemon>-runtime.classpath` — this ADR's manifest.

And writes, per platform:

1. `libexec/<daemon>/<daemon>.jar` — copy of the self jar.
2. `libexec/<daemon>/deps/*.jar` — copy of every path listed in the
   manifest, preserving the file name.
3. `libexec/classpath/<daemon>.argfile` — generated with the
   platform-appropriate separator (`:` on POSIX, `;` on Windows),
   one `-cp` line, `kolt.daemon.MainKt` or `kolt.nativedaemon.MainKt`
   on the final line.

kolt itself never references `libexec/` and never writes an argfile.
The manifest is the handshake; the layout in §1 of ADR 0018 is the
stitcher's concern alone.

### §4 Kind / target matrix

| Target | Kind | Manifest emitted? |
|--------|------|-------------------|
| jvm | app | **yes** |
| jvm | lib | no — downstream resolves its own runtime |
| linuxX64 (and other native) | app | no — native binary, no JVM classpath |
| linuxX64 (and other native) | lib | no — klib directory, not a JVM artifact |

The "no" cases are active — kolt MUST NOT produce
`build/<name>-runtime.classpath` for them. A regression test in
`JvmLibraryInvariantsTest` / `NativeStagePlanTest` pins the
non-emission for lib and native paths.

### §5 Relationship with `kolt run`

JVM `kolt run` today composes the classpath in-process from the
resolver's return value (see `BuildCommands.kt:runCommand`, where
`classpath` is handed straight to the `java` subprocess). It does
**not** read the manifest. The manifest is strictly for external
launchers (i.e. `assemble-dist.sh` and any future tooling that
needs to launch the jar without linking kolt's resolver).

This asymmetry is deliberate: an in-process consumer has the
resolver's typed output and does not need to parse a text file; an
external consumer has neither. Routing both through the file would
add a read-write round trip to `kolt run` for no benefit.

### Consequences

**Positive**
- #97 reduces to implementing this one emit step plus writing
  `assemble-dist.sh`. No further schema decisions block self-host.
- `.kolt.lock`, `build/<name>.jar`, and the new manifest each have
  one purpose. Future schema work touches exactly one of them.
- Spike-proven that `kind = "app"` JVM compile and thin-jar steps
  already work (§Context), so the implementation is scoped to
  "write a file after the jar step".

**Negative**
- Absolute paths bind the manifest to the build host's cache
  layout. A manifest rsync'd to another machine with a different
  `~/.kolt/cache` path is invalid until rebuilt. This is acceptable
  because `assemble-dist.sh` always runs on the same host as the
  `kolt build` that produced the manifest.
- A future change that moves jars into the project tree (e.g. a
  `vendor/` directory) would invalidate any manifest emitted under
  the old layout. This is a general property of cache-derived
  artifacts and is bounded by the mtime cache (ADR 0007) — a
  cache-location change invalidates the jar artifacts anyway.
- The manifest duplicates information already present in
  `.kolt.lock` + `~/.kolt/cache` state. A resolver refactor that
  re-derives jar paths from lockfile coordinates lives entirely
  inside kolt; the manifest's contract is stable against it.

### Confirmation

- `outputRuntimeClasspathPath(config)` exists in `Config.kt`
  alongside the existing artifact-path helpers. Callers compute
  paths through it.
- A new test file (`JvmAppBuildTest` or similar) pins: JVM `kind =
  "app"` builds emit the manifest with the §1 rules; JVM `kind =
  "lib"` does not; native builds do not.
- `scripts/assemble-dist.sh` — when it lands — reads the manifest
  per §3 and is the enforcer of the consumer contract.
- A regression test pins that `kolt run` (JVM app path) does not
  open `build/<name>-runtime.classpath`; the §5 asymmetry lives in
  code, not in docs alone. Easiest form is a test that deletes the
  manifest after `kolt build` and asserts `kolt run` still
  succeeds.
- ADR text and emit code stay in sync via PR review; the manifest
  shape has no schema registry outside this ADR until a third
  consumer exists.

## Alternatives considered

1. **`runtime_classpath` array inside `.kolt.lock`.** Rejected.
   `.kolt.lock` is checked in on many projects; host-local absolute
   paths belong nowhere in that file. The schema would also need
   kind-conditional presence (`kind = "lib"` has no runtime
   classpath to describe), enlarging the lockfile reader surface for
   no gain over a build artifact.
2. **Kolt emits a ready-made `@argfile` (`-cp <paths>` plus main
   class).** Rejected. The argfile uses a platform-specific
   separator (`:` vs `;`) and also carries JVM tuning flags (`-Xmx`
   etc.) per ADR 0018 §2. Emitting a single argfile binds the
   manifest to the build host's OS; baking tuning defaults
   regresses §2 — operators must be able to tune the installed
   tarball without rebuilding kolt. The manifest stays pre-argfile
   so the stitcher, which knows the target platform, finishes
   the job.
3. **Kolt emits per-platform argfiles (`*.posix.argfile` +
   `*.win.argfile`).** Rejected as a variant of alternative 2.
   Solving the separator question does not solve the tuning-flag
   bake-in; kolt would still decide JVM flags at build time
   instead of at tarball-assembly or operator-edit time.

## Related

- #97 — self-host gap; this ADR closes the last schema question
  listed in its "remaining blocker" section.
- ADR 0018 §5 — defers the schema pinned here; §3 above implements
  the contract `assemble-dist.sh` relies on.
- ADR 0025 §3 — JVM lib artifact shape; this ADR's `kind = "app"`
  output is the same thin jar plus one companion file.
- ADR 0003 — TOML config / JSON lockfile boundary; §2 above
  preserves it.
- ADR 0007 — mtime-based build cache; the manifest participates as
  a normal `build/` artifact.
- ADR 0016 / ADR 0024 — warm JVM compiler daemon / native compiler
  daemon; the two consumers of `assemble-dist.sh`'s argfile.
- Dogfood spike 2026-04-22 — recorded inline in Context; both
  daemons' `kolt build` succeeded on the current `kind = "lib"`
  JVM pipeline with self-jar output, confirming that the missing
  piece is exactly this manifest.
