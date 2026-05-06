# bta-shrunk-portability spike

**Throwaway. Not production code. Spec #380 task 1.1 / 1.2.**

Probes BTA 2.3.20's behavior around `shrunk-classpath-snapshot.bin` to decide
whether spec #380's "global content-keyed shrunk snapshot cache" design is
viable.

## Question matrix

| ID | Question | Method | Required for |
|----|----------|--------|--------------|
| Q1 | Is the shrunk file portable across BTA workingDirs? | Copy main scope's file into test scope's bta dir, run BTA compile, check exit + .class output | OQ-1 GO |
| Q2 | Does pre-placing main scope's file at test scope's path reduce test compile wall-time? | Time test compile cold (no pre-place) vs cold-with-pre-place, 5 runs each | OQ-1 GO threshold (>=5%) |
| Q3 | Does BTA write the shrunk file in-place or via tmp+rename? | Stat inode before/after a no-op IC compile that still rewrites IC state | OQ-3 hardlink opt-in |
| Q4 | Are shrunk file contents invariant under compiler-flag changes (-Xfriend-paths, plugin args) on the same classpath? | `cmp -l` between same-classpath runs with toggled flags | R-2 cache key scope |

Q4 is documented as "not measured here, low confidence based on BTA source"
because the experiment requires per-flag fixture variants that complicate the
harness. The risk is bounded: `ClasspathEntrySnapshot` is documented as path-free
class metadata; compiler flags affect compiler behavior, not the class metadata
of the dependencies on the classpath.

## Fixture

Uses `kolt-jvm-compiler-daemon/` (this repo's serialization-plugin sub-project)
as the BTA driver. Its main / test scopes share most of the classpath
(kotlin-stdlib + kotlinx-serialization-core + libs) and produce ~1MB shrunk
files — substantial enough that any wall-time effect from cross-scope reuse is
measurable above noise.

The harness (`harness.sh`) drives `kolt build` / `kolt test` against the
fixture and manipulates IC state under
`~/.kolt/daemon/ic/<kotlinVersion>/<projectIdHash>/{main,test}/bta/` directly
between runs to set up the experimental conditions.

## Reproducing

```
spike/bta-shrunk-portability/harness.sh
```

The harness:
- requires the system kolt to be on PATH (verified at startup)
- wipes the kolt-jvm-compiler-daemon project's IC state between scenarios
- writes raw measurements to `results/Q{1,2,3}.log`
- exits 0 on success (all questions answered, regardless of verdict polarity)

After the harness completes, run task 1.2 to populate `REPORT.md` with the
OQ-1 triage verdict.
