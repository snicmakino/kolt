# ADR 0004: Separate pure algorithms from I/O orchestration

## Status

Accepted (2026-04-09)

## Context

Phase 2 landed the first working transitive dependency resolver, and
Phase 3 grew it into a real BFS walk over POMs with
`dependencyManagement`, scope filtering, conflict resolution, and
cycle detection. The whole thing lived in a single file
(`TransitiveResolver.kt`) that interleaved three kinds of work:

1. **Algorithmic logic**: the BFS queue, version selection, exclusion
   propagation, the highest-version-wins rule, cycle detection.
2. **I/O**: downloading POMs over HTTP, writing JAR files to the
   cache, computing and checking sha256 hashes, reading parent POMs
   from disk.
3. **Caching and memoisation**: a `pomLookup` map built up as the
   walk progresses, so each `(groupArtifact, version)` is parsed at
   most once.

Having them in one function meant the tests were painful. To exercise
a corner of the algorithm (say, a diamond dependency where the two
paths disagree on version), the test had to stand up a fake HTTP
server or drop files into a temp directory, then invoke the whole
pipeline. A single bug in version comparison needed an integration
test to reproduce.

It also meant the algorithm was not reviewable on its own. You could
not read the resolver top-to-bottom and understand what rules it
enforced, because every other line was `downloader.download(...)` or
`file.writeBytes(...)`.

Builder and Runner had already been written as pure argv builders
(`Builder.buildCommand`, `Runner.runCommand`) from day one, which
made them trivial to test. The resolver needed the same treatment,
but doing it after the fact meant untangling a live codebase.

Coursier — the JVM Scala/Java dependency resolver — had solved the
same problem with a pattern we could copy: the resolution algorithm
is a **pure state machine** that takes a "fetch function" as a
parameter. It never touches the network itself. The caller wraps it
with an I/O-aware fetcher and runs the whole thing. This cleanly
separates "what should the graph look like" from "how do we get the
bytes to answer that question".

## Decision

Adopt the same pure-core / I/O-shell split for every non-trivial
subsystem in keel. The two halves live in separate files and
communicate only through **function parameters**.

For the resolver specifically:

- **`Resolution.kt`** (pure). Exposes `resolveGraph(deps, pomLookup)`,
  which walks the dependency graph via BFS. It takes a `pomLookup`
  function — `(groupArtifact, version) -> PomInfo?` — as a parameter.
  `Resolution.kt` never reads files, never makes HTTP calls, never
  computes hashes. It is all value-in / value-out.
- **`TransitiveResolver.kt`** (I/O). Provides
  `createPomLookup(cacheBase, downloader, ...)` which returns a
  closure that encapsulates POM downloading, caching, and parsing,
  and `materialize(nodes)` which downloads and verifies the JARs for
  the resolved graph. `TransitiveResolver.resolve()` is the thin
  orchestrator: build the `pomLookup`, hand it to
  `Resolution.resolveGraph`, then `materialize` the result.

The same split is applied elsewhere in the codebase from Phase 2
onwards:

| Pure module              | I/O module                  | Role                              |
| ------------------------ | --------------------------- | --------------------------------- |
| `Resolution.kt`          | `TransitiveResolver.kt`     | Dependency graph BFS              |
| `PomParser.kt`           | `TransitiveResolver.kt`     | POM XML parsing                   |
| `GradleMetadata.kt`      | `NativeResolver.kt`         | `.module` parsing, redirect       |
| `VersionCompare.kt`      | (used by `Resolution.kt`)   | Maven version comparison          |
| `Builder.kt`             | `BuildCommands.kt`          | kotlinc argv construction         |
| `Runner.kt`              | `BuildCommands.kt`          | `java -jar` argv construction     |
| `TestBuilder.kt`         | `BuildCommands.kt`          | Test compile argv                 |
| `TestRunner.kt`          | `BuildCommands.kt`          | JUnit Platform launcher argv      |
| `TestDeps.kt`            | `BuildCommands.kt`          | Auto-injected test dependencies   |
| `Formatter.kt`           | `FormatCommands.kt`         | ktfmt argv construction           |
| `AddDependency.kt`       | `DependencyCommands.kt`     | TOML string manipulation          |
| `DepsTree.kt`            | `DependencyCommands.kt`     | Dependency tree ASCII rendering   |

The rule is: if a function can be expressed as "inputs in, outputs
out, no side effects", it goes into the pure half. Side-effectful
work (filesystem, process spawning, network, clock, hashing over
file contents) is pushed into the I/O half under `infra/` or into a
thin `*Commands.kt` orchestrator under `cli/`.

## Consequences

