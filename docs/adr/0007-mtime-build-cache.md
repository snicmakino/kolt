# ADR 0007: Skip unchanged builds by comparing mtimes in a JSON state file

## Status

Accepted (2026-04-09)

## Context

Before the build cache, `keel build` always re-invoked kotlinc, even
when nothing had changed. kotlinc on Kotlin/Native takes roughly one
second of fixed overhead per invocation even for a no-op, and the jvm
compiler is no better. For anyone running `keel build && keel run` in
a tight edit-compile-test loop, that fixed overhead was the dominant
cost.

The goal was to skip recompilation when the inputs haven't changed.
The inputs to a keel build are:

- The source files under `src/main/kotlin` (or the configured source
  directory).
- The resource files under `src/main/resources` (if any).
- The configuration file `keel.toml` (compiler args, main class,
  plugin list, custom repositories, target).
- The lockfile `keel.lock` (the resolved dependency graph — if it
  changes, the classpath changes).
- The compiled output directory itself (so that deleting `build/`
  triggers a rebuild).

If none of these has changed since the last successful build, the
output is still valid and kotlinc does not need to run.

There are three common ways to answer "has this file changed":

1. **Content hashing**: read the file, sha256 it, compare to a stored
   hash. Correct, but expensive on every build.
2. **Modification time (mtime)**: `stat` the file, compare its mtime
   to a stored mtime. Cheap, but can be fooled by filesystems with
   coarse mtime resolution or by `touch` without real changes.
3. **Full dependency graph with watchers**: what Gradle and Bazel do.
   Powerful but a major engineering investment.

keel is a lightweight build tool. Option 3 is out of scope. Between
1 and 2, the trade-off is: content hashing is always accurate but
reads every source file on every build; mtime is cheap but may
produce a false negative (skip when it should rebuild) on pathological
edge cases.

For a single-user development loop, false negatives from mtime are
rare and recoverable (`keel clean && keel build` always works).
Content hashing is a performance regression we would pay on every
build in exchange for an accuracy improvement that almost nobody
will hit.

## Decision

Persist a small JSON file at `build/.keel-state.json` that records
the mtimes of every input from the last successful build. On the
next `keel build`, `keel check`, or `keel run`, compute the current
mtimes, compare them field by field, and skip kotlinc entirely if
they all match.

The state record (`BuildState` in `build/BuildCache.kt`):

```kotlin
data class BuildState(
    val configMtime: Long,            // keel.toml
    val sourcesNewestMtime: Long,     // max(mtime) across source files
    val classesDirMtime: Long?,       // compiled output directory
    val lockfileMtime: Long?,         // keel.lock
    val classpath: String? = null,    // serialised classpath string
    val resourcesNewestMtime: Long? = null  // max(mtime) across resources
)
```

`isBuildUpToDate(current, cached)` compares every field for
equality. If any differs, or if the cached state is missing, the
build proceeds.

Key shape decisions:

- **"Newest mtime across a directory"**, not per-file mtime maps.
  Taking `max(mtime)` over every source file collapses the state to
  a single `Long` and is still sufficient: if any source file was
  modified, the max goes up. Deleted files are caught by the
  `classesDirMtime` check (the compiled output does not update for
  deletions, so the classes dir mtime moves backwards relative to
  the expected rebuild output).
- **Nullable fields** for `classesDirMtime`, `lockfileMtime`, and
  `resourcesNewestMtime`. `keel.lock` may not exist yet for a
  project with no dependencies; `resources/` is optional; the
  classes dir does not exist on the first build.
- **Classpath string in the state**: if the user adds or removes a
  dependency without changing the lockfile mtime (rare but possible
  with manual edits), the classpath change is still visible via
  direct string comparison.
- **JSON, not TOML or binary**: the state file is internal to the
  build directory and never edited by hand, but keeping it JSON
  means we can pretty-print it and eyeball the values when debugging
  a cache-invalidation bug.
- **`build/.keel-state.json`** is under `build/`, so
  `keel clean` removes it automatically. There is no separate cache
  directory to manage.

`parseBuildState` swallows any parse error and returns `null`,
treating a corrupt state file as "no cache". That way a botched
write (crash mid-serialise, partial disk) degrades cleanly to a
rebuild rather than a hard failure.

