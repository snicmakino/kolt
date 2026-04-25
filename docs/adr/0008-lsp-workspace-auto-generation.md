---
status: accepted (amended 2026-04-26)
date: 2026-04-09
---

# ADR 0008: Generate IDE workspace files automatically from the resolved graph

## Summary

- `workspace.json` (JetBrains kotlin-lsp) is generated at the project root and kept in sync with the lockfile. (§1)
- ~~`kls-classpath` is also emitted for fwcd/kotlin-language-server.~~ Removed 2026-04-26; see §6.
- Regeneration triggers when the lockfile changes or either file is missing; no regeneration otherwise. (§2)
- Generation is a pure function in `build/Workspace.kt`; I/O is a thin wrapper in `BuildCommands.kt`. (§3)
- Both files are generated artefacts; exclude from version control — the `kolt init` template adds them to `.gitignore` by default. (§4)
- `workspace.json` encodes compiler arguments as a `"J"`-prefixed JSON string and the SDK `homePath` as `JsonNull`. (§5)

## Context and Problem Statement

kolt builds Kotlin projects without Gradle or Maven, creating an LSP gap: Kotlin language servers need to know source directories and classpath JARs, but they have no adapter for `kolt.toml`. Without that, every import from a dependency shows up as "unresolved reference" and autocomplete goes dark.

Two language servers matter in practice: JetBrains kotlin-lsp (used by Zed and other editors, reads `workspace.json`) and fwcd/kotlin-language-server (used by Neovim + `lspconfig` and VS Code extensions, reads `kls-classpath`). Both have Gradle and Maven adapters; neither has a kolt adapter.

The options were: fake a Gradle project descriptor, generate each server's native format directly, or require a separate `kolt lsp export` command. Faking Gradle means a large brittle surface that drifts with Gradle versions. A separate command creates a discoverability failure — users open the editor, see red squiggles, and do not know what to run. Regenerating on every build is wasteful in the no-op case; regenerating only on `kolt install` leaves files stale when the lockfile is rewritten by other paths. The lockfile-change-or-missing-file condition handles both concerns.

## Decision Drivers

- Opening a kolt project in Zed or Neovim after one `kolt build` must light up both language servers without manual configuration.
- The LSP must see exactly the same JARs kotlinc uses; the two views must not drift.
- No regeneration cost on no-op builds.
- Generator functions must be unit-testable without a real filesystem.

## Decision Outcome

Chosen option: **automatic generation of both LSP files triggered by lockfile change or missing file**, because it is the only approach that satisfies discoverability, correctness, and no-op build cost simultaneously.

### §1 Two files, one resolved graph

`generateWorkspaceJson(config, resolvedDeps)` produces the JetBrains kotlin-lsp schema: one `JAVA_MODULE` entry per source set (main and optional test), a `libraries` array with one `java-imported` entry per resolved JAR (cache path as `root`), an `sdks` entry derived from `config.jvmTarget`, and a `kotlinSettings` block. `generateKlsClasspath(resolvedDeps)` produces a colon-separated list of cache paths. Both traverse `resolvedDeps` once; adding a third consumer is a matter of writing another pure generator.

### §2 Regeneration condition

Regeneration happens inside `BuildCommands.kt` as part of the dependency-resolution step under two conditions: the lockfile just changed, or either file is missing from the project root. Both files are written from scratch on each regeneration — no diff merge, no incremental update. If a file is deleted by the user, it comes back on the next build.

### §3 Pure generators in `Workspace.kt`

```kotlin
fun generateWorkspaceJson(config: KoltConfig, resolvedDeps: List<ResolvedDep>): String
fun generateKlsClasspath(resolvedDeps: List<ResolvedDep>): String
```

Both are pure (value-in / value-out) and unit-tested in `WorkspaceTest.kt`. The filesystem writes are a thin wrapper in `BuildCommands.kt`, following ADR 0004.

### §4 Generated artefacts

