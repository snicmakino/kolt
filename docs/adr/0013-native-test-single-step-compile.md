# ADR 0013: Compile native tests in a single konanc invocation

## Status

Accepted (2026-04-13)

## Context

Phase D of Kotlin/Native support (#16 / PR #58) had to decide how
`kolt test` produces a runnable test binary when `target = "native"`.
The JVM path is well established: `doTest` first calls `doBuild()` to
produce `build/classes`, then runs a second `kotlinc` on the test
sources with `-cp build/classes:<deps>` to produce `build/test-classes`,
then invokes the JUnit Platform Console Launcher with both on the
classpath. Main and test compilations are cleanly separated because the
JVM model treats `.class` directories as first-class inputs.

konanc does not share that model. The unit of production is a `.klib`
or a `.kexe`, there is no equivalent of "a directory of already-compiled
classes you point the next compilation at", and the test runner is
synthesised by a compiler lowering pass (`-generate-test-runner`) rather
than wired up externally by a launcher. Research during Phase D also
confirmed that `kotlin.test` for native is bundled in the konanc
distribution — no Maven resolution of a test framework is required,
unlike the JVM path's auto-injection of `kotlin-test-junit5`.

Given those constraints, two approaches are plausible:

**(α) Single-step.** Pass main sources + test sources to konanc in one
invocation together with `-generate-test-runner`, producing a single
`build/<name>-test.kexe`. `internal` visibility across main ↔ test works
naturally because both sides belong to the same compilation unit.
`kolt test` does not call `doNativeBuild` at all.

**(β) Two-step.** Compile main sources to a `.klib` (either as part of
`doNativeBuild` or on demand inside the test path), then compile test
sources in a second konanc invocation with `-library prod.klib` and
`-friend-modules prod.klib`. The `-friend-modules` flag is required to
restore `internal` visibility, which the two-compilation-unit structure
breaks by default.

β is structurally closer to how the Kotlin Gradle plugin handles native
tests and is the prerequisite for any future `.klib` publication story
(which kolt does not currently have — it targets applications, not
libraries). α is simpler and matches the single-binary-output nature
of kolt's current native scope.

A Phase D spike verified that α works end-to-end against konanc 2.1.0:
single-step compile produces a kexe that recognises `kotlin.test.@Test`
annotations, exits non-zero on any failing test, reads `internal`
declarations from main sources without additional flags, and composes
cleanly with transitive klib dependencies resolved by Phase B's
`resolveNativeDependencies`.

## Decision

Adopt α — compile main and test sources together in a single konanc
invocation.

`nativeTestBuildCommand` builds a command of the shape:

```
konanc <main-sources> <test-sources> \
    -p program \
    -generate-test-runner \
    -l <klib1> -l <klib2> ... \
    -o build/<name>-test
```

Two details follow from this choice:

1. **Omit `-e`.** The `-generate-test-runner` lowering synthesises a
   `main()` that calls `testLauncherEntryPoint(args)`. Passing `-e` in
   addition would conflict with it. (See also ADR 0012, which notes
   that the test path does not use `nativeEntryPoint`.)

2. **Bypass `doNativeBuild`.** `doTest` dispatches on target, and the
   native branch calls a dedicated `doNativeTest` that does **not**
   invoke `doBuild()` first. The existing `build/<name>.kexe`
   (production binary) and the new `build/<name>-test.kexe` (test
   binary) are produced by independent konanc calls — neither is an
   input to the other. This is the significant departure from the JVM
   `doTest` flow, which reuses `build/classes` from `doBuild`.

Pass/fail signalling uses the test binary's exit code: `-tr` mode (the
default `-generate-test-runner`) calls `exitProcess(1)` on any failed
test, which maps onto the existing `ProcessError.NonZeroExit` →
`EXIT_TEST_ERROR` flow with no new parsing logic.

`TestDeps.autoInjectedTestDeps` returns `emptyMap()` for native (no
change from the pre-Phase-D behaviour): since `kotlin.test` is bundled
in konanc, there is nothing to resolve.

## Consequences

### Positive

- **Smallest possible diff.** `doNativeBuild` is untouched, no new
  `.klib` artifact to track, no changes to `BuildState` caching rules,
  no new dependency resolution code path. The change fits in one
  function plus a dispatch edit.
- **`internal` visibility works for free.** No `-friend-modules` plumbing
  is needed, which would have required the test path to know the exact
  path of a production `.klib` produced elsewhere in the build.
- **Reuses the Phase B klib resolver as-is.** `resolveNativeDependencies`
  returns klib paths that `nativeTestBuildCommand` feeds directly into
  `-l` flags. No new resolver mode for "test-only dependencies" was
  needed (and none exists on native anyway, because kotlin.test is
  bundled).