### Positive

- **Pure code is cheap to test**: `ResolutionTest` constructs a
  hand-written `pomLookup` lambda that returns `PomInfo` values from
  a `Map` literal. Every BFS corner case — diamonds, parent POM
  chains, version intervals, exclusion propagation, cycles — is a
  table-driven unit test with no filesystem, no network, no temp
  directories. This is the single biggest reason keel has a large
  unit-test suite despite being a build tool with heavy I/O.
- **Algorithms are reviewable top-to-bottom**: `Resolution.kt` reads
  as a specification of the rules. The reader never has to keep two
  concerns in their head at once.
- **I/O is swappable**: because the pure core takes a `pomLookup`
  function, a caller can substitute an offline lookup (e.g. build a
  lookup from `keel.lock` directly), or a recording lookup for
  debugging, without touching the resolver logic. `DepsTree.kt`
  actually does this — it reuses the same `pomLookup` signature to
  walk the graph for the `keel tree` command.
- **Parallel development**: the two native resolvers
  (`TransitiveResolver` for jvm, `NativeResolver` for native) share
  `Resolution.kt`'s style but not its state. They were built
  independently against the same pattern and the same pure helpers
  (`VersionCompare`, `PomExclusion` matching), so bug fixes port
  cleanly when they apply.
- **I/O errors have a single home**: every network and filesystem
  failure bubbles up through the I/O wrapper as a `Result<_, E>`.
  The pure core does not have to know that a download can fail, only
  that `pomLookup` can return `null`.

### Negative

- **The split is discipline, not enforcement**: nothing in the type
  system prevents someone from adding `downloader.download(...)`
  inside `Resolution.kt`. The rule is maintained by code review and
  by the package layout. If a contributor is not familiar with the
  convention, it is easy to break.
- **Two files for what was one**: `TransitiveResolver.kt` is now a
  thin orchestrator, and the real logic lives next door. Following a
  call flow means jumping between files.
- **Closures-as-parameters can hide state**: the `pomLookup` closure
  captures a mutable cache (`pomCache`). Readers have to understand
  that the closure is stateful to reason about repeated lookups.
  This is the price of keeping `Resolution.kt` free of I/O.
- **Not every subsystem is worth splitting**: very small pieces of
  logic that happen to call the filesystem once do not benefit from
  a pure shell. The rule is applied only where the pure core has
  enough logic to warrant its own tests.

### Neutral

- **Convention shared with Coursier, not copied verbatim**: keel does
  not implement Coursier's explicit `Done / Missing / Continue` state
  machine. `resolveGraph` is a loop that terminates when the queue is
  empty. The *separation* is what we borrowed, not the specific
  state-machine encoding.
- **No effect system**: we do not use any effect-tracking library
  (Arrow, monadic IO, etc.). Purity is by convention, verified by the
  fact that the pure modules do not import `infra/` or `platform.posix`.

## Alternatives Considered

1. **Leave the I/O interleaved; rely on integration tests** —
   rejected. Integration tests over a real cache directory were
   slow, flaky (mtime races, concurrent temp dirs) and did not cover
   the algorithmic corner cases at the granularity we needed. The
   resolver has a dozen version-conflict rules; exercising them one
   at a time through HTTP fixtures would have been a full-time job.
2. **Mock the `Downloader` interface** — rejected. It would have let
   us drive the resolver from tests, but it only pushed the mixing
   problem down one layer. The resolver's logic would still have been
   spread across an HTTP call site, a cache-layer method, and a
   parser. You cannot unit-test the BFS in isolation just by mocking
   the fetcher.
3. **Adopt an effect system like Arrow `IO` or `Resource`** —
   rejected. Too heavy a dependency and too novel a pattern for a
   Kotlin/Native CLI whose primary goal is fast builds and simple
   code. The convention-based split buys us most of the benefits at
   zero dependency cost.
4. **Port Coursier's full state-machine resolution verbatim** —
   rejected. Coursier's state machine handles concurrent fetching,
   conflict back-propagation, and a much richer conflict model
   (forced versions, reconciliation strategies). keel does not need
   any of that yet. The simpler BFS with highest-version-wins is
   enough for the scope we support, and we can always tighten it
   later without rewriting the I/O shell.

## Related

- `src/nativeMain/kotlin/keel/resolve/Resolution.kt` — the pure BFS
- `src/nativeMain/kotlin/keel/resolve/TransitiveResolver.kt` — the
  I/O orchestrator and `pomLookup` factory
- Commit `91d77fd` (initial extraction of `resolveGraph` from
  `TransitiveResolver`)
- Coursier — design reference for algorithm/fetch separation
