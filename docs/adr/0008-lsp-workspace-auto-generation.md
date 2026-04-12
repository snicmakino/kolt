# ADR 0008: Generate IDE workspace files automatically from the resolved graph

## Status

Accepted (2026-04-09)

## Context

keel's job is to build and run projects without pulling in Gradle or
Maven. That's fine for the build itself, but it creates a gap the
moment the user opens their editor. Kotlin Language Servers don't
know how to read `keel.toml`. They need to be told where the source
directories are and, critically, which JARs are on the classpath.
Without that, every import from a dependency shows up as
"unresolved reference" and autocomplete goes dark.

Two language servers matter in practice:

- **JetBrains kotlin-lsp** — used by Zed, the official
  JetBrains-branded LSP, increasingly the default for non-IntelliJ
  editors. Its project model is read from a `workspace.json` file
  that describes modules, libraries, source roots, and an SDK entry.
  The schema is a simplified version of IntelliJ's internal project
  model (modules, library entries with roots, Kotlin compiler
  settings).
- **fwcd/kotlin-language-server** — the older community LSP, still
  used by Neovim + `lspconfig` and various VS Code extensions. It
  reads a `kls-classpath` file: a plain list of JAR paths, one per
  line or colon-separated.

Both servers already know how to talk to Gradle and Maven. Neither
has an adapter for keel. The options were:

1. Write a Gradle-compatible project descriptor and rely on the
   existing adapters.
2. Generate each server's native project format directly.
3. Require the user to invoke a separate tool (`keel lsp export` or
   similar) before opening the editor.

Option 1 means pretending to be Gradle, which is a large surface to
fake and brittle in the face of Gradle version drift. Option 3 is a
discoverability failure — users would open the editor, see red
squiggles everywhere, and not know what to run to fix it. That
leaves option 2, and the question becomes *when* to generate the
files.

There's one more constraint specific to keel: the dependency graph
is produced by `Resolver.resolve()` and lives, in effect, in
`keel.lock`. The LSP files are a projection of that same graph. If
we regenerate on every build, we pay a small serialisation cost on
every run. If we regenerate only on `keel install` / `keel update`,
the files go stale when the lockfile is rewritten by some other
path (a resolver change, a `keel add` that bypasses `install`).

## Decision

Generate both `workspace.json` (for JetBrains kotlin-lsp) and
`kls-classpath` (for fwcd/kotlin-language-server) automatically at
the root of the project. Regeneration happens inside
`BuildCommands.kt` as part of the dependency-resolution step, under
two conditions:

- The lockfile just changed (dependencies were added, removed, or
  upgraded), **or**
- Either `workspace.json` or `kls-classpath` is missing from the
  project root.

Neither file is ever regenerated when the lockfile is unchanged and
both files already exist. In the hot path (rebuild with no dependency
changes), the LSP layer costs two `stat` calls and nothing else.

Generation is a pure function in `build/Workspace.kt`:

```kotlin
fun generateWorkspaceJson(config: KeelConfig, resolvedDeps: List<ResolvedDep>): String
fun generateKlsClasspath(resolvedDeps: List<ResolvedDep>): String
```

- `generateWorkspaceJson` produces the JetBrains kotlin-lsp schema:
  one `JAVA_MODULE` entry per source set (main and test if present),
  a `libraries` array with one `java-imported` entry per resolved
  JAR (including the cache path as its `root`), an `sdks` entry
  derived from `config.jvmTarget`, and a `kotlinSettings` block with
  the JSON-encoded compiler arguments prefixed with `"J"` that
  kotlin-lsp expects.
- `generateKlsClasspath` produces a colon-separated list of cache
  paths for every resolved dependency, one JAR per entry.

Both files are written to the project root with ordinary filesystem
operations. Neither goes into `build/` or `~/.keel/`; both are
expected to sit next to `keel.toml` so that the LSP can find them
by looking at the working directory.

`workspace.json` and `kls-classpath` are **generated artefacts**.
The project's `.gitignore` should exclude them; the `keel init`
template does so by default. They are reproducible from `keel.toml`
+ `keel.lock` + the cache contents.

## Consequences

### Positive

- **IDE integration works out of the box**: open a keel project in
  Zed or Neovim, run `keel build` once, and both language servers
  light up. No Gradle, no Maven, no per-editor configuration hand
  written by the user.
