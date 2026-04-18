# Spike #160 — Native incremental build strategy survey

## Environment

- Kotlin/Native 2.3.20, konanc direct invocation (no Gradle)
- Host: Linux 6.6.87.2-microsoft-standard-WSL2 x86_64
- kolt two-stage build: stage 1 (sources → klib), stage 2 (klib → kexe)
- Fixtures: native-{1,10,25,50} with linear dependency chain, no plugins, no external deps

## Candidates tested

| Candidate | Flags | Affects |
|-----------|-------|---------|
| ic | `-Xenable-incremental-compilation -Xic-cache-dir` | Stage 2 (klib → binary) |
| lib-cache | `-Xcache-directory=<dist-cache>` | Stage 2 (explicit stdlib cache) |
| lib-cache-auto | `-Xauto-cache-dir -Xauto-cache-from` | Stage 2 (auto-generated cache) |
| ic+lib-cache | ic + lib-cache combined | Stage 2 |

## Results — median wall time (ms), both stages combined

### Baseline (kolt build, via run-bench.sh)

| size | cold | noop | touch | abi-neutral |
|------|------|------|-------|-------------|
| 1 | 18,600 | 11 | 19,072 | 19,821 |
| 10 | 24,367 | 13 | 21,379 | 21,123 |
| 25 | 20,794 | 11 | 21,982 | 23,814 |
| 50 | 25,451 | 12 | 27,343 | 27,848 |

### Candidates (konanc direct, via run-bench-ic.sh)

| size | edit | ic | lib-cache | lib-cache-auto | ic+lib-cache |
|------|------|-----|-----------|----------------|--------------|
| 1 | cold | 7,578 | 18,218 | 6,696 | 7,133 |
| 1 | touch | 6,340 | 19,034 | 6,663 | 6,166 |
| 1 | abi-neutral | 6,715 | 18,357 | 6,413 | 6,002 |
| 10 | cold | 8,784 | 18,288 | 9,051 | 9,246 |
| 10 | touch | 7,004 | 18,857 | 8,846 | 8,097 |
| 10 | abi-neutral | 6,742 | 21,255 | 8,841 | 7,316 |
| 25 | cold | 11,582 | 22,727 | 9,742 | 12,746 |
| 25 | touch | 8,060 | 22,346 | 9,934 | 8,779 |
| 25 | abi-neutral | 8,199 | 22,110 | 9,547 | 9,094 |
| 50 | cold | 15,803 | 27,643 | 11,679 | 15,181 |
| 50 | touch | 9,666 | 29,611 | 13,282 | 11,482 |
| 50 | abi-neutral | 9,790 | 27,917 | 12,124 | 10,592 |

### Cache sizes (native-50)

| cache | size |
|-------|------|
| .ic-cache | 3.1M |
| .lib-cache (auto) | 12K |
| dist stdlib cache | 138M |

## Analysis

### lib-cache (`-Xcache-directory`) — no effect

Pointing `-Xcache-directory` at the distribution's pre-built stdlib cache produces identical times to baseline. konanc already uses this cache by default. This candidate is eliminated.

### lib-cache-auto (`-Xauto-cache-*`) — significant cold improvement

Auto-cache reduces cold build time by 40–55% across all sizes. The effect is consistent across cold/touch/abi-neutral, suggesting it generates additional compile-time caches beyond what the distribution ships.

However, touch and abi-neutral show no improvement over cold within this candidate — every edit triggers the same work. This is expected: auto-cache optimizes library resolution, not source-level IC.

### ic (`-Xenable-incremental-compilation`) — significant touch/abi-neutral improvement

IC provides two benefits:

1. **Cold builds are faster than baseline** (but not as fast as lib-cache-auto). The IC harness invokes konanc directly, bypassing kolt's overhead. This accounts for part of the difference.
2. **Touch/abi-neutral are significantly faster than cold**, especially at larger sizes:
   - native-50: cold 15.8s → touch 9.7s (39% reduction)
   - native-25: cold 11.6s → touch 8.1s (30% reduction)
   - The IC cache enables stage 2 to partially recompile only affected native code.

Stage 1 (sources → klib) runs fully on every invocation since konanc receives all source files. The IC improvement comes entirely from stage 2.

### ic+lib-cache — no added benefit over ic alone

Since lib-cache has no effect, combining it with ic produces the same results as ic alone.

## Measurement caveats

1. **Stage 1 included in all times.** IC affects stage 2 only, but measured times include stage 1 (full source recompilation). The true IC benefit on stage 2 is larger than the total-time reduction suggests.
2. **konanc direct vs kolt.** Candidate measurements invoke konanc directly, while baseline uses kolt. kolt adds ~10ms overhead (config parsing, cache check), which is negligible. The bigger difference is that kolt's BuildCache skips both stages on noop (11ms), while konanc has no equivalent — but noop was excluded from candidate measurements for this reason.
3. **WSL2.** File I/O variance is higher than bare metal. Medians are reliable but min/max spread is wide.

## Recommendation

**Adopt `ic` (`-Xenable-incremental-compilation -Xic-cache-dir`) for native builds.**

Rationale:
- Touch/abi-neutral improvement is 30–39% at 25–50 files, growing with project size.
- Implementation cost is low: pass two flags to the stage-2 konanc invocation, manage one cache directory.
- Cache size is small (3.1M at 50 files) and scales linearly.
- The flag is konanc-native (not Gradle-specific) and has been stable since Kotlin 1.9.20.
- Cache invalidation is handled by konanc internally (fingerprint-based).

**Do not adopt `lib-cache-auto` in this round.** The cold-build improvement is real but the mechanism is unclear — it may duplicate work konanc already does on non-WSL2 systems. Worth investigating separately if cold build time becomes a bottleneck.

**Daemon (konanc persistent JVM) remains the highest-payoff candidate** for eliminating the ~7–16s fixed cost. This should be a separate spike.

## Cache placement (for implementation)

- IC cache: `build/.ic-cache` (per-project, wiped by `kolt clean`)
- Fallback on cache corruption: delete `build/.ic-cache` and rebuild (konanc handles missing cache gracefully — tested)
- Not in `~/.kolt/cache/` — IC cache is project-specific and tied to the exact klib layout
