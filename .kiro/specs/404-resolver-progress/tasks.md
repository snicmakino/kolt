# Implementation Plan

## Phase 1: Foundation — sink contract, retry callback, signature plumbing

- [ ] 1. Foundation

- [x] 1.1 Define the resolver progress sink contract with a recording-sink test fixture
  - Establish a sink interface with two callbacks: one fired when an artifact's network fetch is about to start, one fired when a 404 advances the per-repository fall-through to the next repository.
  - Provide a no-op companion default so existing resolver consumers inherit silent behavior without code changes.
  - Add a recording-sink test fixture that captures every callback into an ordered list, plus a baseline test that drives the recording sink directly and asserts the recorded sequence matches a manually-constructed reference.
  - Observable completion: the new sink-fixture test passes; `kolt build` and `kolt test` succeed with the new file in place.
  - _Requirements: 1.1, 2.1, 3.3_
  - _Boundary: ResolverProgressSink (new component)_

- [x] 1.2 Surface repository retries from the per-repository fall-through download loop
  - Add a defaulted retry-against callback parameter to the download function that walks the configured repositories on 404 fall-through.
  - Fire the callback exactly when the loop is about to advance to a next repository after an HTTP 404 — never on non-404 errors and never after the final repository in the list (loop exhaustion).
  - TDD: write three failing unit tests using a list-capturing lambda — "404 then 200 fires once with the second repo", "non-404 first attempt fires zero times", "all repositories return 404 fires only on advances and not after the last" — then make them pass.
  - Observable completion: three new unit tests pass; the existing call sites of the download function continue to compile against the default no-op callback (mechanical default-parameter compatibility).
  - _Requirements: 2.1, 2.2, 2.3_
  - _Boundary: TransitiveResolver — downloadFromRepositories signature only_

- [x] 1.3 Thread the sink (defaulted to no-op) through every resolver entry point with no behavior change
  - Add the sink as a defaulted parameter on the top-level resolve dispatcher, the JVM transitive path, the Native target path, the bundle declaration path, the single-artifact bundle path, and the bundle lock-reuse materialization path. Each function forwards the parameter through dispatch only.
  - Do not emit progress in this task and do not propagate the retry callback yet — the sole purpose is parameter wiring so subsequent tasks can land emission independently.
  - Observable completion: `kolt build` and `kolt test` succeed; every existing resolver test continues to pass unchanged because the no-op default preserves prior behavior.
  - _Requirements: 1.1, 1.4_
  - _Boundary: Resolver, TransitiveResolver, NativeResolver, BundleResolver — explicit cross-boundary signature carrier_

## Phase 2: Core — materialize-level emission per resolver path

- [ ] 2. Core emission

