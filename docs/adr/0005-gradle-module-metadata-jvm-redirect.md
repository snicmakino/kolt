# ADR 0005: Follow Gradle Module Metadata redirects for KMP JVM artifacts

## Status

Accepted (2026-04-09)

## Context

Once keel's transitive resolver could walk plain Maven POMs (Phase 3),
it broke almost immediately on modern Kotlin Multiplatform libraries.
The trigger case was OkHttp 5.x, but the same pattern affects
kotlinx-coroutines, kotlinx-serialization, ktor, Arrow, and any other
KMP library that publishes a multi-target artifact.

The symptom was puzzling: `keel build` completed dependency
resolution, downloaded a JAR from Maven Central, put it on the
classpath, and then kotlinc failed with `unresolved reference` for
every symbol from the library. The JAR was there. The coordinate was
right. But the classes were missing.

Looking inside the downloaded file explained it. The root artifact of
a KMP library (e.g. `com.squareup.okhttp3:okhttp:5.0.0`) is a
**metadata-only JAR**. It has a manifest, a `META-INF` directory, and
essentially nothing else. The actual JVM bytecode lives in a
**separate artifact** with a `-jvm` suffix — in this case
`com.squareup.okhttp3:okhttp-jvm:5.0.0`.

This `-jvm` redirect is not encoded anywhere in the POM. The POM for
the root artifact looks normal: `<groupId>`, `<artifactId>`,
`<version>`, `<dependencies>`, all the usual fields. There is no
hint that the JAR it names is empty. The redirect lives in a third
file alongside the POM and JAR: **Gradle Module Metadata**, a JSON
file named `okhttp-5.0.0.module`.

Inside `.module`, each target (JVM, native, Android, common) is a
`variant` with a set of `attributes` (including
`org.jetbrains.kotlin.platform.type = "jvm"`) and optionally an
`available-at` block that points to another module:

```json
"variants": [
  {
    "name": "jvmApiElements-published",
    "attributes": { "org.jetbrains.kotlin.platform.type": "jvm" },
    "available-at": {
      "url": "../../okhttp-jvm/5.0.0/okhttp-jvm-5.0.0.module",
      "group": "com.squareup.okhttp3",
      "module": "okhttp-jvm",
      "version": "5.0.0"
    }
  },
  ...
]
```

Gradle, Maven (via the `gradle-module-metadata` plugin), and
dependency-resolver libraries like Coursier all know about this
format and transparently follow the redirect. keel at this point did
not, which is why the empty JAR ended up on the classpath.

The fix could not be "just download the `-jvm` artifact by naming
convention". The `-jvm` suffix is the common case, but the
`available-at` block is authoritative and may point anywhere. Some
libraries publish into a completely different group/artifact name.
Guessing the coordinate was wrong on principle and would break on
non-conforming libraries.

## Decision

During transitive resolution of a **jvm-target** project, keel
attempts to read a Gradle Module Metadata file (`.module`) alongside
every POM it fetches. If the metadata declares a JVM variant with an
`available-at` redirect, keel switches to the redirect target for
both the POM walk and the JAR download.

The entry point is `parseJvmRedirect(moduleJson)` in
`resolve/GradleMetadata.kt`, a pure function that returns a nullable
`JvmRedirect(group, module, version)`. The rule it encodes:

- If the `.module` file is absent or unparseable → no redirect
  (`null`). The POM is authoritative, and keel proceeds with the
  root coordinate as before.
- If **any** JVM variant has no `available-at` block → no redirect
  (`null`). This signals "the library provides its own JVM jar" and
  is the correct behaviour for non-KMP libraries (they have a JVM
  variant but do not need redirection).
- If **every** JVM variant has an `available-at` block → follow the
  first one. The redirect target's POM is parsed for transitive
  dependencies, and the redirect target's JAR is downloaded for the
  classpath. The root artifact is never put on the classpath.

The "any variant without `available-at` blocks redirection" rule was
added after `c20bf37` fixed a regression on `kotlin-test`: its
metadata has multiple JVM-family variants, and the first one the
parser saw had an `available-at` block pointing at
`kotlin-test-junit`, which broke vanilla `kotlin-test` consumers.
The fix is to only redirect when *all* JVM variants agree they are
pointing elsewhere.

`.module` downloads are memoised. A single resolution run parses each
metadata file at most once, reusing the result for both redirect
detection and, on the native side, variant walking (ADR 0010).

Gradle Module Metadata is treated as **supplemental** on the jvm
side: keel uses it only to find the `available-at` redirect. Once the
redirect is followed, resolution is still driven by POMs. The metadata
file is not read for dependency information on the jvm side. (This is
the opposite of the native side — see ADR 0010 — where metadata is
the sole source of truth.)