- **Exit code signalling is uniform with JVM.** Both paths terminate
  `doTest` via the same `ProcessError.NonZeroExit` branch. The CLI
  contract stays consistent.
- **Reversible.** Moving to β later is a refactor, not a rewrite. The
  user-visible behaviour (`kolt test`, exit codes, CLI args) stays
  identical; only the internal compilation graph changes.

### Negative

- **No incremental test builds.** Every `kolt test` invocation triggers
  a full `konanc` compile of main + test sources together, even when
  only a test file changed. On Kotlin/Native this is the dominant cost
  of a TDD inner loop — empirically ~20s in Phase D's E2E runs. The JVM
  path has the same property today (test compilation is not cached),
  but native's baseline compile time makes the pain more visible. This
  is tracked as follow-up #59.
- **Duplicate compilation of main sources.** `kolt build && kolt test`
  compiles main sources twice: once as `build/<name>.kexe` (via
  `doNativeBuild`) and once as part of `build/<name>-test.kexe` (via
  `doNativeTest`). A user running both commands pays the main-source
  compile cost twice.
- **Structural asymmetry between JVM and native `doTest`.** The JVM path
  reuses `doBuild()`'s artifacts; the native path ignores them. Any
  future refactoring that tries to unify the two (e.g. a shared "build
  then test" helper) has to grow a target-aware branch anyway. The
  asymmetry is documented in the `doTest` comment block but still
  counts as cognitive overhead for new contributors.
- **No `-friend-modules` escape hatch for odd cases.** If a future
  native library wants to publish a `.klib` and separately run tests
  against the *published* artifact (rather than the source), α cannot
  express that. This is not a use case kolt supports today, but it
  would force a β-style rewrite to add.

### Neutral

- **`kolt test` with only test-source edits still rebuilds main.** This
  is a direct consequence of α and is the single biggest reason β might
  eventually win. If #59 is addressed with a test-kexe mtime cache
  keyed on (main sources ∪ test sources ∪ klib set), the "edit only
  tests" case still invalidates the cache because the main sources
  participated in the compile. Only a β-style split can make
  test-only edits skip main compilation; a cache alone cannot.
- **Bundled `kotlin.test` means no `test_dependencies` entry is needed
  for native.** Users coming from Gradle may expect to declare
  `kotlin("test")`; kolt accepts the entry but silently no-ops it for
  native. Documented in the kolt-usage skill.

## Alternatives Considered

1. **(β) Two-step compile with `-library` + `-friend-modules`** —
   rejected for Phase D. It solves a real problem (incremental
   recompile when only tests change) but requires `doNativeBuild` to
   produce a `.klib` in addition to the `.kexe`, plus build-state
   caching updates to track the klib separately, plus path plumbing to
   get the klib path from the build path to the test path. The benefit
   is concentrated in a single use case ("edit only tests") that is
   not yet established as painful in kolt's workflow — and the switch
   is reversible when it does become painful. Tracked as a discussion
   in the Phase D memory; promotion to an issue is deferred until #59
   resolves or is closed as insufficient.
2. **Compile tests in parallel with `doNativeBuild` in a single konanc
   run** — rejected because it would require `doBuild` to know whether
   the caller intends to run tests, violating the separation between
   "build the program" and "run the tests" that users rely on. Also
   produces a test kexe even when the user only runs `kolt build`,
   wasting time.
3. **Use `-tr` via `-Xgenerate-test-runner` (hypothetical) and parse
   stdout for pass/fail** — rejected because konanc's Google Test-style
   output has no machine-readable format (no TAP, no JUnit XML), and
   the test binary's exit code already gives an unambiguous signal.
   Parsing stdout would add fragility with no benefit.
4. **Invoke the test kexe via a wrapper that translates the output to
   a format `kolt` can summarise (e.g. pass/fail counts)** — rejected
   for Phase D as gold-plating. The raw konanc test output is already
   informative to humans; structured summarisation can be added later
   if needed without changing the compile strategy.
5. **Bundle kotlin.test via a synthetic `-l`**, matching how Gradle
   mentions it — rejected because it's bundled in konanc's stdlib
   already. Adding an explicit `-l kotlin-test` either fails (if the
   distribution does not ship a standalone klib by that name) or
   duplicates what the compiler already does. Verified during the
   Phase D spike: `konanc ... -generate-test-runner` resolves
   `kotlin.test.*` imports with no extra flags.

## Related

- #16 (Kotlin/Native target support — parent issue)
- PR #58 (Phase D implementation)
- #59 (follow-up: incremental test build cache — where the main β
  argument lives)
- ADR 0012 (native entry point derivation — notes that the test path
  deliberately omits `-e`)
