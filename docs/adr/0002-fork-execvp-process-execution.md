# ADR 0002: Execute subprocesses via `fork` + `execvp`, capture output via `popen`

## Status

Accepted (2026-04-08)

## Context

kolt shells out to external commands constantly: `kotlinc` for
compilation, `java` for running the built jar and for JUnit Platform
test execution, `ktfmt` for formatting, and `tar`/`unzip` style archive
tools during toolchain installation. The CLI's correctness depends on
these calls doing three things reliably:

1. Start the child process with an **argument vector**, not a shell
   command string.
2. Propagate the child's **exit code** verbatim to the caller, so
   `kolt build` and `kolt test` can return meaningful numbers.
3. Distinguish "failed to start" from "started and exited non-zero"
   from "killed by signal".

Kotlin/Native's standard library does not provide a subprocess API.
There is no `java.lang.ProcessBuilder` equivalent. The options available
on linuxX64 are all in `platform.posix`:

- `system(3)` — runs a command through `/bin/sh -c`.
- `popen(3)` / `pclose(3)` — forks a shell, returns a `FILE*` for the
  child's stdout or stdin.
- `fork(2)` + `execvp(3)` + `waitpid(2)` — the low-level POSIX
  primitives. No shell involved.
- `posix_spawn(3)` — a higher-level wrapper over fork/exec, available
  but with more configuration surface than kolt needs.

`system` and the default `popen` behaviour were immediate
non-starters for the main build path because they interpret the command
through a shell. Every argument that contains a space, quote, `$`, or
backslash becomes a parsing hazard, and kotlinc argument files and
classpath strings regularly contain all of the above. Shell
interpretation is also a shell-injection vector if any part of the
argument vector comes from a `kolt.toml` value (project name, main
class, plugin args).

The same is true for Windows's `cmd.exe` and `CreateProcess`
distinction, but kolt is linux-only today, so the decision is scoped
to POSIX.

## Decision

Use two distinct execution primitives, both in `infra/Process.kt`, each
for a specific need:

- **`executeCommand(args: List<String>)`** — the main path. It takes a
  pre-split argument vector and runs the child via
  `fork()` + `execvp()` + `waitpid()`. **No shell is involved.** Return
  type is `Result<Int, ProcessError>`, with `ProcessError` variants for
  `EmptyArgs`, `ForkFailed`, `WaitFailed`, `NonZeroExit(exitCode)`, and
  `SignalKilled`. Exit status is decoded manually from the `status`
  integer returned by `waitpid`:

  ```
  (status and 0x7F) == 0    -> WIFEXITED, exit code = (status shr 8) and 0xFF
  otherwise                 -> SignalKilled
  ```

  `waitpid` is retried on `EINTR`.

- **`executeAndCapture(command: String)`** — the side path. It runs a
  command through `popen(command, "r")` and reads the child's stdout
  into a `StringBuilder`. Used only for short metadata commands where
  the output is the result (e.g. `kotlinc -version`, `java -version`).
  It accepts shell-interpreted strings, so callers must only ever pass
  trusted, literal commands — never user input. Exit status is decoded
  the same way from `pclose`'s return value.

Every POSIX API call site is annotated `@OptIn(ExperimentalForeignApi::class)`
at function level, per the project coding convention.

## Consequences

### Positive

- **No shell injection**: `executeCommand` passes arguments directly
  via `execvp`'s `argv`. A project name of `foo; rm -rf /` is just a
  literal filename. There is no shell to interpret it.
- **No quoting rules**: callers do not have to worry about spaces,
  quotes, or metacharacters in arguments. A classpath entry with
  `:` characters is just an element in the list.
- **Accurate exit codes**: the parent decodes `WIFEXITED` / `WEXITSTATUS`
  manually, so `NonZeroExit(exitCode)` carries the real number kotlinc
  or java returned. `kolt build` propagates this through `ExitCode.kt`.
- **Signal deaths are visible**: a child killed by `SIGKILL` or
  `SIGSEGV` surfaces as `ProcessError.SignalKilled` rather than being
  conflated with an ordinary non-zero exit. This matters for diagnosing
  OOM kills and kotlinc crashes.
- **EINTR is handled**: `waitpid` retries on `EINTR` so the parent does
  not spuriously report `WaitFailed` if a signal is delivered during
  the wait.
- **No extra process overhead**: `fork` + `execvp` is one subprocess.
  `system`/`popen` is two (the shell, then the real command).

### Negative

- **Manual `cinterop` plumbing at every call site**: the child path
  allocates a `CPointerVar<ByteVar>` array, copies `args[i].cstr.ptr`
  into it, null-terminates, and calls `execvp`. A mistake here
  (forgetting the null terminator, for example) is a native bug, not a
  Kotlin exception. The code is short but unforgiving.
- **Linux-only**: `fork`, `execvp`, `waitpid`, and the status-decoding
  bit layout are POSIX. A future Windows port would need an entirely
  separate implementation using `CreateProcessW`, and the API exposed by
  `Process.kt` would have to grow a platform-specific branch.
- **No stdout/stderr streaming**: `executeCommand` inherits the parent's
  stdio, so kotlinc's output goes straight to the terminal. kolt cannot
  currently capture compiler diagnostics in memory to parse or filter
  them. If we ever want to suppress or reformat kotlinc output, we need
  to add `pipe` + `dup2` + a reader thread, which is non-trivial in a
  single-threaded Kotlin/Native binary.
- **`executeAndCapture` is shell-based**: it uses `popen`, so it inherits
  all the shell-quoting pitfalls for any call site. The rule "only pass
  literal commands, never user input" is an invariant enforced by
  convention, not by types. This is a deliberate trade-off — the API
  exists precisely for cases where we *want* to write
  `"kotlinc -version"` as a single string.

### Neutral

- **Single-threaded by design**: Kotlin/Native's memory model and kolt's
  linear build pipeline mean we do not need `posix_spawn`'s thread
  safety guarantees or a thread-pool reader. `fork` is fine.
- **`_exit(127)` on `execvp` failure**: the child calls `_exit` (not
  `exit`) after a failed `execvp` so that atexit handlers and stdio
  buffers from the parent are not flushed twice. `127` matches the
  shell convention for "command not found".

## Alternatives Considered

1. **`system(3)` for everything** — rejected. Every argument would pass
   through `/bin/sh`, which is a correctness hazard for kotlinc argument
   vectors and a security hazard if any field from `kolt.toml` is
   interpolated into the command.
2. **`posix_spawn(3)` everywhere** — rejected as unnecessary. It is a
   reasonable choice in principle, but it adds `posix_spawn_file_actions_t`
   setup code and does not give us anything `fork` + `execvp` lacks for
   kolt's single-threaded, no-IPC, no-environment-munging use case.
3. **`popen` for everything including the build path** — rejected. It
   is a shell-based API, so it has the same injection and quoting
   problems as `system`, plus it only gives access to one of stdout or
   stdin (not both), plus it wraps the child in a shell that we do not
   want in the exit-code chain.
4. **Shell out to a tiny C helper binary that does the fork/exec dance**
   — rejected. It would solve nothing that `cinterop` does not already
   solve, and it would add a second native artifact to ship.

## Related

- `src/nativeMain/kotlin/kolt/infra/Process.kt` — the implementation
- ADR 0001 (Result type — defines how `ProcessError` is surfaced to
  callers)
