# ADR 0025: Library artifact layout for native and JVM targets

## Status

Implemented (2026-04-22).

## Summary

- `kind = "lib"` (ADR 0023 §1) ships in #30, but ADR 0023 deliberately
  deferred the **artifact shape** question. This ADR pins it: native
  libraries emit a `build/<name>-klib` **directory**; JVM libraries emit a
  thin `build/<name>.jar` with no `Main-Class` manifest attribute and no
  bundled runtime or dependency classes (§1).
- Native lib reuses the existing ADR 0014 stage 1 command unchanged.
  `konanc -p library -nopack` already produces the directory form; no
  repack step is introduced (§2).
- JVM lib reuses the existing `jar cf <output> -C <classes> .` jar-packaging
  invocation. No `-include-runtime` anywhere in the pipeline; the default
  JDK manifest (`Manifest-Version` + `Created-By`) is accepted as-is (§3).
- The `build/<name>` stem is derived from `KoltConfig.name`; callers that
  need to locate the artifact use `outputNativeKlibPath(config)` /
  `outputJarPath(config)` rather than reconstructing the path (§4).
- The directory-not-file choice for native is the load-bearing one for
  downstream specs that actually build libraries: `kolt publish` (#21)
  and `kolt new --lib` (#28). The daemon self-host gap (#97, ADR 0018)
  is **not** a consumer — both daemons have `fun main` and ship as
  `shadowJar` fat jars, so they are applications, not libraries (§5).

## Context and Problem Statement

ADR 0023 §1 reserved `kind = "lib"` at the TOML schema layer and explicitly
deferred the question of what a library build *produces*. Issue #30 delivered
the end-to-end pipeline, and during Task 6 dogfood the artifact shape was
forced into the open:

- On native, the existing ADR 0014 two-stage flow runs stage 1 with
  `konanc -p library -nopack`, which emits an **unpacked klib directory**
  (`default/{ir, linkdata, manifest, resources, targets/…}`) rather than a
  packed `.klib` file. kolt's internal helpers (`outputNativeKlibPath`,
  stage 1 `-o` flag) were already wired to the directory form — the
  library-build-pipeline spec inherited that convention without declaring
  it.
- On JVM, `jar cf` had already been producing a thin class jar without a
  `Main-Class` attribute. The library path simply keeps this shape; a
  potential future `fat_jar = false` axis for apps (currently out of scope)
  would not disturb libraries.

`kolt publish` (#21) and `kolt new --lib` (#28) both need a definitive
answer for the artifact shape and path layout they are consuming. Writing
this ADR closes the gap so those downstream specs don't each re-derive
the contract. (The daemon self-host gap #97 / ADR 0018 appears
unrelated: see §5.)

## Decision Drivers

- **Reuse existing pipeline**: no repack step or new linker invocation if
  avoidable; the existing ADR 0014 stage 1 command should remain the one
  entry point for native library output.
- **No breaking change to `kolt.toml`**: artifact layout must be derivable
  from the current `name` + `kind` + `target` fields, no new required keys.
- **Predictable path derivation**: build-tool consumers (IDE, publish
  pipeline, `kolt run` reject path) must be able to compute the artifact
  path from `KoltConfig` alone, without running the build.
- **Forward compatibility with `kolt publish`**: whatever the shape is must
  be either directly publishable (Maven Central, `.klib` packaged form) or
  repackable with a declared step.

## Considered Options

- **Option A** — Native: unpacked klib directory; JVM: thin jar.
  (What the existing pipeline already produces.)
- **Option B** — Native: packed `.klib` file (run stage 1 without
  `-nopack`, or repack after stage 1); JVM: thin jar.
- **Option C** — Native: both (directory for build cache, packed file for
  publish); JVM: thin jar.

## Decision Outcome

Chosen option: **Option A**, because it requires zero new pipeline code,
keeps ADR 0014 stage helpers untouched, and meets every decision driver.
Packed-form conversion is a `kolt publish` concern and can be layered on
top without revisiting the build contract.

### §1 Canonical artifact paths

| Target | Kind | Artifact | Path |
|--------|------|----------|------|
| linuxX64 | `lib` | directory | `build/<name>-klib/` |
| linuxX64 | `app` | file | `build/<name>.kexe` |
| jvm | `lib` | file | `build/<name>.jar` |
| jvm | `app` | file | `build/<name>.jar` |

`<name>` is `KoltConfig.name` verbatim. Directory vs file is observable to
the user — `kolt build` stdout names the artifact kind ("built library
build/<name>-klib ..." vs "built executable build/<name>.kexe ...").

### §2 Native library build

Stage 1 runs unchanged (`konanc -p library -nopack -o build/<name>-klib
<sources…>`). The library path stops here — no stage 2 link, no
`.kexe`. The ADR 0014 stage helpers (`nativeLibraryCommand`,
`nativeLinkCommand`) are not modified by this ADR; the kind gate lives at
the `doNativeBuild` orchestration layer (see `src/nativeMain/kotlin/kolt/
cli/BuildCommands.kt`, function `nativeStagePlan` / `doNativeBuild`).

The directory layout matches what `konanc -nopack` emits:

```
build/<name>-klib/
└── default/
    ├── ir/
    ├── linkdata/
    ├── manifest
    ├── resources/
    └── targets/linux_x64/
```

No repack into a `.klib` zip archive is performed. Downstream tools that
require the packed form (notably `kolt publish`) run their own repack; it
is out of scope here.

### §3 JVM library build

Compile step: existing `kotlinc -d build/classes <sources…>` invocation.
kolt does **not** pass `-include-runtime` for either kind, now or in the
future — `JvmLibraryInvariantsTest` pins this as a regression trap.

Jar step: existing `jar cf build/<name>.jar -C build/classes .`
invocation. No `-m` / `--manifest` / `Main-Class` arguments. The JDK `jar`
tool emits a default `META-INF/MANIFEST.MF` containing only
`Manifest-Version` and `Created-By`; those two attributes are accepted.

Jar contents for a library build are strictly:

- `META-INF/MANIFEST.MF`
- `META-INF/<module>.kotlin_module` (emitted by kotlinc)
- Compiled project classes under their package directories

Resolved dependency classes and Kotlin stdlib classes are **not** bundled
into the jar. The `.kolt.lock` + the user's downstream consumer handles
resolution.

### §4 Path helpers are the API

Consumers (test harnesses, IDE integration, publish pipeline) compute
artifact paths via `outputNativeKlibPath(config)` and `outputJarPath(config)`,
not by string-concatenating `"build/" + config.name + ...`. The helpers
are the single source of truth for the layout defined in §1.

If this ADR is revised to move artifacts (e.g. a `target/<triple>/`
subdirectory per `cargo build` style, under Rust-compat future work), only
the helpers change; call sites are unaffected.

### §5 Downstream contract scope

Three specs consume this layout today:

- **`kolt publish` (#21)** — reads the built artifact(s) and produces
  Maven-compatible publications. For native libs it owns the repack to
  `.klib` (or whatever the future Kotlin/Native convention turns out to
  be); for JVM libs the thin `.jar` is directly publishable. `kolt publish`
  is a consumer of this ADR, not a modifier of it.
- **`kolt new --lib` (#28)** — scaffolds a library project. The generated
  `kolt.toml` only needs `kind = "lib"` and no `[build] main`; nothing
  about the artifact layout needs templating into the scaffold.
- **ADR 0018 self-host migration is NOT a consumer of this ADR.**
  Both `kolt-compiler-daemon/` and `kolt-native-daemon/` have `fun main`
  entry points and are packaged as `shadowJar` fat jars for `java -jar`
  launch — they are applications (`kind = "app"`), not libraries. Self-
  host of the daemons is tracked by #97, which requires JVM target
  support (partly already present), multi-module layout, and a **fat-jar
  output mode** for `kind = "app"` — none of which this ADR or spec #30
  delivers. Listing the daemons here would be misleading.

### Consequences

**Positive**
- Zero new pipeline code for the library case — every path listed in §1
  was already wired up by pre-Task-1 code or by Tasks 2–4 of #30.
- `kolt publish`, `kolt new --lib`, and the self-host plan can land without
  first relitigating the artifact shape.
- Regression tests (`JvmLibraryInvariantsTest`, `NativeStagePlanTest`, and
  the `spike/lib-dogfood/` fixtures) pin the contract end-to-end; a future
  change to the layout trips at least one of them.

**Negative**
- Native libs are a directory on disk, which surprises users coming from
  Kotlin/JVM (where libs are always single files). The `kolt build` stdout
  line ("built library build/<name>-klib …") is the primary user-facing
  affordance; documentation should call this out when `kolt publish` ships.
- The repack to packed `.klib` is deferred to `kolt publish`. Users who
  manually cp the artifact around will cp a directory — expected, but
  documented only in this ADR until `kolt publish` lands.

### Confirmation

- `JvmLibraryInvariantsTest.libraryJvmJarOutputPathIsBuildNameJar` and
  related cases in `src/nativeTest/kotlin/kolt/cli/BuildLibraryTest.kt`
  enforce the JVM lib layout.
- `NativeStagePlanTest` pins the native branching — lib returns the klib
  directory path, app returns the `.kexe` path.
- `spike/lib-dogfood/{jvm,native}/README.md` captures the on-disk layout
  for reviewers and is replayable.
- A future PR that changes `outputNativeKlibPath` or `outputJarPath`
  shape trips these tests and should revisit this ADR before merging.

## Alternatives considered

1. **Packed `.klib` file for native libraries.** Rejected. Would require
   either switching stage 1 off `-nopack` (untested path; `K2Native`'s
   behavior with packed output plus incremental cache has not been
   validated) or introducing a repack step after stage 1 (new code for no
   build-time benefit, because kolt itself never consumes its own libraries
   in packed form). `kolt publish` will own the repack when it needs to.
2. **Both forms (directory for cache, packed for publish).** Rejected as
   premature. Producing both doubles stage 1 output without a concrete
   consumer today. `kolt publish` has not been designed yet; whichever
   form it prefers can be produced on demand rather than eagerly.

## Related

- #30 — tracking issue ("Implement `kind = \"lib\"` build pipeline").
- ADR 0014 — native two-stage library+link flow (this ADR relies on stage
  1 being unchanged).
- ADR 0023 §1 — target/kind schema (this ADR extends the "reserved lib"
  slot with a concrete artifact definition).
- #21 — `kolt publish` (downstream consumer of §1 and §5).
- #28 — `kolt new --lib` (downstream consumer of §5).
- #97 — "kolt cannot build its own compiler daemon jar (self-host gap)".
  Orthogonal to this ADR: the daemons are `kind = "app"` fat jars, not
  libraries. See §5.
- ADR 0018 — distribution / self-host endgame. Orthogonal to this ADR
  for the same reason as #97; listed here only because early drafts of
  §5 incorrectly coupled them.
- `.kiro/specs/lib-build-pipeline/` — spec artifacts, design, tasks.
- `spike/lib-dogfood/README.md` — replayable on-disk evidence for §1.
