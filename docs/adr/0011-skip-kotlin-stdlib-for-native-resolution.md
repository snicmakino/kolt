# ADR 0011: Skip kotlin-stdlib in Kotlin/Native dependency resolution

## Status

Accepted (2026-04-13)

## Context

When `NativeResolver` walks the Gradle Module Metadata of a Kotlin
Multiplatform library, it finds that **every native variant declares
`org.jetbrains.kotlin:kotlin-stdlib` as a transitive dependency**. For
example, the linuxX64 variant of `kotlinx-coroutines-core:1.9.0` lists:

```json
{
  "dependencies": [
    { "group": "org.jetbrains.kotlinx", "module": "atomicfu",
      "version": { "requires": "0.25.0" } },
    { "group": "org.jetbrains.kotlin", "module": "kotlin-stdlib",
      "version": { "requires": "2.0.0" } }
  ]
}
```

The naïve implementation would resolve `kotlin-stdlib` like any other
dependency: download its `.module`, follow the `available-at` redirect
to a native variant, fetch a `.klib`, and pass it to konanc via
`-library`.

This breaks for two independent reasons:

1. **konanc bundles its own stdlib.** The Kotlin/Native compiler
   distribution ships a `klib/common/stdlib` klib in
   `$installation/klib/`, and konanc auto-links it on every compilation.
   The existence of a `-nostdlib` flag in the compiler reference
   ("Don't link with stdlib") confirms that linking happens by default
   — there would be nothing to opt out of otherwise.
2. **Maven Central does not publish a usable native stdlib variant for
   `org.jetbrains.kotlin:kotlin-stdlib`.** The root `kotlin-stdlib`
   module exposes a JVM jar; the native variants live elsewhere and the
   resolver would either fail to find a `linux_x64` variant
   (`ResolveError.NoNativeVariant`) or, if it did find one, double-link
   it against konanc's bundled copy and produce a link error.

A spike during Phase B research (PR #53) confirmed the konanc bundling
behaviour: a Hello World native binary that calls `println` from
`kotlin.io` builds and runs without any `-library` argument and without
kolt resolving any dependency.

## Decision

`NativeResolver` explicitly skips `org.jetbrains.kotlin:kotlin-stdlib`
at every entry point. The skip is implemented as a single predicate at
the top of the file:

```kotlin
private fun isKotlinStdlib(groupArtifact: String): Boolean =
    groupArtifact == "org.jetbrains.kotlin:kotlin-stdlib"
```

The check is applied in three places:

1. **Direct-dependency validation loop** — even if the user writes
   `"org.jetbrains.kotlin:kotlin-stdlib" = "..."` in `kolt.toml`, it is
   skipped before coordinate validation.
2. **Direct-dependency seeding loop** — stdlib is not added to the BFS
   queue.
3. **Transitive enqueueing inside the BFS** — when walking
   `variants[].dependencies[]`, stdlib entries are dropped before the
   highest-version-wins comparison.

The skip is silent. No warning is emitted, because the user is expected
to never see it: it is a property of how Kotlin/Native is distributed,
not a project-level mistake.

`-nostdlib` is not passed to konanc. We rely on the default behaviour
(stdlib auto-linked by konanc) rather than opting out and re-supplying
stdlib ourselves. This keeps kolt out of the business of versioning
stdlib against the bundled konanc release.

## Consequences

### Positive

- **Builds work.** Without this skip, every native build that depends
  on a Kotlin Multiplatform library would fail at the resolution stage
  (no native variant of `kotlin-stdlib` to be found via `available-at`)
  or at the link stage (double-linked stdlib).
- **No version drift.** kolt never has to decide which stdlib version
  to fetch. The user's `kotlin = "<version>"` in `kolt.toml` already
  pins the konanc release, and the bundled stdlib is the right one for
  that release by construction.
- **Cache cleanliness.** kolt's `~/.kolt/cache/` does not accumulate
  stdlib jars/klibs that would never be used.

### Negative

- **Implicit coupling to konanc internals.** If a future Kotlin/Native
  release stops bundling stdlib (very unlikely), kolt will silently
  break — the skip would still drop stdlib from resolution, but konanc
  would no longer auto-link it. This would surface as a link-time
  "unresolved reference" error from konanc, not as a clean kolt error.
- **The skip is invisible to users.** A user who explicitly writes
  `kotlin-stdlib` in `[dependencies]` will see it disappear from the
  resolved set with no message. This is intentional (it would always
  be wrong to include) but may confuse users porting a JVM project.
- **The constant `"org.jetbrains.kotlin:kotlin-stdlib"` is hard-coded
  in `NativeResolver.kt`.** Future related modules (`kotlin-stdlib-jdk8`,
  `kotlin-stdlib-common`, etc.) would need explicit additions if they
  ever appeared in a native variant's dependencies. As of Phase B, only
  the bare `kotlin-stdlib` is observed in real-world `.module` files.

### Neutral

- **JVM path is unaffected.** `TransitiveResolver` resolves
  `kotlin-stdlib` from POMs as usual. The skip applies only to the
  native pipeline.
- **`-nostdlib` remains available** if a future user case ever needs it
  (e.g. building a freestanding klib that intentionally avoids the
  bundled stdlib). It would require an explicit opt-in in `kolt.toml`.

## Alternatives Considered

1. **Pass `-nostdlib` to konanc and resolve `kotlin-stdlib` from
   Maven Central ourselves** — rejected because there is no native
   `linux_x64` variant of `kotlin-stdlib` published in the way the
   resolver expects. Even if there were, it would tie kolt's release
   cadence to publishing a matching stdlib for every supported
   `kotlin = "<version>"`.
2. **Filter stdlib only at the materialise step (after BFS)** —
   rejected because BFS would still walk `kotlin-stdlib`'s metadata,
   fail to find a native variant, and surface
   `ResolveError.NoNativeVariant` to the user. Skipping at the
   enqueue / seed step is the only place that prevents the failure
   mode entirely.
3. **Emit a warning when the skip fires** — rejected because the
   transitive case fires on every native build, producing noise that
   does not lead to any user action. The skip is a property of the
   target, not a project-level mistake.
4. **Recognise the broader `org.jetbrains.kotlin:*` group as konanc-
   bundled and skip everything in it** — rejected as overreach. As of
   Phase B only `kotlin-stdlib` is bundled in the way that requires
   skipping; other `org.jetbrains.kotlin:*` modules (if any ever
   appeared in native variant dependencies) should be evaluated case by
   case rather than blanket-skipped.

## Related

- #16 (Kotlin/Native target support — parent issue)
- PR #55 (Phase B-2, where the skip was introduced)
- ADR 0010 (Gradle Module Metadata for native resolution — provides
  the resolver context this skip lives inside)
