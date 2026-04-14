# Phase A daemon scaling benchmark — #96

- Date: 2026-04-14T18:57:47+09:00
- Host: Linux 6.6.87.2-microsoft-standard-WSL2 x86_64
- JDK: openjdk version "21.0.7" 2025-04-15 LTS
- kolt binary: `/home/makino/projects/snicmakino/kolt/build/bin/linuxX64/releaseExecutable/kolt.kexe`
- kolt binary mtime: 2026-04-14T18:16:31+09:00
- N per cell: 7 (warm modes discard first 2)

| Fixture | Mode | N | median (s) | min (s) | max (s) | raw |
|---|---|---|---|---|---|---|
| jvm-1 | nodaemon | 7 | 4.83 | 4.37 | 8.12 | 5.09,4.83,4.93,8.12,4.50,4.37,4.49 |
| jvm-1 | daemon-cold | 7 | 4.59 | 4.08 | 10.12 | 4.08,4.10,4.59,10.12,5.14,4.48,4.82 |
| jvm-1 | daemon-warm | 7 | 0.66 | 0.59 | 4.01 | 0.78,0.71,4.01,0.65,0.66,0.59,0.66 |
| jvm-1 | gradle | 7 | 1.19 | 1.15 | 1.56 | 1.56,1.25,1.19,1.19,1.18,1.16,1.15 |
| jvm-10 | nodaemon | 7 | 5.74 | 5.57 | 8.98 | 5.57,5.74,8.98,5.72,5.75,5.82,5.72 |
| jvm-10 | daemon-cold | 7 | 5.89 | 5.75 | 9.32 | 9.17,5.82,5.75,5.89,5.88,9.32,6.00 |
| jvm-10 | daemon-warm | 7 | 0.97 | 0.82 | 1.22 | 1.22,1.11,0.99,0.95,0.97,0.90,0.82 |
| jvm-10 | gradle | 7 | 1.25 | 1.22 | 1.61 | 1.27,1.25,1.61,1.23,1.25,1.22,1.26 |
| jvm-25 | nodaemon | 7 | 7.14 | 7.06 | 10.53 | 7.42,7.06,7.17,10.53,7.13,7.07,7.14 |
| jvm-25 | daemon-cold | 7 | 7.34 | 7.20 | 10.74 | 10.74,7.36,7.21,7.22,10.56,7.34,7.20 |
| jvm-25 | daemon-warm | 7 | 1.36 | 0.94 | 5.04 | 5.04,1.48,1.46,1.34,1.36,1.13,0.94 |
| jvm-25 | gradle | 7 | 1.41 | 1.33 | 1.43 | 1.33,1.41,1.36,1.41,1.43,1.36,1.42 |
| jvm-50 | nodaemon | 7 | 8.64 | 8.41 | 12.03 | 12.03,8.64,8.66,8.59,12.02,8.61,8.41 |
| jvm-50 | daemon-cold | 7 | 8.84 | 8.69 | 12.14 | 12.14,8.84,8.87,8.69,11.97,8.73,8.73 |
| jvm-50 | daemon-warm | 7 | 1.65 | 1.22 | 2.27 | 2.27,1.94,1.82,1.65,1.44,1.33,1.22 |
| jvm-50 | gradle | 7 | 1.61 | 1.54 | 4.65 | 1.56,1.58,2.05,4.65,1.63,1.54,1.61 |

This is **run 2**.  Run 1 (saved as `results-2026-04-14-run1.md`) included a
191.58 s catastrophic sample on `jvm-25 daemon-cold` that is absent here;
that single sample is now attributed to a WSL2 suspend event during the
earlier session.  The rest of the outlier structure reproduces across
both runs and is described below.

## Summary (medians, seconds)

| Fixture | nodaemon | daemon-cold | daemon-warm | gradle-warm | nodaemon / warm | gradle / warm |
|---|---|---|---|---|---|---|
| jvm-1  | 4.83 | 4.59 | 0.66 | 1.19 | 7.3× | 1.8× |
| jvm-10 | 5.74 | 5.89 | 0.97 | 1.25 | 5.9× | 1.3× |
| jvm-25 | 7.14 | 7.34 | 1.36 | 1.41 | 5.3× | 1.0× |
| jvm-50 | 8.64 | 8.84 | 1.65 | 1.61 | 5.2× | 1.0× |

## Observations

