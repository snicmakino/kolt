# bta-compat-138 spike

**Throwaway. Not production code.**

Prerequisite spike for issue #138 (per-kotlinVersion daemon spawn). Answers:

> Is `kotlin-build-tools-api` binary-stable enough that an adapter compiled
> against API 2.3.20 can load API+impl pairs at 2.1.0 / 2.2.x at runtime
> through the daemon's `SharedApiClassesClassLoader` + `URLClassLoader`
> topology?

See `REPORT.md` for the verdict.

## Usage

```
./gradlew -p spike/bta-compat-138 run --args="fixtures/linear-10 /tmp/bta-compat-work"
```

The matrix (`2.1.0`, `2.2.20`, `2.3.0`, `2.3.10`, `2.3.20`) is hard-coded in
`build.gradle.kts`. Each impl version ships with a matching `kotlin-stdlib` in
its own resolvable configuration so the fixture classpath matches the impl.

For each impl version the harness:

1. Builds `URLClassLoader(impl jars, parent = SharedApiClassesClassLoader())`
2. Calls `KotlinToolchains.loadImplementation(loader)`
3. Runs a cold compile on `fixtures/linear-10`, touches `F2.kt`, runs an incremental compile
4. Classifies the outcome (`GREEN` / `RED_LINKAGE` / `RED_METHOD` / `RED_OTHER` / `COMPILE_ERROR`)

Every phase is wrapped so a thrown `LinkageError` / `NoSuchMethodError` does
not abort the matrix — we want to see *which* versions break and *how*.
