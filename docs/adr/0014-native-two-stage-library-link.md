# ADR 0014: Compile Kotlin/Native via library → link two stages

## Status

Accepted (2026-04-13). Supersedes [ADR 0013](0013-native-test-single-step-compile.md).

> **Note (2026-04-13, ADR 0015):** This ADR's discussion still references
> `nativeEntryPoint()` / `needsNativeEntryPointWarning()` as live code.
> Those helpers were deleted by [ADR 0015](0015-main-field-is-kotlin-function-fqn.md)
> when `config.main` was redefined as a Kotlin function FQN. `nativeLinkCommand`
> now emits `-e config.main` on the link stage. The core decision of this ADR
> (two-stage library → link) is unaffected.

## Context

Issue #62 added Kotlin compiler plugin support (`serialization`, `allopen`,
`noarg`) to the native build path. The initial implementation wired
`-Xplugin=<jar>` into the existing single-step `konanc -p program`
invocation used by `doNativeBuild` and `doNativeTest`. Builds succeeded
and konanc accepted the flag without complaint — but the plugin
registrars never actually ran. `@Serializable` was accepted as a plain
annotation, `Foo.serializer()` was never generated, and inspecting the
intermediate klib produced by `-p library` confirmed no serializer
symbols were emitted. The same behaviour reproduced against every
combination we tried: konanc 2.1.0 and 2.3.20, the plugin jars shipped
in the kotlinc distribution and the `-embeddable` variants from Maven
Central, and both `-Xplugin=` and the newer `-Xcompiler-plugin=` flag.
The plugin jars' `META-INF/services/` SPI entries were correct; the
konanc wrapper script passes `-Xplugin` through to the underlying JVM
process unmodified.

The Kotlin Gradle plugin, meanwhile, runs the **same** plugin jar
(`.../kotlin-serialization-compiler-plugin-embeddable-2.3.20.jar`) via
the **same** `-Xplugin=` flag against the **same** konanc binary, and
the plugin does run. The difference turned out to be structural:

- `compileKotlinLinuxX64` produces a klib with `-produce library`.
- `linkDebugExecutableLinuxX64` links the klib into an executable as a
  separate task.

Reproducing that shape directly from a shell made the plugin run:

```
konanc -p library -nopack -Xplugin=<jar> -l <deps> -o build/<name>-klib <sources>
konanc -p program -l <deps> -Xinclude=build/<name>-klib -o build/<name>
```

Stage 1 emits a klib whose IR already contains plugin-generated members
(`serializer()`, `$serializer`, etc.). Stage 2 pulls that klib's IR into
the final program via `-Xinclude`; no plugin is needed here because the
IR has already been transformed. A single-step `-p program` invocation
against konanc 2.1.0/2.3.20 silently skips the plugin registrar
pipeline. We have not filed this upstream; for the purposes of this
ADR it is a fixed cost of the toolchain.

ADR 0013 adopted the single-step α approach for `kolt test` and
explicitly deferred the two-stage β structure as "reversible later if
#59 (incremental test caching) becomes painful." The trigger for
reversing that decision is not #59 but the compiler plugin issue
discovered in #62, and the reversal has to apply to `doNativeBuild` as
well as `doNativeTest` — the plugin problem affects both paths.

## Decision

Split every native compilation into a library stage followed by a link
stage, for both build and test paths. Four new command builders in
`Builder.kt` (all pure functions), wired through `BuildCommands.kt`:

| Function | Stage | Role |
|---|---|---|
| `nativeLibraryCommand` | Stage 1 | `konanc -p library -nopack` on `config.sources`, carrying `-Xplugin` args. Output: `build/<name>-klib/`. |
| `nativeLinkCommand` | Stage 2 | `konanc -p program -Xinclude=build/<name>-klib`. Output: `build/<name>.kexe`. |
| `nativeTestLibraryCommand` | Stage 1 | Same as Stage 1 but compiles `config.sources + config.testSources` together. Output: `build/<name>-test-klib/`. |
| `nativeTestLinkCommand` | Stage 2 | `konanc -p program -generate-test-runner -Xinclude=build/<name>-test-klib`. Output: `build/<name>-test.kexe`. |

`-generate-test-runner` lives on the Stage 2 link command, not the
library stage. The synthesized runner discovers `@Test` classes from
the klib included via `-Xinclude`; verified end-to-end with `kolt.test`
assertions running under the two-stage shape.

Two details follow from the split:

1. **The link command omits `-e`.** `-Xinclude` pulls the compiled
   `main()` out of the klib; there is no need to tell konanc which
   entry point to use, and passing `-e` alongside `-Xinclude` risks
   conflicting with the IR already linked in. ADR 0012's
   `nativeEntryPoint()` helper is now used only by the
   `needsNativeEntryPointWarning` heuristic, not on the compile path.
2. **The link command repeats `-l <klib>` for every transitive klib.**
   `-Xinclude` inlines the *project* klib's IR into the final link
   unit, but any *external* references (e.g. calls from
   plugin-generated serializer code into `kotlinx-serialization-core`)
   are still unresolved at that point and need the transitive klibs on
   the library path. Only the project klib goes through `-Xinclude`;
   everything else stays on `-l`.

`resolveNativePluginArgs` (in `PluginSupport.kt`) short-circuits when
no plugins are enabled, so plugin-less native projects do not pay the
cost of provisioning the kotlinc sidecar. It is also invoked only after
the `isBuildUpToDate` check in `doNativeBuild`, so cached builds skip
plugin resolution entirely.

## Consequences