- **Daemon warm vs subprocess**: 5.2–7.3× stable across sizes.  The ratio
  declines with file count as the compile fraction grows; `jvm-50` sits
  at 5.2× which is the most defensible number to quote outside the
  hello-world regime.
- **Daemon cold regression**: still effectively zero.  Cold medians
  track nodaemon within ±0.25 s across all four sizes, confirming the
  `FallbackCompilerBackend` wiring adds no measurable client-side cost.
- **`warm <1s` ADR target**: holds for `jvm-1` (0.66 s) and `jvm-10`
  (0.97 s), fails at `jvm-25` (1.36 s).  The crossover lives somewhere
  around 10–15 source files.  The target text in ADR 0016 should be
  updated; that is a separate ADR edit, out of scope here per the
  agreed deliverables list.
- **Scaling slope**: daemon-warm per-file slope is
  (1.65 − 0.66) / 49 ≈ 20 ms/file above a ~0.66 s fixed-cost floor
  (resolve + round-trip + jar write).  The ~0.66 s floor still has
  headroom above spike #86/#87's 108 ms pure-compile median — resolve
  phase and native-client JSON encoding are where that gap lives.
- **Gradle crossover**: `jvm-25` and `jvm-50` land within 0.04 s of
  gradle-warm.  For fixtures of this shape gradle catches up by ~25
  files and matches by ~50.  On `jvm-1` kolt is still 1.8× faster due
  to fixed-cost advantage.  This is the scaling anchor that spike
  #86/#87 lacked.

## Outlier analysis

Both runs show a reproducible outlier pattern that is **not**
attributable to laptop sleep.  Laptop sleep accounted only for the
`191.58 s` single sample in run 1.  The remaining outliers:

- Nodaemon / daemon-cold rows show 1–2 samples per cell roughly
  3.3–3.4 s above the median, regardless of fixture size:

  | Cell                   | median | outlier | delta |
  |---|---|---|---|
  | jvm-1 nodaemon         | 4.83   | 8.12    | +3.29 |
  | jvm-10 nodaemon        | 5.74   | 8.98    | +3.24 |
  | jvm-25 nodaemon        | 7.14   | 10.53   | +3.39 |
  | jvm-50 nodaemon        | 8.64   | 12.03   | +3.39 |
  | jvm-50 daemon-cold     | 8.84   | 12.14   | +3.30 |

  The delta is **independent of fixture size**, which means the extra
  cost is a fixed-overhead event, not something that scales with
  compilation work.  ~2 of 7 runs per cell hit it.
- Daemon-warm rows are mostly clean (0.82–2.27 s tight distribution)
  except `jvm-1 run 3 = 4.01 s` and `jvm-25 run 1 = 5.04 s`.  The
  `jvm-25` warm outlier is the post-prime run which suggests the
  prime-run discards did not catch a late resolve-phase event.
- Gradle has a single outlier at `jvm-50 run 4 = 4.65 s` which is
  consistent with the gradle daemon occasionally re-loading a worker;
  it does not scale with fixture size either.

The ~3.3 s fixed-cost outlier appears in every mode that runs kolt's
`resolving dependencies...` phase on each invocation (nodaemon,
daemon-cold, and — since warm compile doesn't skip resolve — the
occasional daemon-warm sample).  It does not appear in gradle, which
reuses the gradle daemon's resolved classpath.  Best guess is a
maven-metadata refresh or kotlinc toolchain re-probe in kolt's
dependency resolver firing on ~28% of invocations.  Flagged for #88
(rotating fixtures regression monitor) and #90 (long-run leak / resolve
tail) follow-up — we now have a reproducible signal that the resolve
phase has ~3 s tail latency.

## Methodology notes

- `run-bench.sh` kills any running `kolt-compiler-daemon` and removes
  `~/.kolt/daemon` before each cold run, and before the first daemon-warm
  run.  `rm -rf build` between every run.
- Warm modes (`daemon-warm`, `gradle`) discard the first 2 runs as JIT
  warmup; reported N=7 after discards.
- `--no-daemon` was measured with the daemon process killed first, per
  issue #96 open question.
- Wall clock via `/usr/bin/time -f '%e'`.
- Binary under test: `build/bin/linuxX64/releaseExecutable/kolt.kexe`
  built from `feat/gradle-decouple-daemon` at session head.  Dev
  fallback resolves `kolt-compiler-daemon-all.jar` without
  `KOLT_DAEMON_JAR` override.