- [x] 2.1 (P) JVM transitive materialize emits per-artifact and per-retry progress for JAR fetches
  - Pre-count uncached JAR nodes before the materialize loop so the total `M` is known up front.
  - Emit the artifact-start callback (with 1-based index `N`, total `M`, and the artifact's `groupArtifact:version`) immediately before each uncached-JAR network download, and forward the retry callback to the download function so 404 retries surface against the next repository.
  - Sources fetch path remains silent: it neither emits artifact-start nor forwards a non-default retry callback.
  - TDD with the recording fixture: tests for "three uncached deps record (1,3),(2,3),(3,3)", "one cached + one uncached records only (1,1)", "fully warm cache records nothing", "404 then 200 records one artifact-start followed by one retry-against the second repo", and "binary-cached + missing-sources records nothing for the sources fetch".
  - Observable completion: five new unit tests pass; running the resolver against a fake `ResolverDeps` produces a recorded callback sequence that matches expectations.
  - _Requirements: 1.1, 1.2, 1.3, 1.4, 2.1, 2.2, 2.4, 3.1, 3.2, 5.1, 5.2_
  - _Boundary: TransitiveResolver — materialize loop_

- [x] 2.2 (P) Native target resolver emits per-artifact and per-retry progress for klib fetches
  - Pre-count uncached klib nodes before the Native materialize loop.
  - Emit the artifact-start callback before each uncached-klib download and forward the retry callback to the klib download function.
  - Module metadata fetches remain silent: they keep the default no-op retry callback and emit no artifact-start.
  - TDD: a Native-equivalent "three uncached klibs record (1,3),(2,3),(3,3)" test against a fake `ResolverDeps` configured for klib URLs, and a 404-then-200 test that asserts one retry-against callback.
  - Observable completion: two new unit tests pass.
  - _Requirements: 1.1, 1.4, 2.1, 2.4, 3.1_
  - _Boundary: NativeResolver — resolveNative_

- [x] 2.3 (P) Bundle resolver paths emit per-artifact and per-retry progress
  - Apply the same pre-count + artifact-start + retry-callback pattern to the bundle declaration path, the single-artifact bundle path, and the bundle lock-reuse materialization path.
  - The lock-reuse path is included so a stale-cache + matching-lock scenario (lockfile present, JAR evicted from cache) surfaces re-downloads instead of going silent.
  - TDD: a "three uncached bundle entries record (1,3),(2,3),(3,3)" test and a "lock-reuse with one evicted JAR records (1,1)" test, both using the recording fixture.
  - Observable completion: two new unit tests pass.
  - _Requirements: 1.1, 1.4, 2.1, 2.4, 3.1_
  - _Boundary: BundleResolver_

## Phase 3: Integration — production CLI wiring, banner relocation, metadata-probe line

- [ ] 3. Integration

- [x] 3.1 Add the production stderr sink and a CLI-internal constructor helper
  - Introduce a private CLI sink class that formats the artifact-start callback as `[N/M] groupArtifact:version` and the retry-against callback as `  -> retry against <repository>`, both via the existing stderr writer.
  - Provide an internal helper that constructs a fresh sink instance per top-level resolver invocation, so multiple CLI entries can share the wiring without duplicating it.
  - Add a unit test that injects a String-capturing emit lambda into the sink and asserts the exact two formatted lines for a synthetic `(2,5)` artifact-start and a sample retry against a repository URL.
  - Observable completion: one new sink-formatting unit test passes.
  - _Requirements: 4.1_
  - _Boundary: DependencyResolution (CLI) — StderrProgressSink_

- [x] 3.2 (P) Wire the production sink through the JVM and Native CLI entries and relocate their banners
  - Construct the sink at the JVM dependency-resolution entry and pass it to both the main resolve call and the bundle-resolve call.
  - Construct the sink at the Native dependency-resolution entry and pass it to its resolve call so Native builds achieve parity with JVM.
  - Move the two existing pre-resolve banners (`resolving dependencies...`, `resolving native dependencies...`) from stdout to stderr.
  - Observable completion: a smoke run against an uncached cache prints `[N/M]` lines on stderr; redirecting stdout to a file (`kolt fetch > out.txt`, `kolt build > out.txt`) leaves the file free of progress and banners while stderr carries them — directly verifies Req 4.3 piping behavior.
  - _Requirements: 1.4, 4.1, 4.2, 4.3_
  - _Boundary: DependencyResolution (CLI)_

- [ ] 3.3 (P) Wire the production sink through `kolt update`, relocate its banner, and add the metadata-probe line for `kolt add`
  - Construct the sink at the update entry and pass it to the explicit resolve and bundle-resolve calls inside the update flow.
  - Move the `updating dependencies...` banner from stdout to stderr.
  - In the latest-version probe path used by `kolt add` without an explicit version, emit a single `fetching latest version of <group>:<artifact>...` line on stderr before the metadata-XML download. Do not introduce `[N/M]` framing — this is a single-artifact metadata fetch, not a fetch loop.
  - Observable completion: `kolt update` against an uncached cache shows banner + `[N/M]` lines on stderr and `kolt update > out.txt` leaves `out.txt` containing only post-resolve command output (verifies Req 4.3); `kolt add com.example:lib` without a version prints the `fetching latest version of com.example:lib...` line on stderr before the resolve banner appears.
  - _Requirements: 1.4, 4.1, 4.2, 4.3_
  - _Boundary: DependencyCommands (CLI)_