### Positive

- **Compiler plugins actually run on native.** The whole point: #62 is
  unblocked, `kolt build` + `kolt run` round-trips a `@Serializable`
  data class through JSON on `target = "native"` (verified via the
  `native-serialization` kolt-examples project).
- **Symmetry between build and test paths.** ADR 0013 listed
  "structural asymmetry between JVM and native `doTest`" as a negative
  consequence because the native test path bypassed `doNativeBuild`.
  Under ADR 0014, both `doNativeBuild` and `doNativeTest` share the
  same library → link shape, and the asymmetry remains only in the
  sense that the JVM path still reuses `build/classes` across build
  and test while native's intermediate klib is not shared between
  paths.
- **Closer to the Kotlin Gradle plugin's shape.** Reviewers, tooling
  authors, and users with prior Gradle experience will find the
  library → link structure familiar, and bug reports against konanc
  behaviour map more directly onto KGP's own issue tracker.
- **Enables incremental test caching (#59).** ADR 0013 noted that a
  `kolt test` edit-only-tests loop could not be sped up by a cache
  alone under α because main and test sources participated in the
  same compilation unit. Under β, a future optimisation can reuse a
  cached `build/<name>-klib` across `kolt build` and `kolt test`
  invocations, letting test-only edits skip main-source recompilation.
  This ADR does not implement that optimisation; it only removes the
  structural obstacle.

### Negative

- **Two konanc invocations per native build.** Every `kolt build` now
  pays the cost of starting konanc twice, even for plugin-less
  projects. We did not measure the overhead explicitly; KGP takes the
  same shape and ships it to all users, so we are betting the overhead
  is dominated by the actual compilation cost.
- **Kotlinc distribution is still provisioned as a sidecar for plugin
  jar lookup.** `pluginArgs()` builds `-Xplugin=<kotlincHome>/lib/<plugin>.jar`,
  so when a native project enables any plugin, `doNativeBuild` calls
  `ensureKotlincBin` in addition to `ensureKonancBin`. Native-only
  users who enable plugins therefore end up downloading a full kotlinc
  distribution. A follow-up (#65) will resolve the plugin jars
  directly from Maven Central by fixed coordinate to drop the kotlinc
  sidecar.
- **`BuildState` still only tracks the final kexe mtime.** The
  intermediate klib at `build/<name>-klib/` is regenerated on every
  non-cached build; there is no caching between stages. This keeps
  the up-to-date check simple but is weaker than what β structurally
  makes possible. Tightening it is follow-up work.
- **`nativeEntryPoint()` is demoted.** It survives only as input to
  the warning message about non-`Kt` class names in `config.main`.
  The warning heuristic is arguably less load-bearing now that the
  link stage discovers `main()` from the klib rather than from an
  explicit `-e` — a non-Kt `main` field with a top-level `fun main()`
  will compile and run fine. The warning is kept for continuity; a
  future ADR could retire it.

### Neutral

- **Intermediate klib layout.** Stage 1 uses `-nopack` and writes an
  unpacked klib directory at `build/<name>-klib/` (as opposed to a
  packed `.klib` file). `-Xinclude` accepts the directory path
  directly. The `-klib` suffix keeps the intermediate distinct from
  the sibling `build/<name>.kexe` file without clashing.
- **Test runner output unchanged.** `-generate-test-runner` emits the
  same Google-Test-style lines and uses the same non-zero exit code
  on failure whether it runs as part of a single-step compile or a
  Stage 2 link. `doNativeTest`'s existing `ProcessError.NonZeroExit`
  handling is unchanged.

## Alternatives Considered

1. **Keep ADR 0013's α for `kolt test` and add β only on the build
   path.** Rejected. The plugin problem affects `doNativeBuild` just
   as much as `doNativeTest`; splitting only one path would leave
   `kolt test` with a second, different-shaped native compilation to
   maintain. The symmetry argument (shared positive above) evaporates.
2. **Drop compiler plugin support on native; tell users to target
   JVM for serialization-heavy code.** Rejected because #61 (self-host
   kolt with kolt) requires the native path to compile kolt itself,
   and kolt uses `kotlinx.serialization` for its lockfile. Plugin
   support is a prerequisite for self-hosting, not an optional
   feature.
3. **Wait for an upstream konanc fix that makes `-p program` honour
   plugin registrars.** Rejected. We found no existing upstream issue
   tracking this behaviour, and filing one would not give us a
   timeline. The library → link workaround exists, works, and is what
   KGP already does; adopting it is cheaper than organising upstream
   advocacy.
4. **Pack the intermediate klib (`build/<name>.klib`) instead of using
   `-nopack`.** Rejected as a minor optimisation that gains nothing
   for a build-local artifact. `-nopack` is slightly cheaper (no zip
   work) and `-Xinclude` accepts either form. We may revisit this if
   we ever want to publish the klib as a distributable artifact.

## Related

- [ADR 0013](0013-native-test-single-step-compile.md) — superseded by
  this ADR. Its α approach for native test compilation is replaced by
  the two-stage split described here.
- [ADR 0012](0012-derive-native-entry-point-from-config-main.md) —
  unchanged in decision but demoted in scope: `nativeEntryPoint()` is
  no longer consumed by the compile path.
- #62 — original issue that surfaced the konanc plugin loading bug.
- #61 — self-host kolt with kolt; the feature gate this ADR unblocks.
- #65 — follow-up to resolve compiler plugin jars from Maven Central
  by fixed coordinate, dropping the kotlinc sidecar.
- #66 — implementation PR.