## Consequences

### Positive

- **KMP libraries work transparently**: OkHttp 5.x,
  kotlinx-coroutines, kotlinx-serialization, ktor, Arrow, and similar
  libraries resolve and compile without the user having to know about
  `-jvm` suffixes. `keel add com.squareup.okhttp3:okhttp:5.0.0` does
  the right thing.
- **Correct classpath entries**: the JAR that actually contains the
  bytecode is the one that ends up on the classpath. Metadata-only
  JARs are never loaded.
- **Authoritative coordinates**: the redirect target is read
  verbatim from `available-at.group` / `module` / `version`. keel
  does not guess, does not apply suffix heuristics, and does not
  break when libraries use non-standard names.
- **Backwards-compatible with plain Maven libraries**: if a library
  has no `.module` file (most of Maven Central), keel falls through
  to POM-only resolution immediately. The metadata fetch is a
  best-effort probe, not a hard requirement.
- **Paved the way for native resolution**: the parser
  (`GradleMetadata.kt`) was later extended with `parseNativeRedirect`
  and `parseNativeArtifact`, reusing the same JSON schema bindings
  and cache keys. ADR 0010 builds on this foundation.

### Negative

- **Extra network probes**: keel now fetches `.module` for every
  dependency during a fresh resolve. For libraries that do not
  publish metadata, this is one wasted GET per artifact (cached as
  a negative result for the run, but still a round-trip on a cold
  cache).
- **Silent failure mode**: the parser returns `null` on any parse
  error. A subtly malformed `.module` file would cause keel to fall
  back to POM-only resolution and produce a broken classpath, with
  no warning. This has not bitten us in practice, but it is a
  trade-off we accepted to avoid per-library special cases.
- **Pick-the-first-variant rule is a heuristic**: when every JVM
  variant has an `available-at` block, keel follows the first one
  it encounters in declaration order. For every library we have
  encountered, all JVM variants redirect to the same target, so
  "first one wins" is equivalent to "any of them". If a library ever
  publishes genuinely divergent JVM variants, we will need a richer
  selection rule.
- **`kotlin-test` class of bug is latent**: the specific case of
  "some JVM variants have `available-at`, others do not" is handled
  (do not redirect), but it took a real regression to find. Future
  metadata shapes could have similar surprises.

### Neutral

- **Bounded metadata parsing**: `GradleMetadata.kt` only needs
  `variants[].attributes.platform.type`, `variants[].available-at`,
  and (for native) `variants[].files[]` and
  `variants[].dependencies[]`. We do not implement the full Gradle
  Module Metadata 1.1 spec, and we have no need to.
- **Lenient JSON parsing**: the decoder is configured with
  `ignoreUnknownKeys = true` so that Gradle evolutions of the
  metadata schema (new attributes, new fields) do not break keel.

## Alternatives Considered

1. **Hardcode the `-jvm` suffix** — rejected. The suffix is
   convention, not contract. Libraries may redirect to any
   coordinate, and the first non-conforming library would silently
   produce a broken classpath. Guessing is a bug factory.
2. **Require users to declare `okhttp-jvm` explicitly** — rejected.
   This pushes the KMP metadata knowledge onto the user and breaks
   the "just add the dependency" ergonomics that keel is trying to
   offer. Worse, the user cannot always know the redirect target
   without reading the library's `.module` file themselves.
3. **Treat metadata as authoritative on jvm too** — rejected.
   Metadata parsing alone works for native (ADR 0010) because native
   artifacts have no POMs. On the jvm side, POMs already encode
   everything we need once the redirect is followed, and two parallel
   walk strategies would duplicate effort. Use metadata for variant
   selection, use POMs for dependency walking.
4. **Fetch metadata lazily only when the POM looks suspicious** —
   rejected. There is no reliable way to tell from a POM whether its
   JAR is metadata-only without downloading the JAR and inspecting
   its contents, which is more expensive than a small `.module` GET.

## Related

- `src/nativeMain/kotlin/keel/resolve/GradleMetadata.kt` —
  `parseJvmRedirect` and the shared JSON schema
- `src/nativeMain/kotlin/keel/resolve/TransitiveResolver.kt` — call
  site for JVM redirect handling
- Commit `7d58f02` (initial Gradle Module Metadata support for KMP)
- Commit `c20bf37` (`kotlin-test` fix: do not redirect if any JVM
  variant lacks `available-at`)
- ADR 0010 (Gradle Module Metadata for Kotlin/Native — uses the same
  parser but treats metadata as authoritative)
