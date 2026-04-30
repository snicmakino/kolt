---
status: proposed
date: 2026-04-27
---

# ADR 0029: Concurrent build safety model

## Summary

- Same-project concurrency is serialised by a project-local advisory
  `flock(2)` over `build/.kolt-build.lock`. The critical section is
  the `kolt.lock` rewrite plus `build/` finalisation; CLI startup,
  `kolt.toml` parse, and read-only commands (`deps tree`, `fmt`,
  `--help`, `--version`) are not lock-protected. The lock is also
  released before any post-build child-process spawn (`kolt run`
  target, `kolt test` runner) so the child's lifetime does not pin the
  lock and nested `kolt` invocations from inside it can proceed (§1).
- Wait protocol: `flock(LOCK_EX|LOCK_NB)` polled at 100 ms with a 30 s
  default upper bound. On first peer detection a single stderr line
  "another kolt is running, waiting..." is emitted; on timeout kolt
  exits with `EXIT_LOCK_TIMEOUT` and leaves `build/` and `kolt.lock`
  intact. `KOLT_LOCK_TIMEOUT_MS` overrides the default for CI (§2).
- Global cache writes under `~/.kolt/cache/<g>/<a>/<v>/` go through
  `<destPath>.tmp.<pid>` followed by `platform.posix.rename` so the
  final path is never observed mid-write. Concurrent downloads of the
  same coordinate are last-writer-wins on SHA-validated bytes (§3).
- Stale temps under each cache directory are swept on every download:
  `cleanupStaleTemps` removes `*.tmp.*` files older than 24 hours
  from the destination's parent directory (§4).
- Daemon socket bind exclusion is reused from ADR 0016 §3-§5 and is
  not redefined here. Cross-machine concurrency over an NFS-shared
  `~/.kolt/` is unsupported; operators should keep `~/.kolt/` on a
  local filesystem (§5).
- Per CLAUDE.md "no backward compatibility until v1.0", no migration
  shim is provided for the new lock file or temp naming; the lock
  file is gitignored under `build/` and there is no opt-out (§6).

## Context and Problem Statement

Multiple `kolt` processes can touch the same `kolt.toml` at the same
time: an IDE-on-save watcher overlapping with a manual `kolt build`,
`kolt watch` co-running with `kolt test`, or a CI matrix of jobs that
share a single `~/.kolt/cache/`. Today every write is direct:
`Downloader.kt:41` `fopen(destPath, "wb")` writes the global-cache
JAR straight to its final path, `DependencyResolution.kt:107` rewrites
`kolt.lock` with a single `writeFileAsString`, and `BuildCommands.kt`
finalises `build/<name>.jar` / `build/<name>-runtime.classpath`
without any cross-process coordination. The current behaviour is
"silently corrupt on collision".

ADR 0016 §3-§5 already covers the daemon side: a single Unix socket
per project hash, with `bind(2)`'s `EADDRINUSE` providing OS-level
exclusion and `DaemonReaper` cleaning stale sockets. That contract is
not the gap. The gap is the project-level state (`kolt.lock`,
`build/`) and the global cache (`~/.kolt/cache/`).

This ADR records the contract.

## Decision Drivers

- A second `kolt build` against the same project must never silently
  corrupt the first one's `kolt.lock` or `build/` outputs.
- Wait must be bounded so an IDE-on-save loop cannot block forever
  on a stuck peer; timeout must be a clean exit with `build/` left
  intact, not a partial overwrite.
- Stale lock state from `SIGKILL` / power loss must not require
  manual cleanup.
- Global-cache writes must remain crash-safe and parallel-friendly
  without a cross-`~/.kolt/cache/` global lock.
- The contract must be documentable in one ADR so operators can read
  one file to decide whether their CI / NFS / shared-cache layout is
  supported.

## Decision Outcome

