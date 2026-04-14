# Phase A daemon scaling benchmark — #96

- Date: 2026-04-14T18:39:16+09:00
- Host: Linux 6.6.87.2-microsoft-standard-WSL2 x86_64
- JDK: openjdk version "21.0.7" 2025-04-15 LTS
- kolt binary: `/home/makino/projects/snicmakino/kolt/build/bin/linuxX64/releaseExecutable/kolt.kexe`
- kolt binary mtime: 2026-04-14T18:16:31+09:00
- N per cell: 7 (warm modes discard first 2)

| Fixture | Mode | N | median (s) | min (s) | max (s) | raw |
|---|---|---|---|---|---|---|
| jvm-1 | nodaemon | 7 | 5.16 | 5.05 | 8.48 | 8.12,5.05,5.39,8.48,5.07,5.08,5.16 |
| jvm-1 | daemon-cold | 7 | 5.10 | 4.98 | 8.05 | 5.08,5.10,8.05,4.98,5.13,5.02,5.31 |
| jvm-1 | daemon-warm | 7 | 0.77 | 0.65 | 0.99 | 0.89,0.99,0.76,0.73,0.77,0.81,0.65 |
| jvm-1 | gradle | 7 | 1.78 | 1.62 | 1.89 | 1.89,1.83,1.78,1.71,1.69,1.83,1.62 |
| jvm-10 | nodaemon | 7 | 6.52 | 6.29 | 10.12 | 9.87,6.48,6.52,6.70,10.12,6.32,6.29 |
| jvm-10 | daemon-cold | 7 | 6.28 | 6.16 | 10.19 | 6.33,6.16,9.62,6.28,6.27,6.19,10.19 |
| jvm-10 | daemon-warm | 7 | 1.09 | 0.92 | 1.67 | 1.67,1.31,1.22,1.09,1.01,1.09,0.92 |
| jvm-10 | gradle | 7 | 1.88 | 1.78 | 5.26 | 1.99,2.08,1.88,5.26,1.86,1.78,1.80 |
| jvm-25 | nodaemon | 7 | 8.06 | 7.71 | 11.67 | 8.21,8.06,11.67,7.74,7.83,7.71,11.15 |
| jvm-25 | daemon-cold | 7 | 8.31 | 7.87 | 191.58 | 7.97,8.31,191.58,8.32,7.87,8.06,10.64 |
| jvm-25 | daemon-warm | 7 | 1.51 | 1.10 | 2.30 | 2.30,1.60,1.52,1.45,1.51,1.19,1.10 |
| jvm-25 | gradle | 7 | 2.01 | 1.91 | 2.20 | 2.18,2.20,1.91,2.01,2.07,1.91,1.93 |
| jvm-50 | nodaemon | 7 | 9.59 | 9.15 | 13.24 | 9.89,12.75,9.59,9.15,13.24,9.59,9.45 |
| jvm-50 | daemon-cold | 7 | 9.98 | 9.34 | 13.73 | 12.94,9.53,9.34,12.99,9.98,9.68,13.73 |
| jvm-50 | daemon-warm | 7 | 1.85 | 1.48 | 4.94 | 2.28,2.21,1.83,1.85,4.94,1.71,1.48 |
| jvm-50 | gradle | 7 | 2.06 | 1.96 | 2.31 | 2.08,2.27,2.04,2.31,2.06,1.96,1.99 |

## Summary (medians, seconds)

| Fixture | nodaemon | daemon-cold | daemon-warm | gradle-warm | nodaemon / warm | gradle / warm |
|---|---|---|---|---|---|---|
| jvm-1  | 5.16 | 5.10 | 0.77 | 1.78 | 6.7× | 2.3× |
| jvm-10 | 6.52 | 6.28 | 1.09 | 1.88 | 6.0× | 1.7× |
| jvm-25 | 8.06 | 8.31 | 1.51 | 2.01 | 5.3× | 1.3× |
| jvm-50 | 9.59 | 9.98 | 1.85 | 2.06 | 5.2× | 1.1× |

## Observations

