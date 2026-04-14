# incremental-ic spike

**Throwaway. Not production code.**

Phase B-1b spike for #104. Drives `kotlin-build-tools-api` 2.3.20
(`KotlinToolchains` entry point, `JvmPlatformToolchain.jvmCompilationOperationBuilder`)
in-process to answer:

1. Does the API actually work end-to-end on a small JVM fixture?
2. Does snapshot-based IC meaningfully reduce the recompile set on
   `linear-10` vs `hub-10` fixture shapes?
3. Answers to Open Questions 1-8 in issue #104.

No tests, no TDD, no daemon integration. The point is to find out
what the adapter layer in B-2 has to cover, not to ship code.

## Usage

```
./gradlew :run --args="<fixture> <caches-dir> <touched-file>"
```

Example:

```
./gradlew :run --args="fixtures/linear-10 /tmp/ic-caches Main.kt"
```

## Observation

The spike records the recompile set by hashing every `.class` file
under the destination directory before and after the incremental run.
`BuildMetricsCollector` is attached as a supplementary signal for the
compiled-files count metric.

## Deliverables

- `REPORT.md` — verdict + answers to #104 open questions 1-8.
