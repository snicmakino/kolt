# ADR 0001: Use kotlin-result `Result<V, E>` for all error handling

## Status

Accepted (2026-04-08)

## Context

The Phase 1 implementation of keel handled errors in three different ways,
often within the same call path:

- **Exceptions**: `parseConfig` threw a custom `ConfigParseException` when
  `keel.json` was malformed or missing required fields.
- **Sentinel exit codes**: `executeCommand` returned `-1` for "process
  failed to start" and any non-zero integer for "process ran but exited
  non-zero". Callers had to know that `-1` was special.
- **Nullable returns**: `readFileAsString` returned `String?`, leaving
  callers to infer whether `null` meant "file missing", "permission
  denied", or "I/O error".

This made error handling unpredictable. `Main.kt` had to mix `try/catch`,
`if (result == -1)` checks, and `?: run { ... }` null-guards in sequence,
and there was no single place that listed all the ways a given operation
could fail. Adding a new failure mode meant auditing every caller.

keel also has a hard constraint from the project charter: **exception
throwing is prohibited**. Kotlin/Native exceptions are awkward
(stack traces are expensive, `CancellationException` semantics differ
from JVM, and coroutine boundaries interact poorly with C interop), and
the tool's error reporting must be deterministic — every `keel build`
failure should map to a known exit code and a known message.

We needed an error-handling discipline that:

1. Made the full set of failure modes visible in the function signature.
2. Forced exhaustive handling at the call site.
3. Did not rely on exceptions.
4. Worked well with Kotlin/Native's type system (no reflection tricks,
   no JVM-only libraries).

## Decision

Adopt `kotlin-result` (`com.michael-bull.kotlin-result:kotlin-result:2.3.1`)
and use `Result<V, E>` as the return type for every side-effectful
function in keel. Pure functions (value-in / value-out, no I/O) are
exempt.

- Each fallible function declares its own error type. Only the errors
  that function can actually produce appear in the signature.
- A sealed class is used when several variants share a meaningful parent
  (e.g. `ProcessError` covers `StartFailed`, `RunFailed`, `ExitNonZero`).
  Independent errors stay as plain data classes (`OpenFailed`,
  `MkdirFailed`).
- Consumers unwrap with `getOrElse { error -> ... }` and match on the
  error with an exhaustive `when`. The compiler enforces that every
  variant is handled.
- `ConfigParseException` is deleted. `parseConfig` returns
  `Result<KeelConfig, ConfigError>`.
- Exit-code sentinels in `executeCommand` are replaced with
  `Result<Int, ProcessError>` — the `Int` is the actual process exit
  code, and start failures are a separate `ProcessError.StartFailed`
  variant.

The canonical signatures established at Phase 1 are:

```
parseConfig()       -> Result<KeelConfig, ConfigError>
readFileAsString()  -> Result<String, OpenFailed>
ensureDirectory()   -> Result<Unit, MkdirFailed>
executeCommand()    -> Result<Int, ProcessError>
executeAndCapture() -> Result<String, ProcessError>
```

All code added after Phase 1 follows the same pattern. New error types
are introduced per subsystem (`ResolveError`, `LockfileError`,
`DownloadError`, `ToolchainError`, etc.).

## Consequences

### Positive

- **Signatures are honest**: a function's error type lists every way it
  can fail. Adding a new failure mode is a type-level change that breaks
  the build at every call site, forcing reviewers to update handling
  deliberately.
- **Exhaustive handling**: `when (error) { ... }` over a sealed class
  is a compile error if a new variant is added and an old call site is
  missed. This has already caught several bugs during refactors
  (ToolchainError expansion, native resolve error additions).
- **No exceptions across native boundaries**: all C interop sites return
  `Result`, so exceptions never propagate through `cinterop` or
  `fork/execvp` boundaries.
- **Composability**: `andThen`, `map`, `mapError`, `flatMap` let us
  chain fallible operations without pyramid-of-doom `if` nesting.
  `BuildCommands` orchestration reads top-to-bottom.
- **Deterministic exit codes**: the CLI has a single place (`ExitCode.kt`
  + per-command error mapping) that translates `Result<_, E>` into a
  numeric exit code. Users never see a Kotlin stack trace.

### Negative

- **Signature noise**: every function gains a `Result<_, _>` wrapper and
  an explicit error type, which is more verbose than `throws` or a
  global exception hierarchy.
- **Error type proliferation**: each subsystem defines its own sealed
  class, and there are now a dozen or more `*Error` types across the
  codebase. Choosing whether a new failure belongs in an existing type
  or a new one is a judgement call.
- **Friction at boundaries**: converting from a third-party API that
  still throws (e.g. ktoml parsing) requires an explicit
  `runCatching { ... }.mapError { ... }` wrapper. kotlin-result does not
  automatically bridge exceptions.
- **Stack traces are lost**: an `Err(OpenFailed(path, errno))` carries
  only the data in the error object. When debugging, we sometimes want
  the call chain, and adding it back means either throwing-and-catching
  or threading an extra `cause` field manually.

### Neutral

- **Pure functions stay plain**: `Builder.buildCommand`,
  `Runner.runCommand`, `VersionCompare.compare`, and the other pure
  helpers return regular values and take regular arguments. The
  `Result` discipline applies to the I/O edge, not to the whole codebase.
- **kotlin-result is a small dependency**: one klib, no transitive
  dependencies, MIT-licensed, stable API. The cost of adopting it is
  negligible compared to the surface it replaces.

## Alternatives Considered

1. **Checked exceptions via `@Throws`** — rejected. Kotlin's `@Throws`
   annotation is only enforced on the JVM for Java interop and has no
   effect on Kotlin call sites. It would be documentation, not
   enforcement.
2. **A single top-level `KeelError` sealed class** — rejected. Every
   function would declare it could return any error, defeating the
   purpose of making signatures precise. Error types need to be local
   to the subsystem they come from.
3. **Arrow `Either<E, V>`** — rejected because Arrow pulls in a much
   larger dependency footprint (arrow-core, arrow-fx-coroutines,
   kotlinx-coroutines) than kotlin-result. For a build tool whose
   selling point is fast Kotlin/Native builds, the extra klibs were
   not worth the incremental ergonomic gain.
4. **Nullable returns with a separate "last error" field** — rejected.
   Stateful error reporting is exactly the pattern we were moving away
   from, and it does not compose.

## Related

- Commit `670e5c3` (initial migration to Result for Phase 1 operations)
- `CLAUDE.md` project rule: "Exception throwing is prohibited — use
  kotlin-result `Result<V, E>` for all error handling"