Chosen: **project-local advisory `flock(2)` for same-project
serialisation, plus `<dest>.tmp.<pid>` + `rename(2)` for the global
cache, while reusing ADR 0016's daemon socket exclusion as-is**. The
hybrid is the cheapest contract that closes the observed gap without
introducing a cross-machine lock service or a global cache mutex.
Alternatives are listed at the end.

### §1 Project-local lock and critical section

The lock file is `build/.kolt-build.lock`:

- Path is relative to the project (i.e. the same directory as
  `kolt.toml`); each project has its own lock and one project's lock
  does not block another's.
- Dot prefix matches conventional hidden-file naming; `-build` in the
  name reserves room for future locks (e.g. a publish lock) without
  having to rename later.
- `build/` is kolt's writable area only and is gitignored, so the
  file does not need a separate `.gitignore` entry.

Critical-section scope:

- **Locked**: `doBuild`, `doNativeBuild`, `doCheck`, `doTest`,
  `doAdd`, `doInstall`, `doUpdate`. The `lock.use { ... }` wrap covers
  dependency resolution (which rewrites `kolt.lock`), compile, and
  `build/` finalisation (`build/classes/`, `build/<name>.jar`,
  `build/kolt.kexe`, `build/<name>-runtime.classpath`). `doCheck`
  rewrites `kolt.lock` via `resolveDependencies` even on the JVM
  syntax-only path, so it is locked as well. Outer wrappers
  delegate to private `*Inner` functions so call chains like
  `doCheck → doBuildInner` and `doTest → doBuildInner` do not
  re-acquire under the same OFD.