- **Daemon warm vs subprocess**: the 5.2×–6.7× wall-time improvement is
  stable across sizes.  Hello-world's 8.5× figure (`project_phase_a_daemon`
  memory) overstated it — that run captured a single-file project where the
  compile fraction was small; here the compile fraction grows and the
  constant-overhead reduction shows up as a ~5× floor rather than a ~8×
  ceiling.
- **Daemon cold regression**: effectively zero (5.10 vs 5.16, 6.28 vs 6.52,
  8.31 vs 8.06, 9.98 vs 9.59 — all within run-to-run noise).  Confirms the
  `FallbackCompilerBackend` wiring isn't adding measurable client-side cost
  on the cold path.  Consistent with 2026-04-14 hello-world Run 2.
- **ADR 0016 `warm <1s` target**: only holds for `jvm-1` (0.77s).  The
  target was set against a hello-world baseline and does not survive a
  scaling curve — `jvm-10` already sits at 1.09s.  This is a target-text
  issue, not a regression; ADR 0016 §Benchmark results should be updated
  separately (out of this issue's scope per agreement to stop at raw data).
- **Scaling slope**: daemon-warm is roughly linear in file count, ~0.025s
  per file above the ~0.75s fixed-cost floor (resolve + round-trip + jar
  write).  ~0.75s still has headroom above the spike's 108ms pure-compile
  median — dependency-resolution phase and native-client JSON encoding are
  the next candidates for Phase B / #90.
- **Gradle comparison**: gradle-warm is strikingly flat (1.78–2.06s).
  Gradle's worker API + embedded kotlinc amortises per-file work so well
  that by `jvm-50` the daemon-warm advantage is only 1.1×.  On single-file
  builds kolt is 2.3× faster; the crossover for these fixture shapes
  would land somewhere past `jvm-100`.  This is the Gradle anchor point
  that spike #86/#87 lacked.
- **Outliers**:
  - `jvm-25 daemon-cold` run 3 = **191.58s** — a single catastrophic run,
    otherwise-stable 7.87–10.64s.  Most likely a one-off Maven Central
    stall or background I/O during the `resolving dependencies...` phase.
    Excluded from median by construction; flagged for #88/#90 follow-up as
    evidence that dependency resolution has tail-latency risk under load.
  - `nodaemon` and `daemon-cold` rows show 2–3 samples per cell in the
    8–13s range when the median sits at 5–9s.  Pattern is consistent
    across fixture sizes — suggests periodic cost in the non-daemon path
    (possible kotlinc installation re-probe, maven metadata refresh, or
    filesystem cache miss).  Daemon-warm does not exhibit this pattern,
    which is consistent with the resolve phase being the source.
- **Fixture sanity**: daemon-warm steady-state per-file slope is
  (1.85 − 0.77) / 49 ≈ 22 ms/file, which is in the same ballpark as the
  spike #86/#87 SharedCompilerHost in-JVM numbers (108ms for a single
  hello-world compile includes fixed cost).  The generator is producing
  enough compiler work to be meaningful; if future measurement wants to
  stress symbol resolution harder, add cross-package refs in `gen.sh`.

## Methodology notes

- `run-bench.sh` kills any running `kolt-compiler-daemon` and removes
  `~/.kolt/daemon` before each cold run, and before the first daemon-warm
  run.  `rm -rf build` between every run.
- Warm modes (`daemon-warm`, `gradle`) discard the first 2 runs as JIT
  warmup; reported N=7 after discards.
- `--no-daemon` was measured with the daemon process killed first, per
  issue #96 open question: this represents "user explicitly opted out and
  there is no daemon running".  A variant with a live daemon present was
  not measured and is expected to be within noise.
- Wall clock via `/usr/bin/time -f '%e'`; kolt's own "built in Xs"
  self-report was not captured separately.
- Binary under test: `build/bin/linuxX64/releaseExecutable/kolt.kexe`
  built from `feat/gradle-decouple-daemon` at the session head.  Dev
  fallback resolves `kolt-compiler-daemon-all.jar` without `KOLT_DAEMON_JAR`
  override (confirmed via #94 re-check).