- **Two editors supported from one code path**: one traversal over
  `resolvedDeps` produces both output formats. Adding a third
  consumer (e.g. VS Code Kotlin extension's cache format) is a
  matter of writing another pure generator function and hooking it
  into the same regeneration condition.
- **No manual "export" step**: the user never has to remember to
  run a separate command. Because regeneration is triggered on
  lockfile change, it stays in sync with whatever the build tool
  actually believes the classpath is.
- **Cheap in the hot path**: the `stat` check on the two files is
  the only cost when nothing has changed. No re-serialisation, no
  I/O on the happy path.
- **Pure generators, testable**: `generateWorkspaceJson` and
  `generateKlsClasspath` are unit-tested like any other pure helper
  in `build/`. The I/O is a thin wrapper in `BuildCommands.kt`.
- **Classpath is authoritative**: the LSP sees exactly the same JARs
  kotlinc does, because both read from the same resolved graph.
  There is no way for the editor view to drift from what `keel build`
  would actually do.

### Negative

- **Schema coupling to kotlin-lsp internals**: `workspace.json` is
  not a documented stable format. It is the internal project-model
  serialisation that JetBrains's kotlin-lsp happens to accept.
  `buildKotlinSettings` in particular encodes a lot of nullable
  fields, a magic `"J"`-prefixed JSON string for `compilerArguments`,
  and a schema `version` integer. A kotlin-lsp update could change
  the expected shape without warning, and the fix would be in
  `Workspace.kt`.
- **`kls-classpath` is classpath-only**: fwcd/kotlin-language-server
  gets JAR paths but no sourceRoots metadata through that file. The
  server infers source layout from its own configuration. Users who
  want sourceRoots beyond the default have to configure the server
  themselves.
- **Generated files in the working directory**: users who forget
  to gitignore `workspace.json` and `kls-classpath` will commit
  machine-specific absolute paths from `~/.keel/cache/`. The
  `keel init` template excludes them, but older projects or
  hand-started ones need to add the entries.
- **Nullable fields that "should" be filled**: the SDK entry's
  `homePath` is `JsonNull` because keel does not know, at the
  generation site, which JDK the toolchain manager will pick.
  kotlin-lsp currently tolerates this, but "currently tolerates"
  is the caveat.
- **No per-module configuration**: `workspace.json` treats the
  whole project as one main module plus an optional test module.
  Multi-module projects (which keel does not yet support anyway)
  would need a richer generator.

### Neutral

- **Regeneration trigger is simple**: "lockfile changed OR file
  missing". No content hash, no mtime comparison on the generated
  files themselves. If a file is deleted by the user, it comes
  back on the next build.
- **No versioning of the generated files**: both files are written
  anew from scratch on each regeneration. There is no diff merge,
  no incremental update. This is intentional — generated files
  should be deterministic functions of their inputs.

## Alternatives Considered

1. **Emit a fake Gradle project and let existing LSP adapters read
   it** — rejected. Gradle's project model is enormous, version-
   dependent, and contains a lot of dynamic evaluation (scripts
   running in a Groovy or Kotlin VM) that keel does not and should
   not execute. Faking enough of it to satisfy an LSP is a much
   larger surface than emitting two JSON files directly.
2. **Require a separate `keel lsp export` command** — rejected. It
   puts a discoverability barrier in front of IDE integration and
   creates a class of "I edited my deps but the LSP is still red"
   bugs. Generation belongs in the same pipeline that writes the
   lockfile.
3. **Write only `workspace.json` and drop fwcd support** — rejected
   for now. fwcd is still the default in several editor integrations,
   and `generateKlsClasspath` is ~3 lines of code that reuses the
   same resolved graph. The marginal cost of supporting it is
   essentially nothing.
4. **Use a single unified format and adapt it per-LSP at runtime** —
   rejected. Neither LSP will read a custom format without code
   changes, and there is no shared "LSP project model" standard
   across the Kotlin ecosystem. Emitting what each server already
   understands is the only path that works today.
5. **Regenerate on every `keel build`** — rejected as wasteful in
   the hot path. The lockfile-change gate is slightly more
   complicated but keeps no-op builds clean.

## Related

- `src/nativeMain/kotlin/keel/build/Workspace.kt` —
  `generateWorkspaceJson`, `generateKlsClasspath`
- `src/nativeMain/kotlin/keel/cli/BuildCommands.kt` — regeneration
  trigger (lockfile-change / missing-file condition)
- Commit `a5af50f` (initial LSP workspace integration, v0.7.0)
- ADR 0004 (pure/IO separation — `Workspace.kt` generators are
  pure, wiring lives in `BuildCommands.kt`)