- **Released before child-process spawn**: `doTest` releases the lock
  after the build / test-compile / state-file write phase finishes
  and before `executeCommand` for the JUnit (JVM) or test-kexe
  (native) runner. Holding the lock through the test child's
  lifetime would deadlock any nested `kolt` invocation from inside
  the test (#303), and the test runner itself does not write
  lock-protected state. `doRun` is not locked at all — it only reads
  prebuilt artifacts and spawns the run target, with no `kolt.lock`
  rewrite or `build/` finalisation in its body.
- **Not locked**: `kolt --help`, `kolt --version`, `kolt deps tree`,
  `kolt fmt`, `kolt run`. These are read-only or write only to source
  files outside `build/` and `kolt.lock`, and locking them would
  block IDE on-save formatting or a running app against an in-progress
  build for no safety gain.
- `kolt watch` does not hold the lock for the lifetime of the watch
  loop. Each rebuild inside the loop acquires and releases the lock
  per iteration, so a watch process does not block manual `kolt`
  invocations between rebuilds.

Lock semantics rely on Linux `flock(2)`:

- Lock is held against the file descriptor's open-file description.
- The kernel releases the lock when the process exits (clean exit,
  crash, `SIGKILL`, or power loss), so stale lock files left behind
  do not block the next `kolt` run.
- The lock is **advisory**: only processes that explicitly call
  `flock(2)` participate. kolt is the only writer to `build/` and
  `kolt.lock` by contract, so this is sufficient.

### §2 Wait protocol

`ProjectLock.acquire(buildDir, timeoutMs = 30_000)`:

1. Open `build/.kolt-build.lock` with `O_CREAT | O_RDWR` (empty file;
   the lock is on the FD, the file content is unused).
2. Try `flock(LOCK_EX | LOCK_NB)`.
3. On `EWOULDBLOCK`, emit one stderr line
   `another kolt is running, waiting...` (only on the first peer
   detection per `acquire` call), then poll `flock(LOCK_EX | LOCK_NB)`
   every 100 ms via `usleep(100_000)`.
4. On success return `Ok(LockHandle)`; on `timeoutMs` exceeded return
   `Err(LockError.TimedOut)` and close the FD.
5. `LockHandle.close()` releases via `LOCK_UN` and `close(2)`. The
   `use { ... }` pattern is the canonical caller shape.

Why polling and not `alarm(2) + signal handler + LOCK_EX`: signal
handling interferes with Kotlin/Native's runtime (signal delivery is
restricted on the GC thread, and `EINTR` on the blocking `flock`
needs explicit retry against the runtime's own signal usage). Polling
is simpler, has no GC interaction, and the 100 ms granularity is
imperceptible against build wall times measured in seconds.

`KOLT_LOCK_TIMEOUT_MS` overrides the 30 s default. CI matrix jobs
that legitimately serialise for minutes can raise it; integration
tests that want to exercise the timeout path can lower it (e.g.
`KOLT_LOCK_TIMEOUT_MS=200`).

Timeout exit:

- Exit code: `EXIT_LOCK_TIMEOUT` (a new code reserved by this ADR;
  the actual numeric value is set in `Main.kt` at implementation
  time and is not part of the ADR contract).
- stderr: a single line naming the timeout duration and pointing
  at the holding process if discoverable; no traceback.
- `build/` and `kolt.lock` are left untouched — the lock guards the
  pre-write boundary, so a timeout cannot leave a half-written
  artifact.

### §3 Global cache atomic write

`Downloader.download(url, destPath)` writes through a per-PID temp
path:

1. Compute `tempPath = "$destPath.tmp.${getpid()}"`.
2. `cleanupStaleTemps(parentDir(destPath))` (see §4).
3. `fopen(tempPath, "wb")`, libcurl fetch, `fclose`.
4. SHA-256 verify. On mismatch, `remove(tempPath)` and return
   `Err(Sha256Mismatch)` without touching `destPath`.
5. On match, `platform.posix.rename(tempPath, destPath)`.
6. On any error in steps 3-4, `remove(tempPath)` and return the
   error; `destPath` is not modified.

`rename(2)` is atomic within the same filesystem on POSIX. The temp
and final paths are siblings under
`~/.kolt/cache/<g>/<a>/<v>/`, so they are always on the same
filesystem and the atomicity guarantee holds. The cross-filesystem
case does not arise inside `~/.kolt/cache/`.

Concurrent download of the same coordinate by two kolt processes:

- Each process writes its own `*.tmp.<pid>` (distinct PIDs for
  concurrent runs, guaranteed by the kernel for the duration of the
  run).
- Each process SHA-verifies its own bytes before `rename`.
- Both `rename` calls succeed; the second overwrites the first
  atomically. Last-writer-wins is safe because both winners produced
  bytes that passed the same SHA-256 check; readers either see the
  pre-rename absence or one of two byte-identical post-rename
  results.

Temp suffix is `<destPath>.tmp.<pid>` (PID alone, no random
component). PID reuse is bounded to a different process generation,
and any leftover from a dead generation is swept by §4 long before
reuse can collide with a live download.

### §4 Stale temp sweep

`cleanupStaleTemps(cacheDir, olderThanSeconds = 86_400)`:

- Lists entries in `cacheDir` matching `*.tmp.*`.
- Removes those whose mtime is older than 24 hours.
- Runs on every `Downloader.download` call, scoped to the
  destination's parent directory only.

Per-directory cost is `O(files in <g>/<a>/<v>/)`, which in practice
is one to a handful of versions per coordinate. The sweep is not
expected to show up in any profile; if it ever does, the cost is
amortisable by hoisting it out of the hot path.

The 24-hour threshold is conservative — a kolt build that runs for a
day has bigger problems than a stale temp file — and avoids racing
against an in-flight slow download by orders of magnitude.

### §5 Out-of-scope concurrency, with cross-references

**Daemon socket bind exclusion** is described in ADR 0016 §3-§5:

- The daemon binds a Unix socket at
  `~/.kolt/daemon/<projectHash>/daemon.sock` and a second one at
  `<projectHash>/<kotlinVersion>/native-daemon.sock` (ADR 0024 §3).
- A second daemon's `bind(2)` fails with `EADDRINUSE`; the OS handles
  the exclusion. There is no application-level lock around bind.
- `DaemonReaper` (ADR 0016 referenced) probes the socket and reaps
  stale entries when the holder process is gone.

This ADR explicitly does not redefine that contract. It only relies
on it: the project-local `flock` covers the kolt CLI processes, the
OS-level socket bind covers the daemon processes, and the two
mechanisms are independent.

**Cross-machine `~/.kolt/` over NFS is unsupported.** Both
mechanisms in this ADR assume local-filesystem semantics:

- `flock(2)` over NFS is implementation-defined and historically
  unreliable; some NFS clients map it to `fcntl` POSIX locks, others
  to no-ops. The 30 s timeout would either fire incorrectly or never.
- `rename(2)` is only required to be atomic on the same local
  filesystem. NFS-exported atomicity is best-effort and depends on
  client/server caching policy.

Operators MUST keep `~/.kolt/` on a local filesystem. A team-shared
`~/.kolt/cache/` over NFS is not a supported configuration. CI with
a non-shared `~/.kolt/cache/` per job is unaffected by this ADR — the
contract simply does not engage when there is no contention.

### §6 No migration shim

Per CLAUDE.md "no backward compatibility until v1.0", this ADR does
not provide a migration path:

- `build/.kolt-build.lock` is a clean addition. It appears on the
  first lock-protected command and is gitignored under `build/`. No
  user action is required.
- `~/.kolt/cache/` may contain stray `*.tmp.*` files from older
  experiments; the `cleanupStaleTemps` sweep removes them on first
  download per directory.
- There is no opt-out flag for the lock; the assumption is that
  serialising same-project builds is always the right behaviour.
  CI matrix jobs that use a shared cache should still see
  per-project locking and no global serialisation.
- The release note for the version that ships this contract states
  "`build/.kolt-build.lock` is a new gitignored file under `build/`
  and requires no action". Older kolt versions running against the
  same project see a stray empty file and do not coordinate with the
  newer kolt; this is acceptable under pre-v1 policy.

## Consequences

**Positive**

- Same-project serialisation is bounded (30 s default, env-tunable)
  and crash-safe (kernel releases the FD lock on process death).
- Global cache writes are atomic and parallel-safe without a global
  mutex; CI matrix jobs sharing `~/.kolt/cache/` no longer race on
  partial JAR bytes.
- The contract is one file: `build/.kolt-build.lock` for the project,
  `<dest>.tmp.<pid>` for the cache. No second-order plumbing
  (lock-server daemon, cross-process named pipe, etc.).
- ADR 0016's daemon contract is reused as-is; no work is duplicated.

**Negative**

- An IDE-on-save loop hitting the 30 s timeout exits with
  `EXIT_LOCK_TIMEOUT` rather than queueing forever. This is by
  design, but tooling that wraps kolt (e.g. an LSP server) must
  surface the timeout cleanly rather than re-spawning.
- `flock(2)` is advisory: any future kolt-adjacent tool that writes
  into `build/` directly must call `flock` on the same path or it
  will not coordinate. The current contract assumes kolt is the only
  writer.
- The `cleanupStaleTemps` 24 h threshold is a heuristic. A
  pathologically slow (>24 h) download will be cleaned up by another
  process's sweep before it finishes; this is acceptable because a
  >24 h download is failure regardless.
- NFS-shared `~/.kolt/` is documented as unsupported, which is a
  capability some teams might want; the alternative (true
  cross-machine lock service) is out of scope and explicitly not
  promised post-v1.

### Confirmation

- `ProjectLock.acquire / LockHandle` lives in
  `src/nativeMain/kotlin/kolt/concurrency/ProjectLock.kt`. Unit tests
  in `ProjectLockTest.kt` exercise in-process two-FD contention and
  the timeout path with `timeoutMs = 200`.
- An integration test (`ConcurrentBuildIT.kt`) spawns two kolt
  processes via `executeCommand` and asserts that the second
  serialises behind the first; a second case asserts that
  `KOLT_LOCK_TIMEOUT_MS=200` produces `EXIT_LOCK_TIMEOUT` when the
  first holds the lock past the bound.
- `Downloader.kt` tests pin: (a) destination unchanged when curl
  fails mid-stream, (b) destination unchanged on SHA mismatch, (c)
  no `*.tmp.*` files left after success, (d) two concurrent
  downloads of the same coordinate both succeed and leave one
  SHA-valid jar at the final path.
- The ADR text and the implementation stay in sync via PR review;
  there is no schema registry for the lock file or temp naming
  outside this ADR.

## Alternatives considered

1. **Cross-machine lock service (e.g. a small HTTP service backing
   the lock).** Rejected. The supported deployment model has each
   developer machine owning its own `~/.kolt/` and `build/`; a lock
   service exists only to support NFS-shared `~/.kolt/`, which is
   an explicit non-goal (§5). Adding a lock service to handle a
   non-supported topology would be substantial code for a
   configuration kolt does not promise.
2. **`fcntl(F_SETLK)` POSIX advisory lock instead of `flock(2)`.**
   Rejected. `fcntl` locks are per-process (not per-FD), which
   complicates in-process testing of contention because two FDs in
   the same process do not contend; `flock` semantics on Linux are
   per open-file description and let the unit tests run two
   contenders in one process. NFS behaviour is no better with
   `fcntl` than with `flock` — both are unreliable — so the
   simplicity argument carries.
3. **`alarm(2) + signal handler + flock(LOCK_EX)` instead of
   polling.** Rejected. Kotlin/Native's runtime restricts signal
   delivery (e.g. on the GC thread); a `SIGALRM`-driven `EINTR` path
   needs careful coordination with the runtime's own signal usage.
   Polling at 100 ms has imperceptible cost against build wall times
   in seconds and avoids the runtime interaction entirely.
4. **Global cache mutex (one lock for all of `~/.kolt/cache/`).**
   Rejected. Concurrent downloads of distinct coordinates are
   independent, and a single mutex would serialise them needlessly.
   The temp-then-rename approach is lockless and gives the same
   crash-safety property per coordinate.
5. **Random suffix in the temp path (`*.tmp.<pid>-<random>`)
   instead of `*.tmp.<pid>`.** Rejected. The PID alone is unique
   for the lifetime of a run, which is the only window during which
   collision matters. The 24 h sweep handles cross-generation
   leftovers; the random suffix would buy nothing and would make the
   sweep harder to reason about.
6. **Per-coordinate lock under `~/.kolt/cache/<g>/<a>/<v>/.lock`.**
   Rejected. Temp-then-rename gives equivalent safety without
   another lock file in the cache, and the last-writer-wins property
   on SHA-validated bytes (§3) means the would-be lock is redundant.

## Related

- ADR 0001 — `Result<V, E>` discipline; `ProjectLock.acquire` and
  `Downloader.download` both return `Result<_, _>` per this rule.
- ADR 0003 — TOML config / JSON lockfile boundary; the lock-protected
  `kolt.lock` rewrite preserves that boundary.
- ADR 0007 — mtime build cache; the new `build/.kolt-build.lock`
  participates as a normal `build/` artifact (gitignored).
- ADR 0016 §3-§5 — warm JVM compiler daemon socket bind exclusion;
  the daemon contract this ADR explicitly relies on rather than
  redefines.
- ADR 0024 §3 — native compiler daemon socket path; same OS-level
  bind exclusion applies under the same project hash.
- ADR 0027 — runtime classpath manifest; one of the `build/`
  finalisation outputs that the lock guards.
- CLAUDE.md — "no backward compatibility until v1.0" rule cited in
  §6 for the no-shim policy.
- Spec `concurrent-build-safety` — requirements.md, design.md,
  research.md drive this ADR.
