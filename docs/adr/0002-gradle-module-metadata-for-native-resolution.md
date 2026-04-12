# ADR 0002: Use Gradle Module Metadata for Kotlin/Native dependency resolution

## Status

Accepted (2026-04-13)

## Context

keel supports two build targets: `target = "jvm"` and `target = "native"`
(see #16). The two targets need different artifacts from Maven Central:

- **jvm** consumes `.jar` files. Their dependency information lives in
  `.pom` files (Maven POMs), which keel parses and walks transitively in
  `TransitiveResolver` / `PomParser`.
- **native** consumes `.klib` files. Modern Kotlin Multiplatform libraries
  (kotlinx-coroutines, kotlinx-serialization, ktor, etc.) publish their
  per-target `.klib` artifacts as **Gradle Module Metadata** (`.module`
  JSON files), not as POMs.

The first decision point was whether keel should:

1. Try to extract native dependencies from POMs anyway (the root POM of a
   KMP library does have a `<dependencies>` section), or
2. Use the `.module` files as the authoritative source for native
   resolution.

A spike against `kotlinx-coroutines-core:1.9.0` (see Phase B research for
#16) confirmed that POMs **cannot** carry the information needed:

- The POM only describes the JVM jar — it has no knowledge of the
  per-target `.klib` artifacts.
- The native variant's `available-at` redirect (e.g. `kotlinx-coroutines-core`
  → `kotlinx-coroutines-core-linuxx64`) is encoded as a Gradle metadata
  *variant*, not as a Maven dependency.
- The native variant's transitive dependencies are listed in the
  `.module` file's `variants[].dependencies[]`, not in the POM.
- File hashes (sha256/sha512) for `.klib` files live in
  `variants[].files[]` in the metadata, not in `.sha256` sidecars (the
  sidecars exist too, but the metadata is the authoritative source).

In short, `.module` files are not optional decoration on top of POMs:
they are the only place where Kotlin/Native artifact resolution
information lives.

## Decision

Native dependency resolution uses Gradle Module Metadata exclusively. The
JVM and native paths are kept as **two parallel pipelines** that share
only the surrounding `Resolver` interface and download / cache plumbing.

`Resolver.resolve()` dispatches on `config.target`:

```kotlin
fun resolve(...): Result<ResolveResult, ResolveError> =
    if (config.target == "native") resolveNative(config, cacheBase, deps)
    else resolveTransitive(config, existingLock, cacheBase, deps)
```

- **jvm path** (`TransitiveResolver` + `PomParser`): unchanged, walks
  POMs as before. KMP libraries with a JVM redirect are followed via
  `parseJvmRedirect` from `GradleMetadata.kt` — Gradle metadata is used
  here only to find the `available-at` JVM-specific module, then keel
  switches back to POM-based resolution from that point.
- **native path** (`NativeResolver`): walks `.module` files only. For
  each direct dependency:
  1. Fetch the root `.module` and find the `available-at` redirect for
     the current native target via `parseNativeRedirect`.
  2. Fetch the redirect-target `.module` and extract the `.klib` file
     reference (url + sha256) and the variant's `dependencies[]` via
     `parseNativeArtifact`.
  3. Verify the `.klib` sha256 against the value declared in the
     metadata.
  4. Recurse on the variant's `dependencies[]`.

The two resolvers are not unified. They share `ResolverDeps`,
`ResolveError`, `ResolveResult`, and the `downloadFromRepositories` /
`fetchAndRead` helpers.

## Consequences

### Positive

- **Correct native artifact resolution**: keel picks up exactly the
  `.klib` published for `linux_x64`, the version pinned by the publisher,
  and the transitive native dependencies the library actually needs at
  link time.
- **Authoritative sha256**: hashes are read from the metadata's
  `files[].sha256` rather than fetched from a separate `.sha256` sidecar,
  which removes one network round-trip and one failure mode.
- **No POM-fitting hacks**: keel does not have to invent fake "native
  classifier" coordinates or guess the `linuxx64` artifact name from the
  POM. The `available-at` redirect is followed verbatim from the
  metadata.
- **Independent evolution**: the native and jvm resolvers can be
  optimised, hardened, or rewritten independently. Bugs in one cannot
  break the other.

### Negative

- **Two resolvers to maintain**: `TransitiveResolver` and
  `NativeResolver` duplicate the BFS skeleton, the highest-version-wins
  rule, and the direct-deps-win precedence. Bug fixes in one path may
  need to be ported to the other.
- **Two parsers to maintain**: `PomParser.kt` (XML) and
  `GradleMetadata.kt` (JSON via kotlinx-serialization) are entirely
  separate code paths.
- **Libraries without Gradle metadata cannot be used as native deps**:
  if a library is published with a POM only and no `.module` file
  (rare for modern Kotlin libraries, but possible for very old or
  hand-rolled artifacts), `parseNativeRedirect` returns `null` and keel
  surfaces `ResolveError.NoNativeVariant`. This is the correct user-
  facing failure mode but may surprise users porting jvm projects.

### Neutral

- **`.module` parsing complexity is bounded**: keel only reads four
  attributes (`platform.type`, `native.target`, `usage`, `category`),
  the `available-at` block, and the `files[]` / `dependencies[]` arrays.
  We do not implement the full Gradle metadata 1.1 specification.
- **JVM path still uses Gradle metadata for KMP redirects**: the JVM
  resolver calls `parseJvmRedirect` from the same `GradleMetadata.kt`
  file. The split is "metadata for variant selection, POMs for
  dependency walking" on the JVM side, and "metadata for everything"
  on the native side.

## Alternatives Considered

1. **Unify under a single `Resolver` that branches inside the BFS** —
   rejected because POMs and Gradle metadata have fundamentally
   different shapes (XML element tree vs JSON variant array, parent POM
   chain vs `available-at` redirect, `<dependencyManagement>` vs version
   constraints in metadata). The branching would have to extend down to
   every helper, producing more conditional code than two parallel
   pipelines.
2. **Treat `.module` files as supplemental metadata on top of POMs** —
   rejected because the POM does not describe native artifacts at all.
   You cannot start from the POM and "augment" with `.module` data; the
   POM literally has nothing to augment.
3. **Walk only the root `.module` and skip the `available-at` redirect**
   (assume artifact name = `<module>-<targetname>` by convention) —
   rejected because Android Native targets, special case names, and
   future targets break the convention. The `available-at.module` value
   must be used verbatim.
4. **Use Coursier as an external dependency** — out of scope; keel is a
   single self-contained Kotlin/Native binary and does not shell out
   to JVM tools for core operations.

## Related

- #16 (Kotlin/Native target support — parent issue)
- PR #53 (Phase B-1, parser-only)
- PR #55 (Phase B-2, native resolver wired into `Resolver.resolve`)
- ADR 0003 (kotlin-stdlib skip — load-bearing assumption that depends
  on this resolver design)
