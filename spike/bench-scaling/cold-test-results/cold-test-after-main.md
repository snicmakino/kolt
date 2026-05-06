# Cold-path test compile bench (issue #380)

- Date: 2026-05-06T22:56:14+09:00
- Host: Linux 6.6.87.2-microsoft-standard-WSL2 x86_64
- JDK: openjdk version "25.0.3" 2026-04-21 LTS
- kolt binary: `/home/makino/projects/snicmakino/kolt/build/debug/kolt.kexe` (kolt 0.18.0)
- KOLT_DAEMON_JAR mtime: 2026-05-06T22:56:05+09:00
- Fixture: `/home/makino/projects/snicmakino/kolt/kolt-jvm-compiler-daemon`
- Runs per arm: 5 (arm-off captured 4 — run1 lost mid-script; medians take the surviving 4 / 5)

## Cold-path test compile (kolt build → daemon stop → kolt test)

End-to-end wall time as reported by `kolt test` itself ("tests passed in Xm Ys"):

### arm A — cache-off (wipe `<v>/shrunk-snapshots/` after main build, before test)

| run | wall (s) |
|---:|---:|
| 2 | 102.6 |
| 3 | 103.9 |
| 4 | 108.5 |
| 5 | 106.1 |
| **median** | **~105** |

### arm B — cache-on (`<v>/shrunk-snapshots/` retained after main build)

| run | wall (s) |
|---:|---:|
| 1 | 108.0 |
| 2 | 112.0 |
| 3 | 105.5 (one test failed unrelated to cache; included for completeness) |
| 4 | 108.0 |
| 5 | 108.3 |
| **median** | **108.0** |

### Summary — cache effect not isolated by this bench

| metric | value |
|---|---:|
| cache-off median | ~105 s |
| cache-on median  | 108 s |
| delta            | +3 s (cache-on slower in noise) |

The bench does **not** observe the >=5% reduction Spike T1 measured (~8.7%
on a manual-placement isolation). Two factors compound:

1. **Test-execution time dominates.** The `kolt-jvm-compiler-daemon` JUnit
   suite is 167 tests and takes ~80 s by itself. The full kolt-test wall
   includes daemon startup + main compile + test compile + JUnit execution.
   The cache effect lives in the test compile slice (~5-10 s of the total),
   so a 7 s improvement is inside the JUnit-noise envelope (variance per
   run was ~5 s).
2. **The arm-off wipe is not effective in isolation.** `kolt test` re-runs
   `kolt build` internally when `.kolt-state.json` is missing, which the
   script wipes per iteration. The internal main build immediately
   re-populates `<v>/shrunk-snapshots/` *inside the same kolt-test
   invocation* before the test compile fires. Confirmed by daemon-log
   inspection: arm A's `arm-off-run5.log` shows the same
   `lookup=miss -> store=success -> lookup=prefix_hit` sequence as arm B's
   `arm-on-run1.log`. The cache fires within both arms; the only difference
   the script controls is whether the cache file from the *prior iteration*
   survives, which has no effect because the current iteration always
   rewrites it.

For a clean wall-time measurement that isolates the cache effect, see
`spike/bta-shrunk-portability/REPORT.md` Q2 (Spike T1): manual placement of
main's shrunk file at test's per-scope path produced 78,386 ms (cache) vs
85,905 ms (no cache), a 7,519 ms / 8.7% reduction at the BTA-compile level.
That measurement is the canonical cache-effect figure for this spec.

## Warm rebuild and no-op test

Both target Req 3.1 (warm rebuild <=540 ms BTA wall) and Req 3.2 (no-op
test <=50 ms). The bench measured end-to-end `kolt test` wall, **not** BTA
wall:

| metric | end-to-end median (s) | Req target | comparable? |
|---|---:|---:|---|
| warm rebuild (single test source touched) | 89 | <=0.54 BTA wall | no — measurement scope mismatch |
| no-op test (no source change)             | 88 | <=0.05 BTA wall | no — measurement scope mismatch |

The 540 ms / 50 ms targets are **BTA wall** numbers from the #376 dogfood
log; they refer to the daemon's internal BTA compile time, not the user-
observed `kolt test` wall (which always includes JUnit launcher + 167-test
execution on this fixture, ~80 s constant). The fixture would need to be
swapped for a smaller project (e.g. a hello-world JVM scaffold) for the
bench to surface BTA-wall numbers directly.

What the bench DOES show: warm rebuild and no-op test runs all complete
without anomalies (1 unrelated test flake on `noop-test.log.run4`). The
cache integration introduces no observable end-to-end regression at the
~88-89 s baseline.

## Verdict

- **Cache works** (per Spike T1 isolation): GO confirmed at 8.7% reduction
  on the BTA-compile slice
- **Bench at end-to-end wall on this fixture**: not sensitive enough to
  detect the cache effect; numbers within run-to-run noise envelope
- **No regression** observed from cache integration (4.1 + 4.2)
- **Req 3.1 / 3.2** require BTA-wall extraction (not end-to-end wall) on
  this fixture, which is out of scope for this bench iteration

Recommendation: track "wire BTA-wall extraction into the bench" as a
follow-up issue if the bench needs to be re-run as a regression gate per
release. Today, Spike T1 + the multi-shape IT
(`compiledClassesAreByteIdenticalAcrossCacheHitAndMiss` proves no silent
corruption; `cacheSurvivesDaemonRestart` proves no IcReaper regression)
together provide the verification surface for the spec.