Both files sit at the project root next to `kolt.toml` so the LSP can find them by looking at the working directory. They are reproducible from `kolt.toml` + `kolt.lock` + cache contents. The `kolt init` template excludes them from `.gitignore`; hand-started projects must add the entries to avoid committing machine-specific absolute paths from `~/.kolt/cache/`.

### §5 `workspace.json` schema details

`buildKotlinSettings` encodes compiler arguments as a magic `"J"`-prefixed JSON string under `compilerArguments`. The SDK `homePath` is `JsonNull` because kolt does not know at generation time which JDK the toolchain manager will select; kotlin-lsp currently tolerates this. The schema `version` integer must match what kotlin-lsp expects; a kotlin-lsp update may change the expected shape without warning, and the fix lands in `Workspace.kt`.

### Consequences

**Positive**
- IDE integration works out of the box after one `kolt build`.
- The LSP sees exactly the same JARs kotlinc uses; the two views cannot drift.
- No-op builds cost two `stat` calls and nothing else.
- Pure generators are unit-tested independently of the filesystem.

**Negative**
- `workspace.json` schema is undocumented and may change in kotlin-lsp updates without warning.
- `kls-classpath` carries classpath only — no sourceRoots metadata. Users needing non-default source layouts configure the server separately.
- Users who forget to gitignore the generated files commit machine-specific absolute paths.
- SDK `homePath` is `JsonNull`; kotlin-lsp currently tolerates it, but future versions may not.
- `workspace.json` treats the project as one main module plus an optional test module. Multi-module projects (not yet supported by kolt) would need a richer generator.

### Confirmation

`WorkspaceTest.kt` unit-tests both generators. The regeneration condition in `BuildCommands.kt` is reviewed on each PR touching the dependency-resolution step.

## Alternatives considered

1. **Emit a fake Gradle project and use existing LSP adapters.** Rejected. Gradle's project model requires dynamic evaluation (Groovy/Kotlin scripts) kolt does not execute. Faking enough of it to satisfy an LSP is a larger surface than emitting two JSON files directly.
2. **Require a separate `kolt lsp export` command.** Rejected. Creates a discoverability barrier and a class of "I edited my deps but the LSP is still red" bugs.
3. **Write only `workspace.json` and drop fwcd support.** Rejected. fwcd is still the default in several editor integrations, and `generateKlsClasspath` is ~3 lines reusing the same resolved graph.
4. **A single unified format adapted per-LSP at runtime.** Rejected. No shared "LSP project model" standard exists across the Kotlin ecosystem. Each server reads its own format.
5. **Regenerate on every `kolt build`.** Rejected as wasteful in the no-op case.

## §6 Update 2026-04-26: drop `kls-classpath`

Reverses Alternatives #3. Reasons:

1. fwcd/kotlin-language-server is upstream-deprecated; its README points users to JetBrains/kotlin-lsp.
2. fwcd's `ShellClassPathResolver` reads `kls-classpath` as an **executable shell script** invoked via `ProcessBuilder` and parses its stdout. kolt has been writing a plain colon-joined text file (mode 644, no shebang), which fwcd logs a warning for and ignores. The emission has therefore never been functionally consumed by any fwcd user of a kolt project.
3. The neovim editor smoke test for #248 confirmed that kotlin-lsp's neovim integration relies on Gradle/Maven discovery, not `kls-classpath` — so dropping the file does not regress the path that motivated keeping it.

`generateKlsClasspath` and the `KLS_CLASSPATH` write are removed; the regeneration trigger now keys only on `workspace.json` presence. Existing project trees may have a stale `kls-classpath` file from prior builds; users can `rm` it.

## Related

- `src/nativeMain/kotlin/kolt/build/Workspace.kt` — `generateWorkspaceJson`
- `src/nativeMain/kotlin/kolt/cli/DependencyResolution.kt` — regeneration trigger (writeWorkspaceFiles)
- Commit `a5af50f` — initial LSP workspace integration (v0.7.0)
- ADR 0004 — pure/IO separation; `Workspace.kt` generators are pure, wiring lives in `DependencyResolution.kt`