## Consequences

### Positive

- **No-op builds are near-instant**: `keel build` with no changes
  does a handful of `stat` calls and a JSON parse, then exits. The
  dominant cost is now process startup, not compilation.
- **Correct for the common case**: edit a source file → mtime moves
  → rebuild. Change `keel.toml` → rebuild. `keel install` writes a
  new lockfile → rebuild. Delete `build/` → rebuild. All the
  ordinary workflows invalidate correctly.
- **Self-cleaning**: the state lives inside `build/`, so
  `keel clean` removes it for free. There is no second cache
  directory in `~/.keel` to keep consistent.
- **Cheap to write**: one small JSON file, one `writeText` after a
  successful build. Fails cleanly if the filesystem is unhappy
  (the Result-based I/O surfaces the error, and the next build
  treats the state as missing).
- **Debuggable**: the state file is human-readable. If a rebuild
  happens when it shouldn't, you can `cat build/.keel-state.json`
  and compare the stored mtimes against `stat` on the real files.
- **Foundation for future work**: if we later want content-hash
  verification for specific fields, we can add them to
  `BuildState` as new nullable columns without changing the
  comparison logic.

### Negative

- **False negatives on coarse mtime filesystems**: filesystems with
  1-second mtime resolution (older ext4, FAT32) can miss a
  modification made within the same second as the last build. For
  a human edit loop this is extremely rare, but CI systems that
  regenerate files programmatically can hit it. The workaround is
  `keel clean`.
- **False negatives on `touch -d`**: a file whose mtime is set
  backwards will not trigger a rebuild. This is a self-inflicted
  wound; no sensible tool does it in practice.
- **Doesn't detect external classpath changes**: a classpath jar
  whose contents change without its path or the lockfile changing
  (manual edit of a cached JAR in `~/.keel/cache`) will not be
  detected. SHA-verified cache entries make this unreachable in
  normal use.
- **Single max-mtime loses granularity**: we cannot tell which
  source file changed, only that *some* file changed. This is fine
  for "rebuild or not" but would need to be richer if we ever want
  incremental compilation inside kotlinc.
- **Classpath stored as a string**: if the ordering of the
  classpath changes without the content changing (different
  resolver pass), the state comparison spuriously invalidates.
  In practice the resolver is deterministic, so this has not
  caused problems.

### Neutral

- **No cache key versioning**: the `BuildState` schema is plain
  kotlinx-serialization with default values. Adding a field as
  nullable-with-default is backwards-compatible; a breaking schema
  change would require a cache bust, which is just "delete
  `build/`".
- **Future incremental compilation is a different problem**:
  skipping the whole build when nothing changed is orthogonal to
  recompiling only changed files when something did change. This
  ADR does not attempt the latter.

## Alternatives Considered

1. **SHA-256 of every source file** — rejected. Reading and
   hashing every source file on every build reintroduces the
   overhead we were trying to eliminate. The accuracy gain does
   not justify the cost for a local build loop.
2. **Gradle-style task graph with up-to-date checking** —
   rejected as far out of scope. keel's whole value proposition is
   being orders of magnitude simpler than Gradle. Rebuilding that
   machinery would defeat the purpose.
3. **`mtime + size` combined key** — considered. `size` changes
   independently for same-second edits that add or remove a
   character. It would reduce false negatives slightly, but the
   cost is non-trivial (a second `stat` field per file, extra
   plumbing) and the added accuracy is marginal. Revisit if the
   simple mtime approach produces bug reports.
4. **Delegate to kotlinc's `-Xbuild-file` incremental cache** —
   rejected. kotlinc's incremental support is complex, version-
   specific, and oriented at Gradle's execution model. Using it
   from an ad-hoc CLI would require replicating a lot of Gradle's
   inputs-tracking surface.

## Related

- `src/nativeMain/kotlin/keel/build/BuildCache.kt` — `BuildState`,
  `isBuildUpToDate`, `serializeBuildState`, `parseBuildState`
- Commit `4d94ce0` (initial mtime-based build cache)
- ADR 0003 (JSON lockfile — same serialisation policy for internal
  machine-readable files)
